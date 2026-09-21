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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        ManagedChannelBuilder
            .forTarget(address)
            .usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024)
            .build(),
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
     * returns (the first `Attach` event is awaited), so every later `openDevice` belongs to a
     * live connection. An empty stream or any failure before the first event fails the connect
     * and closes the newly opened id under a bounded non-cancellable context.
     */
    suspend fun connect(name: String): Connection {
        val id =
            mapped {
                connections
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .open(OpenConnectionRequest.newBuilder().setName(name).build())
                    .connectionId
            }
        val connection = Connection(this, id)
        try {
            connection.attach()
        } catch (error: Throwable) {
            throw error
        }
        return connection
    }

    /**
     * Shuts the channel down. Runs under [NonCancellable] with bounded waits so teardown
     * completes even when the caller is cancelled: `shutdown`, a bounded await, then
     * `shutdownNow` plus a second bounded await for forced termination.
     * Close every [Connection] first.
     */
    suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            channel.shutdown()
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow()
                channel.awaitTermination(5, TimeUnit.SECONDS)
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
 * established before any `OpenSession`. When the scope is cancelled by [close] (or the process
 * dies) the service notices the dropped stream and closes every session of this connection.
 * Never `GlobalScope`: [close] sends `Close` first, then cancels and joins the collection.
 *
 * Lifecycle is linearized: exactly one `Attach` is ever started ([attach] fails on a second
 * call); an empty stream or any failure before the first event fails the connect and closes
 * the newly opened id under a bounded non-cancellable context. Any normal completion or
 * failure of the stream *after* the first event is unexpected: the connection is atomically
 * marked unusable (the terminal cause is retained, never swallowed), further [openDevice]
 * calls and every admitted [Device] operation are rejected locally, and registered session
 * handles are marked invalid. [close] is single-flight: concurrent and repeated callers share
 * one `Close` RPC (60 s gRPC deadline, mapped) and one bounded non-cancellable teardown that
 * cancels and joins the collector even for a stubborn (cancellation-ignoring) collector,
 * preserving the primary `Close` failure and suppressing cleanup failures.
 */
class Connection internal constructor(
    val client: TapClient,
    val id: String,
) {
    private val events = CopyOnWriteArrayList<String>()
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var attachJob: Job? = null
    private val attachStarted = AtomicBoolean(false)
    private val established = AtomicBoolean(false)
    private val unusable = AtomicBoolean(false)
    private val unusableCause = AtomicReference<Throwable?>(null)
    private val closeStarted = AtomicBoolean(false)
    private var closeDeferred: CompletableDeferred<Unit>? = null
    private val openDevices = CopyOnWriteArrayList<Device>()

    /** Messages the service sent on the liveness stream so far (diagnostics). */
    val recentEvents: List<String> get() = events.toList()

    /** True once the stream terminated unexpectedly after establishment (or was never usable). */
    val isInvalid: Boolean get() = unusable.get()

    internal suspend fun attach() {
        if (!attachStarted.compareAndSet(false, true)) {
            throw TapUsageException("Connection($id).attach must run exactly once")
        }
        val flow: Flow<ConnectionEvent>
        try {
            flow = client.connections.attach(AttachRequest.newBuilder().setConnectionId(id).build())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            closeIdBestEffort(error)
            val toThrow: Throwable =
                try {
                    mapped<Unit> { throw error }
                    error
                } catch (mappedError: Throwable) {
                    mappedError
                }
            throw toThrow
        }
        val firstEvent = CompletableDeferred<Unit>()
        val job =
            attachScope.launch {
                var sawFirst = false
                try {
                    flow.collect { event: ConnectionEvent ->
                        events.add(event.message)
                        if (events.size > 200) events.removeAt(0)
                        sawFirst = true
                        if (!firstEvent.isCompleted) firstEvent.complete(Unit)
                    }
                    if (!sawFirst) {
                        val empty = TapException("connection $id attach completed without emitting (empty stream)")
                        if (!firstEvent.isCompleted) {
                            firstEvent.completeExceptionally(empty)
                        } else {
                            markUnusable(empty)
                        }
                    } else {
                        markUnusable(TapException("connection $id attach stream ended unexpectedly after establishment"))
                    }
                } catch (cancelled: CancellationException) {
                    if (!firstEvent.isCompleted) {
                        firstEvent.completeExceptionally(cancelled)
                    } else if (!closeStarted.get()) {
                        markUnusable(cancelled)
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    if (!firstEvent.isCompleted) {
                        firstEvent.completeExceptionally(error)
                    } else {
                        markUnusable(error)
                    }
                }
            }
        stateMutex.withLock { attachJob = job }
        try {
            withTimeout(30_000) { firstEvent.await() }
            established.set(true)
        } catch (setupFailure: Throwable) {
            boundedCancelJoin(job)
            if (setupFailure is CancellationException) {
                // Caller cancelled (or timed out) before establishment: still release the id.
                closeIdBestEffort(setupFailure)
                throw setupFailure
            }
            closeIdBestEffort(setupFailure)
            val toThrow: Throwable =
                try {
                    mapped<Unit> { throw setupFailure }
                    setupFailure
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
     * [DeviceBusyException] — at once, or after [DeviceOptions.waitForDevice]. Rejected locally
     * without an RPC once the connection is closed or its liveness stream ended unexpectedly.
     */
    suspend fun openDevice(
        serial: String,
        autPackage: String,
        timeouts: Timeouts = Timeouts(),
        options: DeviceOptions = DeviceOptions(),
    ): Device {
        ensureUsable("Device.open")
        val device = Device.open(this, serial, autPackage, timeouts, options)
        val terminal: Throwable? =
            stateMutex.withLock {
                openDevices.add(device)
                unusableCause.get()
            }
        // A concurrent invalidation between the pre-RPC gate and registration must still
        // surface on the just-registered handle; the registry keeps the handle until close.
        terminal?.let { device.markConnectionInvalid(it) }
        // A concurrent close between the RPC and registration must not admit a live handle
        // past close ownership: drop it from the registry and fail locally. The server-side
        // session is already covered by the connection Close (or best-effort closed here).
        if (closeStarted.get()) {
            stateMutex.withLock { openDevices.remove(device) }
            runCatching { device.markConnectionInvalid(TapUsageException("Connection($id) is closed")) }
            throw TapUsageException("Connection($id) is closed; Device.open rejected")
        }
        return device
    }

    /**
     * Closes explicitly (recorded as a client request), then drops the liveness stream.
     * Single-flight and idempotent: concurrent and repeated callers share one `Close` RPC
     * (60 s gRPC deadline, mapped, under a longer outer bound that maps distinctly) and one
     * shared completion that resolves only after the RPC plus the bounded attach
     * collector/scope teardown. The close transition is atomic under [stateMutex]: the shared
     * completion is published and closing is marked in one critical section, so no admission
     * passes after close ownership exists. Every caller awaits the same full outcome and
     * rethrows the same primary failure; a cleanup error becomes primary only when there is
     * no Close error, otherwise it is suppressed — never swallowed silently. If the attach
     * already dropped, the service has closed the sessions and `Close` may report
     * unknown-connection (mapped); the scope is still cleaned up exactly once.
     */
    suspend fun close() {
        val deferred: CompletableDeferred<Unit>
        val isOwner: Boolean
        withContext(NonCancellable) {
            stateMutex.withLock {
                val existing = closeDeferred
                if (existing != null) {
                    deferred = existing
                    isOwner = false
                } else {
                    deferred = CompletableDeferred()
                    closeDeferred = deferred
                    closeStarted.set(true)
                    isOwner = true
                }
            }
        }
        if (!isOwner) {
            withContext(NonCancellable) { deferred.await() }
            return
        }
        withContext(NonCancellable) {
            var closeError: Throwable? = null
            try {
                try {
                    withTimeout(closeOuterBoundMs) {
                        mapped {
                            client.connections
                                .withDeadlineAfter(60, TimeUnit.SECONDS)
                                .close(CloseConnectionRequest.newBuilder().setConnectionId(id).build())
                        }
                    }
                } catch (bound: TimeoutCancellationException) {
                    closeError =
                        ServiceException(
                            "DEADLINE_EXCEEDED",
                            "connection $id close timed out after ${closeOuterBoundMs}ms " +
                                "(outer bound past the 60s Close deadline)",
                            bound,
                        )
                } catch (primary: Throwable) {
                    closeError = primary
                }
            } finally {
                var cleanupError: Throwable? = null
                try {
                    val completed =
                        withTimeoutOrNull(teardownBoundMs) {
                            val job = stateMutex.withLock { attachJob }
                            try {
                                job?.cancelAndJoin()
                            } catch (thrown: Throwable) {
                                if (cleanupError == null) cleanupError = thrown
                            }
                            try {
                                attachScope.cancel()
                            } catch (thrown: Throwable) {
                                if (cleanupError == null) cleanupError = thrown
                            }
                        }
                    if (completed == null) {
                        // Bound hit with a stubborn collector: cancel the scope outside the timed
                        // block so the liveness stream still drops (never fails the close by itself).
                        runCatching { attachScope.cancel() }
                    }
                } catch (thrown: Throwable) {
                    if (cleanupError == null) cleanupError = thrown
                }
                val primary = closeError
                when {
                    primary != null && cleanupError != null -> {
                        runCatching { primary.addSuppressed(cleanupError) }
                        deferred.completeExceptionally(primary)
                    }
                    primary != null -> deferred.completeExceptionally(primary)
                    cleanupError != null -> deferred.completeExceptionally(cleanupError)
                    else -> deferred.complete(Unit)
                }
            }
            deferred.await()
        }
    }

    /** Throws when this connection can no longer admit work. */
    internal fun ensureUsable(operation: String) {
        if (closeStarted.get()) {
            throw TapUsageException("Connection($id) is closed; $operation rejected")
        }
        unusableCause.get()?.let { cause ->
            throw ServiceException(
                "UNAVAILABLE",
                "connection $id liveness stream ended; $operation rejected (${cause.message})",
                cause,
            )
        }
        if (unusable.get()) {
            throw ServiceException("UNAVAILABLE", "connection $id liveness stream ended; $operation rejected")
        }
    }

    private fun markUnusable(cause: Throwable) {
        if (unusable.compareAndSet(false, true)) {
            unusableCause.compareAndSet(null, cause)
            openDevices.forEach { it.markConnectionInvalid(cause) }
        }
    }

    internal fun register(device: Device) {
        openDevices.add(device)
    }

    /** Removes a closed handle so the registry retains only live handles. Test-observable. */
    internal fun unregister(device: Device) {
        openDevices.remove(device)
    }

    /** Live (opened, not yet closed) handles. Internal observer for tests. */
    internal val liveDeviceCount: Int get() = openDevices.size

    companion object {
        /** Outer bound past the 60 s Close gRPC deadline. Test seam (must stay longer). */
        internal var closeOuterBoundMs: Long = 65_000L

        /** Bound for the attach collector/scope teardown. Test seam. */
        internal var teardownBoundMs: Long = 5_000L
    }

    private suspend fun boundedCancelJoin(job: Job) {
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                runCatching { job.cancelAndJoin() }
            }
            if (job.isActive) runCatching { attachScope.cancel() }
        }
    }

    private suspend fun closeIdBestEffort(setupFailure: Throwable) {
        withContext(NonCancellable) {
            val closeError =
                withTimeoutOrNull(10_000) {
                    runCatching {
                        mapped {
                            client.connections
                                .withDeadlineAfter(60, TimeUnit.SECONDS)
                                .close(CloseConnectionRequest.newBuilder().setConnectionId(id).build())
                        }
                    }.exceptionOrNull()
                }
            if (closeError != null) runCatching { setupFailure.addSuppressed(closeError) }
        }
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                runCatching {
                    stateMutex.withLock { attachJob }?.cancelAndJoin()
                }
                runCatching { attachScope.cancel() }
            } ?: runCatching { attachScope.cancel() }
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
        try {
            // Own-timeout ownership: a null return is our 2 s probe timing out (dead service
            // -> false); an outer CancellationException propagates with its identity intact.
            val answered =
                withTimeoutOrNull(2_000) {
                    ConnectionServiceGrpcKt
                        .ConnectionServiceCoroutineStub(channel)
                        .withDeadlineAfter(2, TimeUnit.SECONDS)
                        .info(InfoRequest.getDefaultInstance())
                    true
                } ?: return false
            return answered
        } catch (_: Exception) {
            return false
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                channel.shutdownNow()
                channel.awaitTermination(2, TimeUnit.SECONDS)
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
    /** Test seam (same-module fakes): replaces process creation. Null means `ProcessBuilder`. */
    internal var processStarter: ((List<String>) -> Process)? = null

    /** [address] of the service and whether this call [started] it (false = it was already running). */
    data class StartResult(
        val address: String,
        val started: Boolean,
    )

    /**
     * Starts a service in the background unless one is already running in [stateDir]. Extra
     * `tap serve` options (`--adb PATH`) go in [options]. Runs the executable on
     * `Dispatchers.IO` with a bounded wait; the calling coroutine stays cancellable while
     * waiting (polling with `delay`, so cancellation destroys the process promptly).
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
            val command = listOf(executable) + args
            val starter = processStarter
            val process =
                starter?.invoke(command)
                    ?: ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            val outputDeferred = CompletableDeferred<String>()
            val drain =
                Thread {
                    try {
                        outputDeferred.complete(process.inputStream.bufferedReader().readText())
                    } catch (error: Throwable) {
                        outputDeferred.completeExceptionally(error)
                    }
                }.also {
                    it.isDaemon = true
                    it.start()
                }
            try {
                // Own-timeout ownership: withTimeoutOrNull returns null only for our own
                // [timeout]; an outer withTimeout/cancellation throws CancellationException
                // and propagates with its identity (never reported as our timeout).
                val exited =
                    withTimeoutOrNull(timeout) {
                        while (process.isAlive) delay(20)
                        true
                    }
                if (exited == null) {
                    withContext(NonCancellable) {
                        runCatching { process.destroyForcibly() }
                        withTimeoutOrNull(5_000) {
                            while (process.isAlive) delay(20)
                        }
                    }
                    throw TapException("`tap ${args.first()}` did not finish within $timeout")
                }
                val output =
                    withTimeoutOrNull(5_000) { outputDeferred.await() }
                        ?: throw TapException("`tap ${args.first()}` output drain timed out")
                drain.join(1_000)
                if (process.exitValue() != 0) {
                    throw TapException("`tap ${args.first()}` failed (exit ${process.exitValue()}): ${output.trim()}")
                }
                output
            } catch (cancelled: CancellationException) {
                // Outer cancellation (caller cancel or outer withTimeout): always destroy and
                // reap, then rethrow the original to preserve cancellation identity.
                withContext(NonCancellable) {
                    runCatching { process.destroyForcibly() }
                    withTimeoutOrNull(5_000) {
                        while (process.isAlive) delay(20)
                    }
                }
                throw cancelled
            }
        }
}
