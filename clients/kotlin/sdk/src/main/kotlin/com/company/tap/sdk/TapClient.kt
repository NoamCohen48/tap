package com.company.tap.sdk

import com.company.tap.api.v1.AcquireRequest
import com.company.tap.api.v1.AppServiceGrpc
import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseRunRequest
import com.company.tap.api.v1.DeviceFacts
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.InventoryRequest
import com.company.tap.api.v1.OpenRunRequest
import com.company.tap.api.v1.PoolDevice
import com.company.tap.api.v1.PoolServiceGrpc
import com.company.tap.api.v1.ReleaseRequest
import com.company.tap.api.v1.RoleRequest
import com.company.tap.api.v1.RunEvent
import com.company.tap.api.v1.RunServiceGrpc
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
import com.company.tap.api.v1.DeviceConstraints as DeviceConstraintsProto

/** Per-role pool constraints for [Run.acquire]. Null means unconstrained. */
data class DeviceConstraints(
    val serial: String? = null,
    val minApi: Int? = null,
    val maxApi: Int? = null,
    val emulator: Boolean? = null,
    val modelContains: String? = null,
) {
    internal fun toProto(): DeviceConstraintsProto {
        // Not `apply`: inside the builder scope the unqualified field names resolve to the
        // builder's getters, which would set every optional field to its default.
        val b = DeviceConstraintsProto.newBuilder()
        serial?.let(b::setSerial)
        minApi?.let(b::setMinApi)
        maxApi?.let(b::setMaxApi)
        emulator?.let(b::setEmulator)
        modelContains?.let(b::setModelContains)
        return b.build()
    }

    companion object {
        val ANY = DeviceConstraints()
        fun serial(serial: String) = DeviceConstraints(serial = serial)
    }
}

/**
 * Converts gRPC failures into the client's exceptions. Driver outcomes never arrive this way
 * (they are `CommandResult` data); this covers service refusals and host-side failures.
 */
internal inline fun <T> mapped(serial: String? = null, block: () -> T): T = try {
    block()
} catch (error: StatusRuntimeException) {
    val details = error.status.description.orEmpty()
    throw when {
        error.status.code == Status.Code.DEADLINE_EXCEEDED && details.startsWith("Timed out") ->
            WaitTimeoutException(details, serial ?: "?", 0, cause = error)
        error.status.code == Status.Code.FAILED_PRECONDITION -> AppLifecycleException(details, error)
        else -> ServiceException(error.status.code.name, details, error)
    }
}

/**
 * One connection to a Tap host service. Discovery order: [address]; the `tap.service` system
 * property / `TAP_SERVICE` (`host:port`); a live `service.json` in the state dir
 * (`TAP_STATE_DIR`, default `~/.tap`); otherwise `tap serve` is started from `tap.bin` /
 * `TAP_BIN` / `tap` on `PATH`. A started service stays up like the ADB server (`tap stop`).
 */
class TapClient(address: String? = null, autostart: Boolean = true) : AutoCloseable {
    val address: String = address ?: ServiceDiscovery.resolve(autostart)
    val channel: ManagedChannel = ManagedChannelBuilder.forTarget(this.address)
        .usePlaintext()
        .maxInboundMessageSize(64 * 1024 * 1024)
        .build()
    internal val runs: RunServiceGrpc.RunServiceBlockingStub = RunServiceGrpc.newBlockingStub(channel)
    internal val runsAsync: RunServiceGrpc.RunServiceStub = RunServiceGrpc.newStub(channel)
    internal val pool: PoolServiceGrpc.PoolServiceBlockingStub = PoolServiceGrpc.newBlockingStub(channel)
    internal val sessions: SessionServiceGrpc.SessionServiceBlockingStub = SessionServiceGrpc.newBlockingStub(channel)
    internal val apps: AppServiceGrpc.AppServiceBlockingStub = AppServiceGrpc.newBlockingStub(channel)

    fun info(): InfoResponse = mapped { runs.withDeadlineAfter(10, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance()) }

    fun inventory(): List<PoolDevice> =
        mapped { pool.withDeadlineAfter(30, TimeUnit.SECONDS).inventory(InventoryRequest.getDefaultInstance()).devicesList }

    /** Opens and attaches a run: if this process dies, the service releases everything it held. */
    fun openRun(name: String): Run {
        val id = mapped { runs.withDeadlineAfter(10, TimeUnit.SECONDS).open(OpenRunRequest.newBuilder().setName(name).build()).runId }
        return Run(this, id).also { it.attach() }
    }

    override fun close() {
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/** Ownership scope for leases and sessions; see [TapClient.openRun]. */
class Run internal constructor(val client: TapClient, val id: String) : AutoCloseable {
    private val events = CopyOnWriteArrayList<String>()
    @Volatile private var stream: ClientCallStreamObserver<AttachRequest>? = null

    /** Messages the service sent on the liveness stream so far (diagnostics). */
    val recentEvents: List<String> get() = events.toList()

    internal fun attach() {
        val acknowledged = CountDownLatch(1)
        var failure: Throwable? = null
        client.runsAsync.attach(
            AttachRequest.newBuilder().setRunId(id).build(),
            object : ClientResponseObserver<AttachRequest, RunEvent> {
                override fun beforeStart(requestStream: ClientCallStreamObserver<AttachRequest>) {
                    stream = requestStream
                }

                override fun onNext(value: RunEvent) {
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

    /** All-or-none lease of one device per role, queued until [timeout]. */
    fun acquire(roles: Map<String, DeviceConstraints>, timeout: Duration = 300.seconds): Map<String, DeviceFacts> {
        val request = AcquireRequest.newBuilder().setRunId(id).setTimeoutMs(timeout.inWholeMilliseconds)
        roles.forEach { (role, constraints) ->
            request.addRoles(RoleRequest.newBuilder().setRole(role).setConstraints(constraints.toProto()))
        }
        return mapped {
            client.pool.withDeadlineAfter(timeout.inWholeSeconds + 30, TimeUnit.SECONDS).acquire(request.build())
                .assignmentsList.associate { it.role to it.device }
        }
    }

    /** Releases [serials] (empty = everything this run holds). Sessions on them are closed first. */
    fun release(serials: Collection<String> = emptyList()): Int = mapped {
        client.pool.withDeadlineAfter(60, TimeUnit.SECONDS)
            .release(ReleaseRequest.newBuilder().setRunId(id).addAllSerials(serials).build()).released
    }

    fun openDevice(
        serial: String,
        autPackage: String,
        timeouts: Timeouts = Timeouts(),
        options: DeviceOptions = DeviceOptions(),
    ): Device = Device.open(this, serial, autPackage, timeouts, options)

    /** Closes explicitly (recorded as a client request), then drops the liveness stream. */
    override fun close() {
        try {
            mapped { client.runs.withDeadlineAfter(60, TimeUnit.SECONDS).close(CloseRunRequest.newBuilder().setRunId(id).build()) }
        } finally {
            stream?.cancel("run closed", null)
            stream = null
        }
    }

}

/** Locates or starts the host service. */
object ServiceDiscovery {
    fun stateDir(): Path = System.getenv("TAP_STATE_DIR")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".tap")

    fun resolve(autostart: Boolean = true): String {
        (System.getProperty("tap.service") ?: System.getenv("TAP_SERVICE"))?.takeIf { it.isNotBlank() }?.let { return it }
        val dir = stateDir()
        descriptorPort(dir)?.let { port ->
            val address = "127.0.0.1:$port"
            if (alive(address)) return address
        }
        if (!autostart) throw TapException("no running tap service (no live descriptor in $dir); start one with `tap serve`")
        val binary = findBinary() ?: throw TapException("no running tap service and no `tap` binary found (set tap.bin / TAP_BIN or add it to PATH)")
        return start(binary, dir)
    }

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
            RunServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance())
            true
        } catch (_: StatusRuntimeException) {
            false
        } finally {
            channel.shutdownNow()
        }
    }

    /** Spawns `tap serve` detached and returns its address once it prints `TAP_SERVICE_READY`. */
    fun start(binary: String, dir: Path, timeout: Duration = 30.seconds): String {
        Files.createDirectories(dir)
        val log = dir.resolve("service.log").toFile()
        val process = ProcessBuilder(binary, "serve", "--state-dir", dir.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
            .also { it.outputStream.close() }
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            descriptorPort(dir)?.let { port ->
                val address = "127.0.0.1:$port"
                if (alive(address)) return address
            }
            if (!process.isAlive) break
            Thread.sleep(100)
        }
        process.destroy()
        throw TapException("tap serve did not become ready within $timeout (see $log)")
    }
}
