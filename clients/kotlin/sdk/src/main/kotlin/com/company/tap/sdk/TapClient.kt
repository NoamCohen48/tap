package com.company.tap.sdk

import com.company.tap.api.v1.AppServiceGrpcKt
import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpc
import com.company.tap.api.v1.ConnectionServiceGrpcKt
import com.company.tap.api.v1.DeviceEntry
import com.company.tap.api.v1.DeviceServiceGrpcKt
import com.company.tap.api.v1.DeviceState
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.ListDevicesRequest
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.SessionServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A channel to a Tap host service. Discovery order: [address]; the `tap.service` system
 * property / `TAP_SERVICE` (`host:port`); a live `service.json` in the state dir
 * (`TAP_STATE_DIR`, default `~/.tap`). The client never starts a service: run `tap start`
 * (or [TapServiceProcess.start]) first. A started service stays up like the ADB server
 * until `tap stop`.
 *
 * Construction takes an explicit address and performs no I/O; use [create] to resolve one
 * (which probes the service with `Info`). All RPCs are `suspend` over grpc-kotlin
 * `CoroutineStub`s: per-call `withDeadlineAfter` is still applied server-side, while caller
 * (or test-root) cancellation promptly cancels the gRPC call client-side. Closing is
 * `suspend` and drops `AutoCloseable`: callers use `try`/`finally` inside a coroutine.
 */
/**
 * Advanced seam (tests, custom transports): a client over an existing channel.
 * Production callers use [TapClient] + [create].
 */
class TapClient public constructor(
    val address: String,
    val channel: ManagedChannel,
) {
    /** Creates a client for [address] with its own channel. Performs no I/O. */
    constructor(address: String) : this(
        address,
        ManagedChannelBuilder.forTarget(address).usePlaintext().maxInboundMessageSize(64 * 1024 * 1024).build(),
    )
    internal val connections = ConnectionServiceGrpcKt.ConnectionServiceCoroutineStub(channel)
    internal val devices = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
    internal val sessions = SessionServiceGrpcKt.SessionServiceCoroutineStub(channel)
    internal val apps = AppServiceGrpcKt.AppServiceCoroutineStub(channel)

    /** Service version, protocol version, ADB executable, state dir, bundled driver. */
    suspend fun info(): InfoResponse =
        mapped {
            connections.withDeadlineAfter(10, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance())
        }

    /** Every device ADB lists, with its state (`FREE`, `LEASED`, `QUARANTINED`, `OFFLINE`). */
    suspend fun devices(): List<DeviceEntry> =
        mapped {
            devices
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .listDevices(ListDevicesRequest.getDefaultInstance())
                .devicesList
        }

    /**
     * Opens a [Connection] and attaches its liveness stream: if this process dies, the service
     * closes every session the connection opened. The attach stream is established before this
     * returns, so every later `openDevice` belongs to a live connection.
     */
    suspend fun connect(name: String): Connection {
        val id =
            mapped {
                connections
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .open(OpenConnectionRequest.newBuilder().setName(name).build())
                    .connectionId
            }
        return Connection(this, id).also { it.attach() }
    }

    /**
     * Shuts the channel down. Runs under [kotlinx.coroutines.NonCancellable] with a bounded
     * wait so teardown completes; close every [Connection] first.
     */
    suspend fun close() {
        withContext(kotlinx.coroutines.NonCancellable) {
            withContext(Dispatchers.IO) {
                channel.shutdown()
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) channel.shutdownNow()
            }
        }
    }

    companion object {
        /**
         * Resolves an address ([address], else `tap.service`/`TAP_SERVICE`, else the live
         * `service.json`) and returns a client for it. Probes the service with `Info`, so a
         * dead descriptor fails here with "no running tap service; run `tap start`" rather than
         * on the first call.
         */
        suspend fun create(address: String? = null): TapClient = TapClient(address ?: ServiceDiscovery.resolve())
    }
}

/**
 * This process's identity at the service: every [Device] it opens belongs to it and is closed
 * with it — explicitly by [close], or by the service when the process goes away. One per process
 * is the norm; see [TapClient.connect].
 *
 * Liveness is an explicitly owned [attachScope]: a `SupervisorJob + Dispatchers.IO` scope that
 * lives exactly as long as this connection. [attach] collects the server-streaming `Attach`
 * `Flow` in that scope and waits for the first event before returning, so the stream is
 * established before any `OpenSession`. When the scope is cancelled (or the process dies) the
 * service notices the dropped stream and closes every session of this connection. Never
 * `GlobalScope`: [close] sends `Close` first, then cancels and joins the collection.
 */
class Connection internal constructor(
    val client: TapClient,
    val id: String,
) {
    private val events = CopyOnWriteArrayList<String>()
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var attachJob: Job? = null

    /** Messages the service sent on the liveness stream so far (diagnostics). */
    val recentEvents: List<String> get() = events.toList()

    internal suspend fun attach() {
        val request = AttachRequest.newBuilder().setConnectionId(id).build()
        val flow = client.connections.attach(request)
        val established = CompletableDeferred<Unit>()
        attachJob =
            attachScope.launch {
                try {
                    flow.collect { event: ConnectionEvent ->
                        events.add(event.message)
                        if (events.size > 200) events.removeAt(0)
                        if (!established.isCompleted) established.complete(Unit)
                    }
                    if (!established.isCompleted) established.complete(Unit)
                } catch (cancelled: CancellationException) {
                    if (!established.isCompleted) established.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Throwable) {
                    if (!established.isCompleted) established.completeExceptionally(error)
                }
            }
        try {
            withTimeout(30_000) { established.await() }
        } catch (cancelled: CancellationException) {
            attachJob?.cancelAndJoin()
            throw cancelled
        } catch (error: Throwable) {
            attachJob?.cancelAndJoin()
            val toThrow: Throwable = try {
                mapped<Unit> { throw error }
                error
            } catch (mappedError: Throwable) {
                mappedError
            }
            throw toThrow
        }
    }

    /**
     * Serials a test can use, from [TapClient.devices]: online and not quarantined, free ones
     * first, then ones another session holds (opening then waits, see [DeviceOptions.waitForDevice]).
     * Exclusive use is enforced by the session itself, so there is nothing to acquire beforehand.
     */
    suspend fun availableSerials(): List<String> =
        client
            .devices()
            .filter { it.state == DeviceState.DEVICE_FREE || it.state == DeviceState.DEVICE_LEASED }
            .sortedBy { it.state != DeviceState.DEVICE_FREE }
            .map { it.serial }

    /**
     * Open a driver session on [serial] for [autPackage]. The session holds the device's
     * per-serial lock until [Device.close]; if another session holds it, the open fails with
     * [DeviceBusyException] — at once, or after [DeviceOptions.waitForDevice].
     */
    suspend fun openDevice(
        serial: String,
        autPackage: String,
        timeouts: Timeouts = Timeouts(),
        options: DeviceOptions = DeviceOptions(),
    ): Device = Device.open(this, serial, autPackage, timeouts, options)

    /**
     * Closes explicitly (recorded as a client request), then drops the liveness stream.
     * Sends `Close` under a bounded non-cancellable context first so an explicit close is
     * recorded even when the caller is cancelled; then cancels and joins the attach
     * collection. If the attach already dropped, the service has closed the sessions and
     * `Close` may report unknown-connection (mapped, still cleans up the scope).
     */
    suspend fun close() {
        try {
            withContext(kotlinx.coroutines.NonCancellable) {
                withTimeout(60_000) {
                    mapped {
                        client.connections.close(
                            CloseConnectionRequest.newBuilder().setConnectionId(id).build(),
                        )
                    }
                }
            }
        } finally {
            attachJob?.cancelAndJoin()
            attachScope.cancel()
        }
    }
}

/** Locates or starts the host service. */

/** Finds a running service; never starts one (see [TapServiceProcess]). */
object ServiceDiscovery {
    fun stateDir(): Path =
        System.getenv("TAP_STATE_DIR")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".tap")

    /**
     * `tap.service` / `TAP_SERVICE`, else the address in a live `service.json`. Probes the
     * descriptor with `Info` over a short-lived coroutine stub; a dead descriptor fails with
     * "no running tap service; run `tap start`".
     */
    suspend fun resolve(): String {
        (System.getProperty("tap.service") ?: System.getenv("TAP_SERVICE"))
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val dir = stateDir()
        return running(dir) ?: throw TapException("no running tap service (no live descriptor in $dir); run `tap start`")
    }

    /** Address of the service `service.json` in [dir] points at, if it answers `Info`. */
    suspend fun running(dir: Path = stateDir()): String? {
        val port = descriptorPort(dir) ?: return null
        val address = "127.0.0.1:$port"
        return if (alive(address)) address else null
    }

    /** The `tap` executable: `tap.bin` / `TAP_BIN`, else `tap` on `PATH`. */
    fun findBinary(): String? =
        (System.getProperty("tap.bin") ?: System.getenv("TAP_BIN"))?.takeIf { it.isNotBlank() }
            ?: System
                .getenv("PATH")
                .orEmpty()
                .split(java.io.File.pathSeparator)
                .map { Path.of(it, "tap") }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()

    private fun descriptorPort(dir: Path): Int? =
        runCatching {
            Regex(""""port":(\d+)""")
                .find(Files.readString(dir.resolve("service.json")))
                ?.groupValues
                ?.get(1)
                ?.toInt()
        }.getOrNull()

    private suspend fun alive(address: String): Boolean {
        val channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build()
        return try {
            withTimeout(2_000) {
                ConnectionServiceGrpcKt
                    .ConnectionServiceCoroutineStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .info(InfoRequest.getDefaultInstance())
            }
            true
        } catch (_: CancellationException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            withContext(Dispatchers.IO) {
                channel.shutdownNow()
            }
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
    data class StartResult(
        val address: String,
        val started: Boolean,
    )

    /**
     * Starts a service in the background unless one is already running in [stateDir]. Extra
     * `tap serve` options (`--adb PATH`) go in [options]. Runs the executable on
     * `Dispatchers.IO` with a bounded wait; the calling coroutine stays cancellable while
     * waiting.
     */
    suspend fun start(
        binary: String? = null,
        stateDir: Path = ServiceDiscovery.stateDir(),
        options: List<String> = emptyList(),
        timeout: Duration = 45.seconds,
    ): StartResult {
        val output = run(binary, listOf("start", "--state-dir", stateDir.toString()) + options, timeout)
        val match =
            Regex("""^(started|running) (\S+)""", RegexOption.MULTILINE).find(output)
                ?: throw TapException("unexpected `tap start` output: $output")
        return StartResult(match.groupValues[2], started = match.groupValues[1] == "started")
    }

    /** Stops the service recorded in [stateDir]; a no-op when none is running. */
    suspend fun stop(
        binary: String? = null,
        stateDir: Path = ServiceDiscovery.stateDir(),
        timeout: Duration = 30.seconds,
    ) {
        run(binary, listOf("stop", "--state-dir", stateDir.toString()), timeout)
    }

    private suspend fun run(
        binary: String?,
        args: List<String>,
        timeout: Duration,
    ): String =
        withContext(Dispatchers.IO) {
            val executable =
                binary ?: ServiceDiscovery.findBinary()
                    ?: throw TapException("no `tap` executable found (set tap.bin / TAP_BIN or add it to PATH)")
            val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
            process.outputStream.close()
            val reader = process.inputStream.bufferedReader()
            val outputDeferred = CompletableDeferred<String>()
            val drain =
                Thread {
                    try {
                        outputDeferred.complete(reader.readText())
                    } catch (error: Throwable) {
                        outputDeferred.completeExceptionally(error)
                    }
                }.also {
                    it.isDaemon = true
                    it.start()
                }
            try {
                val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    throw TapException("`tap ${args.first()}` did not finish within $timeout")
                }
                val output = outputDeferred.await()
                drain.join(1_000)
                if (process.exitValue() !=
                    0
                ) {
                    throw TapException("`tap ${args.first()}` failed (exit ${process.exitValue()}): ${output.trim()}")
                }
                output
            } catch (cancelled: CancellationException) {
                process.destroyForcibly()
                throw cancelled
            }
        }
}
