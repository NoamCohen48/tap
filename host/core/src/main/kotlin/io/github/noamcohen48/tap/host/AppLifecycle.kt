package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.IntentExtra
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.Path

/** An AUT lifecycle postcondition did not hold (process still alive, window never appeared...). */
class AppLifecycleException(
    message: String,
    cause: Throwable? = null,
) : TapHostException(message, cause)

/** A host-side wait ran out of time. Carries the last observation so a log line is diagnosable. */
class HostWaitTimeoutException(
    val description: String,
    val serial: String,
    val elapsedMs: Long,
    val polls: Int,
    val lastObservation: String?,
    cause: Throwable? = null,
) : TapHostException(
        buildString {
            append("Timed out after ${elapsedMs}ms waiting for $description on $serial")
            if (polls > 0) append(" ($polls polls)")
            if (lastObservation != null) append("; last observed: $lastObservation")
        },
        cause,
    )

/**
 * Lifecycle of one package on one device, executed by the host. Every operation verifies its
 * postcondition through ADB (package manager / process table) rather than assuming the
 * command worked. This is the single implementation behind the daemon's `AppServer`;
 * clients never run ADB themselves.
 */
class AppLifecycle internal constructor(
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

    suspend fun isInstalled(): Boolean = session.guardAdb { adb.isInstalled(serial, packageName) }

    /**
     * The app's own languages (`cmd locale`, API 33+), BCP-47 tags in preference order; empty =
     * it follows the system. Read back afterwards and restored on detach, like the device
     * conditions. Tags must be well-formed ([canonicalLocales]).
     */
    suspend fun setLocales(locales: List<String>) {
        val canonical = canonicalLocales(locales)
        session.requireApi(APP_LOCALES_API, "Per-app languages")
        session.change(mapOf(StateKey.AppLocales(packageName).id to canonical.joinToString(",")))
    }

    /** The app's own languages now; empty = it follows the system. API 33+. */
    suspend fun locales(): List<String> {
        session.requireApi(APP_LOCALES_API, "Per-app languages")
        val value = session.guardAdb { adb.readState(serial, StateKey.AppLocales(packageName).id) }
        return value.orEmpty().split(',').filter { it.isNotEmpty() }
    }

    suspend fun install(
        apk: Path,
        timeoutMs: Long,
    ) {
        session.guardAdb { adb.install(serial, apk, timeoutMs) }
        if (!isInstalled()) throw AppLifecycleException("$packageName is not installed after install on $serial")
        syncIdentity = null
    }

    suspend fun uninstall() {
        val output = session.guardAdb { adb.uninstall(serial, packageName) }
        if (isInstalled()) throw AppLifecycleException("$packageName still installed after uninstall on $serial: $output")
        syncIdentity = null
    }

    /** `am force-stop` plus proof that no process and no activity of the package remain. */
    suspend fun forceStop(timeoutMs: Long) {
        session.guardAdb { adb.forceStop(serial, packageName) }
        awaitStopped(timeoutMs, "force-stop")
        syncIdentity = null
    }

    /** `pm clear`: data, cache, and runtime permissions are gone; the app is left stopped, as after [forceStop]. */
    suspend fun clearData(timeoutMs: Long) {
        val output = session.guardAdb { adb.clearData(serial, packageName) }
        if ("Success" !in output) throw AppLifecycleException("pm clear $packageName failed on $serial: $output")
        awaitStopped(timeoutMs, "pm clear")
        syncIdentity = null
    }

    /** `pm grant`, then proof from `dumpsys package` that the permission reads as granted. */
    suspend fun grantPermission(permission: String) {
        session.guardAdb { adb.grantPermission(serial, packageName, permission) }
        if (!session.guardAdb { adb.isPermissionGranted(serial, packageName, permission) }) {
            throw AppLifecycleException("$permission is not granted to $packageName on $serial after pm grant")
        }
    }

    /** Whether `dumpsys package` lists [permission] as granted to the package. */
    suspend fun isPermissionGranted(permission: String): Boolean =
        session.guardAdb { adb.isPermissionGranted(serial, packageName, permission) }

    /**
     * `pm revoke`, then proof from `dumpsys package` that the permission no longer reads as
     * granted. Android kills the app's process when one of its runtime permissions is revoked.
     */
    suspend fun revokePermission(permission: String) {
        session.guardAdb { adb.revokePermission(serial, packageName, permission) }
        if (session.guardAdb { adb.isPermissionGranted(serial, packageName, permission) }) {
            throw AppLifecycleException("$permission is still granted to $packageName on $serial after pm revoke")
        }
    }

    /**
     * Starts [activity] (or the launcher activity) with `am start -W`, carrying [extras], and
     * returns once Android reports the launch complete, bounded by [timeoutMs]. Nothing about the app's UI is
     * assumed: a test that needs the app in front or settled waits for that itself
     * ([awaitAppVisible], `wait_screen_stable`). Does not assert anything about prior process
     * state; see [coldLaunch].
     */
    suspend fun launch(
        activity: String?,
        timeoutMs: Long,
        extras: List<IntentExtra> = emptyList(),
    ) {
        launchUntil(activity, extras, deadlineAfter(timeoutMs), timeoutMs)
    }

    /**
     * Verified force-stop (bounded by [stopTimeoutMs]), launch, then the *new* process
     * identity. Launch and the process observation share the one [timeoutMs] deadline.
     */
    suspend fun coldLaunch(
        activity: String?,
        timeoutMs: Long,
        stopTimeoutMs: Long = 10_000,
        extras: List<IntentExtra> = emptyList(),
    ): ProcessObservation {
        session.checkUsable()
        forceStop(stopTimeoutMs)
        val deadline = deadlineAfter(timeoutMs)
        launchUntil(activity, extras, deadline, timeoutMs)
        val remaining = remainingOrTimeout(deadline, timeoutMs, "a $packageName process after launch")
        return session.guardAdb { observeProcess(adb, serial, packageName, remaining) }
    }

    private suspend fun launchUntil(
        activity: String?,
        extras: List<IntentExtra>,
        deadline: Long,
        timeoutMs: Long,
    ) {
        session.checkUsable()
        val component = "$packageName/${activity ?: launcherActivity()}"
        val startBudget = remainingOrTimeout(deadline, timeoutMs, "am start $component")
        val output = session.guardAdb { adb.startActivity(serial, component, startBudget, extras) }
        AmStartOutput.failure(output)?.let { failure ->
            throw AppLifecycleException("am start $component failed on $serial: $failure\n$output")
        }
    }

    /**
     * Brings the app back as the home screen would: `am start -W` of its launcher intent. An
     * existing task returns as it was left (its back stack untouched); without one the launcher
     * activity starts. Like [launch], nothing about the app's UI is assumed.
     */
    suspend fun foreground(timeoutMs: Long) {
        session.checkUsable()
        val deadline = deadlineAfter(timeoutMs)
        val component = "$packageName/${launcherActivity()}"
        val budget = remainingOrTimeout(deadline, timeoutMs, "am start $component")
        val output = session.guardAdb { adb.startLauncherIntent(serial, component, budget) }
        AmStartOutput.failure(output)?.let { failure ->
            throw AppLifecycleException("am start $component failed on $serial: $failure\n$output")
        }
    }

    /**
     * Opens [uri] with a VIEW intent: only this package may handle it unless [anyApp], which
     * resolves it as a tapped link would (another app, the browser, or a chooser — never tapped
     * here). Returns the activity `am start` reports it started or delivered the intent to; what
     * the app shows is for the test to wait for.
     */
    suspend fun openLink(
        uri: String,
        anyApp: Boolean,
        timeoutMs: Long,
    ): String? {
        session.checkUsable()
        val output = session.guardAdb { adb.startView(serial, uri, packageName.takeUnless { anyApp }, timeoutMs) }
        AmStartOutput.failure(output)?.let { failure ->
            throw AppLifecycleException("Opening $uri failed on $serial: $failure\n$output")
        }
        return AmStartOutput.activity(output)
    }

    /** Current single process identity (PID + start token); waits briefly for it to exist. */
    suspend fun process(timeoutMs: Long): ProcessObservation = session.guardAdb { observeProcess(adb, serial, packageName, timeoutMs) }

    suspend fun isRunning(): Boolean = session.guardAdb { adb.processIds(serial, packageName) }.isNotEmpty()

    /**
     * Waits on the device until the package owns the focused window. Only the driver's
     * `WAIT_TIMEOUT` becomes a [HostWaitTimeoutException]; every other failure (a poisoned
     * session, a transport loss, cancellation) propagates unchanged.
     */
    suspend fun awaitAppVisible(timeoutMs: Long) {
        session.checkUsable()
        try {
            client.execute(Commands.waitAppVisible(packageName), timeoutMs = timeoutMs)
        } catch (timeout: RemoteCommandException) {
            if (timeout.code != ErrorCode.ERR_WAIT_TIMEOUT) throw timeout
            // Diagnostic only: a failed lookup must not mask the timeout, but cancellation wins.
            val current =
                try {
                    client.execute(Commands.deviceInfo(), timeoutMs = 5_000).deviceInfo.takeIf { it.hasCurrentPackage() }?.currentPackage
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            throw HostWaitTimeoutException(
                "package $packageName to be in the foreground",
                serial,
                timeout.durationMs,
                0,
                "currentPackage=$current",
                timeout,
            )
        }
    }

    /**
     * Waits until the app's `TapSynchronization` reports no busy work for a stable window.
     * Requires the app's E2E build to ship `sync-sdk` and be signed like the driver. The first
     * call after a launch/clear bootstraps the process identity; a process restart in between
     * fails with `AUT_MISMATCH` rather than silently re-bootstrapping.
     */
    suspend fun awaitIdle(
        timeoutMs: Long,
        stableForMs: Long = 200,
    ) {
        session.checkUsable()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val identity = syncIdentity ?: bootstrapSync(remainingMs(deadline)).also { syncIdentity = it }
        var zeroGeneration: Long? = null
        var zeroObservedAt = 0L
        var polls = 0
        while (true) {
            val remaining = remainingMs(deadline)
            if (remaining <= 0) {
                throw HostWaitTimeoutException(
                    "$packageName to become idle",
                    serial,
                    timeoutMs,
                    polls,
                    "zeroGeneration=$zeroGeneration",
                )
            }
            polls++
            val state =
                callSync(minOf(5_000, remaining)) { before ->
                    Requests.syncPoll(
                        before.pid,
                        before.startToken,
                        identity.processStartUuid,
                        identity.sessionIdentity,
                        packageName,
                        "$packageName.tap-sync",
                    )
                }
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
            delay(pollIntervalMs.coerceAtMost(50))
        }
    }

    private suspend fun bootstrapSync(timeoutMs: Long): SyncState =
        callSync(timeoutMs.coerceIn(1, 5_000)) { before ->
            Requests.syncBootstrap(before.pid, before.startToken, packageName, "$packageName.tap-sync")
        }

    /** The driver checks identity against the process the host observed around the call. */
    private suspend inline fun callSync(
        timeoutMs: Long,
        request: (ProcessObservation) -> Request,
    ): SyncState {
        val before = process(5_000)
        val state = client.execute(request(before), timeoutMs = timeoutMs).sync
        val after = process(5_000)
        if (after != before) throw AppLifecycleException("$packageName restarted during a synchronization call: $before -> $after")
        return state
    }

    private suspend fun launcherActivity(): String {
        val component =
            session.guardAdb { adb.launcherActivity(serial, packageName) }
                ?: throw AppLifecycleException("No launcher activity for $packageName on $serial")
        return component.substringAfter('/')
    }

    /**
     * Waits until the package has no process and Android has also destroyed its activities.
     * The process dies first; the task goes later, and on API 34 a task whose removal times out
     * is killed by package — taking down a process started for a launch in the meantime and
     * leaving that launch on its splash screen until `am start -W` times out (seen on CI).
     */
    private suspend fun awaitStopped(
        timeoutMs: Long,
        action: String,
    ) {
        val started = System.nanoTime()
        val deadline = started + timeoutMs * 1_000_000
        var polls = 0
        while (true) {
            polls++
            val pids = session.guardAdb { adb.processIds(serial, packageName) }
            val activities = pids.isEmpty() && session.guardAdb { adb.hasActivities(serial, packageName) }
            if (pids.isEmpty() && !activities) return
            if (System.nanoTime() >= deadline) {
                throw HostWaitTimeoutException(
                    "$packageName to have no process and no activity after $action",
                    serial,
                    (System.nanoTime() - started) / 1_000_000,
                    polls,
                    if (activities) "an activity still exiting" else "pids=$pids",
                )
            }
            delay(pollIntervalMs)
        }
    }

    private fun remainingMs(deadlineNanos: Long): Long = (deadlineNanos - System.nanoTime()) / 1_000_000

    private fun deadlineAfter(timeoutMs: Long): Long {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        return System.nanoTime() + timeoutMs * 1_000_000
    }

    /** What is left of a shared launch deadline, or a timeout naming the step that ran out. */
    private fun remainingOrTimeout(
        deadlineNanos: Long,
        timeoutMs: Long,
        description: String,
    ): Long {
        val remaining = remainingMs(deadlineNanos)
        if (remaining <= 0) throw HostWaitTimeoutException(description, serial, timeoutMs, 0, null)
        return remaining
    }

    override fun toString(): String = "AppLifecycle($packageName on $serial)"
}

/**
 * Reads `am start -W` output by line prefix. Substring matching is wrong both ways: a component
 * such as `.ErrorActivity` or a package like `com.x.exceptions` is not a failure, while a
 * `Status:` other than `ok` is one even without the word "Error".
 */
internal object AmStartOutput {
    private val exceptionLine = Regex("""^(?:[a-z_][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error)(?::.*)?$""")

    /** The `Activity: package/class` component `am start -W` reports, or null when it names none. */
    fun activity(output: String): String? =
        output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("Activity:") }
            ?.removePrefix("Activity:")
            ?.trim()
            ?.takeIf { '/' in it }

    /** The line that says the start failed, or null when the output reports none. */
    fun failure(output: String): String? {
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Error:") || line.startsWith("Error type") -> return line
                line.startsWith("Exception occurred") -> return line
                exceptionLine.matches(line) -> return line
                line.startsWith("Status:") -> {
                    // `timeout` only means the first frame took longer than am waits; the
                    // visibility wait that follows decides.
                    val status = line.removePrefix("Status:").trim()
                    if (status != "ok" && status != "timeout") return line
                }
            }
        }
        return null
    }
}

/** Android 13 added per-app languages (`LocaleManager`, `cmd locale`). */
const val APP_LOCALES_API = 33

/** At most this many languages per app. */
const val MAX_APP_LOCALES = 16

/**
 * [locales] as canonical BCP-47 tags (`fr-fr` → `fr-FR`), as Android reports them back. Throws
 * [IllegalArgumentException] for an ill-formed or repeated tag, or more than [MAX_APP_LOCALES].
 */
fun canonicalLocales(locales: List<String>): List<String> {
    require(locales.size <= MAX_APP_LOCALES) { "at most $MAX_APP_LOCALES locales, not ${locales.size}" }
    val canonical =
        locales.map { tag ->
            require(tag.isNotBlank()) { "a locale tag is blank" }
            try {
                java.util.Locale.Builder().setLanguageTag(tag).build().toLanguageTag()
            } catch (ill: java.util.IllformedLocaleException) {
                throw IllegalArgumentException("$tag is not a well-formed BCP-47 language tag", ill)
            }
        }
    require(canonical.toSet().size == canonical.size) { "locales repeat a tag: ${locales.joinToString()}" }
    return canonical
}
