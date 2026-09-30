package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AttachRequest
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DetachRequest
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.api.v1.DumpHierarchy
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.ScreenshotRequest
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.api.v1.WaitScreenStable
import kotlinx.coroutines.CancellationException
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
 * Extra time the gRPC deadline of a device RPC allows past the command's own timeout: the
 * server enforces the command timeout on the device, and the slack covers the server-side
 * session handshake and transport so that a command timeout is reported by the device as a
 * result rather than as a client-side `DEADLINE_EXCEEDED`.
 */
internal const val RPC_DEADLINE_SLACK_MS = 60_000L

/**
 * Immutable per-device close bounds. Injected at construction; tests create isolated [Device]
 * instances (via an isolated [TapConnection] carrying these bounds) with short bounds instead of
 * mutating shared state, so parallel test runs stay deterministic. Defaults cover production
 * (admitted-operation drain + 120 s DetachDevice deadline + margin).
 */
internal data class DeviceBounds(
    val drainMs: Long = 130_000L,
    val detachOuterMs: Long = 130_000L,
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
) {
    companion object {
        /** Default quiet period for `App.awaitIdle`. */
        val IDLE_STABLE_FOR: Duration = 200.milliseconds

        /** Default wait for a device another session holds (the JUnit `tap.acquireTimeoutSeconds`). */
        val ACQUIRE: Duration = 300.seconds
    }
}

/**
 * Device-attachment options. The driver is always the daemon's (bundled, or the APKs given to
 * `tap serve --driver-apk X --driver-test-apk Y`); [skipDriverInstall] only skips reinstalling it.
 */
data class DeviceOptions(
    /** Skip installing the daemon's driver: the device already has the right one. */
    val skipDriverInstall: Boolean = false,
    /** Content-provider authority of the AUT's `sync-sdk`, when it is not the default. Experimental. */
    @property:ExperimentalTapApi
    val syncAuthority: String? = null,
    /** How long to wait for a device another session holds before failing; zero fails at once. */
    val waitForDevice: Duration = Duration.ZERO,
)

/**
 * One attached device backed by one live driver session, proxied by the host server. All calls are
 * `suspend`. The client does not serialize calls on one device: issue them sequentially (the
 * normal test style), and use `coroutineScope` + `async` for concurrency across devices
 * (sibling failure cancels the other's in-flight RPC). Nothing here caches UI
 * state: [element] returns a lazy selector that every action resolves again, and mutations
 * fail with `AMBIGUOUS`/`NOT_FOUND` before any input when the selector does not match exactly
 * one node.
 *
 * Every suspending call requires an owning scope (`tapScope` in scripts, `tapTest` in JUnit);
 * construction ([attachDevice]) does too. Detaching is `suspend` (no `AutoCloseable`): callers use
 * `try`/`finally` inside the scope. Per-call `withDeadlineAfter` is still applied
 * server-side; caller cancellation promptly cancels the gRPC call client-side.
 *
 * Command admission is linearized with [detach]: each operation is admitted under a short lock
 * (closed/invalidated gates checked at admission, an in-flight counter incremented), runs its
 * RPC without holding the lock, then releases the counter in a cancellation-safe `finally`.
 * Admission is reentrant by counting, so compound helpers ([awaitUntil], `ElementWait`,
 * `App` calls that fan back into [execute]) never deadlock. Once [detachAndReport] starts,
 * new operations are rejected locally with [TapUsageException] and the connection-invalid
 * gate ([ServerException] `UNAVAILABLE`) applies at the same admission point; admitted
 * operations finish before the single `Detach` RPC (120 s gRPC deadline, mapped).
 * Concurrent and duplicate closes share one RPC and one result, including the quarantine
 * detail.
 */
class Device internal constructor(
    val ownerConnection: TapConnection,
    val attachedDeviceId: String,
    val serial: String,
    val generation: Long,
    /** The AUT package the device was attached for; AUT-scoped selectors resolve in it. */
    val autPackage: String,
    val timeouts: Timeouts,
    private val bounds: DeviceBounds = DeviceBounds(),
) {
    private val client get() = ownerConnection.client
    private val stateMutex = Mutex()
    private var activeOps = 0
    private var drain: CompletableDeferred<Unit>? = null
    private val detachStarted = AtomicBoolean(false)
    private var detachDeferred: CompletableDeferred<String?>? = null
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

    /** True once [detachAndReport] started (new operations are rejected locally). */
    val isDetached: Boolean get() = detachStarted.get()

    // --- Protocol commands (internal: the public API is the typed methods) ------------------------

    /**
     * Runs one protocol command and returns the result as data (the outcome may be `error`).
     * [build] sets exactly one `op` case on the builder; [timeout] defaults to [Timeouts.action].
     */
    internal suspend fun execute(
        timeout: Duration? = null,
        build: Command.Builder.() -> Unit,
    ): CommandResult {
        ensureTapBound("Device.execute")
        return admitted("Device.execute") {
            rpcExecute(timeout ?: timeouts.action, build)
        }
    }

    /** [execute] that throws [CommandException] instead of returning a failed result. */
    internal suspend fun executeOrThrow(
        timeout: Duration? = null,
        selector: Selector? = null,
        build: Command.Builder.() -> Unit,
    ): CommandResult {
        ensureTapBound("Device.executeOrThrow")
        return admitted("Device.executeOrThrow") {
            val result = rpcExecute(timeout ?: timeouts.action, build)
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

    /** API level, model, display size and the package owning the focused window. */
    suspend fun info(): DeviceInfo = executeOrThrow { deviceInfo = DeviceInfoQuery.getDefaultInstance() }.deviceInfo.toModel()

    /** Send `KEYCODE_BACK`. */
    suspend fun pressBack() = pressKey(KEYCODE_BACK)

    /** Send `KEYCODE_HOME`. */
    suspend fun pressHome() = pressKey(KEYCODE_HOME)

    /** Injects one Android key code (a mutation: never replayed on transport loss). */
    suspend fun pressKey(keyCode: Int) {
        executeOrThrow { pressKey = PressKey.newBuilder().setKeyCode(keyCode).build() }
    }

    /**
     * Opens the notification shade (the system's accessibility action, as a swipe down from the
     * status bar would). Only whether the system accepted it is reported: wait for what the test
     * needs in the shade, and [pressBack] closes it.
     */
    suspend fun openNotifications() = openSystemPanel(SystemPanel.SYSTEM_PANEL_NOTIFICATIONS)

    /** Opens the quick settings panel; otherwise as [openNotifications]. */
    suspend fun openQuickSettings() = openSystemPanel(SystemPanel.SYSTEM_PANEL_QUICK_SETTINGS)

    private suspend fun openSystemPanel(panel: SystemPanel) {
        executeOrThrow { openSystemPanel = OpenSystemPanel.newBuilder().setPanel(panel).build() }
    }

    /**
     * Types [value] as real key events into whatever has input focus now: no target and no
     * click (see [Element.typeText] for tap-then-type). Unsupported characters are rejected
     * before any input with `INVALID_REQUEST`/`UNSUPPORTED_CHARACTERS`; otherwise it reports
     * whether every key event was accepted. Where the characters landed is for the test to assert.
     */
    suspend fun typeText(
        value: String,
        timeout: Duration? = null,
    ) {
        executeOrThrow(timeout ?: timeouts.action) { typeText = TypeText.newBuilder().setText(value).build() }
    }

    /**
     * A PNG [Screenshot] of the screen, with its size. The server verifies the bytes against the
     * driver's checksum and the client checks the returned `sha256` again, so a corrupted
     * transfer fails instead of producing a broken file. Keep it with [Screenshot.save] or use
     * [Screenshot.bytes] directly.
     */
    suspend fun screenshot(timeout: Duration = timeouts.lifecycle): Screenshot {
        ensureTapBound("Device.screenshot")
        return admitted("Device.screenshot") {
            val response =
                mapped(serial) {
                    client.devices
                        .withDeadlineAfter(timeout.inWholeMilliseconds + RPC_DEADLINE_SLACK_MS, TimeUnit.MILLISECONDS)
                        .screenshot(
                            ScreenshotRequest
                                .newBuilder()
                                .setClientConnectionId(ownerConnection.id)
                                .setAttachedDeviceId(attachedDeviceId)
                                .setTimeoutMs(timeout.inWholeMilliseconds)
                                .build(),
                        )
                }
            val png = response.png.toByteArray()
            if (response.sha256.isNotEmpty()) {
                val actual = sha256Hex(png)
                if (!actual.equals(response.sha256, ignoreCase = true)) {
                    throw TapException("screenshot of $serial failed its checksum: sha256 $actual, server said ${response.sha256}")
                }
            }
            Screenshot.png(png)
        }
    }

    /** The diagnostic accessibility [Hierarchy]. Never used by selectors; keep it out of assertions. */
    suspend fun dumpHierarchy(timeout: Duration = timeouts.lifecycle): Hierarchy =
        Hierarchy(executeOrThrow(timeout) { dumpHierarchy = DumpHierarchy.getDefaultInstance() }.text)

    /** The driver instrumentation's recent output. */
    suspend fun driverLog(): DriverLog {
        ensureTapBound("Device.driverLog")
        return admitted("Device.driverLog") {
            mapped(serial) {
                client.devices
                    .withDeadlineAfter(30, TimeUnit.SECONDS)
                    .driverLog(
                        DriverLogRequest
                            .newBuilder()
                            .setClientConnectionId(ownerConnection.id)
                            .setAttachedDeviceId(attachedDeviceId)
                            .build(),
                    )
                    .linesList
                    .let(::DriverLog)
            }
        }
    }

    /**
     * Waits on the device until [packageName] owns the focused window. Throws
     * [WaitTimeoutException] only when the device reports `WAIT_TIMEOUT`; any other failure
     * (driver unhealthy, transport lost, ...) is a [CommandException].
     */
    suspend fun awaitAppVisible(
        packageName: String = autPackage,
        timeout: Duration = timeouts.wait,
    ) {
        ensureTapBound("Device.awaitAppVisible")
        admitted("Device.awaitAppVisible") {
            val result =
                rpcExecute(timeout) {
                    waitAppVisible = WaitAppVisible.newBuilder().setPackageName(packageName).build()
                }
            if (result.hasError()) {
                if (result.error.code != ErrorCodeProto.ERR_WAIT_TIMEOUT) {
                    throw CommandException(result, "wait_app_visible", serial, null)
                }
                throw WaitTimeoutException.of(
                    result,
                    "package $packageName to be in the foreground",
                    serial,
                    "currentPackage=${observeOrNull { infoInner() }?.currentPackage}",
                )
            }
        }
    }

    /**
     * Waits on the device until the AUT's focused window has stopped changing for [stableFor]
     * according to [signal]: the accessibility tree ([StabilitySignal.TREE]), the
     * window pixels ([StabilitySignal.PIXELS], 0.5 % tolerance) or both (default).
     * Content-changed events restart the quiet period. Use it explicitly after an action that
     * starts an animation or a transition; no command waits for this implicitly. A screen that
     * keeps changing (indeterminate spinner, ticker, video) times out with `SCREEN_CHANGING`.
     * [awaitAppSettled] and [awaitAnimationEnd] are the two single-signal shorthands. Only a
     * device `WAIT_TIMEOUT` becomes [WaitTimeoutException]; other failures are [CommandException]s.
     */
    suspend fun awaitScreenStable(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
        signal: StabilitySignal = StabilitySignal.ALL,
    ) {
        ensureTapBound("Device.awaitScreenStable")
        admitted("Device.awaitScreenStable") {
            val result =
                rpcExecute(timeout) {
                    waitScreenStable =
                        WaitScreenStable
                            .newBuilder()
                            .setPackageName(packageName)
                            .setStableForMs(stableFor.inWholeMilliseconds)
                            .setSignal(signal.toProto())
                            .build()
                }
            if (result.hasError()) {
                if (result.error.code != ErrorCodeProto.ERR_WAIT_TIMEOUT) {
                    throw CommandException(result, "wait_screen_stable", serial, null)
                }
                val what =
                    when (signal) {
                        StabilitySignal.TREE -> "hierarchy"
                        StabilitySignal.PIXELS -> "pixels"
                        StabilitySignal.ALL -> "screen"
                    }
                throw WaitTimeoutException.of(result, "the $packageName $what to stay unchanged for $stableFor", serial)
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
    ) = awaitScreenStable(stableFor, timeout, packageName, StabilitySignal.TREE)

    /**
     * Maestro's `waitForAnimationToEnd`, on request only: the AUT's window pixels have not
     * changed (beyond 0.5 %) for [stableFor]. Costs one screenshot per 100 ms while waiting.
     */
    suspend fun awaitAnimationEnd(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
    ) = awaitScreenStable(stableFor, timeout, packageName, StabilitySignal.PIXELS)

    /**
     * Host-side polling for conditions the driver cannot evaluate in one command (cross-device,
     * backend state). Prefer [await] for UI conditions: it polls on the device in one RPC.
     * Uses [delay], so test-root cancellation and sibling failure cancel the poll promptly.
     * Admitted like any command, so [detachAndReport] waits for an in-flight poll and rejects
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
                        observeOrNull { observe() },
                    )
                }
                delay(pollInterval)
            }
        }
    }

    /**
     * Detaches the device. Returns the quarantine detail when the device could not be left
     * clean (the pool keeps it out of circulation), else null. Single-flight: concurrent and
     * duplicate callers share one `Detach` RPC (120 s gRPC deadline, mapped) and one
     * shared completion, including the quarantine detail. Admitted operations finish before the
     * RPC under an explicit total drain bound ([DeviceBounds.drainMs]); operations starting
     * after detach began are rejected locally. A detach invoked from the same admitted operation
     * (for example an [awaitUntil] condition calling detach) fails immediately with
     * [TapUsageException] instead of waiting for itself. Runs under a bounded non-cancellable
     * context so teardown completes even when the caller is cancelled.
     *
     * Drain-timeout contract (fail closed, this device only): when admitted operations are
     * still in flight past the per-instance drain bound, the first caller throws
     * `DEADLINE_EXCEEDED` immediately. The handle is already poisoned (detach started, so every
     * new operation is rejected locally) and exactly one fail-closed job is launched on the
     * explicitly owned per-Device [cleanupScope] (never `GlobalScope`). That job sends one
     * best-effort, bounded `Detach` for this device — the server tears the session down
     * even with the stuck command in flight — then unregisters the handle and publishes the
     * shared terminal failure. The owner [TapConnection] and every other device attached
     * through it are left untouched: one wedged command must not tear down unrelated devices
     * (in JUnit, the connection is shared by every later test). A `Detach` failure or
     * quarantine detail is suppressed into that failure (observable via `suppressed`) without
     * stranding the registry or the scope. Subsequent callers await the same shared
     * completion and receive the same terminal failure instance. The normal path (drain
     * succeeds) issues one bounded `Detach` and returns its quarantine result.
     */
    suspend fun detachAndReport(): String? {
        ensureTapBound("Device.detach")
        if (DeviceAdmission.holds(this)) {
            throw TapUsageException("Device($serial).detach invoked from its own admitted operation; refusing to self-wait")
        }
        val deferred: CompletableDeferred<String?>
        val isOwner: Boolean
        withContext(NonCancellable) {
            stateMutex.withLock {
                val existing = detachDeferred
                if (existing != null) {
                    deferred = existing
                    isOwner = false
                } else {
                    deferred = CompletableDeferred()
                    detachDeferred = deferred
                    detachStarted.set(true)
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
                        ServerException(
                            "DEADLINE_EXCEEDED",
                            "device $serial detach drain timed out after ${bounds.drainMs}ms " +
                                "with admitted operations still in flight; fail-closed: best-effort DetachDevice " +
                                "for this device only (the client connection and other devices stay attached)",
                        )
                    launchFailClosed(failClosed, deferred)
                    throw failClosed
                }
            }
            try {
                val detail = boundedDetach()
                deferred.complete(detail)
                detail
            } catch (primary: Throwable) {
                deferred.completeExceptionally(primary)
                throw primary
            } finally {
                runCatching { ownerConnection.unregister(this@Device) }
                runCatching { cleanupScope.cancel() }
            }
        }
    }

    /** [detachAndReport] that fails when the device was quarantined. */
    suspend fun detach() {
        detachAndReport()?.let { throw TapException("$serial quarantined on detach: $it") }
    }

    override fun toString(): String = "Device($serial, generation=$generation)"

    /**
     * Admits one operation: rejects locally when detach started ([TapUsageException]) or the
     * connection liveness stream ended ([ServerException] `UNAVAILABLE`), then counts the
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
        if (DeviceAdmission.holds(this)) {
            return block()
        }
        stateMutex.withLock {
            if (detachStarted.get()) {
                throw TapUsageException("Device($serial) is closed; $operation rejected")
            }
            connectionInvalidCause.get()?.let { cause ->
                throw ServerException(
                    "UNAVAILABLE",
                    "client connection ${ownerConnection.id} liveness stream ended; $operation on $serial rejected (${cause.message})",
                    cause,
                )
            }
            if (connectionInvalid.get()) {
                throw ServerException(
                    "UNAVAILABLE",
                    "client connection ${ownerConnection.id} liveness stream ended; $operation on $serial rejected",
                )
            }
            try {
                ownerConnection.ensureUsable(operation)
            } catch (invalid: ServerException) {
                markConnectionInvalid(invalid.cause ?: invalid)
                throw invalid
            }
            activeOps++
        }
        try {
            return withContext(DeviceAdmission.including(this)) { block() }
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    activeOps--
                    if (detachStarted.get() && activeOps == 0) {
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

    /** One bounded AttachedDevice Detach RPC (120 s gRPC deadline under the per-instance outer bound). */
    private suspend fun boundedDetach(): String? {
        val response =
            try {
                withTimeout(bounds.detachOuterMs) {
                    mapped(serial) {
                        client.devices
                            .withDeadlineAfter(120, TimeUnit.SECONDS)
                            .detach(
                                DetachRequest
                                    .newBuilder()
                                    .setClientConnectionId(ownerConnection.id)
                                    .setAttachedDeviceId(attachedDeviceId)
                                    .build(),
                            )
                    }
                }
            } catch (bound: TimeoutCancellationException) {
                throw ServerException(
                    "DEADLINE_EXCEEDED",
                    "device $serial detach timed out after ${bounds.detachOuterMs}ms " +
                        "(outer bound past the 120s DetachDevice deadline)",
                    bound,
                )
            }
        return if (response.clean) null else response.detail
    }

    /**
     * Launches the exactly-once fail-closed teardown on the owned [cleanupScope]: one bounded,
     * best-effort `Detach` for this device, then registry removal, then the shared
     * terminal [failure]. A detach failure (or quarantine detail) is suppressed into [failure]
     * without stranding removal or scope cancellation; nothing escapes as an unhandled
     * exception, and the owner connection is never closed from here.
     */
    private fun launchFailClosed(
        failure: ServerException,
        deferred: CompletableDeferred<String?>,
    ) {
        failClosedJob =
            cleanupScope.launch {
                try {
                    boundedDetach()?.let { detail ->
                        failure.addSuppressed(TapException("$serial quarantined on fail-closed detach: $detail"))
                    }
                } catch (failed: Throwable) {
                    if (failed !== failure) runCatching { failure.addSuppressed(failed) }
                }
                runCatching { ownerConnection.unregister(this@Device) }
                runCatching { deferred.completeExceptionally(failure) }
                runCatching { cleanupScope.cancel() }
            }
    }

    /** `info()` without re-entering admission (for use inside an already-admitted block). */
    private suspend fun infoInner(): DeviceInfo {
        val result = rpcExecute(timeouts.action) { deviceInfo = DeviceInfoQuery.getDefaultInstance() }
        if (result.hasError()) throw CommandException(result, "info", serial, null)
        return result.deviceInfo.toModel()
    }

    /**
     * The one `Execute` RPC every command goes through: [build] sets the `op`; [timeout] becomes
     * the command timeout unless [build] sets one, and the gRPC deadline allows
     * [RPC_DEADLINE_SLACK_MS] on top. Does not admit; callers run it inside [admitted].
     */
    private suspend fun rpcExecute(
        timeout: Duration,
        build: Command.Builder.() -> Unit,
    ): CommandResult {
        val command =
            Command
                .newBuilder()
                .setTimeoutMs(timeout.inWholeMilliseconds)
                .apply(build)
                .build()
        return mapped(serial) {
            client.devices
                .withDeadlineAfter(command.timeoutMs + RPC_DEADLINE_SLACK_MS, TimeUnit.MILLISECONDS)
                .execute(
                    ExecuteRequest
                        .newBuilder()
                        .setClientConnectionId(ownerConnection.id)
                        .setAttachedDeviceId(attachedDeviceId)
                        .setCommand(command)
                        .build(),
                ).result
        }
    }

    /** Best-effort diagnostics for a timeout message: null on failure, but never swallows cancellation. */
    private suspend fun <T> observeOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    companion object {
        @OptIn(ExperimentalTapApi::class) // passes DeviceOptions.syncAuthority through
        internal suspend fun attachDevice(
            connection: TapConnection,
            serial: String,
            autPackage: String,
            timeouts: Timeouts,
            options: DeviceOptions,
            bounds: DeviceBounds = DeviceBounds(),
        ): Device {
            ensureTapBound("Device.attachDevice")
            connection.ensureUsable("Device.attachDevice")
            val request =
                AttachRequest
                    .newBuilder()
                    .setClientConnectionId(connection.id)
                    .setSerial(serial)
                    .setAutPackage(autPackage)
                    .setDefaultTimeoutMs(timeouts.action.inWholeMilliseconds)
                    .apply {
                        // Absent = fail at once when another session holds the device.
                        if (options.waitForDevice.isPositive()) setLeaseTimeoutMs(options.waitForDevice.inWholeMilliseconds)
                        if (options.skipDriverInstall) setSkipDriverInstall(true)
                        options.syncAuthority?.let { setSyncAuthority(it) }
                    }.build()
            val response =
                mapped(serial) {
                    connection.client.devices
                        .withDeadlineAfter(180 + options.waitForDevice.inWholeSeconds, TimeUnit.SECONDS)
                        .attach(request)
                }
            return Device(connection, response.attachedDeviceId, response.serial, response.generation, autPackage, timeouts, bounds)
        }
    }
}

/** Lower-case hex SHA-256 of [bytes]. */
internal fun sha256Hex(bytes: ByteArray): String =
    java.util.HexFormat
        .of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

/**
 * Coroutine-context token marking the body of admitted [Device] operations: every device whose
 * operation encloses the current coroutine, so an operation on B nested inside one on A keeps
 * A admitted. Nested helpers for an admitted device observe it and run inline (no double
 * count, no deadlock); [Device] close observes it and fails immediately instead of waiting
 * for itself.
 */
internal class DeviceAdmission private constructor(
    val devices: Set<Device>,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<DeviceAdmission> {
        suspend fun holds(device: Device): Boolean = currentCoroutineContext()[Key]?.devices?.contains(device) == true

        suspend fun including(device: Device): DeviceAdmission =
            DeviceAdmission(currentCoroutineContext()[Key]?.devices.orEmpty() + device)
    }

    override val key: CoroutineContext.Key<*> get() = Key
}
