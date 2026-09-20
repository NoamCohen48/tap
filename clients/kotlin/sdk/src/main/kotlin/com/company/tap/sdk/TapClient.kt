package com.company.tap.sdk

import com.company.tap.api.v1.AppServiceGrpc
import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpc
import com.company.tap.api.v1.DeviceEntry
import com.company.tap.api.v1.DeviceServiceGrpc
import com.company.tap.api.v1.DeviceState
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.ListDevicesRequest
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.SessionServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Converts gRPC failures into the client's exceptions. Driver outcomes never arrive this way
 * (they are `CommandResult` data); this covers service refusals and host-side failures.
 */
/** The service's wording for a held per-serial lock (`DeviceBusyException` in `:host:core`). */
internal const val DEVICE_BUSY_MARKER = "is in use by another session"

internal inline fun <T> mapped(serial: String? = null, block: () -> T): T = try {
    block()
} catch (error: StatusRuntimeException) {
    val details = error.status.description.orEmpty()
    throw when {
        error.status.code == Status.Code.DEADLINE_EXCEEDED && details.startsWith("Timed out") ->
            WaitTimeoutException(details, serial ?: "?", 0, cause = error)
        details.contains(DEVICE_BUSY_MARKER) -> DeviceBusyException(details, error)
        error.status.code == Status.Code.FAILED_PRECONDITION -> AppLifecycleException(details, error)
        else -> ServiceException(error.status.code.name, details, error)
    }
}

/**
 * A channel to a Tap host service. Discovery order: [address]; the `tap.service` system
 * property / `TAP_SERVICE` (`host:port`); a live `service.json` in the state dir
 * (`TAP_STATE_DIR`, default `~/.tap`). The client never starts a service: run `tap start`
 * (or [TapServiceProcess.start]) first. A started service stays up like the ADB server
 * until `tap stop`.
 */
class TapClient(address: String? = null) : AutoCloseable {
    val address: String = address ?: ServiceDiscovery.resolve()
    val channel: ManagedChannel = ManagedChannelBuilder.forTarget(this.address)
        .usePlaintext()
        .maxInboundMessageSize(64 * 1024 * 1024)
        .build()
    internal val connections: ConnectionServiceGrpc.ConnectionServiceBlockingStub = ConnectionServiceGrpc.newBlockingStub(channel)
    internal val connectionsAsync: ConnectionServiceGrpc.ConnectionServiceStub = ConnectionServiceGrpc.newStub(channel)
    internal val devices: DeviceServiceGrpc.DeviceServiceBlockingStub = DeviceServiceGrpc.newBlockingStub(channel)
    internal val sessions: SessionServiceGrpc.SessionServiceBlockingStub = SessionServiceGrpc.newBlockingStub(channel)
    internal val apps: AppServiceGrpc.AppServiceBlockingStub = AppServiceGrpc.newBlockingStub(channel)

    /** Service version, protocol version, ADB executable, state dir, bundled driver. */
    fun info(): InfoResponse = mapped { connections.withDeadlineAfter(10, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance()) }

    /** Every device ADB lists, with its state (`FREE`, `LEASED`, `QUARANTINED`, `OFFLINE`). */
    fun devices(): List<DeviceEntry> =
        mapped { devices.withDeadlineAfter(30, TimeUnit.SECONDS).listDevices(ListDevicesRequest.getDefaultInstance()).devicesList }

    /**
     * Opens a [Connection] and attaches its liveness stream: if this process dies, the service
     * closes every session the connection opened.
     */
    fun connect(name: String): Connection {
        val id = mapped { connections.withDeadlineAfter(10, TimeUnit.SECONDS).open(OpenConnectionRequest.newBuilder().setName(name).build()).connectionId }
        return Connection(this, id).also { it.attach() }
    }

    override fun close() {
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * This process's identity at the service: every [Device] it opens belongs to it and is closed
 * with it — explicitly by [close], or by the service when the process goes away. One per process
 * is the norm; see [TapClient.connect].
 */
class Connection internal constructor(val client: TapClient, val id: String) : AutoCloseable {
    private val events = CopyOnWriteArrayList<String>()
    @Volatile private var stream: ClientCallStreamObserver<AttachRequest>? = null

    /** Messages the service sent on the liveness stream so far (diagnostics). */
    val recentEvents: List<String> get() = events.toList()

    internal fun attach() {
        val acknowledged = CountDownLatch(1)
        var failure: Throwable? = null
        client.connectionsAsync.attach(
            AttachRequest.newBuilder().setConnectionId(id).build(),
            object : ClientResponseObserver<AttachRequest, ConnectionEvent> {
                override fun beforeStart(requestStream: ClientCallStreamObserver<AttachRequest>) {
                    stream = requestStream
                }

                override fun onNext(value: ConnectionEvent) {
                    events.add(value.message)
                    if (events.size > 200) events.removeAt(0)
                    acknowledged.countDown()
                }

                override fun onError(t: Throwable) {
                    failure = t
                    acknowledged.countDown()
                }

                override fun onCompleted() = acknowledged.countDown()
            },
        )
        acknowledged.await(30, TimeUnit.SECONDS)
        failure?.let { cause -> mapped<Unit> { throw cause } }
    }

    /**
     * Serials a test can use, from [TapClient.devices]: online and not quarantined, free ones
     * first, then ones another session holds (opening then waits, see [DeviceOptions.waitForDevice]).
     * Exclusive use is enforced by the session itself, so there is nothing to acquire beforehand.
     */
    fun availableSerials(): List<String> = client.devices()
        .filter { it.state == DeviceState.DEVICE_FREE || it.state == DeviceState.DEVICE_LEASED }
        .sortedBy { it.state != DeviceState.DEVICE_FREE }
        .map { it.serial }

    /**
     * Open a driver session on [serial] for [autPackage]. The session holds the device's
     * per-serial lock until [Device.close]; if another session holds it, the open fails with
     * [DeviceBusyException] — at once, or after [DeviceOptions.waitForDevice].
     */
    fun openDevice(
        serial: String,
        autPackage: String,
        timeouts: Timeouts = Timeouts(),
        options: DeviceOptions = DeviceOptions(),
    ): Device = Device.open(this, serial, autPackage, timeouts, options)

    /** Closes explicitly (recorded as a client request), then drops the liveness stream. */
    override fun close() {
        try {
            mapped { client.connections.withDeadlineAfter(60, TimeUnit.SECONDS).close(CloseConnectionRequest.newBuilder().setConnectionId(id).build()) }
        } finally {
            stream?.cancel("connection closed", null)
            stream = null
        }
    }

}

/** Locates or starts the host service. */
/** Finds a running service; never starts one (see [TapServiceProcess]). */
object ServiceDiscovery {
    fun stateDir(): Path = System.getenv("TAP_STATE_DIR")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".tap")

    /** `tap.service` / `TAP_SERVICE`, else the address in a live `service.json`. */
    fun resolve(): String {
        (System.getProperty("tap.service") ?: System.getenv("TAP_SERVICE"))?.takeIf { it.isNotBlank() }?.let { return it }
        val dir = stateDir()
        return running(dir) ?: throw TapException("no running tap service (no live descriptor in $dir); run `tap start`")
    }

    /** Address of the service `service.json` in [dir] points at, if it answers `Info`. */
    fun running(dir: Path = stateDir()): String? {
        val port = descriptorPort(dir) ?: return null
        val address = "127.0.0.1:$port"
        return if (alive(address)) address else null
    }

    /** The `tap` executable: `tap.bin` / `TAP_BIN`, else `tap` on `PATH`. */
    fun findBinary(): String? =
        (System.getProperty("tap.bin") ?: System.getenv("TAP_BIN"))?.takeIf { it.isNotBlank() }
            ?: System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
                .map { Path.of(it, "tap") }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()

    private fun descriptorPort(dir: Path): Int? = runCatching {
        Regex(""""port":(\d+)""").find(Files.readString(dir.resolve("service.json")))?.groupValues?.get(1)?.toInt()
    }.getOrNull()

    private fun alive(address: String): Boolean {
        val channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build()
        return try {
            ConnectionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance())
            true
        } catch (_: StatusRuntimeException) {
            false
        } finally {
            channel.shutdownNow()
        }
    }
}

/**
 * Explicit service lifecycle from a test process: `tap start` and `tap stop` through the `tap`
 * executable ([ServiceDiscovery.findBinary]). The executable is the only thing that spawns a
 * service; it picks the port, detaches the process and waits for `Info` to answer.
 */
object TapServiceProcess {
    /** [address] of the service and whether this call [started] it (false = it was already running). */
    data class StartResult(val address: String, val started: Boolean)

    /**
     * Starts a service in the background unless one is already running in [stateDir]. Extra
     * `tap serve` options (`--adb PATH`) go in [options].
     */
    fun start(
        binary: String? = null,
        stateDir: Path = ServiceDiscovery.stateDir(),
        options: List<String> = emptyList(),
        timeout: Duration = 45.seconds,
    ): StartResult {
        val output = run(binary, listOf("start", "--state-dir", stateDir.toString()) + options, timeout)
        val match = Regex("""^(started|running) (\S+)""", RegexOption.MULTILINE).find(output)
            ?: throw TapException("unexpected `tap start` output: $output")
        return StartResult(match.groupValues[2], started = match.groupValues[1] == "started")
    }

    /** Stops the service recorded in [stateDir]; a no-op when none is running. */
    fun stop(binary: String? = null, stateDir: Path = ServiceDiscovery.stateDir(), timeout: Duration = 30.seconds) {
        run(binary, listOf("stop", "--state-dir", stateDir.toString()), timeout)
    }

    private fun run(binary: String?, args: List<String>, timeout: Duration): String {
        val executable = binary ?: ServiceDiscovery.findBinary()
            ?: throw TapException("no `tap` executable found (set tap.bin / TAP_BIN or add it to PATH)")
        val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw TapException("`tap ${args.first()}` did not finish within $timeout: $output")
        }
        if (process.exitValue() != 0) throw TapException("`tap ${args.first()}` failed (exit ${process.exitValue()}): ${output.trim()}")
        return output
    }
}
