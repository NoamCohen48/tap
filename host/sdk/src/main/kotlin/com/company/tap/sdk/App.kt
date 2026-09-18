package com.company.tap.sdk

import com.company.tap.host.ProcessObservation
import com.company.tap.host.observeProcess
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Response
import com.company.tap.protocol.SyncState
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lifecycle of one package on one device. Every operation verifies its postcondition
 * through ADB (package manager / process table) rather than assuming the command worked.
 */
class App internal constructor(
    val device: Device,
    val packageName: String,
) {
    private val adb get() = device.session.adb
    private val serial get() = device.serial

    /** Identity the synchronization provider handed out at bootstrap; reset by lifecycle changes. */
    @Volatile
    private var syncIdentity: SyncState? = null

    fun isInstalled(): Boolean =
        adb.run(serial, "shell", "pm", "path", packageName).lineSequence().any { it.startsWith("package:") }

    fun install(apk: Path, timeout: Duration = device.timeouts.lifecycle) {
        adb.install(serial, apk, timeout.inWholeMilliseconds)
        if (!isInstalled()) throw AppLifecycleException("$packageName is not installed after install on $serial")
        syncIdentity = null
    }

    fun uninstall() {
        val output = adb.run(serial, "uninstall", packageName)
        if (isInstalled()) throw AppLifecycleException("$packageName still installed after uninstall on $serial: $output")
        syncIdentity = null
    }

    /** `am force-stop` plus proof that no process of the package remains. */
    fun forceStop(timeout: Duration = device.timeouts.action) {
        adb.run(serial, "shell", "am", "force-stop", packageName)
        awaitNoProcess(timeout, "force-stop")
        syncIdentity = null
    }

    /** `pm clear`: data, cache, and runtime permissions are gone; the app is left stopped. */
    fun clearData(timeout: Duration = device.timeouts.action) {
        val output = adb.run(serial, "shell", "pm", "clear", packageName)
        if ("Success" !in output) throw AppLifecycleException("pm clear $packageName failed on $serial: $output")
        awaitNoProcess(timeout, "pm clear")
        syncIdentity = null
    }

    fun grantPermission(permission: String) {
        adb.run(serial, "shell", "pm", "grant", packageName, permission)
    }

    /**
     * Starts [activity] (or the launcher activity) and waits until the package owns the
     * focused window. Does not assert anything about prior process state; see [coldLaunch].
     */
    fun launch(activity: String? = null, timeout: Duration = device.timeouts.lifecycle) {
        val component = "$packageName/${activity ?: launcherActivity()}"
        val output = adb.run(serial, "shell", "am", "start", "-W", "-n", component, timeoutMs = timeout.inWholeMilliseconds)
        if ("Error" in output || "Exception" in output) {
            throw AppLifecycleException("am start $component failed on $serial: $output")
        }
        device.awaitAppVisible(packageName, timeout)
    }

    /** Verified force-stop, launch, then proof of a *new* process identity in the foreground. */
    fun coldLaunch(activity: String? = null, timeout: Duration = device.timeouts.lifecycle): ProcessObservation {
        forceStop()
        launch(activity, timeout)
        return observeProcess(adb, serial, packageName, timeout.inWholeMilliseconds)
    }

    /** Current single process identity (PID + start token); waits briefly for it to exist. */
    fun process(timeout: Duration = device.timeouts.action): ProcessObservation =
        observeProcess(adb, serial, packageName, timeout.inWholeMilliseconds)

    fun isRunning(): Boolean = adb.processIds(serial, packageName).isNotEmpty()

    /**
     * Waits until the app's `TapSynchronization` reports no busy work for a stable window.
     * Requires the app's E2E build to ship `sync-sdk` and be signed like the driver. The first
     * call after a launch/clear bootstraps the process identity; a process restart in between
     * fails with `AUT_MISMATCH` rather than silently re-bootstrapping.
     */
    fun awaitIdle(timeout: Duration = device.timeouts.wait, stableFor: Duration = 200.milliseconds) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        val identity = syncIdentity ?: bootstrapSync(remainingMs(deadline)).also { syncIdentity = it }
        var zeroGeneration: Long? = null
        var zeroObservedAt = 0L
        var polls = 0
        while (true) {
            val remaining = remainingMs(deadline)
            if (remaining <= 0) {
                throw WaitTimeoutException(
                    "$packageName to become idle", serial, null, timeout.inWholeMilliseconds, polls,
                    "zeroGeneration=$zeroGeneration",
                )
            }
            polls++
            val state = requireNotNull(callSync(Operation.SYNC_STATE, identity, minOf(5_000, remaining)).syncState)
            val now = System.nanoTime()
            if (state.busyCount == 0) {
                if (zeroGeneration == state.generation && now - zeroObservedAt >= stableFor.inWholeNanoseconds) return
                if (zeroGeneration != state.generation) {
                    zeroGeneration = state.generation
                    zeroObservedAt = now
                }
            } else {
                zeroGeneration = null
            }
            Thread.sleep(device.timeouts.pollInterval.inWholeMilliseconds.coerceAtMost(50))
        }
    }

    private fun bootstrapSync(timeoutMs: Long): SyncState =
        requireNotNull(callSync(Operation.SYNC_BOOTSTRAP, null, timeoutMs.coerceIn(1, 5_000)).syncState)

    /** The driver checks identity against the process the host observed around the call. */
    private fun callSync(operation: Operation, expected: SyncState?, timeoutMs: Long): Response {
        val before = process()
        val response = device.client.executeOrThrow(
            operation,
            timeoutMs = timeoutMs,
            observedPid = before.pid,
            observedStartToken = before.startToken,
            expectedProcessStartUuid = expected?.processStartUuid,
            expectedSessionIdentity = expected?.sessionIdentity,
        )
        val after = process()
        if (after != before) throw AppLifecycleException("$packageName restarted during a synchronization call: $before -> $after")
        return response
    }

    private fun launcherActivity(): String {
        val output = adb.run(
            serial, "shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageName,
        )
        val component = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("$packageName/") }
            ?: throw AppLifecycleException("No launcher activity for $packageName on $serial: $output")
        return component.substringAfter('/')
    }

    private fun awaitNoProcess(timeout: Duration, action: String) {
        device.awaitUntil("$packageName to have no process after $action", timeout,
            observe = { "pids=${adb.processIds(serial, packageName)}" }) {
            adb.processIds(serial, packageName).isEmpty()
        }
    }

    private fun remainingMs(deadlineNanos: Long): Long = (deadlineNanos - System.nanoTime()) / 1_000_000

    override fun toString(): String = "App($packageName on $serial)"
}
