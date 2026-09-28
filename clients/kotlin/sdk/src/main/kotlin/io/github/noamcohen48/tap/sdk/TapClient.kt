package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.ListDevicesRequest
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.api.v1.ObserveResponse
import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A channel to a Tap host server. Discovery order: [address]; the `tap.server` system
 * property / `TAP_SERVER` (`host:port`); a live `daemon.json` in the state dir
 * (`TAP_STATE_DIR`, default `~/.tap`). The client never starts the daemon: run `tap start`
 * (or [TapDaemonProcess.start]) first. A started daemon stays up like the ADB server
 * until `tap stop`.
 *
 * Every RPC carries the daemon's bearer token (`authorization: Bearer <token>`): the one in
 * `daemon.json` when the address was discovered there, else [token] / `tap.token` /
 * `TAP_TOKEN` for an explicit address. A wrong or missing token fails every call with
 * [ServerException] `UNAUTHENTICATED`.
 *
 * Construction takes an explicit address and performs no I/O; use [create] to resolve one
 * (which probes the server with `Info`). All RPCs are `suspend` over grpc-kotlin
 * `CoroutineStub`s: per-call `withDeadlineAfter` is still applied server-side, while caller
 * (or test-root) cancellation promptly cancels the gRPC call client-side. Closing is
 * `suspend` and drops `AutoCloseable`: callers use `try`/`finally` inside a coroutine.
 *
 * The primary constructor is an advanced seam (tests, custom transports): a client over an
 * existing [channel]. Production callers use [create].
 */
class TapClient public constructor(
    val address: String,
    val channel: ManagedChannel,
    token: String? = null,
) {
    /**
     * Creates a client for [address] with its own channel, sending [token] (default: `tap.token`
     * / `TAP_TOKEN`). Performs no I/O.
     */
    constructor(address: String, token: String? = DaemonDiscovery.explicitToken()) : this(
        address,
        ManagedChannelBuilder
            .forTarget(address)
            .usePlaintext()
            .maxInboundMessageSize(64 * 1024 * 1024)
            .build(),
        token,
    )

    private val callChannel: Channel = authorized(channel, token)
    internal val clientConnections = ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineStub(callChannel)
    internal val devices = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(callChannel)
    internal val apps = AppServiceGrpcKt.AppServiceCoroutineStub(callChannel)

    /** Daemon version and pid, protocol version, ADB executable, state dir, whether a driver is available. */
    suspend fun info(): ServerInfo =
        mapped {
            clientConnections.withDeadlineAfter(10, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance())
        }.toModel()

    /**
     * Every device ADB lists, with its state (`FREE`, `LEASED`, `QUARANTINED`, `OFFLINE`,
     * `UNAUTHORIZED`); only `FREE` and `LEASED` devices can be attached.
     */
    suspend fun devices(): List<DeviceEntry> =
        mapped {
            devices
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .listDevices(ListDevicesRequest.getDefaultInstance())
                .devicesList
                .map { it.toModel() }
        }

    /**
     * Opens a [TapConnection] and starts its liveness observation: if this process dies, the server
     * detaches every device the connection owns. The observe stream is established before this
     * returns (the first `Observe` event, `observing`, is awaited), so every later `attachDevice`
     * belongs to a live connection. An empty stream, a `closing` first event or any failure
     * before the first event fails the connect and closes the newly opened id under a bounded
     * non-cancellable context. A later `closing` event (the daemon reaped this connection or is
     * shutting down) makes the connection unusable with the daemon's reason.
     */
    suspend fun connect(name: String): TapConnection {
        val id =
            mapped {
                clientConnections
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .connect(ConnectRequest.newBuilder().setName(name).build())
                    .clientConnectionId
            }
        // observe() closes the new id itself when it fails before the first event.
        return TapConnection(this, id).also { it.observe() }
    }

    /**
     * Shuts the channel down. Runs under [NonCancellable] with bounded waits so teardown
     * completes even when the caller is cancelled: `shutdown`, a bounded await, then
     * `shutdownNow` plus a second bounded await for forced termination.
     * Close every [TapConnection] first.
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

    /**
     * Runs [block] with this client, then [close]s it. A [block] failure wins; a close failure
     * after it is added as suppressed.
     */
    suspend fun <R> use(block: suspend (TapClient) -> R): R = closing({ close() }) { block(this) }

    companion object {
        /**
         * Resolves the server ([address], else `tap.server`/`TAP_SERVER`, else the live
         * `daemon.json`) and returns a client for it. An explicit address sends [token], else
         * `tap.token`/`TAP_TOKEN`; a discovered one sends the token from `daemon.json`.
         * Discovery probes the server with `Info`, so a dead descriptor fails here with "no
         * running tap server; run `tap start`" rather than on the first call.
         */
        suspend fun create(
            address: String? = null,
            token: String? = null,
        ): TapClient {
            val endpoint =
                if (address != null) {
                    DaemonEndpoint(address, token ?: DaemonDiscovery.explicitToken())
                } else {
                    DaemonDiscovery.resolveEndpoint()
                }
            return TapClient(endpoint.address, endpoint.token)
        }
    }
}

/**
 * Immutable per-connection bounds. Injected at construction; tests create isolated
 * [TapConnection] instances with short bounds instead of mutating shared state, so parallel
 * test runs stay deterministic. Defaults cover production (60 s Close deadline + margin,
 * bounded attach teardown).
 */
internal data class TapConnectionBounds(
    val closeOuterMs: Long = 65_000L,
    val teardownMs: Long = 5_000L,
)

/**
 * This process's identity at the server: every [Device] it attaches belongs to it and is detached
 * with it — explicitly by [close], or by the server when the process goes away. One per process
 * is the norm; see [TapClient.connect].
 *
 * Liveness is an explicitly owned [observeScope]: a `SupervisorJob + Dispatchers.IO` scope that
 * lives exactly as long as this connection. [observe] collects the server-streaming `Observe`
 * `Flow` in that scope and waits for the first event before returning, so the stream is
 * established before any `AttachDevice`. When the scope is cancelled by [close] (or the process
 * dies) the server notices the dropped stream and detaches every device of this connection.
 * Never `GlobalScope`: [close] sends `Disconnect` first, then cancels and joins the collection.
 *
 * Lifecycle is linearized: exactly one `Observe` is ever started ([observe] fails on a second
 * call); an empty stream or any failure before the first event fails the connect and closes
 * the newly opened id under a bounded non-cancellable context. Any normal completion or
 * failure of the stream *after* the first event is unexpected: the connection is atomically
 * marked unusable (the terminal cause is retained, never swallowed), further [attachDevice]
 * calls and every admitted [Device] operation are rejected locally, and registered device
 * handles are marked invalid. [close] is single-flight: concurrent and repeated callers share
 * one `Disconnect` RPC (60 s gRPC deadline, mapped) and one bounded non-cancellable teardown that
 * cancels and joins the collector even for a stubborn (cancellation-ignoring) collector,
 * preserving the primary `Disconnect` failure and suppressing cleanup failures.
 */
class TapConnection internal constructor(
    val client: TapClient,
    val id: String,
    private val bounds: TapConnectionBounds = TapConnectionBounds(),
    private val deviceBounds: DeviceBounds = DeviceBounds(),
) {
    private val events = CopyOnWriteArrayList<String>()
    private val observeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var observeJob: Job? = null
    private val observeStarted = AtomicBoolean(false)
    private val established = AtomicBoolean(false)
    private val unusable = AtomicBoolean(false)
    private val unusableCause = AtomicReference<Throwable?>(null)
    private val closeStarted = AtomicBoolean(false)
    private var closeDeferred: CompletableDeferred<Unit>? = null
    private val duplicateCloseObserved = AtomicInteger(0)
    private val attachedDevices = CopyOnWriteArrayList<Device>()

    /**
     * The liveness stream's events so far (diagnostics, last 200): `observing` and
     * `closing: <reason>`; heartbeats are not recorded.
     */
    val recentEvents: List<String> get() = events.toList()

    /** True once the stream terminated unexpectedly after establishment (or was never usable). */
    val isInvalid: Boolean get() = unusable.get()

    /** True once [close] started. */
    val isClosed: Boolean get() = closeStarted.get()

    /** True while the connection can still attach devices: neither closed nor [isInvalid]. */
    val isUsable: Boolean get() = !isClosed && !isInvalid

    internal suspend fun observe() {
        if (!observeStarted.compareAndSet(false, true)) {
            throw TapUsageException("TapConnection($id).observe must run exactly once")
        }
        val flow: Flow<ObserveResponse>
        try {
            flow = client.clientConnections.observe(ObserveRequest.newBuilder().setClientConnectionId(id).build())
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
            observeScope.launch {
                var sawFirst = false
                try {
                    flow.collect { event: ObserveResponse ->
                        val closing =
                            if (event.hasClosing()) {
                                TapException("daemon closed client connection $id: ${event.closing.reason}")
                            } else {
                                null
                            }
                        when {
                            event.hasObserving() -> record("observing")
                            closing != null -> record("closing: ${event.closing.reason}")
                        }
                        sawFirst = true
                        if (!firstEvent.isCompleted) {
                            if (closing != null) firstEvent.completeExceptionally(closing) else firstEvent.complete(Unit)
                        } else if (closing != null && !closeStarted.get()) {
                            markUnusable(closing)
                        }
                    }
                    if (!sawFirst) {
                        val empty = TapException("connection $id attach completed without emitting (empty stream)")
                        if (!firstEvent.isCompleted) {
                            firstEvent.completeExceptionally(empty)
                        } else {
                            markUnusable(empty)
                        }
                    } else {
                        markUnusable(TapException("connection $id observe stream ended unexpectedly after establishment"))
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
        stateMutex.withLock { observeJob = job }
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
            .filter { it.state == DeviceState.FREE || it.state == DeviceState.LEASED }
            .sortedBy { it.state != DeviceState.FREE }
            .map { it.serial }

    /**
     * Attach [serial] for [autPackage]. The underlying device session holds the device's
     * per-serial lock until [Device.detach]; if another session holds it, attachment fails with
     * [DeviceBusyException] — at once, or after [DeviceOptions.waitForDevice]. Rejected locally
     * without an RPC once the connection is closed or its liveness stream ended unexpectedly.
     */
    suspend fun attachDevice(
        serial: String,
        autPackage: String,
        timeouts: Timeouts = Timeouts(),
        options: DeviceOptions = DeviceOptions(),
    ): Device {
        ensureUsable("Device.attachDevice")
        val device = Device.attachDevice(this, serial, autPackage, timeouts, options, deviceBounds)
        val terminal: Throwable? =
            stateMutex.withLock {
                attachedDevices.add(device)
                unusableCause.get()
            }
        // A concurrent invalidation between the pre-RPC gate and registration must still
        // surface on the just-registered handle; the registry keeps the handle until close.
        terminal?.let { device.markConnectionInvalid(it) }
        // A concurrent close between the RPC and registration must not admit a live handle
        // past close ownership: drop it from the registry and fail locally. The server-side
        // attachment is already covered by the connection Disconnect (or best-effort detached here).
        if (closeStarted.get()) {
            stateMutex.withLock { attachedDevices.remove(device) }
            runCatching { device.markConnectionInvalid(TapUsageException("TapConnection($id) is closed")) }
            throw TapUsageException("TapConnection($id) is closed; Device.attachDevice rejected")
        }
        return device
    }

    /**
     * Attaches [serial] for [autPackage] ([attachDevice]), runs [block] with the device and
     * always detaches it afterwards. Runs inside the caller's `tapTest` / `tapScope`, or installs
     * a [TapContext] of its own when there is none, so a script needs no `tapScope`. A [block]
     * failure wins and a detach failure after it is added as suppressed; when [block] succeeds,
     * a detach failure (including quarantine) is thrown.
     */
    suspend fun <R> attach(
        serial: String,
        autPackage: String,
        timeouts: Timeouts = Timeouts(),
        options: DeviceOptions = DeviceOptions(),
        block: suspend (Device) -> R,
    ): R {
        suspend fun run(): R {
            val device = attachDevice(serial, autPackage, timeouts, options)
            return closing({ withContext(NonCancellable) { device.detach() } }) { block(device) }
        }
        return if (currentCoroutineContext()[TapContext] != null) run() else withContext(TapContext("attach:$serial")) { run() }
    }

    /**
     * Runs [block] with this connection, then [close]s it. A [block] failure wins; a close
     * failure after it is added as suppressed.
     */
    suspend fun <R> use(block: suspend (TapConnection) -> R): R = closing({ close() }) { block(this) }

    /**
     * Closes explicitly (recorded as a client request), then drops the liveness stream.
     * Single-flight and idempotent: concurrent and repeated callers share one `Disconnect` RPC
     * (60 s gRPC deadline, mapped, under a longer outer bound that maps distinctly) and one
     * shared completion that resolves only after the RPC plus the bounded observe
     * collector/scope teardown. The close transition is atomic under [stateMutex]: the shared
     * completion is published and closing is marked in one critical section, so no admission
     * passes after close ownership exists. Every caller awaits the same full outcome and
     * rethrows the same primary failure; a cleanup error becomes primary only when there is
     * no Disconnect error, otherwise it is suppressed — never swallowed silently. If observation
     * already dropped, the server has detached the devices and `Disconnect` may report
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
            duplicateCloseObserved.incrementAndGet()
            withContext(NonCancellable) { deferred.await() }
            return
        }
        withContext(NonCancellable) {
            var closeError: Throwable? = null
            try {
                try {
                    withTimeout(bounds.closeOuterMs) {
                        mapped {
                            client.clientConnections
                                .withDeadlineAfter(60, TimeUnit.SECONDS)
                                .disconnect(DisconnectRequest.newBuilder().setClientConnectionId(id).build())
                        }
                    }
                } catch (bound: TimeoutCancellationException) {
                    closeError =
                        ServerException(
                            "DEADLINE_EXCEEDED",
                            "connection $id close timed out after ${bounds.closeOuterMs}ms " +
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
                        withTimeoutOrNull(bounds.teardownMs) {
                            val job = stateMutex.withLock { observeJob }
                            try {
                                job?.cancelAndJoin()
                            } catch (thrown: Throwable) {
                                if (cleanupError == null) cleanupError = thrown
                            }
                            try {
                                observeScope.cancel()
                            } catch (thrown: Throwable) {
                                if (cleanupError == null) cleanupError = thrown
                            }
                        }
                    if (completed == null) {
                        // Bound hit with a stubborn collector: cancel the scope outside the timed
                        // block so the liveness stream still drops (never fails the close by itself).
                        runCatching { observeScope.cancel() }
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

                    primary != null -> {
                        deferred.completeExceptionally(primary)
                    }

                    cleanupError != null -> {
                        deferred.completeExceptionally(cleanupError)
                    }

                    else -> {
                        deferred.complete(Unit)
                    }
                }
            }
            deferred.await()
        }
    }

    /** Throws when this connection can no longer admit work. */
    internal fun ensureUsable(operation: String) {
        if (closeStarted.get()) {
            throw TapUsageException("TapConnection($id) is closed; $operation rejected")
        }
        unusableCause.get()?.let { cause ->
            throw ServerException(
                "UNAVAILABLE",
                "connection $id liveness stream ended; $operation rejected (${cause.message})",
                cause,
            )
        }
        if (unusable.get()) {
            throw ServerException("UNAVAILABLE", "connection $id liveness stream ended; $operation rejected")
        }
    }

    private fun record(event: String) {
        events.add(event)
        if (events.size > 200) events.removeAt(0)
    }

    private fun markUnusable(cause: Throwable) {
        if (unusable.compareAndSet(false, true)) {
            unusableCause.compareAndSet(null, cause)
            attachedDevices.forEach { it.markConnectionInvalid(cause) }
        }
    }

    internal fun register(device: Device) {
        attachedDevices.add(device)
    }

    /** Removes a closed handle so the registry retains only live handles. Test-observable. */
    internal fun unregister(device: Device) {
        attachedDevices.remove(device)
    }

    /** Live (attached, not yet detached) handles. Internal observer for tests. */
    internal val liveDeviceCount: Int get() = attachedDevices.size

    /**
     * Duplicates that observed the shared close completion after the owner published it.
     * Incremented after a non-owner sees [closeDeferred], before it awaits the shared
     * outcome: a test parks the Disconnect RPC, waits for this to reach duplicates, then releases
     * the RPC, proving every duplicate joined the single flight deterministically.
     */
    internal val duplicateCloseCount: Int get() = duplicateCloseObserved.get()

    private suspend fun boundedCancelJoin(job: Job) {
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                runCatching { job.cancelAndJoin() }
            }
            if (job.isActive) runCatching { observeScope.cancel() }
        }
    }

    private suspend fun closeIdBestEffort(setupFailure: Throwable) {
        withContext(NonCancellable) {
            val closeError =
                withTimeoutOrNull(10_000) {
                    runCatching {
                        mapped {
                            client.clientConnections
                                .withDeadlineAfter(60, TimeUnit.SECONDS)
                                .disconnect(DisconnectRequest.newBuilder().setClientConnectionId(id).build())
                        }
                    }.exceptionOrNull()
                }
            if (closeError != null) runCatching { setupFailure.addSuppressed(closeError) }
        }
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                runCatching {
                    stateMutex.withLock { observeJob }?.cancelAndJoin()
                }
                runCatching { observeScope.cancel() }
            } ?: runCatching { observeScope.cancel() }
        }
    }
}

/** A server address and the bearer token its RPCs carry (null: none). */
data class DaemonEndpoint(
    val address: String,
    val token: String?,
) {
    override fun toString(): String = "DaemonEndpoint($address, token=${if (token == null) "none" else "***"})"
}

private val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

/** [channel] with `authorization: Bearer <token>` on every call, or [channel] itself without a token. */
internal fun authorized(
    channel: Channel,
    token: String?,
): Channel {
    if (token == null) return channel
    val headers = Metadata().apply { put(AUTHORIZATION, "Bearer $token") }
    return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
}

/** Finds a running server; never starts one (see [TapDaemonProcess]). */
object DaemonDiscovery {
    /**
     * Per-call probe dependencies for [running]. Immutable: each [running] invocation gets
     * its own instance, so parallel tests stay deterministic with no shared mutation.
     * Production uses the defaults (real plaintext channel, real `Info` probe, 2 s own
     * timeout); tests pass fakes for this invocation only.
     */
    internal data class DiscoveryDeps(
        val channelFactory: (String) -> ManagedChannel = { address ->
            ManagedChannelBuilder.forTarget(address).usePlaintext().build()
        },
        val infoProbe: suspend (Channel) -> Unit = { channel ->
            ClientConnectionServiceGrpcKt
                .ClientConnectionServiceCoroutineStub(channel)
                .withDeadlineAfter(2, TimeUnit.SECONDS)
                .info(InfoRequest.getDefaultInstance())
            Unit
        },
        val probeTimeoutMs: Long = 2_000L,
    )

    fun stateDir(): Path =
        System.getenv("TAP_STATE_DIR")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".tap")

    /** The token for an explicitly given address: `tap.token` / `TAP_TOKEN`, else null. */
    fun explicitToken(): String? = (System.getProperty("tap.token") ?: System.getenv("TAP_TOKEN"))?.takeIf { it.isNotBlank() }

    /**
     * `tap.server` / `TAP_SERVER`, else the address in a live `daemon.json`. Probes the
     * descriptor with `Info` over a short-lived coroutine stub; a dead descriptor fails with
     * "no running tap server; run `tap start`".
     */
    suspend fun resolve(): String = resolveEndpoint().address

    /**
     * [resolve] plus the token: an explicit `tap.server` / `TAP_SERVER` pairs with
     * [explicitToken]; a discovered server with the token in its `daemon.json`.
     */
    suspend fun resolveEndpoint(): DaemonEndpoint {
        (System.getProperty("tap.server") ?: System.getenv("TAP_SERVER"))
            ?.takeIf { it.isNotBlank() }
            ?.let { return DaemonEndpoint(it, explicitToken()) }
        val dir = stateDir()
        return runningEndpoint(dir, DiscoveryDeps())
            ?: throw TapException("no running tap server (no live descriptor in $dir); run `tap start`")
    }

    /** Address of the server `daemon.json` in [dir] points at, if it answers `Info`. */
    suspend fun running(dir: Path = stateDir()): String? = running(dir, DiscoveryDeps())

    /**
     * Per-call injectable [running]: tests pass their own channel factory and suspending
     * info probe for this invocation only. Exercises the same [alive] implementation as
     * production (own-timeout ownership, cancellation catch/rethrow, NonCancellable
     * shutdown/await); only the dependencies differ.
     */
    internal suspend fun running(
        dir: Path,
        deps: DiscoveryDeps,
    ): String? = runningEndpoint(dir, deps)?.address

    private suspend fun runningEndpoint(
        dir: Path,
        deps: DiscoveryDeps,
    ): DaemonEndpoint? {
        val descriptor = readDescriptor(dir) ?: return null
        val endpoint = DaemonEndpoint("127.0.0.1:${descriptor.port}", descriptor.token)
        return if (alive(endpoint, deps)) endpoint else null
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

    /** `daemon.json` contents the client needs: the port and the bearer token. */
    internal data class Descriptor(
        val port: Int,
        val token: String?,
    )

    /** Parses `daemon.json` in [dir]; null when absent, unreadable, not JSON or without a port. */
    internal fun readDescriptor(dir: Path): Descriptor? =
        runCatching {
            val json = Json.parseToJsonElement(Files.readString(dir.resolve("daemon.json"))).jsonObject
            val port = json["port"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: return null
            val token = json["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            Descriptor(port, token)
        }.getOrNull()

    private suspend fun alive(
        endpoint: DaemonEndpoint,
        deps: DiscoveryDeps,
    ): Boolean {
        val channel = deps.channelFactory(endpoint.address)
        try {
            // Own-timeout ownership: a null return is our probe timing out (dead server
            // -> false); an outer CancellationException (including TimeoutCancellationException
            // from an outer withTimeout) propagates with its identity intact, never mapped.
            val answered =
                withTimeoutOrNull(deps.probeTimeoutMs) {
                    deps.infoProbe(authorized(channel, endpoint.token))
                    true
                } ?: return false
            return answered
        } catch (cancelled: CancellationException) {
            throw cancelled
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
 * Explicit daemon lifecycle from a test process: `tap start` and `tap stop` through the `tap`
 * executable ([DaemonDiscovery.findBinary]). The executable is the only thing that spawns a
 * server; it picks the port, detaches the process and waits for `Info` to answer.
 */
object TapDaemonProcess {
    private val processBuilder: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() }

    /** [address] of the server and whether this call [started] it (false = it was already running). */
    data class StartResult(
        val address: String,
        val started: Boolean,
    )

    /**
     * Starts a server in the background unless one is already running in [stateDir]. Extra
     * `tap serve` options (`--adb PATH`) go in [options]. Runs the executable on
     * `Dispatchers.IO` with a bounded wait; the calling coroutine stays cancellable while
     * waiting on the process exit (cancellation destroys the process promptly).
     */
    suspend fun start(
        binary: String? = null,
        stateDir: Path = DaemonDiscovery.stateDir(),
        options: List<String> = emptyList(),
        timeout: Duration = 45.seconds,
    ): StartResult = start(binary, stateDir, options, timeout, processBuilder)

    /** [start] with an injected process factory (same-module fakes). */
    internal suspend fun start(
        binary: String?,
        stateDir: Path = DaemonDiscovery.stateDir(),
        options: List<String> = emptyList(),
        timeout: Duration,
        starter: (List<String>) -> Process,
    ): StartResult {
        val output = run(binary, listOf("start", "--state-dir", stateDir.toString()) + options, timeout, starter)
        val match =
            Regex("""^(started|running) (\S+)""", RegexOption.MULTILINE).find(output)
                ?: throw TapException("unexpected `tap start` output: $output")
        return StartResult(match.groupValues[2], started = match.groupValues[1] == "started")
    }

    /** Stops the server recorded in [stateDir]; a no-op when none is running. */
    suspend fun stop(
        binary: String? = null,
        stateDir: Path = DaemonDiscovery.stateDir(),
        timeout: Duration = 30.seconds,
    ) {
        run(binary, listOf("stop", "--state-dir", stateDir.toString()), timeout, processBuilder)
    }

    private suspend fun run(
        binary: String?,
        args: List<String>,
        timeout: Duration,
        starter: (List<String>) -> Process,
    ): String =
        withContext(Dispatchers.IO) {
            val executable =
                binary ?: DaemonDiscovery.findBinary()
                    ?: throw TapException("no `tap` executable found (set tap.bin / TAP_BIN or add it to PATH)")
            val command = listOf(executable) + args
            val process = starter(command)
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
                        process.onExit().await()
                        true
                    }
                if (exited == null) {
                    withContext(NonCancellable) {
                        runCatching { process.destroyForcibly() }
                        withTimeoutOrNull(5_000) {
                            process.onExit().await()
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
                        process.onExit().await()
                    }
                }
                throw cancelled
            }
        }
}

/** `use` for suspend-closed resources: a [block] failure wins, a [close] failure after it is suppressed. */
internal suspend fun <R> closing(
    close: suspend () -> Unit,
    block: suspend () -> R,
): R {
    val result =
        try {
            block()
        } catch (primary: Throwable) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                if (closeFailure !== primary) primary.addSuppressed(closeFailure)
            }
            throw primary
        }
    close()
    return result
}
