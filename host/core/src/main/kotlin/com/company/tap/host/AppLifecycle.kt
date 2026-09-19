package com.company.tap.host

import com.company.tap.protocol.Operation
import com.company.tap.protocol.Response
import com.company.tap.protocol.SyncState
import java.nio.file.Path

/** An AUT lifecycle postcondition did not hold (process still alive, window never appeared...). */
class AppLifecycleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A host-side wait ran out of time. Carries the last observation so a log line is diagnosable. */
class HostWaitTimeoutException(
    val description: String,
    val serial: String,
    val elapsedMs: Long,
    val polls: Int,
    val lastObservation: String?,
) : RuntimeException(
    buildString {
        append("Timed out after ${elapsedMs}ms waiting for $description on $serial")
        if (polls > 0) append(" ($polls polls)")
        if (lastObservation != null) append("; last observed: $lastObservation")
    },
)

/**
 * Lifecycle of one package on one device, executed by the host. Every operation verifies its
 * postcondition through ADB (package manager / process table) rather than assuming the
 * command worked. This is the single implementation behind the service's `AppService`;
 * clients never run ADB themselves.
 */
class AppLifecycle(
    val session: DeviceSession,
    val packageName: String,
    private val pollIntervalMs: Long = 100,
) {
    private val adb get() = session.adb
    private val serial get() = session.serial
    private val client get() = session.client

    /** Identity the synchronization provider handed out at bootstrap; reset by lifecycle changes. */
    @Volatile
    private var syncIdentity: SyncState? = null

    fun isInstalled(): Boolean =
        adb.run(serial, "shell", "pm", "path", packageName).lineSequence().any { it.startsWith("package:") }

    fun install(apk: Path, timeoutMs: Long) {
        adb.install(serial, apk, timeoutMs)
        if (!isInstalled()) throw AppLifecycleException("$packageName is not installed after install on $serial")
        syncIdentity = null
    }

    fun uninstall() {
        val output = adb.run(serial, "uninstall", packageName)
        if (isInstalled()) throw AppLifecycleException("$packageName still installed after uninstall on $serial: $output")
        syncIdentity = null
    }

    /** `am force-stop` plus proof that no process of the package remains. */
    fun forceStop(timeoutMs: Long) {
        adb.run(serial, "shell", "am", "force-stop", packageName)
        awaitNoProcess(timeoutMs, "force-stop")
        syncIdentity = null
    }

    /** `pm clear`: data, cache, and runtime permissions are gone; the app is left stopped. */
    fun clearData(timeoutMs: Long) {
        val output = adb.run(serial, "shell", "pm", "clear", packageName)
        if ("Success" !in output) throw AppLifecycleException("pm clear $packageName failed on $serial: $output")
        awaitNoProcess(timeoutMs, "pm clear")
        syncIdentity = null
    }

    fun grantPermission(permission: String) {
        adb.run(serial, "shell", "pm", "grant", packageName, permission)
    }

    /**
     * Starts [activity] (or the launcher activity) and waits until the package owns the
     * focused window. Does not assert anything about prior process state; see [coldLaunch].
     */
    fun launch(activity: String?, timeoutMs: Long) {
        val component = "$packageName/${activity ?: launcherActivity()}"
        val output = adb.run(serial, "shell", "am", "start", "-W", "-n", component, timeoutMs = timeoutMs)
        if ("Error" in output || "Exception" in output) {
            throw AppLifecycleException("am start $component failed on $serial: $output")
        }
        awaitAppVisible(timeoutMs)
    }

    /** Verified force-stop, launch, then proof of a *new* process identity in the foreground. */
    fun coldLaunch(activity: String?, timeoutMs: Long, stopTimeoutMs: Long = 10_000): ProcessObservation {
        forceStop(stopTimeoutMs)
        launch(activity, timeoutMs)
        return observeProcess(adb, serial, packageName, timeoutMs)
    }

    /** Current single process identity (PID + start token); waits briefly for it to exist. */
    fun process(timeoutMs: Long): ProcessObservation = observeProcess(adb, serial, packageName, timeoutMs)

    fun isRunning(): Boolean = adb.processIds(serial, packageName).isNotEmpty()

    /** Waits on the device until the package owns the focused window. */
    fun awaitAppVisible(timeoutMs: Long) {
        val response = client.execute(Operation.WAIT_APP_VISIBLE, packageName = packageName, timeoutMs = timeoutMs)
        if (!response.ok) {
            val current = runCatching {
                client.executeOrThrow(Operation.DEVICE_INFO, timeoutMs = 5_000).deviceInfo?.currentPackage
            }.getOrNull()
            throw HostWaitTimeoutException(
                "package $packageName to be in the foreground", serial, response.durationMs, 0, "currentPackage=$current",
            )
        }
    }

    /**
     * Waits until the app's `TapSynchronization` reports no busy work for a stable window.
     * Requires the app's E2E build to ship `sync-sdk` and be signed like the driver. The first
     * call after a launch/clear bootstraps the process identity; a process restart in between
     * fails with `AUT_MISMATCH` rather than silently re-bootstrapping.
     */
    fun awaitIdle(timeoutMs: Long, stableForMs: Long = 200) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val identity = syncIdentity ?: bootstrapSync(remainingMs(deadline)).also { syncIdentity = it }
        var zeroGeneration: Long? = null
        var zeroObservedAt = 0L
        var polls = 0
        while (true) {
            val remaining = remainingMs(deadline)
            if (remaining <= 0) {
                throw HostWaitTimeoutException(
                    "$packageName to become idle", serial, timeoutMs, polls, "zeroGeneration=$zeroGeneration",
                )
            }
            polls++
            val state = requireNotNull(callSync(Operation.SYNC_STATE, identity, minOf(5_000, remaining)).syncState)
            val now = System.nanoTime()
            if (state.busyCount == 0) {
                if (zeroGeneration == state.generation && now - zeroObservedAt >= stableForMs * 1_000_000) return
                if (zeroGeneration != state.generation) {
                    zeroGeneration = state.generation
                    zeroObservedAt = now
                }
            } else {
                zeroGeneration = null
            }
            Thread.sleep(pollIntervalMs.coerceAtMost(50))
        }
    }

    private fun bootstrapSync(timeoutMs: Long): SyncState =
        requireNotNull(callSync(Operation.SYNC_BOOTSTRAP, null, timeoutMs.coerceIn(1, 5_000)).syncState)

    /** The driver checks identity against the process the host observed around the call. */
    private fun callSync(operation: Operation, expected: SyncState?, timeoutMs: Long): Response {
        val before = process(5_000)
        val response = client.executeOrThrow(
            operation,
            timeoutMs = timeoutMs,
            observedPid = before.pid,
            observedStartToken = before.startToken,
            expectedProcessStartUuid = expected?.processStartUuid,
            expectedSessionIdentity = expected?.sessionIdentity,
        )
        val after = process(5_000)
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

    private fun awaitNoProcess(timeoutMs: Long, action: String) {
        val started = System.nanoTime()
        val deadline = started + timeoutMs * 1_000_000
        var polls = 0
        while (true) {
            polls++
            val pids = adb.processIds(serial, packageName)
            if (pids.isEmpty()) return
            if (System.nanoTime() >= deadline) {
                throw HostWaitTimeoutException(
                    "$packageName to have no process after $action", serial,
                    (System.nanoTime() - started) / 1_000_000, polls, "pids=$pids",
                )
            }
            Thread.sleep(pollIntervalMs)
        }
    }

    private fun remainingMs(deadlineNanos: Long): Long = (deadlineNanos - System.nanoTime()) / 1_000_000

    override fun toString(): String = "AppLifecycle($packageName on $serial)"
}
