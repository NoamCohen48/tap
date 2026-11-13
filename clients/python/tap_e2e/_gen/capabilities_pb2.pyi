# ruff: noqa
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CapabilityId(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAPABILITY_UNSPECIFIED: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_INFO: _ClassVar[CapabilityId]
    CAPABILITY_PRESS_KEY: _ClassVar[CapabilityId]
    CAPABILITY_DUMP_HIERARCHY: _ClassVar[CapabilityId]
    CAPABILITY_EXISTS: _ClassVar[CapabilityId]
    CAPABILITY_COUNT: _ClassVar[CapabilityId]
    CAPABILITY_SNAPSHOT: _ClassVar[CapabilityId]
    CAPABILITY_WAIT_VISIBLE: _ClassVar[CapabilityId]
    CAPABILITY_WAIT_GONE: _ClassVar[CapabilityId]
    CAPABILITY_WAIT_APP_VISIBLE: _ClassVar[CapabilityId]
    CAPABILITY_WAIT_SCREEN_STABLE: _ClassVar[CapabilityId]
    CAPABILITY_TAP: _ClassVar[CapabilityId]
    CAPABILITY_LONG_TAP: _ClassVar[CapabilityId]
    CAPABILITY_SET_TEXT: _ClassVar[CapabilityId]
    CAPABILITY_TYPE_TEXT: _ClassVar[CapabilityId]
    CAPABILITY_CLEAR_TEXT: _ClassVar[CapabilityId]
    CAPABILITY_SWIPE: _ClassVar[CapabilityId]
    CAPABILITY_SCROLL: _ClassVar[CapabilityId]
    CAPABILITY_OPEN_SYSTEM_PANEL: _ClassVar[CapabilityId]
    CAPABILITY_SET_ORIENTATION: _ClassVar[CapabilityId]
    CAPABILITY_SET_DISPLAY_ROTATION: _ClassVar[CapabilityId]
    CAPABILITY_UNFREEZE_ROTATION: _ClassVar[CapabilityId]
    CAPABILITY_DISMISS_KEYGUARD: _ClassVar[CapabilityId]
    CAPABILITY_DOUBLE_TAP: _ClassVar[CapabilityId]
    CAPABILITY_DRAG: _ClassVar[CapabilityId]
    CAPABILITY_PINCH: _ClassVar[CapabilityId]
    CAPABILITY_FLING: _ClassVar[CapabilityId]
    CAPABILITY_WAIT_PERMISSION_PROMPT: _ClassVar[CapabilityId]
    CAPABILITY_CHOOSE_PERMISSION: _ClassVar[CapabilityId]
    CAPABILITY_HIDE_KEYBOARD: _ClassVar[CapabilityId]
    CAPABILITY_KEYBOARD_IME_ACTION: _ClassVar[CapabilityId]
    CAPABILITY_CLIPBOARD_WRITE: _ClassVar[CapabilityId]
    CAPABILITY_CLIPBOARD_READ: _ClassVar[CapabilityId]
    CAPABILITY_AWAIT_TOAST: _ClassVar[CapabilityId]
    CAPABILITY_ACCESSIBILITY_ACTION: _ClassVar[CapabilityId]
    CAPABILITY_SET_PROGRESS: _ClassVar[CapabilityId]
    CAPABILITY_SET_LOCATION: _ClassVar[CapabilityId]
    CAPABILITY_AWAIT_NOTIFICATION: _ClassVar[CapabilityId]
    CAPABILITY_LIST_NOTIFICATIONS: _ClassVar[CapabilityId]
    CAPABILITY_OPEN_NOTIFICATION: _ClassVar[CapabilityId]
    CAPABILITY_DISMISS_NOTIFICATION: _ClassVar[CapabilityId]
    CAPABILITY_MULTI_TAP: _ClassVar[CapabilityId]
    CAPABILITY_TIMED_PRESS: _ClassVar[CapabilityId]
    CAPABILITY_WAIT_STATE: _ClassVar[CapabilityId]
    CAPABILITY_ACCESSIBILITY_PAGE_UP: _ClassVar[CapabilityId]
    CAPABILITY_ACCESSIBILITY_PAGE_DOWN: _ClassVar[CapabilityId]
    CAPABILITY_ACCESSIBILITY_PAGE_LEFT: _ClassVar[CapabilityId]
    CAPABILITY_ACCESSIBILITY_PAGE_RIGHT: _ClassVar[CapabilityId]
    CAPABILITY_ACCESSIBILITY_PRESS_AND_HOLD: _ClassVar[CapabilityId]
    CAPABILITY_SCREENSHOT: _ClassVar[CapabilityId]
    CAPABILITY_DRIVER_LOG: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_LOGS: _ClassVar[CapabilityId]
    CAPABILITY_SCREEN_SNAPSHOT: _ClassVar[CapabilityId]
    CAPABILITY_RESOLVE_REF: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_ANIMATIONS: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_DARK_MODE: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_FONT_SCALE: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_DENSITY: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_NETWORK: _ClassVar[CapabilityId]
    CAPABILITY_NETWORK_DISABLE_WIFI: _ClassVar[CapabilityId]
    CAPABILITY_NETWORK_ENABLE_AIRPLANE_MODE: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_SYSTEM_LOCALES: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_STAY_AWAKE: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_ACCESSIBILITY_DISPLAY: _ClassVar[CapabilityId]
    CAPABILITY_DEVICE_SET_BOLD_TEXT: _ClassVar[CapabilityId]
    CAPABILITY_GET_FOREGROUND_ACTIVITY: _ClassVar[CapabilityId]
    CAPABILITY_PUSH_FILE: _ClassVar[CapabilityId]
    CAPABILITY_PULL_FILE: _ClassVar[CapabilityId]
    CAPABILITY_ADD_MEDIA: _ClassVar[CapabilityId]
    CAPABILITY_RECORD_VIDEO: _ClassVar[CapabilityId]
    CAPABILITY_RECORD_AUDIO_OUTPUT: _ClassVar[CapabilityId]
    CAPABILITY_RECORD_AUDIO_PLAYBACK: _ClassVar[CapabilityId]
    CAPABILITY_RECORD_AUDIO_MIC: _ClassVar[CapabilityId]
    CAPABILITY_STOP_RECORDING: _ClassVar[CapabilityId]
    CAPABILITY_APP_INSTALL: _ClassVar[CapabilityId]
    CAPABILITY_APP_UNINSTALL: _ClassVar[CapabilityId]
    CAPABILITY_APP_IS_INSTALLED: _ClassVar[CapabilityId]
    CAPABILITY_APP_FORCE_STOP: _ClassVar[CapabilityId]
    CAPABILITY_APP_CLEAR_DATA: _ClassVar[CapabilityId]
    CAPABILITY_APP_GRANT_PERMISSION: _ClassVar[CapabilityId]
    CAPABILITY_APP_REVOKE_PERMISSION: _ClassVar[CapabilityId]
    CAPABILITY_APP_IS_PERMISSION_GRANTED: _ClassVar[CapabilityId]
    CAPABILITY_APP_SET_LOCALES: _ClassVar[CapabilityId]
    CAPABILITY_APP_GET_LOCALES: _ClassVar[CapabilityId]
    CAPABILITY_APP_LAUNCH: _ClassVar[CapabilityId]
    CAPABILITY_APP_COLD_LAUNCH: _ClassVar[CapabilityId]
    CAPABILITY_APP_FOREGROUND: _ClassVar[CapabilityId]
    CAPABILITY_APP_OPEN_LINK: _ClassVar[CapabilityId]
    CAPABILITY_APP_PROCESS: _ClassVar[CapabilityId]
    CAPABILITY_APP_IS_RUNNING: _ClassVar[CapabilityId]

class CapabilityStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAPABILITY_STATUS_UNSPECIFIED: _ClassVar[CapabilityStatus]
    CAPABILITY_STATUS_SUPPORTED: _ClassVar[CapabilityStatus]
    CAPABILITY_STATUS_CONDITIONAL: _ClassVar[CapabilityStatus]
    CAPABILITY_STATUS_UNSUPPORTED: _ClassVar[CapabilityStatus]
    CAPABILITY_STATUS_UNKNOWN: _ClassVar[CapabilityStatus]

class CapabilityReason(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAPABILITY_REASON_UNSPECIFIED: _ClassVar[CapabilityReason]
    CAPABILITY_REASON_REQUIRES_API: _ClassVar[CapabilityReason]
    CAPABILITY_REASON_MISSING_HOST_TOOL: _ClassVar[CapabilityReason]
    CAPABILITY_REASON_NETWORK_ADB: _ClassVar[CapabilityReason]
    CAPABILITY_REASON_NOT_NEGOTIATED: _ClassVar[CapabilityReason]
    CAPABILITY_REASON_FACT_UNAVAILABLE: _ClassVar[CapabilityReason]
    CAPABILITY_REASON_PREREQUISITE: _ClassVar[CapabilityReason]

class CapabilityRequirement(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAPABILITY_REQUIREMENT_UNSPECIFIED: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_TARGET_OFFERS_ACTION: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_NONSECURE_KEYGUARD: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_PERMISSION_CHOICE_OFFERED: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_RECORDING_RUNTIME: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_CAPTURE_NOT_EXCLUDED: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_SAFE_NETWORK_VALUES: _ClassVar[CapabilityRequirement]
    CAPABILITY_REQUIREMENT_RECORDING_ACTIVE: _ClassVar[CapabilityRequirement]
CAPABILITY_UNSPECIFIED: CapabilityId
CAPABILITY_DEVICE_INFO: CapabilityId
CAPABILITY_PRESS_KEY: CapabilityId
CAPABILITY_DUMP_HIERARCHY: CapabilityId
CAPABILITY_EXISTS: CapabilityId
CAPABILITY_COUNT: CapabilityId
CAPABILITY_SNAPSHOT: CapabilityId
CAPABILITY_WAIT_VISIBLE: CapabilityId
CAPABILITY_WAIT_GONE: CapabilityId
CAPABILITY_WAIT_APP_VISIBLE: CapabilityId
CAPABILITY_WAIT_SCREEN_STABLE: CapabilityId
CAPABILITY_TAP: CapabilityId
CAPABILITY_LONG_TAP: CapabilityId
CAPABILITY_SET_TEXT: CapabilityId
CAPABILITY_TYPE_TEXT: CapabilityId
CAPABILITY_CLEAR_TEXT: CapabilityId
CAPABILITY_SWIPE: CapabilityId
CAPABILITY_SCROLL: CapabilityId
CAPABILITY_OPEN_SYSTEM_PANEL: CapabilityId
CAPABILITY_SET_ORIENTATION: CapabilityId
CAPABILITY_SET_DISPLAY_ROTATION: CapabilityId
CAPABILITY_UNFREEZE_ROTATION: CapabilityId
CAPABILITY_DISMISS_KEYGUARD: CapabilityId
CAPABILITY_DOUBLE_TAP: CapabilityId
CAPABILITY_DRAG: CapabilityId
CAPABILITY_PINCH: CapabilityId
CAPABILITY_FLING: CapabilityId
CAPABILITY_WAIT_PERMISSION_PROMPT: CapabilityId
CAPABILITY_CHOOSE_PERMISSION: CapabilityId
CAPABILITY_HIDE_KEYBOARD: CapabilityId
CAPABILITY_KEYBOARD_IME_ACTION: CapabilityId
CAPABILITY_CLIPBOARD_WRITE: CapabilityId
CAPABILITY_CLIPBOARD_READ: CapabilityId
CAPABILITY_AWAIT_TOAST: CapabilityId
CAPABILITY_ACCESSIBILITY_ACTION: CapabilityId
CAPABILITY_SET_PROGRESS: CapabilityId
CAPABILITY_SET_LOCATION: CapabilityId
CAPABILITY_AWAIT_NOTIFICATION: CapabilityId
CAPABILITY_LIST_NOTIFICATIONS: CapabilityId
CAPABILITY_OPEN_NOTIFICATION: CapabilityId
CAPABILITY_DISMISS_NOTIFICATION: CapabilityId
CAPABILITY_MULTI_TAP: CapabilityId
CAPABILITY_TIMED_PRESS: CapabilityId
CAPABILITY_WAIT_STATE: CapabilityId
CAPABILITY_ACCESSIBILITY_PAGE_UP: CapabilityId
CAPABILITY_ACCESSIBILITY_PAGE_DOWN: CapabilityId
CAPABILITY_ACCESSIBILITY_PAGE_LEFT: CapabilityId
CAPABILITY_ACCESSIBILITY_PAGE_RIGHT: CapabilityId
CAPABILITY_ACCESSIBILITY_PRESS_AND_HOLD: CapabilityId
CAPABILITY_SCREENSHOT: CapabilityId
CAPABILITY_DRIVER_LOG: CapabilityId
CAPABILITY_DEVICE_LOGS: CapabilityId
CAPABILITY_SCREEN_SNAPSHOT: CapabilityId
CAPABILITY_RESOLVE_REF: CapabilityId
CAPABILITY_DEVICE_SET_ANIMATIONS: CapabilityId
CAPABILITY_DEVICE_SET_DARK_MODE: CapabilityId
CAPABILITY_DEVICE_SET_FONT_SCALE: CapabilityId
CAPABILITY_DEVICE_SET_DENSITY: CapabilityId
CAPABILITY_DEVICE_SET_NETWORK: CapabilityId
CAPABILITY_NETWORK_DISABLE_WIFI: CapabilityId
CAPABILITY_NETWORK_ENABLE_AIRPLANE_MODE: CapabilityId
CAPABILITY_DEVICE_SET_SYSTEM_LOCALES: CapabilityId
CAPABILITY_DEVICE_SET_STAY_AWAKE: CapabilityId
CAPABILITY_DEVICE_SET_ACCESSIBILITY_DISPLAY: CapabilityId
CAPABILITY_DEVICE_SET_BOLD_TEXT: CapabilityId
CAPABILITY_GET_FOREGROUND_ACTIVITY: CapabilityId
CAPABILITY_PUSH_FILE: CapabilityId
CAPABILITY_PULL_FILE: CapabilityId
CAPABILITY_ADD_MEDIA: CapabilityId
CAPABILITY_RECORD_VIDEO: CapabilityId
CAPABILITY_RECORD_AUDIO_OUTPUT: CapabilityId
CAPABILITY_RECORD_AUDIO_PLAYBACK: CapabilityId
CAPABILITY_RECORD_AUDIO_MIC: CapabilityId
CAPABILITY_STOP_RECORDING: CapabilityId
CAPABILITY_APP_INSTALL: CapabilityId
CAPABILITY_APP_UNINSTALL: CapabilityId
CAPABILITY_APP_IS_INSTALLED: CapabilityId
CAPABILITY_APP_FORCE_STOP: CapabilityId
CAPABILITY_APP_CLEAR_DATA: CapabilityId
CAPABILITY_APP_GRANT_PERMISSION: CapabilityId
CAPABILITY_APP_REVOKE_PERMISSION: CapabilityId
CAPABILITY_APP_IS_PERMISSION_GRANTED: CapabilityId
CAPABILITY_APP_SET_LOCALES: CapabilityId
CAPABILITY_APP_GET_LOCALES: CapabilityId
CAPABILITY_APP_LAUNCH: CapabilityId
CAPABILITY_APP_COLD_LAUNCH: CapabilityId
CAPABILITY_APP_FOREGROUND: CapabilityId
CAPABILITY_APP_OPEN_LINK: CapabilityId
CAPABILITY_APP_PROCESS: CapabilityId
CAPABILITY_APP_IS_RUNNING: CapabilityId
CAPABILITY_STATUS_UNSPECIFIED: CapabilityStatus
CAPABILITY_STATUS_SUPPORTED: CapabilityStatus
CAPABILITY_STATUS_CONDITIONAL: CapabilityStatus
CAPABILITY_STATUS_UNSUPPORTED: CapabilityStatus
CAPABILITY_STATUS_UNKNOWN: CapabilityStatus
CAPABILITY_REASON_UNSPECIFIED: CapabilityReason
CAPABILITY_REASON_REQUIRES_API: CapabilityReason
CAPABILITY_REASON_MISSING_HOST_TOOL: CapabilityReason
CAPABILITY_REASON_NETWORK_ADB: CapabilityReason
CAPABILITY_REASON_NOT_NEGOTIATED: CapabilityReason
CAPABILITY_REASON_FACT_UNAVAILABLE: CapabilityReason
CAPABILITY_REASON_PREREQUISITE: CapabilityReason
CAPABILITY_REQUIREMENT_UNSPECIFIED: CapabilityRequirement
CAPABILITY_REQUIREMENT_TARGET_OFFERS_ACTION: CapabilityRequirement
CAPABILITY_REQUIREMENT_NONSECURE_KEYGUARD: CapabilityRequirement
CAPABILITY_REQUIREMENT_PERMISSION_CHOICE_OFFERED: CapabilityRequirement
CAPABILITY_REQUIREMENT_RECORDING_RUNTIME: CapabilityRequirement
CAPABILITY_REQUIREMENT_CAPTURE_NOT_EXCLUDED: CapabilityRequirement
CAPABILITY_REQUIREMENT_SAFE_NETWORK_VALUES: CapabilityRequirement
CAPABILITY_REQUIREMENT_RECORDING_ACTIVE: CapabilityRequirement

class DeviceCapability(_message.Message):
    __slots__ = ("id", "status", "reasons", "required_api", "requirements", "detail")
    ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    REASONS_FIELD_NUMBER: _ClassVar[int]
    REQUIRED_API_FIELD_NUMBER: _ClassVar[int]
    REQUIREMENTS_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    id: CapabilityId
    status: CapabilityStatus
    reasons: _containers.RepeatedScalarFieldContainer[CapabilityReason]
    required_api: int
    requirements: _containers.RepeatedScalarFieldContainer[CapabilityRequirement]
    detail: str
    def __init__(self, id: _Optional[_Union[CapabilityId, str]] = ..., status: _Optional[_Union[CapabilityStatus, str]] = ..., reasons: _Optional[_Iterable[_Union[CapabilityReason, str]]] = ..., required_api: _Optional[int] = ..., requirements: _Optional[_Iterable[_Union[CapabilityRequirement, str]]] = ..., detail: _Optional[str] = ...) -> None: ...

class GetDeviceCapabilitiesRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ...) -> None: ...

class GetDeviceCapabilitiesResponse(_message.Message):
    __slots__ = ("capabilities",)
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    capabilities: _containers.RepeatedCompositeFieldContainer[DeviceCapability]
    def __init__(self, capabilities: _Optional[_Iterable[_Union[DeviceCapability, _Mapping]]] = ...) -> None: ...
