package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.AwaitIdleRequest
import io.github.noamcohen48.tap.api.v1.ClearDataRequest
import io.github.noamcohen48.tap.api.v1.ColdLaunchRequest
import io.github.noamcohen48.tap.api.v1.ForceStopRequest
import io.github.noamcohen48.tap.api.v1.ForegroundRequest
import io.github.noamcohen48.tap.api.v1.GetLocalesRequest
import io.github.noamcohen48.tap.api.v1.GrantPermissionRequest
import io.github.noamcohen48.tap.api.v1.InstallHeader
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.api.v1.IsInstalledRequest
import io.github.noamcohen48.tap.api.v1.IsPermissionGrantedRequest
import io.github.noamcohen48.tap.api.v1.IsRunningRequest
import io.github.noamcohen48.tap.api.v1.LaunchRequest
import io.github.noamcohen48.tap.api.v1.OpenLinkRequest
import io.github.noamcohen48.tap.api.v1.ProcessRequest
import io.github.noamcohen48.tap.api.v1.RevokePermissionRequest
import io.github.noamcohen48.tap.api.v1.SetLocalesRequest
import io.github.noamcohen48.tap.api.v1.UninstallRequest
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Size of each `InstallRequest.chunk` streamed by [App.install]. */
internal const val INSTALL_CHUNK_BYTES = 1 shl 20

/**
 * One app on a device: its elements, its lifecycle and the waits on it. Obtain with
 * [Device.app], which performs no I/O.
 *
 * [element] and [await] add the package as one more selector predicate, so only this app's
 * nodes match (in any of its windows); [Device.screen] matches anywhere. Lifecycle calls are
 * executed by the host server (ADB plus verified postconditions); the client only names the
 * package and the timeouts, and [install] uploads the APK from this machine.
 *
 * ```kotlin
 * val app = device.app("com.example.shop")
 * app.coldLaunch()
 * app.element(res("search")).setText("socks")
 * app.await(text("3 results")).visible()
 * ```
 *
 * All I/O methods are `suspend` and require an owning scope (`tapScope`/`tapTest`); the
 * constructor ([Device.app]) only binds values, so it stays non-suspend. Per-call
 * `withDeadlineAfter` is applied server-side; caller cancellation promptly cancels the RPC.
 * Every call is admitted through the device's command gate, so a started [Device.detach]
 * or an invalidated connection rejects it locally and an in-flight call delays the session
 * close.
 */
class App internal constructor(
    val device: Device,
    val packageName: String,
) {
    private val apps get() = device.ownerConnection.client.apps

    /** A lazy element: [selector] restricted to this package's nodes. Performs no I/O. */
    fun element(selector: Selector): Element = Element(device, selector.inPackage(packageName))

    /** A wait on [selector] restricted to this package's nodes. */
    fun await(
        selector: Selector,
        timeout: Duration = device.timeouts.wait,
    ): ElementWait = ElementWait(device, selector.inPackage(packageName), timeout)

    private val target: AppTarget
        get() =
            AppTarget
                .newBuilder()
                .setClientConnectionId(device.ownerConnection.id)
                .setAttachedDeviceId(device.attachedDeviceId)
                .setPackageName(packageName)
                .build()

    private suspend fun <T> call(
        timeout: Duration?,
        block: suspend (AppServiceGrpcKt.AppServiceCoroutineStub) -> T,
    ): T {
        ensureTapBound("App.$packageName")
        return device.admitted("App.$packageName") {
            mapped(device.serial) {
                block(
                    apps.withDeadlineAfter(
                        ((timeout ?: device.timeouts.lifecycle) + RPC_DEADLINE_SLACK_MS.milliseconds).inWholeMilliseconds,
                        TimeUnit.MILLISECONDS,
                    ),
                )
            }
        }
    }

    /** Whether the package is installed. */
    suspend fun isInstalled(): Boolean =
        call(null) { it.isInstalled(IsInstalledRequest.newBuilder().setApp(target).build()) }.installed

    /**
     * `adb install -r -t` of [apk], verified. [apk] is a file on *this* machine: its bytes are
     * streamed to the server in 1 MiB chunks, so the server may run elsewhere.
     */
    suspend fun install(
        apk: Path,
        timeout: Duration = device.timeouts.lifecycle,
    ) {
        require(apk.isRegularFile()) { "APK $apk is not a readable file" }
        val size = apk.fileSize()
        call(timeout + 120.seconds) { it.install(installParts(apk, size, timeout)) }
    }

    private fun installParts(
        apk: Path,
        size: Long,
        timeout: Duration,
    ): Flow<InstallRequest> =
        flow {
            val header =
                InstallHeader
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .setSizeBytes(size)
            emit(InstallRequest.newBuilder().setHeader(header).build())
            Files.newInputStream(apk).use { input ->
                val buffer = ByteArray(INSTALL_CHUNK_BYTES)
                while (true) {
                    val read = input.readNBytes(buffer, 0, buffer.size)
                    if (read <= 0) break
                    emit(InstallRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, read)).build())
                }
            }
        }.flowOn(Dispatchers.IO)

    /** `pm uninstall`, verified. */
    suspend fun uninstall() {
        call(120.seconds) { it.uninstall(UninstallRequest.newBuilder().setApp(target).build()) }
    }

    /** `am force-stop` plus proof that no process and no activity of the package remain. */
    suspend fun forceStop(timeout: Duration = device.timeouts.action) {
        call(timeout) {
            it.forceStop(ForceStopRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }
    }

    /** `pm clear`: data, cache, and runtime permissions are gone; the app is left stopped, as after [forceStop]. */
    suspend fun clearData(timeout: Duration = device.timeouts.action) {
        call(timeout) {
            it.clearData(ClearDataRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }
    }

    /** `pm grant` a runtime permission, e.g. `android.permission.CAMERA`. */
    suspend fun grantPermission(permission: String) {
        call(null) {
            it.grantPermission(GrantPermissionRequest.newBuilder().setApp(target).setPermission(permission).build())
        }
    }

    /**
     * Whether [permission] is granted to the package now, as `dumpsys package` lists it: the
     * read-back for [grantPermission], [revokePermission], [clearData] and the permission dialog.
     */
    suspend fun isPermissionGranted(permission: String): Boolean =
        call(null) {
            it.isPermissionGranted(IsPermissionGrantedRequest.newBuilder().setApp(target).setPermission(permission).build())
        }.granted

    /**
     * The app's own languages (Android's per-app language, API 33+), as BCP-47 tags in
     * preference order (`"fr-FR"`, `"en"`); an empty list makes it follow the system again. The
     * tags are checked and canonicalized by the server (`INVALID_ARGUMENT` for a malformed one)
     * and the change is read back. Like the [Device] conditions, the app's languages before the
     * session's first change come back on [Device.detach]. Below API 33: [ServerException]
     * ([FailureReason.UNSUPPORTED_API]). Android applies it as a configuration change: the app's
     * activities are recreated unless it handles the change itself.
     */
    suspend fun setLocales(locales: List<String>) {
        call(null) {
            it.setLocales(SetLocalesRequest.newBuilder().setApp(target).addAllLocales(locales).build())
        }
    }

    /** The app's own languages now (canonical BCP-47 tags); empty when it follows the system. API 33+. */
    suspend fun locales(): List<String> =
        call(null) {
            it.getLocales(GetLocalesRequest.newBuilder().setApp(target).build())
        }.localesList

    /**
     * `pm revoke` a runtime permission, proven by `dumpsys package`. Android kills the app's
     * process when one of its runtime permissions is revoked.
     */
    suspend fun revokePermission(permission: String) {
        call(null) {
            it.revokePermission(RevokePermissionRequest.newBuilder().setApp(target).setPermission(permission).build())
        }
    }

    /**
     * Starts [activity] (or the launcher activity) with `am start -W` and returns when Android
     * reports the launch complete. Nothing about the UI is assumed: wait for what the test needs
     * ([awaitVisible], [awaitScreenStable], an element wait).
     *
     * [extras] are put on the intent (`am start --es/--ez/--ei/--el/--ef`): values must be
     * `String`, `Boolean`, `Int`, `Long` or `Float`, so `getLongExtra` needs a `Long` (`42L`).
     * Anything else fails with [IllegalArgumentException] before the call.
     */
    suspend fun launch(
        activity: String? = null,
        timeout: Duration = device.timeouts.lifecycle,
        extras: Map<String, Any> = emptyMap(),
    ) {
        val intentExtras = intentExtras(extras)
        call(timeout) {
            it.launch(
                LaunchRequest
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .apply { activity?.let { name -> setActivity(name) } }
                    .addAllExtras(intentExtras)
                    .build(),
            )
        }
    }

    /**
     * Waits on the device until this package owns the focused window. Throws
     * [WaitTimeoutException] only when the device reports `WAIT_TIMEOUT`; any other failure
     * (driver unhealthy, transport lost, ...) is a [CommandException].
     */
    suspend fun awaitVisible(timeout: Duration = device.timeouts.wait) = device.awaitAppVisible(packageName, timeout)

    /**
     * [Device.awaitToast] for this package's toasts only: on Android 11+ a text toast is drawn
     * by SystemUI but still reported under the app that posted it.
     */
    suspend fun awaitToast(
        text: String? = null,
        mode: MatchMode = MatchMode.EXACT,
        timeout: Duration = device.timeouts.wait,
    ): Toast = device.awaitToast(text, mode, packageName, timeout)

    /** [Device.awaitNotification] for this package's notifications only. */
    suspend fun awaitNotification(
        title: String? = null,
        text: String? = null,
        mode: MatchMode = MatchMode.EXACT,
        timeout: Duration = device.timeouts.wait,
    ): Notification = device.awaitNotification(title, text, mode, packageName, timeout)

    /**
     * Waits on the device until this package's focused window has stopped changing for
     * [stableFor] according to [signal]: the accessibility tree ([StabilitySignal.TREE]), the
     * window pixels ([StabilitySignal.PIXELS], 0.5 % tolerance) or both (default).
     * Content-changed events restart the quiet period. Use it explicitly after an action that
     * starts an animation or a transition; no command waits for this implicitly. A screen that
     * keeps changing (indeterminate spinner, ticker, video) times out with `SCREEN_CHANGING`.
     * [awaitSettled] and [awaitAnimationEnd] are the two single-signal shorthands. Only a
     * device `WAIT_TIMEOUT` becomes [WaitTimeoutException]; other failures are [CommandException]s.
     */
    suspend fun awaitScreenStable(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = device.timeouts.wait,
        signal: StabilitySignal = StabilitySignal.ALL,
    ) = device.awaitScreenStable(packageName, stableFor, timeout, signal)

    /**
     * Maestro's `waitForAppToSettle`, on request only: this package's accessibility hierarchy
     * has not changed for [stableFor]. Cheap (no screenshots); sees layout, text and state
     * changes but not pure drawing (a canvas animation, video).
     */
    suspend fun awaitSettled(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = device.timeouts.wait,
    ) = awaitScreenStable(stableFor, timeout, StabilitySignal.TREE)

    /**
     * Maestro's `waitForAnimationToEnd`, on request only: this package's window pixels have not
     * changed (beyond 0.5 %) for [stableFor]. Costs one screenshot per 100 ms while waiting.
     */
    suspend fun awaitAnimationEnd(
        stableFor: Duration = 500.milliseconds,
        timeout: Duration = device.timeouts.wait,
    ) = awaitScreenStable(stableFor, timeout, StabilitySignal.PIXELS)

    /**
     * Brings the app to the front the way the launcher does: its launcher intent with
     * `NEW_TASK | RESET_TASK_IF_NEEDED`, so a running task resumes where it was instead of
     * gaining a second copy of the launcher activity, and a stopped app starts. Returns when
     * `am start -W` reports the launch complete; wait for the screen you need.
     */
    suspend fun foreground(timeout: Duration = device.timeouts.lifecycle) {
        call(timeout) {
            it.foreground(ForegroundRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }
    }

    /**
     * Sends the app to the background with the Home key, as a user would; the process keeps
     * running. Bring it back with [foreground].
     */
    suspend fun background() {
        device.pressHome()
    }

    /**
     * Opens [uri] (a deep link or app link, e.g. `myapp://orders/42` or `https://example.com/x`)
     * with an `ACTION_VIEW` intent restricted to this package, so no chooser or browser can take
     * it; pass `anyApp = true` to let Android resolve it like a tap on a link elsewhere would.
     * Returns the activity Android reported starting (`package/.Activity`) when it reported one.
     * Nothing about the screen is checked: wait for what the link should show.
     */
    suspend fun openLink(
        uri: String,
        anyApp: Boolean = false,
        timeout: Duration = device.timeouts.lifecycle,
    ): String? =
        call(timeout) {
            it.openLink(
                OpenLinkRequest
                    .newBuilder()
                    .setApp(target)
                    .setUri(uri)
                    .setAnyApp(anyApp)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .build(),
            )
        }.let { if (it.hasActivity()) it.activity else null }

    /** Verified force-stop, [launch] (with its [extras]), then the *new* process identity. */
    suspend fun coldLaunch(
        activity: String? = null,
        timeout: Duration = device.timeouts.lifecycle,
        extras: Map<String, Any> = emptyMap(),
    ): AppProcess {
        val intentExtras = intentExtras(extras)
        return call(timeout) {
            it.coldLaunch(
                ColdLaunchRequest
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .apply { activity?.let { name -> setActivity(name) } }
                    .addAllExtras(intentExtras)
                    .build(),
            )
        }.process.toModel()
    }

    /** Current single process identity (PID + start token); waits briefly for it to exist. */
    suspend fun process(timeout: Duration = device.timeouts.action): AppProcess =
        call(timeout) {
            it.process(ProcessRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }.process.toModel()

    /** Whether any process of the package is alive. */
    suspend fun isRunning(): Boolean = call(null) { it.isRunning(IsRunningRequest.newBuilder().setApp(target).build()) }.running

    /**
     * Waits until the app's `TapSynchronization` reports no busy work for [stableFor]. Requires
     * the app's E2E build to ship `sync-sdk` and be signed like the driver. Experimental:
     * synchronization is still being designed ([ExperimentalTapApi]).
     */
    @ExperimentalTapApi
    suspend fun awaitIdle(
        timeout: Duration = device.timeouts.wait,
        stableFor: Duration = Timeouts.IDLE_STABLE_FOR,
    ) {
        call(timeout) {
            it.awaitIdle(
                AwaitIdleRequest
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .setStableForMs(stableFor.inWholeMilliseconds)
                    .build(),
            )
        }
    }

    override fun toString(): String = "App($packageName on ${device.serial})"
}
