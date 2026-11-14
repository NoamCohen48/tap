package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.CapabilityId
import io.github.noamcohen48.tap.api.v1.CapabilityId.*
import io.github.noamcohen48.tap.api.v1.CapabilityReason.*
import io.github.noamcohen48.tap.api.v1.CapabilityRequirement
import io.github.noamcohen48.tap.api.v1.CapabilityRequirement.*
import io.github.noamcohen48.tap.api.v1.CapabilityStatus.*
import io.github.noamcohen48.tap.api.v1.DeviceCapability
import io.github.noamcohen48.tap.protocol.OperationRequirements
import java.nio.file.Files
import java.nio.file.Path

/** Read-only facts for discovery; null means inspection could not establish the fact. */
data class CapabilityFacts(
    val apiLevel: Int?,
    val serial: String,
    val negotiatedCapabilities: Set<String>,
    val scrcpyAvailable: Boolean?,
)

/**
 * Canonical operation catalog. The maps also classify every device/app RPC and command for the
 * coverage test. Composites in clients are built from these primitives, never a second catalog.
 */
object DeviceCapabilities {
    val commands: Map<String, CapabilityId> =
        mapOf(
            "device_info" to CAPABILITY_DEVICE_INFO,
            "press_key" to CAPABILITY_PRESS_KEY,
            "dump_hierarchy" to CAPABILITY_DUMP_HIERARCHY,
            "exists" to CAPABILITY_EXISTS,
            "count" to CAPABILITY_COUNT,
            "snapshot" to CAPABILITY_SNAPSHOT,
            "wait_visible" to CAPABILITY_WAIT_VISIBLE,
            "wait_gone" to CAPABILITY_WAIT_GONE,
            "wait_app_visible" to CAPABILITY_WAIT_APP_VISIBLE,
            "wait_screen_stable" to CAPABILITY_WAIT_SCREEN_STABLE,
            "tap" to CAPABILITY_TAP,
            "long_tap" to CAPABILITY_LONG_TAP,
            "set_text" to CAPABILITY_SET_TEXT,
            "type_text" to CAPABILITY_TYPE_TEXT,
            "clear_text" to CAPABILITY_CLEAR_TEXT,
            "swipe" to CAPABILITY_SWIPE,
            "scroll" to CAPABILITY_SCROLL,
            "open_system_panel" to CAPABILITY_OPEN_SYSTEM_PANEL,
            "set_orientation" to CAPABILITY_SET_ORIENTATION,
            "set_display_rotation" to CAPABILITY_SET_DISPLAY_ROTATION,
            "unfreeze_rotation" to CAPABILITY_UNFREEZE_ROTATION,
            "dismiss_keyguard" to CAPABILITY_DISMISS_KEYGUARD,
            "double_tap" to CAPABILITY_DOUBLE_TAP,
            "drag" to CAPABILITY_DRAG,
            "pinch" to CAPABILITY_PINCH,
            "fling" to CAPABILITY_FLING,
            "wait_permission_prompt" to CAPABILITY_WAIT_PERMISSION_PROMPT,
            "choose_permission" to CAPABILITY_CHOOSE_PERMISSION,
            "hide_keyboard" to CAPABILITY_HIDE_KEYBOARD,
            "perform_ime_action" to CAPABILITY_KEYBOARD_IME_ACTION,
            "set_clipboard" to CAPABILITY_CLIPBOARD_WRITE,
            "get_clipboard" to CAPABILITY_CLIPBOARD_READ,
            "await_toast" to CAPABILITY_AWAIT_TOAST,
            "perform_accessibility_action" to CAPABILITY_ACCESSIBILITY_ACTION,
            "set_progress" to CAPABILITY_SET_PROGRESS,
            "set_location" to CAPABILITY_SET_LOCATION,
            "await_notification" to CAPABILITY_AWAIT_NOTIFICATION,
            "list_notifications" to CAPABILITY_LIST_NOTIFICATIONS,
            "open_notification" to CAPABILITY_OPEN_NOTIFICATION,
            "dismiss_notification" to CAPABILITY_DISMISS_NOTIFICATION,
            "multi_tap" to CAPABILITY_MULTI_TAP,
            "hold" to CAPABILITY_TIMED_PRESS,
            "wait_state" to CAPABILITY_WAIT_STATE,
        )

    /** Empty sets explicitly exclude attachment/control-plane RPCs, not device operations. */
    val deviceRpcs: Map<String, Set<CapabilityId>> =
        mapOf(
            "ListDevices" to emptySet(),
            "Unquarantine" to emptySet(),
            "Attach" to emptySet(),
            "Detach" to emptySet(),
            "Execute" to emptySet(),
            "GetDeviceCapabilities" to emptySet(),
            "Screenshot" to setOf(CAPABILITY_SCREENSHOT),
            "DriverLog" to setOf(CAPABILITY_DRIVER_LOG),
            "DeviceLogs" to setOf(CAPABILITY_DEVICE_LOGS),
            "ListAppDeaths" to emptySet(),
            "StartRecording" to
                setOf(
                    CAPABILITY_RECORD_VIDEO,
                    CAPABILITY_RECORD_AUDIO_OUTPUT,
                    CAPABILITY_RECORD_AUDIO_PLAYBACK,
                    CAPABILITY_RECORD_AUDIO_MIC,
                ),
            "StopRecording" to setOf(CAPABILITY_STOP_RECORDING),
            "ScreenSnapshot" to setOf(CAPABILITY_SCREEN_SNAPSHOT),
            "ResolveRef" to setOf(CAPABILITY_RESOLVE_REF),
            "SetAnimations" to setOf(CAPABILITY_DEVICE_SET_ANIMATIONS),
            "SetDarkMode" to setOf(CAPABILITY_DEVICE_SET_DARK_MODE),
            "SetFontScale" to setOf(CAPABILITY_DEVICE_SET_FONT_SCALE),
            "SetDensity" to setOf(CAPABILITY_DEVICE_SET_DENSITY),
            "SetNetwork" to setOf(CAPABILITY_DEVICE_SET_NETWORK, CAPABILITY_NETWORK_DISABLE_WIFI, CAPABILITY_NETWORK_ENABLE_AIRPLANE_MODE),
            "SetSystemLocales" to setOf(CAPABILITY_DEVICE_SET_SYSTEM_LOCALES),
            "SetLocation" to setOf(CAPABILITY_SET_LOCATION),
            "SetStayAwake" to setOf(CAPABILITY_DEVICE_SET_STAY_AWAKE),
            "SetAccessibilityDisplay" to setOf(CAPABILITY_DEVICE_SET_ACCESSIBILITY_DISPLAY, CAPABILITY_DEVICE_SET_BOLD_TEXT),
            "GetForegroundActivity" to setOf(CAPABILITY_GET_FOREGROUND_ACTIVITY),
            "PushFile" to setOf(CAPABILITY_PUSH_FILE),
            "PullFile" to setOf(CAPABILITY_PULL_FILE),
            "AddMedia" to setOf(CAPABILITY_ADD_MEDIA),
        )

    /** AwaitIdle is intentionally excluded: synchronization is a separate work in progress. */
    val appRpcs: Map<String, Set<CapabilityId>> =
        mapOf(
            "Install" to setOf(CAPABILITY_APP_INSTALL),
            "Uninstall" to setOf(CAPABILITY_APP_UNINSTALL),
            "IsInstalled" to setOf(CAPABILITY_APP_IS_INSTALLED),
            "ForceStop" to setOf(CAPABILITY_APP_FORCE_STOP),
            "ClearData" to setOf(CAPABILITY_APP_CLEAR_DATA),
            "GrantPermission" to setOf(CAPABILITY_APP_GRANT_PERMISSION),
            "RevokePermission" to setOf(CAPABILITY_APP_REVOKE_PERMISSION),
            "IsPermissionGranted" to setOf(CAPABILITY_APP_IS_PERMISSION_GRANTED),
            "SetLocales" to setOf(CAPABILITY_APP_SET_LOCALES),
            "GetLocales" to setOf(CAPABILITY_APP_GET_LOCALES),
            "Launch" to setOf(CAPABILITY_APP_LAUNCH),
            "ColdLaunch" to setOf(CAPABILITY_APP_COLD_LAUNCH),
            "Foreground" to setOf(CAPABILITY_APP_FOREGROUND),
            "OpenLink" to setOf(CAPABILITY_APP_OPEN_LINK),
            "Process" to setOf(CAPABILITY_APP_PROCESS),
            "IsRunning" to setOf(CAPABILITY_APP_IS_RUNNING),
            "AwaitIdle" to emptySet(),
        )

    private val pages =
        setOf(
            CAPABILITY_ACCESSIBILITY_PAGE_UP,
            CAPABILITY_ACCESSIBILITY_PAGE_DOWN,
            CAPABILITY_ACCESSIBILITY_PAGE_LEFT,
            CAPABILITY_ACCESSIBILITY_PAGE_RIGHT,
        )
    private val actions =
        pages +
            setOf(
                CAPABILITY_ACCESSIBILITY_PRESS_AND_HOLD,
                CAPABILITY_ACCESSIBILITY_ACTION,
                CAPABILITY_SET_PROGRESS,
                CAPABILITY_KEYBOARD_IME_ACTION,
            )
    private val recording =
        setOf(CAPABILITY_RECORD_VIDEO, CAPABILITY_RECORD_AUDIO_OUTPUT, CAPABILITY_RECORD_AUDIO_PLAYBACK, CAPABILITY_RECORD_AUDIO_MIC)
    private val ids = (commands.values + deviceRpcs.values.flatten() + appRpcs.values.flatten() + actions).distinct().sortedBy { it.number }

    /** One result per catalog ID, sorted; no I/O, mutations, UI inspection or permission changes. */
    fun discover(facts: CapabilityFacts): List<DeviceCapability> =
        ids.map { id ->
            val minimum =
                when (id) {
                    CAPABILITY_KEYBOARD_IME_ACTION -> OperationRequirements.IME_ACTION_API
                    in pages -> OperationRequirements.PAGE_ACTION_API
                    CAPABILITY_ACCESSIBILITY_PRESS_AND_HOLD -> OperationRequirements.PRESS_AND_HOLD_API
                    CAPABILITY_DEVICE_SET_DARK_MODE -> DeviceConditions.DARK_MODE_API
                    CAPABILITY_DEVICE_SET_NETWORK, CAPABILITY_NETWORK_DISABLE_WIFI, CAPABILITY_NETWORK_ENABLE_AIRPLANE_MODE -> DeviceConditions.NETWORK_API
                    CAPABILITY_DEVICE_SET_BOLD_TEXT -> DeviceConditions.BOLD_TEXT_API
                    CAPABILITY_APP_SET_LOCALES, CAPABILITY_APP_GET_LOCALES -> APP_LOCALES_API
                    CAPABILITY_RECORD_AUDIO_OUTPUT, CAPABILITY_RECORD_AUDIO_MIC -> OperationRequirements.RECORD_AUDIO_API
                    CAPABILITY_RECORD_AUDIO_PLAYBACK -> OperationRequirements.RECORD_PLAYBACK_API
                    else -> null
                }
            val requirements =
                buildList<CapabilityRequirement> {
                    if (id in actions) add(CAPABILITY_REQUIREMENT_TARGET_OFFERS_ACTION)
                    if (id == CAPABILITY_DISMISS_KEYGUARD) add(CAPABILITY_REQUIREMENT_NONSECURE_KEYGUARD)
                    if (id == CAPABILITY_CHOOSE_PERMISSION) add(CAPABILITY_REQUIREMENT_PERMISSION_CHOICE_OFFERED)
                    if (id in recording) add(CAPABILITY_REQUIREMENT_RECORDING_RUNTIME)
                    if (id == CAPABILITY_RECORD_AUDIO_PLAYBACK) add(CAPABILITY_REQUIREMENT_CAPTURE_NOT_EXCLUDED)
                    if (id == CAPABILITY_DEVICE_SET_NETWORK && overNetwork(facts.serial)) add(CAPABILITY_REQUIREMENT_SAFE_NETWORK_VALUES)
                    if (id == CAPABILITY_STOP_RECORDING) add(CAPABILITY_REQUIREMENT_RECORDING_ACTIVE)
                }
            val wire =
                when (id) {
                    CAPABILITY_SCREENSHOT -> "artifact.screenshot.v1"
                    CAPABILITY_DUMP_HIERARCHY, CAPABILITY_SCREEN_SNAPSHOT -> "diagnostic.hierarchy.v1"
                    CAPABILITY_PRESS_KEY, CAPABILITY_TYPE_TEXT, CAPABILITY_HIDE_KEYBOARD -> "input.key-events.v1"
                    else -> null
                }
            val reasons =
                buildList {
                    if (minimum != null && facts.apiLevel != null && facts.apiLevel > 0 &&
                        facts.apiLevel < minimum
                    ) {
                        add(CAPABILITY_REASON_REQUIRES_API)
                    }
                    if (id in recording && facts.scrcpyAvailable == false) add(CAPABILITY_REASON_MISSING_HOST_TOOL)
                    if (id in setOf(CAPABILITY_NETWORK_DISABLE_WIFI, CAPABILITY_NETWORK_ENABLE_AIRPLANE_MODE) &&
                        overNetwork(facts.serial)
                    ) {
                        add(CAPABILITY_REASON_NETWORK_ADB)
                    }
                    if (wire != null && wire !in facts.negotiatedCapabilities) add(CAPABILITY_REASON_NOT_NEGOTIATED)
                }.toMutableList()
            val status =
                when {
                    reasons.isNotEmpty() -> {
                        CAPABILITY_STATUS_UNSUPPORTED
                    }

                    (minimum != null && (facts.apiLevel == null || facts.apiLevel <= 0)) ||
                        (id in recording && facts.scrcpyAvailable == null) -> {
                        reasons.add(CAPABILITY_REASON_FACT_UNAVAILABLE)
                        CAPABILITY_STATUS_UNKNOWN
                    }

                    requirements.isNotEmpty() -> {
                        reasons.add(CAPABILITY_REASON_PREREQUISITE)
                        CAPABILITY_STATUS_CONDITIONAL
                    }

                    else -> {
                        CAPABILITY_STATUS_SUPPORTED
                    }
                }
            val detail =
                buildList {
                    if (minimum != null) add("Requires Android API $minimum; device API ${facts.apiLevel ?: "unknown"}.")
                    if (CAPABILITY_REASON_MISSING_HOST_TOOL in reasons) add("scrcpy is not executable on the daemon host.")
                    if (CAPABILITY_REASON_NETWORK_ADB in reasons) add("Refused: this change would disconnect network ADB.")
                    if (CAPABILITY_REASON_NOT_NEGOTIATED in reasons) add("Driver did not negotiate $wire.")
                    if (CAPABILITY_REASON_FACT_UNAVAILABLE in reasons) add("A required device or host fact could not be established.")
                    if (id in actions) add("The target must offer the requested accessibility action; inspect the element's actions.")
                    if (id == CAPABILITY_DISMISS_KEYGUARD) add("Only a keyguard without a PIN, pattern or password can be dismissed.")
                    if (id == CAPABILITY_CHOOSE_PERMISSION) add("The permission dialog must offer the requested choice and accuracy.")
                    if (id in
                        recording
                    ) {
                        add("Recording still requires compatible scrcpy, device encoders and capture access; discovery starts no process.")
                    }
                    if (id ==
                        CAPABILITY_RECORD_AUDIO_PLAYBACK
                    ) {
                        add("Apps may exclude their playback audio; silence does not prove the app was silent.")
                    }
                    if (id == CAPABILITY_DEVICE_SET_NETWORK &&
                        overNetwork(facts.serial)
                    ) {
                        add("Over network ADB, Wi-Fi off and airplane mode on are refused.")
                    }
                    if (id == CAPABILITY_STOP_RECORDING) add("A recording must have been started on this attachment.")
                    if (id ==
                        CAPABILITY_CLIPBOARD_READ
                    ) {
                        add("Tap adopts shell background-read permission on API 29+; no manual IME or focus setup is normally needed.")
                    }
                    if (id in
                        setOf(
                            CAPABILITY_AWAIT_NOTIFICATION,
                            CAPABILITY_LIST_NOTIFICATIONS,
                            CAPABILITY_OPEN_NOTIFICATION,
                            CAPABILITY_DISMISS_NOTIFICATION,
                        )
                    ) {
                        add("Tap grants notification-listener access on the first notification operation, not during discovery.")
                    }
                    add("Execution-time checks and Android acceptance still apply.")
                }.joinToString(" ")
            DeviceCapability
                .newBuilder()
                .setId(id)
                .setStatus(status)
                .addAllReasons(reasons)
                .addAllRequirements(requirements)
                .setDetail(detail)
                .apply { minimum?.let { requiredApi = it } }
                .build()
        }
}

/**
 * Inspect executable presence only, without starting a process or touching the device. A present
 * tool is not proof of compatible flags/codecs. Null means inspection failed or PATH was excessive.
 */
internal fun executableAvailable(
    executable: String,
    searchPath: String? = System.getenv("PATH"),
): Boolean? =
    try {
        if ('/' in executable) {
            Path.of(executable).let { Files.isRegularFile(it) && Files.isExecutable(it) }
        } else {
            val directories = searchPath?.split(java.io.File.pathSeparator) ?: emptyList()
            if (directories.size > 128) {
                null
            } else {
                directories.any { directory ->
                    Path.of(directory.ifEmpty { "." }, executable).let { Files.isRegularFile(it) && Files.isExecutable(it) }
                }
            }
        }
    } catch (_: SecurityException) {
        null
    } catch (_: java.nio.file.InvalidPathException) {
        null
    }
