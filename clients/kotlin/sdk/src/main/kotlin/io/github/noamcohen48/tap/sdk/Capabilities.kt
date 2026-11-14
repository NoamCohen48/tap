package io.github.noamcohen48.tap.sdk

/** Operation IDs. Client composites use these primitives; unknown future IDs remain in entries. */
enum class Capability {
    UNKNOWN,
    DEVICE_INFO,
    PRESS_KEY,
    DUMP_HIERARCHY,
    EXISTS,
    COUNT,
    SNAPSHOT,
    WAIT_VISIBLE,
    WAIT_GONE,
    WAIT_APP_VISIBLE,
    WAIT_SCREEN_STABLE,
    TAP,
    LONG_TAP,
    SET_TEXT,
    TYPE_TEXT,
    CLEAR_TEXT,
    SWIPE,
    SCROLL,
    OPEN_SYSTEM_PANEL,
    SET_ORIENTATION,
    SET_DISPLAY_ROTATION,
    UNFREEZE_ROTATION,
    DISMISS_KEYGUARD,
    DOUBLE_TAP,
    DRAG,
    PINCH,
    FLING,
    WAIT_PERMISSION_PROMPT,
    CHOOSE_PERMISSION,
    HIDE_KEYBOARD,
    KEYBOARD_IME_ACTION,
    CLIPBOARD_WRITE,
    CLIPBOARD_READ,
    AWAIT_TOAST,
    ACCESSIBILITY_ACTION,
    SET_PROGRESS,
    SET_LOCATION,
    AWAIT_NOTIFICATION,
    LIST_NOTIFICATIONS,
    OPEN_NOTIFICATION,
    DISMISS_NOTIFICATION,
    MULTI_TAP,
    TIMED_PRESS,
    WAIT_STATE,
    ACCESSIBILITY_PAGE_UP,
    ACCESSIBILITY_PAGE_DOWN,
    ACCESSIBILITY_PAGE_LEFT,
    ACCESSIBILITY_PAGE_RIGHT,
    ACCESSIBILITY_PRESS_AND_HOLD,
    SCREENSHOT,
    DRIVER_LOG,
    DEVICE_LOGS,
    SCREEN_SNAPSHOT,
    RESOLVE_REF,
    DEVICE_SET_ANIMATIONS,
    DEVICE_SET_DARK_MODE,
    DEVICE_SET_FONT_SCALE,
    DEVICE_SET_DENSITY,
    DEVICE_SET_NETWORK,
    NETWORK_DISABLE_WIFI,
    NETWORK_ENABLE_AIRPLANE_MODE,
    DEVICE_SET_SYSTEM_LOCALES,
    DEVICE_SET_STAY_AWAKE,
    DEVICE_SET_ACCESSIBILITY_DISPLAY,
    DEVICE_SET_BOLD_TEXT,
    GET_FOREGROUND_ACTIVITY,
    PUSH_FILE,
    PULL_FILE,
    ADD_MEDIA,
    RECORD_VIDEO,
    RECORD_AUDIO_OUTPUT,
    RECORD_AUDIO_PLAYBACK,
    RECORD_AUDIO_MIC,
    STOP_RECORDING,
    APP_INSTALL,
    APP_UNINSTALL,
    APP_IS_INSTALLED,
    APP_FORCE_STOP,
    APP_CLEAR_DATA,
    APP_GRANT_PERMISSION,
    APP_REVOKE_PERMISSION,
    APP_IS_PERMISSION_GRANTED,
    APP_SET_LOCALES,
    APP_GET_LOCALES,
    APP_LAUNCH,
    APP_COLD_LAUNCH,
    APP_FOREGROUND,
    APP_OPEN_LINK,
    APP_PROCESS,
    APP_IS_RUNNING,
}

/** Eligibility, not guaranteed execution success. Conditional and unknown are not unsupported. */
enum class CapabilityStatus { SUPPORTED, CONDITIONAL, UNSUPPORTED, UNKNOWN }

/** Machine-readable explanations; [DeviceCapability.detail] is for people, not branching. */
enum class CapabilityReason { REQUIRES_API, MISSING_HOST_TOOL, NETWORK_ADB, NOT_NEGOTIATED, FACT_UNAVAILABLE, PREREQUISITE, UNKNOWN }

/** Remaining prerequisites; discovery never exercises the operation to satisfy them. */
enum class CapabilityRequirement {
    TARGET_OFFERS_ACTION,
    NONSECURE_KEYGUARD,
    PERMISSION_CHOICE_OFFERED,
    RECORDING_RUNTIME,
    CAPTURE_NOT_EXCLUDED,
    SAFE_NETWORK_VALUES,
    RECORDING_ACTIVE,
    UNKNOWN,
}

/** One operation's eligibility; [rawId] preserves an unrecognised future server ID. */
data class DeviceCapability(
    val id: Capability,
    val status: CapabilityStatus,
    val reasons: List<CapabilityReason> = emptyList(),
    val requiredApi: Int? = null,
    val requirements: List<CapabilityRequirement> = emptyList(),
    val detail: String = "",
    val rawId: Int = 0,
)

/** Snapshot of operation eligibility. Missing entries index as UNKNOWN, never unsupported. */
data class DeviceCapabilities(
    val entries: List<DeviceCapability>,
) {
    /** Find a known ID; missing entries report UNKNOWN. Inspect [entries] for future IDs. */
    operator fun get(capability: Capability): DeviceCapability {
        require(capability != Capability.UNKNOWN) { "Inspect entries and rawId for unknown IDs" }
        return entries.firstOrNull { it.id == capability } ?: DeviceCapability(
            id = capability,
            status = CapabilityStatus.UNKNOWN,
            reasons = listOf(CapabilityReason.FACT_UNAVAILABLE),
            detail = "The server did not report this capability.",
        )
    }
}

private inline fun <reified T : Enum<T>> enumOrUnknown(
    name: String,
    prefix: String,
): T =
    enumValues<T>().firstOrNull { it.name == name.removePrefix(prefix) }
        ?: enumValueOf<T>("UNKNOWN")

internal fun io.github.noamcohen48.tap.api.v1.GetDeviceCapabilitiesResponse.toModel(): DeviceCapabilities =
    DeviceCapabilities(
        capabilitiesList.map { entry ->
            DeviceCapability(
                id = enumOrUnknown(entry.id.name, "CAPABILITY_"),
                status = enumOrUnknown(entry.status.name, "CAPABILITY_STATUS_"),
                reasons = entry.reasonsList.map { enumOrUnknown(it.name, "CAPABILITY_REASON_") },
                requiredApi = entry.requiredApi.takeIf { entry.hasRequiredApi() },
                requirements = entry.requirementsList.map { enumOrUnknown(it.name, "CAPABILITY_REQUIREMENT_") },
                detail = entry.detail,
                rawId = entry.idValue,
            )
        },
    )
