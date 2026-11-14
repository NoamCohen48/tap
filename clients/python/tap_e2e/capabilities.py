"""Typed operation eligibility returned by the daemon, not a guarantee of execution success."""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from . import _gen as pb


class Capability(Enum):
    """Device/app operation IDs. Composites use these primitives; UNKNOWN retains future IDs."""

    UNKNOWN = "UNKNOWN"
    DEVICE_INFO = "DEVICE_INFO"
    PRESS_KEY = "PRESS_KEY"
    DUMP_HIERARCHY = "DUMP_HIERARCHY"
    EXISTS = "EXISTS"
    COUNT = "COUNT"
    SNAPSHOT = "SNAPSHOT"
    WAIT_VISIBLE = "WAIT_VISIBLE"
    WAIT_GONE = "WAIT_GONE"
    WAIT_APP_VISIBLE = "WAIT_APP_VISIBLE"
    WAIT_SCREEN_STABLE = "WAIT_SCREEN_STABLE"
    TAP = "TAP"
    LONG_TAP = "LONG_TAP"
    SET_TEXT = "SET_TEXT"
    TYPE_TEXT = "TYPE_TEXT"
    CLEAR_TEXT = "CLEAR_TEXT"
    SWIPE = "SWIPE"
    SCROLL = "SCROLL"
    OPEN_SYSTEM_PANEL = "OPEN_SYSTEM_PANEL"
    SET_ORIENTATION = "SET_ORIENTATION"
    SET_DISPLAY_ROTATION = "SET_DISPLAY_ROTATION"
    UNFREEZE_ROTATION = "UNFREEZE_ROTATION"
    DISMISS_KEYGUARD = "DISMISS_KEYGUARD"
    DOUBLE_TAP = "DOUBLE_TAP"
    DRAG = "DRAG"
    PINCH = "PINCH"
    FLING = "FLING"
    WAIT_PERMISSION_PROMPT = "WAIT_PERMISSION_PROMPT"
    CHOOSE_PERMISSION = "CHOOSE_PERMISSION"
    HIDE_KEYBOARD = "HIDE_KEYBOARD"
    KEYBOARD_IME_ACTION = "KEYBOARD_IME_ACTION"
    CLIPBOARD_WRITE = "CLIPBOARD_WRITE"
    CLIPBOARD_READ = "CLIPBOARD_READ"
    AWAIT_TOAST = "AWAIT_TOAST"
    ACCESSIBILITY_ACTION = "ACCESSIBILITY_ACTION"
    SET_PROGRESS = "SET_PROGRESS"
    SET_LOCATION = "SET_LOCATION"
    AWAIT_NOTIFICATION = "AWAIT_NOTIFICATION"
    LIST_NOTIFICATIONS = "LIST_NOTIFICATIONS"
    OPEN_NOTIFICATION = "OPEN_NOTIFICATION"
    DISMISS_NOTIFICATION = "DISMISS_NOTIFICATION"
    MULTI_TAP = "MULTI_TAP"
    TIMED_PRESS = "TIMED_PRESS"
    WAIT_STATE = "WAIT_STATE"
    ACCESSIBILITY_PAGE_UP = "ACCESSIBILITY_PAGE_UP"
    ACCESSIBILITY_PAGE_DOWN = "ACCESSIBILITY_PAGE_DOWN"
    ACCESSIBILITY_PAGE_LEFT = "ACCESSIBILITY_PAGE_LEFT"
    ACCESSIBILITY_PAGE_RIGHT = "ACCESSIBILITY_PAGE_RIGHT"
    ACCESSIBILITY_PRESS_AND_HOLD = "ACCESSIBILITY_PRESS_AND_HOLD"
    SCREENSHOT = "SCREENSHOT"
    DRIVER_LOG = "DRIVER_LOG"
    DEVICE_LOGS = "DEVICE_LOGS"
    SCREEN_SNAPSHOT = "SCREEN_SNAPSHOT"
    RESOLVE_REF = "RESOLVE_REF"
    DEVICE_SET_ANIMATIONS = "DEVICE_SET_ANIMATIONS"
    DEVICE_SET_DARK_MODE = "DEVICE_SET_DARK_MODE"
    DEVICE_SET_FONT_SCALE = "DEVICE_SET_FONT_SCALE"
    DEVICE_SET_DENSITY = "DEVICE_SET_DENSITY"
    DEVICE_SET_NETWORK = "DEVICE_SET_NETWORK"
    NETWORK_DISABLE_WIFI = "NETWORK_DISABLE_WIFI"
    NETWORK_ENABLE_AIRPLANE_MODE = "NETWORK_ENABLE_AIRPLANE_MODE"
    DEVICE_SET_SYSTEM_LOCALES = "DEVICE_SET_SYSTEM_LOCALES"
    DEVICE_SET_STAY_AWAKE = "DEVICE_SET_STAY_AWAKE"
    DEVICE_SET_ACCESSIBILITY_DISPLAY = "DEVICE_SET_ACCESSIBILITY_DISPLAY"
    DEVICE_SET_BOLD_TEXT = "DEVICE_SET_BOLD_TEXT"
    GET_FOREGROUND_ACTIVITY = "GET_FOREGROUND_ACTIVITY"
    PUSH_FILE = "PUSH_FILE"
    PULL_FILE = "PULL_FILE"
    ADD_MEDIA = "ADD_MEDIA"
    RECORD_VIDEO = "RECORD_VIDEO"
    RECORD_AUDIO_OUTPUT = "RECORD_AUDIO_OUTPUT"
    RECORD_AUDIO_PLAYBACK = "RECORD_AUDIO_PLAYBACK"
    RECORD_AUDIO_MIC = "RECORD_AUDIO_MIC"
    STOP_RECORDING = "STOP_RECORDING"
    APP_INSTALL = "APP_INSTALL"
    APP_UNINSTALL = "APP_UNINSTALL"
    APP_IS_INSTALLED = "APP_IS_INSTALLED"
    APP_FORCE_STOP = "APP_FORCE_STOP"
    APP_CLEAR_DATA = "APP_CLEAR_DATA"
    APP_GRANT_PERMISSION = "APP_GRANT_PERMISSION"
    APP_REVOKE_PERMISSION = "APP_REVOKE_PERMISSION"
    APP_IS_PERMISSION_GRANTED = "APP_IS_PERMISSION_GRANTED"
    APP_SET_LOCALES = "APP_SET_LOCALES"
    APP_GET_LOCALES = "APP_GET_LOCALES"
    APP_LAUNCH = "APP_LAUNCH"
    APP_COLD_LAUNCH = "APP_COLD_LAUNCH"
    APP_FOREGROUND = "APP_FOREGROUND"
    APP_OPEN_LINK = "APP_OPEN_LINK"
    APP_PROCESS = "APP_PROCESS"
    APP_IS_RUNNING = "APP_IS_RUNNING"


class CapabilityStatus(Enum):
    """Eligibility: unknown and conditional are never silently treated as unsupported."""

    SUPPORTED = "SUPPORTED"
    CONDITIONAL = "CONDITIONAL"
    UNSUPPORTED = "UNSUPPORTED"
    UNKNOWN = "UNKNOWN"


class CapabilityReason(Enum):
    """Machine-readable explanations; detail text is for people, not branching."""

    REQUIRES_API = "REQUIRES_API"
    MISSING_HOST_TOOL = "MISSING_HOST_TOOL"
    NETWORK_ADB = "NETWORK_ADB"
    NOT_NEGOTIATED = "NOT_NEGOTIATED"
    FACT_UNAVAILABLE = "FACT_UNAVAILABLE"
    PREREQUISITE = "PREREQUISITE"
    UNKNOWN = "UNKNOWN"


class CapabilityRequirement(Enum):
    """Remaining prerequisites discovery does not exercise an operation to satisfy."""

    TARGET_OFFERS_ACTION = "TARGET_OFFERS_ACTION"
    NONSECURE_KEYGUARD = "NONSECURE_KEYGUARD"
    PERMISSION_CHOICE_OFFERED = "PERMISSION_CHOICE_OFFERED"
    RECORDING_RUNTIME = "RECORDING_RUNTIME"
    CAPTURE_NOT_EXCLUDED = "CAPTURE_NOT_EXCLUDED"
    SAFE_NETWORK_VALUES = "SAFE_NETWORK_VALUES"
    RECORDING_ACTIVE = "RECORDING_ACTIVE"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class DeviceCapability:
    """Support for one operation. raw_id preserves an unrecognised future server ID."""

    id: Capability
    status: CapabilityStatus
    reasons: tuple[CapabilityReason, ...] = ()
    required_api: int | None = None
    requirements: tuple[CapabilityRequirement, ...] = ()
    detail: str = ""
    raw_id: int = 0


@dataclass(frozen=True)
class DeviceCapabilities:
    """Immutable snapshot of eligibility. Missing entries index as UNKNOWN, never unsupported."""

    entries: tuple[DeviceCapability, ...]

    def __getitem__(self, capability: Capability) -> DeviceCapability:
        """Find a known ID; a missing entry explicitly reports UNKNOWN."""
        if capability is Capability.UNKNOWN:
            raise KeyError("Inspect entries and raw_id for unknown capability IDs")
        return next((entry for entry in self.entries if entry.id is capability), DeviceCapability(
            id=capability, status=CapabilityStatus.UNKNOWN,
            reasons=(CapabilityReason.FACT_UNAVAILABLE,), detail="The server did not report this capability.",
        ))


def _enum(enum_type, wire_enum, value: int, prefix: str):
    try:
        return enum_type[wire_enum.Name(value).removeprefix(prefix)]
    except (ValueError, KeyError):
        return enum_type.UNKNOWN


def _from_proto(response: pb.GetDeviceCapabilitiesResponse) -> DeviceCapabilities:
    return DeviceCapabilities(tuple(DeviceCapability(
        id=_enum(Capability, pb.CapabilityId, entry.id, "CAPABILITY_"),
        status=_enum(CapabilityStatus, pb.CapabilityStatus, entry.status, "CAPABILITY_STATUS_"),
        reasons=tuple(_enum(CapabilityReason, pb.CapabilityReason, reason, "CAPABILITY_REASON_") for reason in entry.reasons),
        required_api=entry.required_api if entry.HasField("required_api") else None,
        requirements=tuple(_enum(CapabilityRequirement, pb.CapabilityRequirement, requirement, "CAPABILITY_REQUIREMENT_") for requirement in entry.requirements),
        detail=entry.detail, raw_id=entry.id,
    ) for entry in response.capabilities))
