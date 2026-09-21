package com.company.tap.sdk

import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.Command
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.DeviceInfo
import com.company.tap.api.v1.DeviceInfoQuery
import com.company.tap.api.v1.Direction
import com.company.tap.api.v1.DriverLogRequest
import com.company.tap.api.v1.DumpHierarchy
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.PressKey
import com.company.tap.api.v1.ScreenshotRequest
import com.company.tap.api.v1.StabilitySignal
import com.company.tap.api.v1.WaitAppVisible
import com.company.tap.api.v1.WaitScreenStable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val DEFAULT_GESTURE_PERCENT = 80

/**
 * Immutable per-device close bounds. Injected at construction; tests create isolated [Device]
 * instances (via an isolated [Connection] carrying these bounds) with short bounds instead of
 * mutating shared state, so parallel test runs stay deterministic. Defaults cover production
 * (admitted-operation drain + 120 s Session Close deadline + margin).
 */
internal data class DeviceBounds(
    val drainMs: Long = 130_000L,
    val closeOuterMs: Long = 130_000L,
)

/** Per-device defaults. Every call also accepts an explicit timeout. */
data class Timeouts(
    /** Deadline for a single action or query (tap, setText, exists...). */
    val action: Duration = 10.seconds,
    /** Default for `await(...)` conditions. */
    val wait: Duration = 10.seconds,
    /** Default for app launches and screenshots. */
    val lifecycle: Duration = 30.seconds,
    val pollInterval: Duration = 100.milliseconds,
)

/** Session-open options; the defaults use the driver bundled in the service. */
data class DeviceOptions(
    val driverApk: String? = null,
    val driverTestApk: String? = null,
    val skipDriverInstall: Boolean = false,
    val syncAuthority: String? = null,
    val allowedSystemPackages: List<String> = emptyList(),
    /** How long to wait for a device another session holds before failing; zero fails at once. */
    val waitForDevice: Duration = Duration.ZERO,
)

/**
 * One device under one live driver session, proxied by the host service. All calls are
 * `suspend` and serialized on the device; use `coroutineScope` + `async` for concurrency
 * across devices (sibling failure cancels the other's in-flight RPC). Nothing here caches UI
 * state: [element] returns a lazy selector that every action resolves again, and mutations
 * fail with `AMBIGUOUS`/`NOT_FOUND` before any input when the selector does not match exactly
 * one node.
 *
 * Every suspending call requires an owning scope (`tapScope` in scripts, `tapTest` in JUnit);
 * construction ([open]) does too. Closing is `suspend` (no `AutoCloseable`): callers use
 * `try`/`finally` inside the scope. Per-call `withDeadlineAfter` is still applied
 * server-side; caller cancellation promptly cancels the gRPC call client-side.
 *
 * Command admission is linearized with [close]: each operation is admitted under a short lock
 * (closed/invalidated gates checked at admission, an in-flight counter incremented), runs its
 * RPC without holding the lock, then releases the counter in a cancellation-safe `finally`.
 * Admission is reentrant by counting, so compound helpers ([awaitUntil], `ElementWait`,
 * `App` calls that fan back into [execute]) never deadlock. Once [closeAndReport] starts,
 * new operations are rejected locally with [TapUsageException] and the connection-invalid
 * gate ([ServiceException] `UNAVAILABLE`) applies at the same admission point; admitted
 * operations finish before the single `Session Close` RPC (120 s gRPC deadline, mapped).
 * Concurrent and duplicate closes share one RPC and one result, including the quarantine
 * detail.
 */
class Device internal constructor(
    val connection: Connection,
    val sessionId: String,
    val serial: String,
    val generation: Long,
    /** The AUT package the session was opened for; AUT-scoped selectors resolve in it. */
    val autPackage: String,
    val timeouts: Timeouts,
    private val bounds: DeviceBounds = DeviceBounds(),
) {
    private val client get() = connection.client
    private val stateMutex = Mutex()
    private var activeOps = 0
    private var drain: CompletableDeferred<Unit>? = null
    private val closeStarted = AtomicBoolean(false)
    private var closeDeferred: CompletableDeferred<String?>? = null
    private val connectionInvalid = AtomicBoolean(false)
    private val connectionInvalidCause = AtomicReference<Throwable?>(null)
    // Explicitly owned fail-closed scope: SupervisorJob + IO, one per Device, never GlobalScope.
    // It hosts at most one fail-closed job (drain-timeout path only) and is cancelled after
    // every terminal close path, so no background work outlives the handle.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var failClosedJob: Job? = null

    /** True once the drain-timeout path launched its fail-closed job. Test-observable. */
    internal val failClosedStarted: Boolean get() = failClosedJob != null

    /** The fail-closed job, if launched. Test-observable (must be terminal after fail-closed). */
    internal val failClosedHandle: Job? get() = failClosedJob

    /** True once the owned cleanup scope terminated. Test-observable. */
    internal val cleanupTerminated: Boolean get() = cleanupScope.coroutineContext[Job]?.isCompleted == true

    /** True once [closeAndReport] started (new operations are rejected locally). */
    val isClosed: Boolean get() = closeStarted.get()

    // --- Raw protocol escape hatch ----------------------------------------------------------------

    /**
     * Runs one protocol command and returns the result as data (the outcome may be `error`).
     * [build] sets exactly one `op` case on the builder; [timeout] defaults to [Timeouts.action].
     */
    suspend fun execute(
        timeout: Duration? = null,
        build: Command.Builder.() -> Unit,
    ): CommandResult {
        ensureTapBound("Device.execute")
        return admitted("Device.execute") {
            val command =
                Command
                    .newBuilder()
                    .setTimeoutMs((timeout ?: timeouts.action).inWholeMilliseconds)
                    .apply(build)
                    .build()
            mapped(serial) {
                client.sessions
                    .withDeadlineAfter(command.timeoutMs + 60_000, TimeUnit.MILLISECONDS)
                    .execute(
                        ExecuteRequest
                            .newBuilder()
                            .setSessionId(sessionId)
                            .setCommand(command)
                            .build(),
                    )
            }
        }
    }

    /** [execute] that throws [CommandException] instead of returning a failed result. */
    suspend fun executeOrThrow(
        timeout: Duration? = null,
        selector: Selector? = null,
        build: Command.Builder.() -> Unit,
    ): CommandResult {
        ensureTapBound("Device.executeOrThrow")
        return admitted("Device.executeOrThrow") {
            val command = Command.newBuilder().apply(build).build()
            // Build the wire command inline (instead of calling execute()) so admission is
            // counted once per terminal RPC; nested admission would also be correct (counting
            // is reentrant) but a single count keeps close-drain accounting exact.
            val wire =
                Command
                    .newBuilder()
                    .setTimeoutMs((timeout ?: timeouts.action).inWholeMilliseconds)
                    .mergeFrom(command)
                    .build()
            val result =
                mapped(serial) {
                    client.sessions
                        .withDeadlineAfter(wire.timeoutMs + 60_000, TimeUnit.MILLISECONDS)
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setSessionId(sessionId)
                                .setCommand(wire)
                                .build(),
                        )
                }
            if (result.hasError()) throw CommandException(result, "execute", serial, selector?.render())
            result
        }
    }

    // --- Elements and waits -------------------------------------------------------------------------

    /** A lazy [Element] for [selector]. Performs no I/O, so it is not suspend. */
    fun element(selector: Selector): Element = Element(this, selector)

    /** An [ElementWait] on [selector]. Performs no I/O, so it is not suspend. */
    fun await(
        selector: Selector,
        timeout: Duration = timeouts.wait,
    ): ElementWait = ElementWait(this, selector, timeout)

    /** The [App] for [packageName] (default: the app under test). Performs no I/O, so it is not suspend. */
    fun app(packageName: String = autPackage): App = App(this, packageName)

    /** Serial, API level, model and display size. */
    suspend fun info(): DeviceInfo = executeOrThrow { deviceInfo = DeviceInfoQuery.getDefaultInstance() }.deviceInfo

    /** Send `KEYCODE_BACK`. */
    suspend fun pressBack() = pressKey(KEYCODE_BACK)

    /** Send `KEYCODE_HOME`. */
    suspend fun pressHome() = pressKey(KEYCODE_HOME)

    /** Injects one Android key code (a mutation: never replayed on transport loss). */
    suspend fun pressKey(keyCode: Int) {
        executeOrThrow { pressKey = PressKey.newBuilder().setKeyCode(keyCode).build() }
    }

    /** PNG bytes, verified against the driver's checksum. */
    suspend fun screenshot(timeout: Duration = timeouts.lifecycle): ByteArray {
        ensureTapBound("Device.screenshot")
        return admitted("Device.screenshot") {
            mapped(serial) {
                client.sessions
                    .withDeadlineAfter(timeout.inWholeMilliseconds + 60_000, TimeUnit.MILLISECONDS)
                    .screenshot(
                        ScreenshotRequest
                            .newBuilder()
                            .setSessionId(sessionId)
                            .setTimeoutMs(timeout.inWholeMilliseconds)
                            .build(),
                    ).png
                    .toByteArray()
            }
        }
    }

    /** Diagnostic accessibility XML. Never used by selectors; keep it out of assertions. */
    suspend fun dumpHierarchy(timeout: Duration = timeouts.lifecycle): String =
        executeOrThrow(timeout) { dumpHierarchy = DumpHierarchy.getDefaultInstance() }.text

    /** The driver instrumentation's recent output lines. */
    suspend fun driverLog(): List<String> {
        ensureTapBound("Device.driverLog")
        return admitted("Device.driverLog") {
            mapped(serial) {
                client.sessions
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .driverLog(DriverLogRequest.newBuilder().setSessionId(sessionId).build())
                    .linesList
            }
        }
    }

    /** Waits on the device until [packageName] owns the focused window. */
    suspend fun awaitAppVisible(
        packageName: String = autPackage,
        timeout: Duration = timeouts.wait,
    ) {
        ensureTapBound("Device.awaitAppVisible")
        admitted("Device.awaitAppVisible") {
            val command =
                Command
                    .newBuilder()
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .setWaitAppVisible(WaitAppVisible.newBuilder().setPackageName(packageName).build())
                    .build()
            val result =
                mapped(serial) {
                    client.sessions
                        .withDeadlineAfter(command.timeoutMs + 60_000, TimeUnit.MILLISECONDS)
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setSessionId(sessionId)
                                .setCommand(command)
                                .build(),
                        )
                }
            if (result.hasError()) {
                throw WaitTimeoutException(
                    "package $packageName to be in the foreground",
                    serial,
                    result.durationMs,
                    0,
                    "currentPackage=${runCatching { infoInner() }.getOrNull()?.currentPackage}",
                )
            }
        }
    }

    /**
     * Waits on the device until the AUT's focused window has stopped changing for [stableFor]
     * according to [signal]: the accessibility tree ([StabilitySignal.STABILITY_TREE]), the
     * window pixels ([StabilitySignal.STABILITY_PIXELS], 0.5 % tolerance) or both (default).
     * Content-changed events restart the quiet period. Use it explicitly after an action that
     * starts an animation or a transition; no command waits for this implicitly. A screen that
     * keeps changing (indeterminate spinner, ticker, video) times out with `SCREEN_CHANGING`.
     * [awaitAppSettled] and [awaitAnimationEnd] are the two single-signal shorthands.
     */
    suspend fun awaitScreenStable(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
        signal: StabilitySignal = StabilitySignal.STABILITY_ALL,
    ) {
        ensureTapBound("Device.awaitScreenStable")
        admitted("Device.awaitScreenStable") {
            val command =
                Command
                    .newBuilder()
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .setWaitScreenStable(
                        WaitScreenStable
                            .newBuilder()
                            .setPackageName(packageName)
                            .setStableForMs(stableFor.inWholeMilliseconds)
                            .setSignal(signal)
                            .build(),
                    ).build()
            val result =
                mapped(serial) {
                    client.sessions
                        .withDeadlineAfter(command.timeoutMs + 60_000, TimeUnit.MILLISECONDS)
                        .execute(
                            ExecuteRequest
                                .newBuilder()
                                .setSessionId(sessionId)
                                .setCommand(command)
                                .build(),
                        )
                }
            if (result.hasError()) {
                val what =
                    when (signal) {
                        StabilitySignal.STABILITY_TREE -> "hierarchy"
                        StabilitySignal.STABILITY_PIXELS -> "pixels"
                        else -> "screen"
                    }
                throw WaitTimeoutException(
                    "the $packageName $what to stay unchanged for $stableFor",
                    serial,
                    result.durationMs,
                    0,
                    if (result.error.hasDetail()) result.error.detail else null,
                )
            }
        }
    }

    /**
     * Maestro's `waitForAppToSettle`, on request only: the accessibility hierarchy of the AUT's
     * window has not changed for [stableFor]. Cheap (no screenshots); sees layout, text and
     * state changes but not pure drawing (a canvas animation, video).
     */
    suspend fun awaitAppSettled(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
    ) = awaitScreenStable(stableFor, timeout, packageName, StabilitySignal.STABILITY_TREE)

    /**
     * Maestro's `waitForAnimationToEnd`, on request only: the AUT's window pixels have not
     * changed (beyond 0.5 %) for [stableFor]. Costs one screenshot per 100 ms while waiting.
     */
    suspend fun awaitAnimationEnd(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
    ) = awaitScreenStable(stableFor, timeout, packageName, StabilitySignal.STABILITY_PIXELS)

    /**
     * Host-side polling for conditions the driver cannot evaluate in one command (cross-device,
     * backend state). Prefer [await] for UI conditions: it polls on the device in one RPC.
     * Uses [delay], so test-root cancellation and sibling failure cancel the poll promptly.
     * Admitted like any command, so [closeAndReport] waits for an in-flight poll and rejects
     * new polls once close starts; the [condition] runs inside the admission (reentrant), so
     * device helpers called from it do not deadlock.
     */
    suspend fun awaitUntil(
        description: String,
        timeout: Duration = timeouts.wait,
        pollInterval: Duration = timeouts.pollInterval,
        observe: suspend () -> String? = { null },
        condition: suspend () -> Boolean,
    ) {
        ensureTapBound("Device.awaitUntil")
        admitted("Device.awaitUntil") {
            val started = System.nanoTime()
            val deadline = started + timeout.inWholeNanoseconds
            var polls = 0
            while (true) {
                polls++
                if (condition()) return@admitted
                if (System.nanoTime() >= deadline) {
                    throw WaitTimeoutException(
                        description,
                        serial,
                        (System.nanoTime() - started) / 1_000_000,
                        polls,
                        runCatching { observe() }.getOrNull(),
                    )
                }
                delay(pollInterval)
            }
        }
    }

    /**
     * Closes the session. Returns the quarantine detail when the device could not be left
     * clean (the pool keeps it out of circulation), else null. Single-flight: concurrent and
     * duplicate callers share one `Session Close` RPC (120 s gRPC deadline, mapped) and one
     * shared completion, including the quarantine detail. Admitted operations finish before the
     * RPC under an explicit total drain bound ([DeviceBounds.drainMs]); operations starting
     * after close began are rejected locally. A close invoked from the same admitted operation
     * (for example an [awaitUntil] condition calling close) fails immediately with
     * [TapUsageException] instead of waiting for itself. Runs under a bounded non-cancellable
     * context so teardown completes even when the caller is cancelled.
     *
     * Drain-timeout contract (fail closed): when admitted operations are still in flight past
     * the per-instance drain bound, the first caller throws `DEADLINE_EXCEEDED` immediately
     * and exactly one fail-closed job is launched on the explicitly owned per-Device
     * [cleanupScope] (never `GlobalScope`). That job issues exactly one bounded
     * Connection Close — the authoritative server-side teardown for all sessions, so no
     * Session Close follows (no unbounded drain wait, no Session/Connection TOCTOU, no
     * fabricated unknown-session, no clean-null quarantine claim) — then marks the
     * connection unusable, marks every registered handle invalid, and removes registry
     * entries before publishing the shared terminal failure. The original caller throws the
     * documented `DEADLINE_EXCEEDED`; subsequent callers await the same shared completion
     * and receive the same terminal failure instance. A Connection Close failure is
     * suppressed into that failure (observable via `suppressed`) without stranding the
     * registry or the scope: invalidation, removal and scope cancellation still run. The
     * owned scope/job terminates after the bounded Connection Close. The normal path
     * (drain succeeds) still issues one bounded Session Close and returns its quarantine
     * result.
     */
    suspend fun closeAndReport(): String? {
        ensureTapBound("Device.close")
        if (currentCoroutineContext()[DeviceAdmission]?.device === this) {
            throw TapUsageException("Device($serial).close invoked from its own admitted operation; refusing to self-wait")
        }
        val deferred: CompletableDeferred<String?>
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
                    if (activeOps > 0) drain = CompletableDeferred()
                    isOwner = true
                }
            }
        }
        if (!isOwner) {
            return withContext(NonCancellable) { deferred.await() }
        }
        return withContext(NonCancellable) {
            val toDrain = stateMutex.withLock { drain }
            if (toDrain != null) {
                val drained = withTimeoutOrNull(bounds.drainMs) { toDrain.await() }
                if (drained == null) {
                    // Fail closed: report the drain timeout immediately and let exactly one
                    // owned job tear the connection down. The shared completion stays pending
                    // until registry removal, so duplicates share the same terminal failure.
                    val failClosed =
                        ServiceException(
                            "DEADLINE_EXCEEDED",
                            "device $serial close drain timed out after ${bounds.drainMs}ms " +
                                "with admitted operations still in flight; fail-closed Connection close " +
                                "triggered (authoritative server-side teardown, no Session Close)",
                        )
                    launchFailClosed(failClosed, deferred)
                    throw failClosed
                }
            }
            try {
                val detail = boundedSessionClose()
                deferred.complete(detail)
                detail
            } catch (primary: Throwable) {
                deferred.completeExceptionally(primary)
                throw primary
            } finally {
                runCatching { connection.unregister(this@Device) }
                runCatching { cleanupScope.cancel() }
            }
        }
    }

    /** [closeAndReport] that fails when the device was quarantined. */
    suspend fun close() {
        closeAndReport()?.let { throw TapException("$serial quarantined on close: $it") }
    }

    override fun toString(): String = "Device($serial, generation=$generation)"

    /**
     * Admits one operation: rejects locally when close started ([TapUsageException]) or the
     * connection liveness stream ended ([ServiceException] `UNAVAILABLE`), then counts the
     * operation until [block] finishes. Admission carries a coroutine-context token
     * ([DeviceAdmission]): a nested call for the same device runs inline without double
     * counting, so compound helpers ([awaitUntil] conditions, `Element`/`ElementWait` terminal
     * calls, [App] calls) never deadlock and close-drain accounting stays exact. Release is
     * cancellation-safe and wakes a closing waiter when the last admitted operation leaves.
     */
    internal suspend fun <T> admitted(
        operation: String,
        block: suspend () -> T,
    ): T {
        if (currentCoroutineContext()[DeviceAdmission]?.device === this) {
            return block()
        }
        stateMutex.withLock {
            if (closeStarted.get()) {
                throw TapUsageException("Device($serial) is closed; $operation rejected")
            }
            connectionInvalidCause.get()?.let { cause ->
                throw ServiceException(
                    "UNAVAILABLE",
                    "connection ${connection.id} liveness stream ended; $operation on $serial rejected (${cause.message})",
                    cause,
                )
            }
            if (connectionInvalid.get()) {
                throw ServiceException(
                    "UNAVAILABLE",
                    "connection ${connection.id} liveness stream ended; $operation on $serial rejected",
                )
            }
            try {
                connection.ensureUsable(operation)
            } catch (invalid: ServiceException) {
                markConnectionInvalid(invalid.cause ?: invalid)
                throw invalid
            }
            activeOps++
        }
        try {
            return withContext(DeviceAdmission(this)) { block() }
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    activeOps--
                    if (closeStarted.get() && activeOps == 0) {
                        drain?.complete(Unit)
                    }
                }
            }
        }
    }

    /** Marks this handle invalid after the connection liveness stream ended unexpectedly. */
    internal fun markConnectionInvalid(cause: Throwable) {
        connectionInvalid.set(true)
        connectionInvalidCause.compareAndSet(null, cause)
    }

    /** One bounded Session Close RPC (120 s gRPC deadline under the per-instance outer bound). */
    private suspend fun boundedSessionClose(): String? {
        val response =
            try {
                withTimeout(bounds.closeOuterMs) {
                    mapped(serial) {
                        client.sessions
                            .withDeadlineAfter(120, TimeUnit.SECONDS)
                            .close(CloseSessionRequest.newBuilder().setSessionId(sessionId).build())
                    }
                }
            } catch (bound: TimeoutCancellationException) {
                throw ServiceException(
                    "DEADLINE_EXCEEDED",
                    "device $serial close timed out after ${bounds.closeOuterMs}ms " +
                        "(outer bound past the 120s Session Close deadline)",
                    bound,
                )
            }
        return if (response.clean) null else response.detail
    }

    /**
     * Launches the exactly-once fail-closed teardown on the owned [cleanupScope]. Issues one
     * bounded Connection Close (authoritative teardown, no Session Close), then invalidates
     * every handle and clears the registry before publishing the shared terminal [failure].
     * A Connection Close failure is suppressed into [failure] (still observable) without
     * stranding invalidation, removal or scope cancellation. The scope is cancelled after the
     * bounded close so the job terminates; nothing escapes as an unhandled exception.
     */
    private fun launchFailClosed(
        failure: ServiceException,
        deferred: CompletableDeferred<String?>,
    ) {
        failClosedJob =
            cleanupScope.launch {
                var closeError: Throwable? = null
                try {
                    connection.close()
                } catch (failed: Throwable) {
                    closeError = failed
                }
                try {
                    connection.failClosedInvalidate(failure)
                } catch (failed: Throwable) {
                    if (closeError == null) closeError = failed
                }
                val suppressed = closeError
                if (suppressed != null && suppressed !== failure) {
                    runCatching { failure.addSuppressed(suppressed) }
                }
                runCatching { deferred.completeExceptionally(failure) }
                runCatching { cleanupScope.cancel() }
            }
    }

    /** `info()` without re-entering admission (for use inside an already-admitted block). */
    private suspend fun infoInner(): DeviceInfo {
        val command =
            Command
                .newBuilder()
                .setTimeoutMs(timeouts.action.inWholeMilliseconds)
                .setDeviceInfo(DeviceInfoQuery.getDefaultInstance())
                .build()
        val result =
            mapped(serial) {
                client.sessions
                    .withDeadlineAfter(command.timeoutMs + 60_000, TimeUnit.MILLISECONDS)
                    .execute(
                        ExecuteRequest
                            .newBuilder()
                            .setSessionId(sessionId)
                            .setCommand(command)
                            .build(),
                    )
            }
        if (result.hasError()) throw CommandException(result, "info", serial, null)
        return result.deviceInfo
    }

    companion object {
        internal suspend fun open(
            connection: Connection,
            serial: String,
            autPackage: String,
            timeouts: Timeouts,
            options: DeviceOptions,
            bounds: DeviceBounds = DeviceBounds(),
        ): Device {
            ensureTapBound("Device.open")
            connection.ensureUsable("Device.open")
            val request =
                OpenSessionRequest
                    .newBuilder()
                    .setConnectionId(connection.id)
                    .setSerial(serial)
                    .setAutPackage(autPackage)
                    .setDefaultTimeoutMs(timeouts.action.inWholeMilliseconds)
                    .setLeaseTimeoutMs(options.waitForDevice.inWholeMilliseconds)
                    .addAllAllowedSystemPackages(options.allowedSystemPackages)
                    .apply {
                        options.driverApk?.let { setDriverApk(it) }
                        options.driverTestApk?.let { setDriverTestApk(it) }
                        if (options.skipDriverInstall) setSkipDriverInstall(true)
                        options.syncAuthority?.let { setSyncAuthority(it) }
                    }.build()
            val response =
                mapped(serial) {
                    connection.client.sessions
                        .withDeadlineAfter(180 + options.waitForDevice.inWholeSeconds, TimeUnit.SECONDS)
                        .open(request)
                }
            return Device(connection, response.sessionId, response.serial, response.generation, autPackage, timeouts, bounds)
        }
    }
}

/** Direction aliases without the proto prefix. */
object Directions {
    val UP: Direction = Direction.DIR_UP
    val DOWN: Direction = Direction.DIR_DOWN
    val LEFT: Direction = Direction.DIR_LEFT
    val RIGHT: Direction = Direction.DIR_RIGHT
}

/**
 * Coroutine-context token marking the body of one admitted [Device] operation. Nested helpers
 * for the same device observe it and run inline (no double count, no deadlock); [Device]
 * close observes it and fails immediately instead of waiting for itself.
 */
internal class DeviceAdmission(
    val device: Device,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<DeviceAdmission>

    override val key: CoroutineContext.Key<*> get() = Key
}
