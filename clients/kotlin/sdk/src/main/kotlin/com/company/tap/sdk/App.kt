package com.company.tap.sdk

import com.company.tap.api.v1.AppAwaitIdleRequest
import com.company.tap.api.v1.AppGrantRequest
import com.company.tap.api.v1.AppInstallRequest
import com.company.tap.api.v1.AppLaunchRequest
import com.company.tap.api.v1.AppRequest
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Process identity as observed by the host (PID plus `/proc` start token). */
data class ProcessIdentity(val pid: Int, val startToken: String)

/**
 * Lifecycle of one package on one device, executed by the host service (ADB plus verified
 * postconditions). The client only names the package and the timeouts.
 */
class App internal constructor(
    val device: Device,
    val packageName: String,
) {
    private val apps get() = device.run.client.apps

    private fun request(timeout: Duration?): AppRequest = AppRequest.newBuilder()
        .setSessionId(device.sessionId)
        .setPackageName(packageName)
        .setTimeoutMs(timeout?.inWholeMilliseconds ?: 0)
        .build()

    private fun <T> call(timeout: Duration?, block: (com.company.tap.api.v1.AppServiceGrpc.AppServiceBlockingStub) -> T): T =
        mapped(device.serial) {
            block(apps.withDeadlineAfter(((timeout ?: device.timeouts.lifecycle) + 60.seconds).inWholeMilliseconds, TimeUnit.MILLISECONDS))
        }

    /** Whether the package is installed. */
    fun isInstalled(): Boolean = call(null) { it.isInstalled(request(null)) }.value

    /** `adb install -r -t` of [apk] (a path on the service's machine), verified. */
    fun install(apk: Path, timeout: Duration = device.timeouts.lifecycle) {
        call(timeout + 120.seconds) {
            it.install(AppInstallRequest.newBuilder().setApp(request(timeout)).setApkPath(apk.absolutePathString()).build())
        }
    }

    /** `pm uninstall`, verified. */
    fun uninstall() {
        call(120.seconds) { it.uninstall(request(null)) }
    }

    /** `am force-stop` plus proof that no process of the package remains. */
    fun forceStop(timeout: Duration = device.timeouts.action) {
        call(timeout) { it.forceStop(request(timeout)) }
    }

    /** `pm clear`: data, cache, and runtime permissions are gone; the app is left stopped. */
    fun clearData(timeout: Duration = device.timeouts.action) {
        call(timeout) { it.clearData(request(timeout)) }
    }

    /** `pm grant` a runtime permission, e.g. `android.permission.CAMERA`. */
    fun grantPermission(permission: String) {
        call(null) { it.grantPermission(AppGrantRequest.newBuilder().setApp(request(null)).setPermission(permission).build()) }
    }

    /** Starts [activity] (or the launcher activity) and waits until the package owns the focused window. */
    fun launch(activity: String? = null, timeout: Duration = device.timeouts.lifecycle) {
        call(timeout) { it.launch(launchRequest(activity, timeout)) }
    }

    /** Verified force-stop, launch, then proof of a *new* process identity in the foreground. */
    fun coldLaunch(activity: String? = null, timeout: Duration = device.timeouts.lifecycle): ProcessIdentity =
        call(timeout) { it.coldLaunch(launchRequest(activity, timeout)) }.let { ProcessIdentity(it.pid, it.startToken) }

    /** Current single process identity (PID + start token); waits briefly for it to exist. */
    fun process(timeout: Duration = device.timeouts.action): ProcessIdentity =
        call(timeout) { it.process(request(timeout)) }.let { ProcessIdentity(it.pid, it.startToken) }

    /** Whether any process of the package is alive. */
    fun isRunning(): Boolean = call(null) { it.isRunning(request(null)) }.value

    /**
     * Waits until the app's `TapSynchronization` reports no busy work for [stableFor]. Requires
     * the app's E2E build to ship `sync-sdk` and be signed like the driver.
     */
    fun awaitIdle(timeout: Duration = device.timeouts.wait, stableFor: Duration = 200.milliseconds) {
        call(timeout) {
            it.awaitIdle(AppAwaitIdleRequest.newBuilder().setApp(request(timeout)).setStableForMs(stableFor.inWholeMilliseconds).build())
        }
    }

    private fun launchRequest(activity: String?, timeout: Duration): AppLaunchRequest =
        AppLaunchRequest.newBuilder().setApp(request(timeout)).apply { activity?.let { setActivity(it) } }.build()

    override fun toString(): String = "App($packageName on ${device.serial})"
}
