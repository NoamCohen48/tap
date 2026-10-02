package io.github.noamcohen48.tap.sdk

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AddMediaHeader
import io.github.noamcohen48.tap.api.v1.AddMediaRequest
import io.github.noamcohen48.tap.api.v1.AttachRequest
import io.github.noamcohen48.tap.api.v1.AwaitNotification
import io.github.noamcohen48.tap.api.v1.AwaitToast
import io.github.noamcohen48.tap.api.v1.ChoosePermission
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DetachRequest
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.DismissKeyguard
import io.github.noamcohen48.tap.api.v1.DismissNotification
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.api.v1.DumpHierarchy
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.GetClipboard
import io.github.noamcohen48.tap.api.v1.HideKeyboard
import io.github.noamcohen48.tap.api.v1.ListNotifications
import io.github.noamcohen48.tap.api.v1.NotificationMatch
import io.github.noamcohen48.tap.api.v1.OpenNotification
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.PullFileRequest
import io.github.noamcohen48.tap.api.v1.PushFileHeader
import io.github.noamcohen48.tap.api.v1.PushFileRequest
import io.github.noamcohen48.tap.api.v1.ScreenshotRequest
import io.github.noamcohen48.tap.api.v1.SetAnimationsRequest
import io.github.noamcohen48.tap.api.v1.SetClipboard
import io.github.noamcohen48.tap.api.v1.SetDarkModeRequest
import io.github.noamcohen48.tap.api.v1.SetDensityRequest
import io.github.noamcohen48.tap.api.v1.SetDisplayRotation
import io.github.noamcohen48.tap.api.v1.SetFontScaleRequest
import io.github.noamcohen48.tap.api.v1.GetForegroundActivityRequest
import io.github.noamcohen48.tap.api.v1.SetAccessibilityDisplayRequest
import io.github.noamcohen48.tap.api.v1.SetNetworkRequest
import io.github.noamcohen48.tap.api.v1.SetStayAwakeRequest
import io.github.noamcohen48.tap.api.v1.SetLocationRequest
import io.github.noamcohen48.tap.api.v1.SetSystemLocalesRequest
import io.github.noamcohen48.tap.api.v1.SetOrientation
import io.github.noamcohen48.tap.api.v1.StartRecordingRequest
import io.github.noamcohen48.tap.api.v1.StopRecordingRequest
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.UnfreezeRotation
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.api.v1.WaitPermissionPrompt
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Largest file [Device.pushFile], [Device.pullFile] and [Device.addMedia] move (512 MiB). */
const val MAX_FILE_BYTES = 512L shl 20

/** Size of each chunk the file uploads stream. */
private const val FILE_CHUNK_BYTES = 1 shl 20

/** A file transfer's whole-call deadline: the server's 5-minute adb transfer plus the upload. */
private const val FILE_DEADLINE_MS = 10 * 60_000L

const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val KEYCODE_SLEEP = 223
const val KEYCODE_WAKEUP = 224
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
    /** How long to wait for a device another session holds before failing; zero fails at once. */
    val waitForDevice: Duration = Duration.ZERO,
)

/**
 * One attached device backed by one live driver session, proxied by the host server. All calls are
 * `suspend`. The client does not serialize calls on one device: issue them sequentially (the
 * normal test style), and use `coroutineScope` + `async` for concurrency across devices
 * (sibling failure cancels the other's in-flight RPC). Nothing here caches UI
 * state: [App.element] and [Screen.element] return a lazy selector that every action resolves again, and mutations
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

    // --- Selector contexts --------------------------------------------------------------------------

    /** Elements of any window, of any app or the system UI; see [Screen]. */
    val screen: Screen = Screen(this)

    /** The app [packageName] on this device: its elements, lifecycle and waits; see [App]. Performs no I/O. */
    fun app(packageName: String): App = App(this, packageName)

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
     * Rotates the display to [orientation] geometry, whatever the device's natural orientation,
     * and freezes it there until [unfreezeRotation]. Fails only when Android refuses the request;
     * the call then waits briefly for the display to turn. The foreground app may pin its own
     * orientation and keep the display where it wants it: assert with [info] (`orientation`,
     * `displayRotation`). The rotation settings the device had before the session's first
     * rotation call are restored when the device is detached.
     */
    suspend fun setOrientation(orientation: Orientation) {
        executeOrThrow { setOrientation = SetOrientation.newBuilder().setOrientation(orientation.toProto()).build() }
    }

    /** Rotates the display to the exact [rotation] and freezes it there; otherwise as [setOrientation]. */
    suspend fun setDisplayRotation(rotation: DisplayRotation) {
        executeOrThrow { setDisplayRotation = SetDisplayRotation.newBuilder().setRotation(rotation.toProto()).build() }
    }

    /** Hands rotation back to the device's sensor (auto-rotate), without choosing a rotation. */
    suspend fun unfreezeRotation() {
        executeOrThrow { unfreezeRotation = UnfreezeRotation.getDefaultInstance() }
    }

    /** Turns the screen on (`KEYCODE_WAKEUP`; nothing happens when it is on). The keyguard may still show: see [dismissKeyguard]. */
    suspend fun wake() = pressKey(KEYCODE_WAKEUP)

    /** Turns the screen off (`KEYCODE_SLEEP`; nothing happens when it is off). */
    suspend fun sleep() = pressKey(KEYCODE_SLEEP)

    /**
     * Dismisses a keyguard that has no PIN, pattern or password (`wm dismiss-keyguard`); with no
     * keyguard showing it sends nothing. A secure keyguard fails with `ACTION_REJECTED` /
     * `KEYGUARD_SECURE` before any input: Tap never unlocks one. [info] reports the state.
     */
    suspend fun dismissKeyguard() {
        executeOrThrow { dismissKeyguard = DismissKeyguard.getDefaultInstance() }
    }

    /**
     * Waits until a runtime-permission dialog shows and returns the choices it offers. Buttons
     * are recognised by the permission controller's resource ids, never by label or position.
     * Throws [WaitTimeoutException] (`NO_PERMISSION_PROMPT`) when none appears within [timeout].
     */
    suspend fun awaitPermissionPrompt(timeout: Duration = timeouts.wait): PermissionPrompt {
        ensureTapBound("Device.awaitPermissionPrompt")
        return admitted("Device.awaitPermissionPrompt") {
            val result = rpcExecute(timeout) { waitPermissionPrompt = WaitPermissionPrompt.getDefaultInstance() }
            if (result.hasError()) {
                if (result.error.code != ErrorCodeProto.ERR_WAIT_TIMEOUT) {
                    throw CommandException(result, "wait_permission_prompt", serial, null)
                }
                throw WaitTimeoutException.of(result, "a permission dialog", serial)
            }
            result.permissionPrompt.toModel()
        }
    }

    /**
     * Taps the permission dialog's button for [choice] (`NOT_FOUND` before any input when the
     * dialog does not offer it). With [accuracy], the location dialog's Precise / Approximate
     * radio is selected first; it must be offered too ([PermissionPrompt.accuracies], Android 12+
     * when the app asks for fine location), or nothing is tapped. Only the taps are reported:
     * assert the outcome, e.g. the dialog gone and the app's state.
     */
    suspend fun choosePermission(
        choice: PermissionChoice,
        accuracy: LocationAccuracy? = null,
        timeout: Duration? = null,
    ) {
        executeOrThrow(timeout) {
            choosePermission =
                ChoosePermission.newBuilder().setChoice(choice.toProto()).apply { accuracy?.let { setAccuracy(it.toProto()) } }.build()
        }
    }

    /** Whether a soft keyboard (any input method's window) is on screen; [info] reports the same. */
    suspend fun keyboardShown(): Boolean = info().keyboardShown

    /**
     * Hides the soft keyboard with one Back key, which the keyboard consumes; with no keyboard
     * showing nothing is sent, so Back never reaches the app. Only the key is reported: assert
     * [keyboardShown] when it matters.
     */
    suspend fun hideKeyboard(timeout: Duration? = null) {
        executeOrThrow(timeout) { hideKeyboard = HideKeyboard.getDefaultInstance() }
    }

    /** Puts [text] on the device clipboard as plain text (at most 4096 characters). */
    suspend fun setClipboard(
        text: String,
        timeout: Duration? = null,
    ) {
        executeOrThrow(timeout) { setClipboard = SetClipboard.newBuilder().setText(text).build() }
    }

    /** The device clipboard as text; `""` when it is empty or holds nothing that reads as text. */
    suspend fun clipboard(timeout: Duration? = null): String =
        executeOrThrow(timeout) { getClipboard = GetClipboard.getDefaultInstance() }.text

    /**
     * Turns the window, transition and animator animations off (all three scales 0) or on (all
     * 1) for this session. Like every device condition below, the value the device had before
     * the session's first change is restored on [detach] (or by the next attach when the daemon
     * died first), and the change is read back: a value the device did not take throws
     * [ServerException] ([FailureReason.DEVICE_SETTING]). [info] reports
     * [DeviceInfo.animationsEnabled].
     */
    suspend fun setAnimations(enabled: Boolean) {
        condition("Device.setAnimations") {
            setAnimations(
                SetAnimationsRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .setEnabled(enabled).build(),
            )
        }
    }

    /**
     * Dark theme on or off (`cmd uimode night`) until [detach], see [setAnimations]. API 29+:
     * below, [ServerException] ([FailureReason.UNSUPPORTED_API]). Read back with [DeviceInfo.darkMode].
     */
    suspend fun setDarkMode(enabled: Boolean) {
        condition("Device.setDarkMode") {
            setDarkMode(
                SetDarkModeRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .setEnabled(enabled).build(),
            )
        }
    }

    /**
     * The system font scale, 0.5 to 2.0 (1.0 = default), until [detach], see [setAnimations].
     * Read back with [DeviceInfo.fontScale].
     */
    suspend fun setFontScale(scale: Float) {
        condition("Device.setFontScale") {
            setFontScale(
                SetFontScaleRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .setScale(scale).build(),
            )
        }
    }

    /**
     * Overrides the display density with [dpi] (100 to 1000), or with `null` goes back to the
     * display's physical density, until [detach], see [setAnimations]. Read back with
     * [DeviceInfo.densityDpi].
     */
    suspend fun setDensity(dpi: Int?) {
        condition("Device.setDensity") {
            setDensity(
                SetDensityRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .apply { if (dpi != null) setDpi(dpi) }.build(),
            )
        }
    }

    /**
     * Switches airplane mode, Wi-Fi and mobile data on or off until [detach], see [setAnimations];
     * a `null` switch is left as it is. These are the device's real switches (nothing is mocked):
     * the app sees what it would see if a user flipped them, and turning Wi-Fi on says nothing about
     * when it connects. Airplane mode is applied first, so `setNetwork(airplaneMode = true, wifi =
     * true)` is airplane mode with Wi-Fi on. API 29+. A device reached over ADB on the network
     * refuses Wi-Fi off or airplane mode on ([FailureReason.DEVICE_SETTING]): that would cut Tap off.
     * Read back with [DeviceInfo.airplaneMode], [DeviceInfo.wifiEnabled] and
     * [DeviceInfo.mobileDataEnabled].
     */
    suspend fun setNetwork(
        airplaneMode: Boolean? = null,
        wifi: Boolean? = null,
        mobileData: Boolean? = null,
    ) {
        require(airplaneMode != null || wifi != null || mobileData != null) { "set at least one of airplaneMode, wifi or mobileData" }
        condition("Device.setNetwork") {
            setNetwork(
                SetNetworkRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .apply {
                        airplaneMode?.let(::setAirplaneMode)
                        wifi?.let(::setWifi)
                        mobileData?.let(::setMobileData)
                    }.build(),
            )
        }
    }

    /**
     * Sets the device's languages (Settings › Languages) until [detach], see [setAnimations]:
     * BCP-47 tags in preference order, 1 to 16 of them (`"fr-FR"`, `"en"`). Every app that
     * follows the system sees the change as a configuration change; an app with its own
     * languages ([App.setLocales]) keeps them. Android has no shell command for this: the Tap
     * driver app applies it as Settings' language picker does, and the result is read back
     * ([FailureReason.DEVICE_SETTING] when the device reports another list). Read back with
     * [DeviceInfo.systemLocales].
     */
    suspend fun setSystemLocales(locales: List<String>) {
        require(locales.isNotEmpty()) { "set at least one locale" }
        condition("Device.setSystemLocales") {
            setSystemLocales(
                SetSystemLocalesRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .addAllLocales(locales).build(),
            )
        }
    }

    /**
     * Mocks the device location until [detach]: the gps and network providers (and fused, API
     * 31+) report this fix, sent again every second so an app that starts listening later still
     * gets one. [latitude] -90..90, [longitude] -180..180, [accuracyM] in meters (> 0; null = 5),
     * [altitudeM] in meters (null = none). The Tap driver app becomes the device's mock-location
     * app and, when location is off, location is turned on; both are restored on [detach], which
     * ends the mock. Calling it again moves the fix. Apps that read location through Google Play
     * services see it as Play services relays the platform providers.
     */
    suspend fun setLocation(
        latitude: Double,
        longitude: Double,
        accuracyM: Float? = null,
        altitudeM: Double? = null,
    ) {
        require(latitude in -90.0..90.0) { "latitude must be -90 to 90, not $latitude" }
        require(longitude in -180.0..180.0) { "longitude must be -180 to 180, not $longitude" }
        require(accuracyM == null || (accuracyM.isFinite() && accuracyM > 0f)) { "accuracyM must be a positive number of meters, not $accuracyM" }
        require(altitudeM == null || altitudeM.isFinite()) { "altitudeM must be finite, not $altitudeM" }
        condition("Device.setLocation") {
            setLocation(
                SetLocationRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .setLatitude(latitude).setLongitude(longitude)
                    .apply {
                        accuracyM?.let(::setAccuracyM)
                        altitudeM?.let(::setAltitudeM)
                    }.build(),
            )
        }
    }

    /**
     * Keeps the screen on while the device is plugged in (USB, AC or wireless), or lets it time out
     * again, until [detach], see [setAnimations]: what Developer options › Stay awake sets. A device
     * on ADB over USB is plugged in, so a long test does not find the screen off. Read back with
     * [DeviceInfo.stayAwake].
     */
    suspend fun setStayAwake(enabled: Boolean) {
        condition("Device.setStayAwake") {
            setStayAwake(SetStayAwakeRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId).setEnabled(enabled).build())
        }
    }

    /**
     * Turns the accessibility display settings on or off until [detach], see [setAnimations]; a
     * `null` setting is left as it is. [highContrastText] draws text with an outline in black or
     * white, [colorInversion] inverts the display's colors (screenshots stay uninverted: the
     * inversion happens in the display pipeline), and [boldText] makes the system font bold (API
     * 31+, [FailureReason.UNSUPPORTED_API] below, before anything changes). The values are what
     * Settings › Accessibility writes, read back ([FailureReason.DEVICE_SETTING] when the device
     * did not take one). Read back with [DeviceInfo.highContrastText], [DeviceInfo.colorInversion]
     * and [DeviceInfo.boldText].
     */
    suspend fun setAccessibilityDisplay(
        highContrastText: Boolean? = null,
        colorInversion: Boolean? = null,
        boldText: Boolean? = null,
    ) {
        require(highContrastText != null || colorInversion != null || boldText != null) {
            "set at least one of highContrastText, colorInversion or boldText"
        }
        condition("Device.setAccessibilityDisplay") {
            setAccessibilityDisplay(
                SetAccessibilityDisplayRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                    .apply {
                        highContrastText?.let(::setHighContrastText)
                        colorInversion?.let(::setColorInversion)
                        boldText?.let(::setBoldText)
                    }.build(),
            )
        }
    }

    /**
     * The activity on top of the screen (the resumed one, the focused one in multi-window), or
     * `null` when none is resumed: the keyguard is showing, or an activity is starting. Read on
     * the host from `dumpsys activity`; changes nothing. Use it to check that a deep link or a
     * notification opened the right screen.
     */
    suspend fun foregroundActivity(): ForegroundActivity? {
        val operation = "Device.foregroundActivity"
        ensureTapBound(operation)
        val response =
            admitted(operation) {
                mapped(serial) {
                    client.devices
                        .withDeadlineAfter(timeouts.lifecycle.inWholeMilliseconds + RPC_DEADLINE_SLACK_MS, TimeUnit.MILLISECONDS)
                        .getForegroundActivity(
                            GetForegroundActivityRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId).build(),
                        )
                }
            }
        return if (response.hasPackageName()) ForegroundActivity(response.packageName, response.activity) else null
    }

    /** [setSystemLocales] with the tags as arguments. */
    suspend fun setSystemLocales(
        first: String,
        vararg more: String,
    ) = setSystemLocales(listOf(first, *more))

    /**
     * Copies [bytes] to [devicePath] on the device (absolute and normalised, at most 512 MiB):
     * `/data/local/tmp/…`, or shared storage (`/sdcard/Download/…`) for an app with storage
     * access to read. The directory must exist. A file already there is refused
     * ([FailureReason.DEVICE_FILE]) unless this device handle pushed it, so a test never
     * overwrites the device's own files; the size is read back. The file is deleted on [detach].
     */
    suspend fun pushFile(
        devicePath: String,
        bytes: ByteArray,
    ) = pushFile(devicePath, bytes.size.toLong()) { bytes.inputStream() }

    /** [pushFile] with the bytes of the local file [source], streamed. */
    suspend fun pushFile(
        devicePath: String,
        source: Path,
    ) {
        require(Files.isRegularFile(source)) { "$source is not a readable file" }
        pushFile(devicePath, Files.size(source)) { Files.newInputStream(source) }
    }

    private suspend fun pushFile(
        devicePath: String,
        size: Long,
        open: () -> InputStream,
    ) {
        require(size <= MAX_FILE_BYTES) { "at most $MAX_FILE_BYTES bytes are pushed, not $size" }
        val header =
            PushFileHeader.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                .setDevicePath(devicePath).setSizeBytes(size).build()
        fileCall("Device.pushFile") {
            pushFile(
                uploadParts(
                    PushFileRequest.newBuilder().setHeader(header).build(),
                    { PushFileRequest.newBuilder().setChunk(it).build() },
                    open,
                ),
            )
        }
    }

    /** The bytes of the regular file at [devicePath] on the device (at most 512 MiB). */
    suspend fun pullFile(devicePath: String): ByteArray = ByteArrayOutputStream().also { pullFile(devicePath, it) }.toByteArray()

    /** [pullFile] into the local file [target], replaced only once the whole file has arrived. */
    suspend fun pullFile(
        devicePath: String,
        target: Path,
    ) {
        val absolute = target.toAbsolutePath()
        val partial = withContext(Dispatchers.IO) { Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".part") }
        try {
            withContext(Dispatchers.IO) { Files.newOutputStream(partial) }.use { pullFile(devicePath, it) }
            withContext(Dispatchers.IO) { Files.move(partial, absolute, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(partial) }
        }
    }

    private suspend fun pullFile(
        devicePath: String,
        out: OutputStream,
    ) {
        val request =
            PullFileRequest.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                .setDevicePath(devicePath).build()
        fileCall("Device.pullFile") {
            var expected = -1L
            var received = 0L
            pullFile(request).collect { part ->
                if (expected < 0) expected = part.sizeBytes
                received += part.chunk.size()
                withContext(Dispatchers.IO) { part.chunk.writeTo(out) }
            }
            check(received == expected) { "pulled $received of $expected bytes of $devicePath" }
        }
    }

    /**
     * Adds a photo or video to the device's gallery and returns its device path: [bytes] are
     * written as [fileName] (letters, digits, `.`, `_`, `-` or spaces, with a photo extension —
     * jpg, jpeg, png, gif, webp, heic, heif, bmp — or a video one — mp4, 3gp, webm, mkv, mov)
     * to `/sdcard/Pictures/Tap/` or `/sdcard/Movies/Tap/`, and indexed by the media scanner so
     * gallery apps and photo pickers list it (read back). A name already there is refused
     * ([FailureReason.DEVICE_FILE]) unless this device handle added it. Deleted, and dropped
     * from the index, on [detach]; nothing else in the gallery is touched.
     */
    suspend fun addMedia(
        fileName: String,
        bytes: ByteArray,
    ): String = addMedia(fileName, bytes.size.toLong()) { bytes.inputStream() }

    /** [addMedia] with the bytes of the local file [source], named [fileName] (its own name by default). */
    suspend fun addMedia(
        source: Path,
        fileName: String = source.fileName.toString(),
    ): String {
        require(Files.isRegularFile(source)) { "$source is not a readable file" }
        return addMedia(fileName, Files.size(source)) { Files.newInputStream(source) }
    }

    private suspend fun addMedia(
        fileName: String,
        size: Long,
        open: () -> InputStream,
    ): String {
        require(size <= MAX_FILE_BYTES) { "at most $MAX_FILE_BYTES bytes are added, not $size" }
        val header =
            AddMediaHeader.newBuilder().setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                .setFileName(fileName).setSizeBytes(size).build()
        var path = ""
        fileCall("Device.addMedia") {
            path =
                addMedia(
                    uploadParts(
                        AddMediaRequest.newBuilder().setHeader(header).build(),
                        { AddMediaRequest.newBuilder().setChunk(it).build() },
                        open,
                    ),
                ).devicePath
        }
        return path
    }

    private fun <R> uploadParts(
        header: R,
        chunk: (ByteString) -> R,
        open: () -> InputStream,
    ): Flow<R> =
        flow {
            emit(header)
            open().use { input ->
                val buffer = ByteArray(FILE_CHUNK_BYTES)
                while (true) {
                    val read = input.readNBytes(buffer, 0, buffer.size)
                    if (read <= 0) break
                    emit(chunk(ByteString.copyFrom(buffer, 0, read)))
                }
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun fileCall(
        operation: String,
        block: suspend DeviceServiceGrpcKt.DeviceServiceCoroutineStub.() -> Unit,
    ) {
        ensureTapBound(operation)
        admitted(operation) {
            mapped(serial) {
                client.devices.withDeadlineAfter(FILE_DEADLINE_MS, TimeUnit.MILLISECONDS).block()
            }
        }
    }

    private suspend fun condition(
        operation: String,
        block: suspend DeviceServiceGrpcKt.DeviceServiceCoroutineStub.() -> Unit,
    ) {
        ensureTapBound(operation)
        admitted(operation) {
            mapped(serial) {
                client.devices
                    .withDeadlineAfter(timeouts.lifecycle.inWholeMilliseconds + RPC_DEADLINE_SLACK_MS, TimeUnit.MILLISECONDS)
                    .block()
            }
        }
    }

    /**
     * Waits for a toast and returns it: one shown in the last 3.5 s (the longest a toast stays up)
     * or arriving within [timeout]. [text] matches with [mode] (any text when null); a toast from
     * any package counts unless [packageName] names one ([App.awaitToast] passes its own). Not
     * consuming: the same toast can satisfy two calls in a row. Throws [WaitTimeoutException]
     * (`NO_TOAST`) when none matches.
     */
    suspend fun awaitToast(
        text: String? = null,
        mode: MatchMode = MatchMode.EXACT,
        packageName: String? = null,
        timeout: Duration = timeouts.wait,
    ): Toast {
        ensureTapBound("Device.awaitToast")
        return admitted("Device.awaitToast") {
            val result =
                rpcExecute(timeout) {
                    awaitToast =
                        AwaitToast
                            .newBuilder()
                            .apply {
                                text?.let { setText(it).setMode(mode.toProto()) }
                                packageName?.let(::setPackageName)
                            }.build()
                }
            if (result.hasError()) {
                if (result.error.code != ErrorCodeProto.ERR_WAIT_TIMEOUT) throw CommandException(result, "await_toast", serial, null)
                val what = text?.let { "a toast \"$it\"" } ?: "a toast"
                val from = packageName?.let { " from $it" }.orEmpty()
                throw WaitTimeoutException.of(result, what + from, serial)
            }
            result.toast.toModel()
        }
    }

    /**
     * Waits until a matching notification is active (one already posted counts) and returns the
     * newest. [title] and [text] match with [mode] (any when null); [packageName] narrows it to
     * one app's notifications ([App.awaitNotification] passes its own). Notifications are read as
     * data from a notification listener in the Tap driver app, not from the shade, so nothing is
     * opened on screen; the server gives the driver notification access for the session (taken
     * back on [detach]). Group summaries are left out. Throws [WaitTimeoutException]
     * (`NO_NOTIFICATION`) when none matches within [timeout].
     */
    suspend fun awaitNotification(
        title: String? = null,
        text: String? = null,
        mode: MatchMode = MatchMode.EXACT,
        packageName: String? = null,
        timeout: Duration = timeouts.wait,
    ): Notification {
        ensureTapBound("Device.awaitNotification")
        return admitted("Device.awaitNotification") {
            val result =
                rpcExecute(timeout) {
                    awaitNotification = AwaitNotification.newBuilder().setMatch(notificationMatch(title, text, mode, packageName)).build()
                }
            if (result.hasError()) {
                if (result.error.code != ErrorCodeProto.ERR_WAIT_TIMEOUT) throw CommandException(result, "await_notification", serial, null)
                val what = listOfNotNull(title?.let { "title \"$it\"" }, text?.let { "text \"$it\"" }).joinToString(" and ")
                val from = packageName?.let { " from $it" }.orEmpty()
                throw WaitTimeoutException.of(result, "a notification" + (if (what.isEmpty()) "" else " with $what") + from, serial)
            }
            result.notification.toModel()
        }
    }

    /** The active notifications, newest first (group summaries left out), as [awaitNotification] reads them. */
    suspend fun notifications(timeout: Duration? = null): List<Notification> =
        executeOrThrow(timeout) { listNotifications = ListNotifications.getDefaultInstance() }
            .notifications.notificationsList.map { it.toModel() }

    /**
     * Opens the one active notification that matches (as for [awaitNotification]) as a tap on it
     * in the shade does: sends its content intent and, when it auto-cancels, removes it. With
     * [action], presses its action button with exactly that title instead, and the notification
     * stays. No match or several matches throw [CommandException] (`NOT_FOUND` / `AMBIGUOUS`),
     * and a notification that opens nothing or has no such action `ACTION_REJECTED`
     * (`ACTION_NOT_OFFERED`), before anything is sent. What the app does then is for the test to
     * assert (an activity, say: [foregroundActivity]).
     */
    suspend fun openNotification(
        title: String? = null,
        text: String? = null,
        mode: MatchMode = MatchMode.EXACT,
        packageName: String? = null,
        action: String? = null,
        timeout: Duration? = null,
    ) {
        executeOrThrow(timeout) {
            openNotification =
                OpenNotification.newBuilder().setMatch(notificationMatch(title, text, mode, packageName)).apply { action?.let(::setAction) }.build()
        }
    }

    /**
     * Dismisses the one active notification that matches (as for [awaitNotification]) as a swipe
     * does. No match or several matches throw [CommandException] (`NOT_FOUND` / `AMBIGUOUS`), and
     * an ongoing notification `ACTION_REJECTED` (`NOT_CLEARABLE`), before anything changes.
     */
    suspend fun dismissNotification(
        title: String? = null,
        text: String? = null,
        mode: MatchMode = MatchMode.EXACT,
        packageName: String? = null,
        timeout: Duration? = null,
    ) {
        executeOrThrow(timeout) { dismissNotification = DismissNotification.newBuilder().setMatch(notificationMatch(title, text, mode, packageName)).build() }
    }

    private fun notificationMatch(
        title: String?,
        text: String?,
        mode: MatchMode,
        packageName: String?,
    ): NotificationMatch =
        NotificationMatch
            .newBuilder()
            .apply {
                title?.let(::setTitle)
                text?.let(::setText)
                if (title != null || text != null) setMode(mode.toProto())
                packageName?.let(::setPackageName)
            }.build()

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
     * Begin recording this device with scrcpy on the server host (`tap start --scrcpy`, default
     * `scrcpy` on `PATH`). [video] defaults to silent H.264 MP4; [audioSource] may be `output`
     * (Android 11+, redirects local playback), `playback` (Android 13+, keeps local sound but
     * apps can opt out), or `mic`. Audio only (`video = false`) is Opus; both tracks are
     * Matroska. Video is bounded to 30 seconds/16 MiB, audio only to 60 seconds/3 MiB, and
     * [maxSeconds] ends the capture early. One recording per attached device; detach discards
     * an unfinished one. Capture begins shortly after this returns, and a capture the device
     * refuses is reported by [stopRecording].
     */
    suspend fun startRecording(video: Boolean = true, audioSource: String? = null, maxSeconds: Int = if (video) 30 else 60) {
        ensureTapBound("Device.startRecording")
        require(video || audioSource != null) { "Select video or an audio source" }
        require(audioSource == null || audioSource in setOf("output", "playback", "mic")) { "Unknown audio source: $audioSource" }
        require(maxSeconds in 1..(if (video) 30 else 60)) { "maxSeconds is outside the recording limit" }
        admitted("Device.startRecording") {
            mapped(serial) {
                client.devices.withDeadlineAfter(30, TimeUnit.SECONDS).startRecording(
                    StartRecordingRequest.newBuilder()
                        .setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId)
                        .setVideo(video).setAudioSource(audioSource.orEmpty()).setMaxSeconds(maxSeconds).build(),
                )
            }
        }
    }

    /** Stop and return a checksummed [Recording], formatted as MP4, Matroska or Opus. */
    suspend fun stopRecording(): Recording {
        ensureTapBound("Device.stopRecording")
        return admitted("Device.stopRecording") {
            val response = mapped(serial) {
                client.devices.withDeadlineAfter(30, TimeUnit.SECONDS).stopRecording(
                    StopRecordingRequest.newBuilder()
                        .setClientConnectionId(ownerConnection.id).setAttachedDeviceId(attachedDeviceId).build(),
                )
            }
            val bytes = response.data.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (hash != response.sha256) throw IllegalStateException("Recording checksum mismatch for $serial")
            Recording(bytes, response.format)
        }
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

    /** [App.awaitVisible]. */
    internal suspend fun awaitAppVisible(
        packageName: String,
        timeout: Duration,
    ) {
        ensureTapBound("App.awaitVisible")
        admitted("App.awaitVisible") {
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

    /** [App.awaitScreenStable] and its single-signal shorthands. */
    internal suspend fun awaitScreenStable(
        packageName: String,
        stableFor: Duration,
        timeout: Duration,
        signal: StabilitySignal,
    ) {
        ensureTapBound("App.awaitScreenStable")
        admitted("App.awaitScreenStable") {
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
     * Host-side polling for conditions the driver cannot evaluate in one command (cross-device,
     * backend state). Prefer [App.await] / [Screen.await] for UI conditions: they poll on the device in one RPC.
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
        internal suspend fun attachDevice(
            connection: TapConnection,
            serial: String,
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
                    .setDefaultTimeoutMs(timeouts.action.inWholeMilliseconds)
                    .apply {
                        // Absent = fail at once when another session holds the device.
                        if (options.waitForDevice.isPositive()) setLeaseTimeoutMs(options.waitForDevice.inWholeMilliseconds)
                        if (options.skipDriverInstall) setSkipDriverInstall(true)
                    }.build()
            val response =
                mapped(serial) {
                    connection.client.devices
                        .withDeadlineAfter(180 + options.waitForDevice.inWholeSeconds, TimeUnit.SECONDS)
                        .attach(request)
                }
            return Device(connection, response.attachedDeviceId, response.serial, response.generation, timeouts, bounds)
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
