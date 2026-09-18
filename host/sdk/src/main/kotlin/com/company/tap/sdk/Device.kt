package com.company.tap.sdk

import com.company.tap.host.DeviceSession
import com.company.tap.host.DeviceSessionConfig
import com.company.tap.host.DriverClient
import com.company.tap.protocol.DeviceInfo
import com.company.tap.protocol.KEYCODE_BACK
import com.company.tap.protocol.KEYCODE_HOME
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Selector
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

/**
 * One device under one live [DeviceSession]. All calls are blocking and serialized on the
 * device; use separate `Device`s (and threads or coroutines) for concurrency across devices.
 * Nothing here caches UI state: [element] returns a lazy selector that every action resolves
 * again, and mutations fail with `AMBIGUOUS`/`NOT_FOUND` before any input when the selector
 * does not match exactly one node.
 */
class Device internal constructor(
    val session: DeviceSession,
    val timeouts: Timeouts = Timeouts(),
) : AutoCloseable {
    val serial: String get() = session.serial

    /** The AUT package the session was opened for; AUT-scoped selectors resolve in it. */
    val autPackage: String get() = session.config.autPackage

    /** Escape hatch to raw protocol operations. */
    val client: DriverClient get() = session.client

    fun element(selector: Selector): Element = Element(this, selector)

    fun await(selector: Selector, timeout: Duration = timeouts.wait): ElementWait =
        ElementWait(this, selector, timeout)

    fun app(packageName: String = autPackage): App = App(this, packageName)

    fun info(): DeviceInfo = requireNotNull(
        client.executeOrThrow(Operation.DEVICE_INFO, timeoutMs = timeouts.action.inWholeMilliseconds).deviceInfo,
    )

    fun pressBack() = pressKey(KEYCODE_BACK)
    fun pressHome() = pressKey(KEYCODE_HOME)

    /** Injects one Android key code (a mutation: never replayed on transport loss). */
    fun pressKey(keyCode: Int) {
        client.executeOrThrow(Operation.PRESS_KEY, keyCode = keyCode, timeoutMs = timeouts.action.inWholeMilliseconds)
    }

    /** PNG bytes, verified against the driver's checksum. */
    fun screenshot(timeout: Duration = timeouts.lifecycle): ByteArray =
        client.screenshot(timeout.inWholeMilliseconds).png

    /** Diagnostic accessibility XML. Never used by selectors; keep it out of assertions. */
    fun dumpHierarchy(timeout: Duration = timeouts.lifecycle): String =
        requireNotNull(client.executeOrThrow(Operation.DUMP_HIERARCHY, timeoutMs = timeout.inWholeMilliseconds).text)

    /** Waits on the device until [packageName] owns the focused window. */
    fun awaitAppVisible(packageName: String = autPackage, timeout: Duration = timeouts.wait) {
        val response = client.execute(
            Operation.WAIT_APP_VISIBLE,
            packageName = packageName,
            timeoutMs = timeout.inWholeMilliseconds,
        )
        if (!response.ok) {
            throw WaitTimeoutException(
                "package $packageName to be in the foreground", serial, null,
                response.durationMs, 0, "currentPackage=${runCatching { info().currentPackage }.getOrNull()}",
            )
        }
    }

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
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        val started = System.nanoTime()
        var polls = 0
        while (true) {
            polls++
            if (condition()) return
            if (System.nanoTime() >= deadline) {
                throw WaitTimeoutException(
                    description, serial, null,
                    (System.nanoTime() - started) / 1_000_000, polls, runCatching(observe).getOrNull(),
                )
            }
            Thread.sleep(pollInterval.inWholeMilliseconds)
        }
    }

    override fun close() = session.close()

    override fun toString(): String = "Device($serial, generation=${session.generation})"

    companion object {
        /** Opens a session (installing the driver APKs when configured) and returns a ready device. */
        fun connect(config: DeviceSessionConfig, timeouts: Timeouts = Timeouts()): Device =
            Device(DeviceSession.open(config), timeouts)
    }
}
