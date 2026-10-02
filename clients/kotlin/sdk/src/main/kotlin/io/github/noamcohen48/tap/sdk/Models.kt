package io.github.noamcohen48.tap.sdk

import kotlin.time.Duration

/*
 * The SDK's own value types. The server API's protobuf messages stay an implementation detail
 * of the client (mapped in ProtoMapping.kt), so a schema addition never changes these signatures
 * and callers never import generated code.
 */

/** How a text property is compared with a selector's value. */
enum class MatchMode {
    /** The whole value equals the text. */
    EXACT,

    /** The text contains the value. */
    CONTAINS,

    /** The text starts with the value. */
    STARTS_WITH,

    /** The text ends with the value. */
    ENDS_WITH,

    /** The text fully matches the value as an RE2 regular expression (linear time; no backreferences or lookaround). */
    REGEX,
}

/**
 * Gesture direction: where the finger moves for [Element.swipe], the content edge moved
 * towards for [Element.scroll] and [Element.fling] (`DOWN` reveals content below).
 */
enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Display geometry: taller than wide, or wider than tall ([Device.setOrientation], [DeviceInfo.orientation]). */
enum class Orientation { PORTRAIT, LANDSCAPE }

/**
 * Clockwise display rotation relative to the device's natural orientation (portrait on phones,
 * often landscape on tablets): `Surface.ROTATION_0` / `_90` / `_180` / `_270`.
 */
enum class DisplayRotation { NATURAL, LEFT, UPSIDE_DOWN, RIGHT }

/**
 * A button of the runtime-permission dialog, by what it grants ([Device.awaitPermissionPrompt],
 * [Device.choosePermission]). Which ones a dialog offers depends on the permission, the Android
 * version and the device's permission controller.
 */
enum class PermissionChoice {
    /** "Allow". */
    ALLOW,

    /** "While using the app". */
    ALLOW_FOREGROUND_ONLY,

    /** "Only this time". */
    ALLOW_ONE_TIME,

    /** "Allow all the time" (background location on Android 10). */
    ALLOW_ALWAYS,

    /** "Select photos and videos" (partial media access, Android 14). */
    ALLOW_SELECTED,

    /** "Allow all" (media, Android 14). */
    ALLOW_ALL,

    /** "Don't allow" / "Deny". */
    DENY,

    /** "Deny & don't ask again" / "Don't allow" on a repeated request. */
    DENY_AND_DONT_ASK_AGAIN,

    /** "Keep while app is in use": refuses an upgrade to background access. */
    KEEP_FOREGROUND_ONLY,

    /** "Keep only this time": refuses an upgrade of a one-time grant. */
    KEEP_ONE_TIME,
}

/** The Precise / Approximate choice of the location permission dialog (Android 12+). */
enum class LocationAccuracy { PRECISE, APPROXIMATE }

/**
 * The runtime-permission dialog on screen: the [packageName] of its window (`device.app(packageName)`
 * reaches its other elements), the [choices] it offers, in [PermissionChoice] order, and the
 * location [accuracies] it lets the user pick (empty unless it asks for precise location).
 */
data class PermissionPrompt(
    val packageName: String,
    val choices: List<PermissionChoice>,
    val accuracies: List<LocationAccuracy> = emptyList(),
)

/**
 * A standard accessibility action that takes no arguments, as a screen reader performs it
 * ([Element.performAction]). Click and long click are not here: [Element.tap] and
 * [Element.longTap] touch the screen.
 */
enum class StandardAction {
    EXPAND,
    COLLAPSE,
    DISMISS,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,

    /** API 29+. */
    PAGE_UP,

    /** API 29+. */
    PAGE_DOWN,

    /** API 29+. */
    PAGE_LEFT,

    /** API 29+. */
    PAGE_RIGHT,
    SHOW_ON_SCREEN,
    CONTEXT_CLICK,

    /** API 30+. */
    PRESS_AND_HOLD,
    SELECT,
    CLEAR_SELECTION,

    /** Input focus. */
    FOCUS,
    CLEAR_FOCUS,
    COPY,
    CUT,
    PASTE,
}

/** How a range node counts its value. */
enum class RangeType { INT, FLOAT, PERCENT, UNKNOWN }

/** A range node's (SeekBar, Slider, RatingBar, ProgressBar) value and bounds, in its own units. */
data class Range(
    val type: RangeType,
    val min: Float,
    val max: Float,
    val current: Float,
)

/**
 * A toast [Device.awaitToast] saw: its [text] and the [packageName] that showed it (on Android 11+
 * a text toast is drawn by SystemUI but still reported under the app that posted it).
 */
data class Toast(
    val text: String,
    val packageName: String,
)

/** What [App.awaitScreenStable] watches for changes. */
enum class StabilitySignal {
    /** The accessibility tree of the focused window. */
    TREE,

    /** The window's pixels (0.5 % tolerance). */
    PIXELS,

    /** Both [TREE] and [PIXELS]. */
    ALL,
}

/**
 * Why a driver command failed ([CommandException.code]). [UNKNOWN] also stands for a code this
 * client version does not know.
 */
enum class ErrorCode {
    INVALID_REQUEST,
    INVALID_SELECTOR,
    UNSUPPORTED,
    UNAUTHENTICATED,
    SESSION_MISMATCH,
    DUPLICATE_OR_STALE,
    OVERLOADED,
    AUT_MISMATCH,

    /** No node matched a selector that needs one. */
    NOT_FOUND,

    /** More than one node matched a selector that needs exactly one. */
    AMBIGUOUS,
    NOT_INTERACTABLE,
    STALE_DURING_COMMAND,

    /** The node refused the action (for example `ACTION_SET_TEXT`). */
    ACTION_REJECTED,

    /** A device-side wait ran out of time; waits raise [WaitTimeoutException] instead. */
    WAIT_TIMEOUT,
    CANCELLED,
    DEADLINE_EXCEEDED,
    AUT_NOT_INSTALLED,
    AUT_CRASHED,
    AUT_ANR,
    SYNC_PROVIDER_UNAVAILABLE,
    DRIVER_UNHEALTHY,

    /** The driver connection dropped; [CommandException.detail] says whether the command was sent. */
    TRANSPORT_LOST,

    /** The command may or may not have run. Never retry a mutation after this. */
    INDETERMINATE,
    ARTIFACT_TRANSFER_FAILED,
    PAYLOAD_TOO_LARGE,
    INTERNAL,
    UNKNOWN,
}

/**
 * The server's structured reason for a failed call ([ServerException.reason]). [UNSPECIFIED]
 * means the failure did not come from the daemon (a transport failure, a proxy, a cancelled
 * call) or carried a reason this client version does not know.
 */
enum class FailureReason {
    UNSPECIFIED,

    /** A bug in the daemon or host core. Report it with the daemon log. */
    INTERNAL,

    /** Caller input the daemon rejected before doing anything. */
    INVALID_ARGUMENT,

    /** Missing or wrong daemon token. */
    UNAUTHENTICATED,

    /** The client connection is not (or no longer) known to the daemon. */
    UNKNOWN_CLIENT_CONNECTION,

    /** The attached device is not (or no longer) attached. */
    UNKNOWN_ATTACHED_DEVICE,

    /** The device is attached by another client connection. */
    NOT_OWNER,

    /** Another session holds the device's lock ([DeviceBusyException]). */
    DEVICE_BUSY,

    /** The device's journal keeps it out of service ([DeviceQuarantinedException]). */
    DEVICE_QUARANTINED,

    /** A host-side wait (app process, window, idle) ran out of time ([WaitTimeoutException]). */
    HOST_WAIT_TIMEOUT,

    /** An ADB command did not finish within its timeout. */
    ADB_TIMEOUT,

    /** An ADB command failed. */
    ADB_FAILED,

    /** An ADB command left an uncertain residual; the device may have changed. */
    ADB_REAP_UNCERTAIN,

    /** An app lifecycle postcondition did not hold ([AppLifecycleException]). */
    APP_LIFECYCLE,

    /** The installed driver is not the build this daemon expects. */
    DRIVER_BUILD_MISMATCH,

    /** The driver could not be started or authenticated. */
    DRIVER_START_FAILED,

    /** The driver connection was lost around a host-issued command. */
    DRIVER_TRANSPORT,

    /** A host-issued driver command failed. */
    DRIVER_COMMAND,

    /** The session can no longer run operations: detach and attach again. */
    SESSION_UNUSABLE,

    /** The daemon cannot serve the request in its current state. */
    DAEMON_PRECONDITION,

    /** The ref is not in the attached device's latest screen snapshot. */
    UNKNOWN_REF,

    /** The ref's node had no selector that matched it alone. */
    REF_NOT_ADDRESSABLE,

    /** The device's API level is too low for the call (detail `REQUIRES_API_<n>`). */
    UNSUPPORTED_API,

    /** A device setting did not read back as written (or could not be restored). */
    DEVICE_SETTING,

    /**
     * A device file was not written or read as asked: it exists and this device handle did not
     * create it, its directory is missing, it is not a regular file, or it did not read back.
     */
    DEVICE_FILE,
}

/** Why a device-side wait timed out ([WaitTimeoutException.reason]). */
enum class WaitReason {
    /** `visible` / `one`: nothing matched. */
    NO_MATCH,

    /** `one`: several nodes matched ([WaitTimeoutException.matchCount] says how many). */
    AMBIGUOUS,

    /** `gone`: the selector still matched. */
    STILL_PRESENT,

    /** `awaitScreenStable`: the screen kept changing. */
    SCREEN_CHANGING,

    /** `App.awaitVisible` / `App.awaitScreenStable`: the package never owned the focused window. */
    APP_NOT_VISIBLE,

    /** `awaitPermissionPrompt`: no runtime-permission dialog showed a known choice. */
    NO_PERMISSION_PROMPT,

    /** `awaitToast`: no matching toast was shown. */
    NO_TOAST,
    ;

    internal companion object {
        fun of(detail: String): WaitReason? = entries.firstOrNull { it.name == detail }
    }
}

/** A device's state in [TapClient.devices]. Only [FREE] and [LEASED] devices can be attached. */
enum class DeviceState {
    /** Online and not held by any session. */
    FREE,

    /** Held by a session, of this daemon or another process; attaching waits or fails busy. */
    LEASED,

    /** Kept out of service by its journal until an explicit reset. */
    QUARANTINED,
    OFFLINE,
    UNAUTHORIZED,

    /** A state this client version does not know. */
    UNKNOWN,
}

/** A node's bounds in screen pixels. */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * The state of one node at one instant ([Element.snapshot]). Text properties are null when the
 * node has none. [text] is the raw accessibility text: on API 26+ an empty field reports its hint
 * there and [showingHint] is true.
 */
data class ElementSnapshot(
    val className: String?,
    val packageName: String?,
    val resourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val bounds: Bounds,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val childCount: Int,
    val showingHint: Boolean,
    /** The standard actions the node offers ([Element.performAction]). */
    val actions: List<StandardAction> = emptyList(),
    /** The labels of the custom actions the node offers ([Element.performCustomAction]). */
    val customActions: List<String> = emptyList(),
    /** The node's range, when it is a range node ([Element.setProgress]). */
    val range: Range? = null,
)

/** A process of an app as the host sees it: PID plus the `/proc` start token that tells reused PIDs apart. */
data class AppProcess(
    val pid: Int,
    val startToken: String,
)

/** One device ADB lists ([TapClient.devices]). */
data class DeviceEntry(
    val serial: String,
    val state: DeviceState,
    /** The holding client connection when this daemon holds the device; null otherwise. */
    val clientConnectionId: String?,
    /** Why the device is [DeviceState.QUARANTINED]. */
    val quarantineReason: String?,
)

/** What the server reports about itself ([TapClient.info]). */
data class ServerInfo(
    val daemonVersion: String,
    val hostBuildId: String,
    val protocolVersion: String,
    val adbExecutable: String,
    val stateDir: String,
    /** Whether attaching installs a driver this daemon carries. */
    val driverAvailable: Boolean,
    val pid: Long,
    /** The timeouts the daemon applies when a call leaves one out; clients use the same numbers. */
    val defaults: ServerDefaults,
)

/** The daemon's default timeouts ([ServerInfo.defaults]). */
data class ServerDefaults(
    /** Commands without their own timeout; force-stop, clear-data and process queries. */
    val action: Duration,
    /** `App.awaitIdle` without a timeout. */
    val wait: Duration,
    /** Install, launch and cold launch without a timeout. */
    val lifecycle: Duration,
    /** `App.awaitIdle` quiet period. */
    val idleStable: Duration,
    /** How long to wait for a device another session holds. */
    val acquire: Duration,
)
