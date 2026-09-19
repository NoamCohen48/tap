package com.company.tap.sdk

import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.Command
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.DeviceInfo
import com.company.tap.api.v1.Direction
import com.company.tap.api.v1.StabilitySignal
import com.company.tap.api.v1.DriverLogRequest
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.Operation
import com.company.tap.api.v1.ScreenshotRequest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val DEFAULT_GESTURE_PERCENT = 80

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
)

/**
 * One device under one live driver session, proxied by the host service. All calls are
 * blocking and serialized on the device; use separate `Device`s (and threads) for concurrency
 * across devices. Nothing here caches UI state: [element] returns a lazy selector that every
 * action resolves again, and mutations fail with `AMBIGUOUS`/`NOT_FOUND` before any input when
 * the selector does not match exactly one node.
 */
class Device internal constructor(
    val run: Run,
    val sessionId: String,
    val serial: String,
    val generation: Long,
    /** The AUT package the session was opened for; AUT-scoped selectors resolve in it. */
    val autPackage: String,
    val timeouts: Timeouts,
) : AutoCloseable {
    private val client get() = run.client
    @Volatile private var closed = false

    // --- Raw protocol escape hatch ----------------------------------------------------------------

    /** Runs one protocol operation and returns the result as data (`ok` may be false). */
    fun execute(
        operation: Operation,
        selector: Selector? = null,
        timeout: Duration? = null,
        configure: Command.Builder.() -> Unit = {},
    ): CommandResult {
        val command = Command.newBuilder()
            .setOperation(operation)
            .setTimeoutMs((timeout ?: timeouts.action).inWholeMilliseconds)
            .apply { selector?.let { setSelector(it.proto) } }
            .apply(configure)
            .build()
        return mapped(serial) {
            client.sessions.withDeadlineAfter(command.timeoutMs + 60_000, TimeUnit.MILLISECONDS)
                .execute(ExecuteRequest.newBuilder().setSessionId(sessionId).setCommand(command).build())
        }
    }

    /** [execute] that throws [CommandException] instead of returning a failed result. */
    fun executeOrThrow(
        operation: Operation,
        selector: Selector? = null,
        timeout: Duration? = null,
        configure: Command.Builder.() -> Unit = {},
    ): CommandResult {
        val result = execute(operation, selector, timeout, configure)
        if (!result.ok) throw CommandException(result, operation.name.removePrefix("OP_"), serial, selector?.render())
        return result
    }

    // --- Elements and waits -------------------------------------------------------------------------

    /** A lazy [Element] for [selector]. */
    fun element(selector: Selector): Element = Element(this, selector)

    /** An [ElementWait] on [selector]. */
    fun await(selector: Selector, timeout: Duration = timeouts.wait): ElementWait = ElementWait(this, selector, timeout)

    /** The [App] for [packageName] (default: the app under test). */
    fun app(packageName: String = autPackage): App = App(this, packageName)

    /** Serial, API level, model and display size. */
    fun info(): DeviceInfo = executeOrThrow(Operation.OP_DEVICE_INFO).deviceInfo

    /** Send `KEYCODE_BACK`. */
    fun pressBack() = pressKey(KEYCODE_BACK)
    /** Send `KEYCODE_HOME`. */
    fun pressHome() = pressKey(KEYCODE_HOME)

    /** Injects one Android key code (a mutation: never replayed on transport loss). */
    fun pressKey(keyCode: Int) {
        executeOrThrow(Operation.OP_PRESS_KEY) { setKeyCode(keyCode) }
    }

    /** PNG bytes, verified against the driver's checksum. */
    fun screenshot(timeout: Duration = timeouts.lifecycle): ByteArray = mapped(serial) {
        client.sessions.withDeadlineAfter(timeout.inWholeMilliseconds + 60_000, TimeUnit.MILLISECONDS)
            .screenshot(ScreenshotRequest.newBuilder().setSessionId(sessionId).setTimeoutMs(timeout.inWholeMilliseconds).build())
            .png.toByteArray()
    }

    /** Diagnostic accessibility XML. Never used by selectors; keep it out of assertions. */
    fun dumpHierarchy(timeout: Duration = timeouts.lifecycle): String =
        executeOrThrow(Operation.OP_DUMP_HIERARCHY, timeout = timeout).text

    /** The driver instrumentation's recent output lines. */
    fun driverLog(): List<String> = mapped(serial) {
        client.sessions.withDeadlineAfter(30, TimeUnit.SECONDS)
            .driverLog(DriverLogRequest.newBuilder().setSessionId(sessionId).build()).linesList
    }

    /** Waits on the device until [packageName] owns the focused window. */
    fun awaitAppVisible(packageName: String = autPackage, timeout: Duration = timeouts.wait) {
        val result = execute(Operation.OP_WAIT_APP_VISIBLE, timeout = timeout) { setPackageName(packageName) }
        if (!result.ok) {
            throw WaitTimeoutException(
                "package $packageName to be in the foreground", serial, result.durationMs, 0,
                "currentPackage=${runCatching { info().currentPackage }.getOrNull()}",
            )
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
    fun awaitScreenStable(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
        signal: StabilitySignal = StabilitySignal.STABILITY_ALL,
    ) {
        val result = execute(Operation.OP_WAIT_SCREEN_STABLE, timeout = timeout) {
            setPackageName(packageName)
            setStableForMs(stableFor.inWholeMilliseconds)
            setStableSignal(signal)
        }
        if (!result.ok) {
            val what = when (signal) {
                StabilitySignal.STABILITY_TREE -> "hierarchy"
                StabilitySignal.STABILITY_PIXELS -> "pixels"
                else -> "screen"
            }
            throw WaitTimeoutException(
                "the $packageName $what to stay unchanged for $stableFor", serial, result.durationMs, 0,
                result.detail.ifEmpty { null },
            )
        }
    }

    /**
     * Maestro's `waitForAppToSettle`, on request only: the accessibility hierarchy of the AUT's
     * window has not changed for [stableFor]. Cheap (no screenshots); sees layout, text and
     * state changes but not pure drawing (a canvas animation, video).
     */
    fun awaitAppSettled(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
    ) = awaitScreenStable(stableFor, timeout, packageName, StabilitySignal.STABILITY_TREE)

    /**
     * Maestro's `waitForAnimationToEnd`, on request only: the AUT's window pixels have not
     * changed (beyond 0.5 %) for [stableFor]. Costs one screenshot per 100 ms while waiting.
     */
    fun awaitAnimationEnd(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = timeouts.wait,
        packageName: String = autPackage,
    ) = awaitScreenStable(stableFor, timeout, packageName, StabilitySignal.STABILITY_PIXELS)

    /**
     * Host-side polling for conditions the driver cannot evaluate in one command (cross-device,
     * backend state). Prefer [await] for UI conditions: it polls on the device in one RPC.
     */
    fun awaitUntil(
        description: String,
        timeout: Duration = timeouts.wait,
        pollInterval: Duration = timeouts.pollInterval,
        observe: () -> String? = { null },
        condition: () -> Boolean,
    ) {
        val started = System.nanoTime()
        val deadline = started + timeout.inWholeNanoseconds
        var polls = 0
        while (true) {
            polls++
            if (condition()) return
            if (System.nanoTime() >= deadline) {
                throw WaitTimeoutException(
                    description, serial, (System.nanoTime() - started) / 1_000_000, polls, runCatching(observe).getOrNull(),
                )
            }
            Thread.sleep(pollInterval.inWholeMilliseconds)
        }
    }

    /**
     * Closes the session. Returns the quarantine detail when the device could not be left
     * clean (the pool keeps it out of circulation), else null.
     */
    fun closeAndReport(): String? {
        if (closed) return null
        closed = true
        val response = mapped(serial) {
            client.sessions.withDeadlineAfter(120, TimeUnit.SECONDS)
                .close(CloseSessionRequest.newBuilder().setSessionId(sessionId).build())
        }
        return if (response.clean) null else response.detail
    }

    /** [closeAndReport] that fails when the device was quarantined. */
    override fun close() {
        closeAndReport()?.let { throw TapException("$serial quarantined on close: $it") }
    }

    override fun toString(): String = "Device($serial, generation=$generation)"

    companion object {
        internal fun open(run: Run, serial: String, autPackage: String, timeouts: Timeouts, options: DeviceOptions): Device {
            val request = OpenSessionRequest.newBuilder()
                .setRunId(run.id)
                .setSerial(serial)
                .setAutPackage(autPackage)
                .setDefaultTimeoutMs(timeouts.action.inWholeMilliseconds)
                .addAllAllowedSystemPackages(options.allowedSystemPackages)
                .apply {
                    options.driverApk?.let { setDriverApk(it) }
                    options.driverTestApk?.let { setDriverTestApk(it) }
                    if (options.skipDriverInstall) setSkipDriverInstall(true)
                    options.syncAuthority?.let { setSyncAuthority(it) }
                }
                .build()
            val response = mapped(serial) { run.client.sessions.withDeadlineAfter(180, TimeUnit.SECONDS).open(request) }
            return Device(run, response.sessionId, response.serial, response.generation, autPackage, timeouts)
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
