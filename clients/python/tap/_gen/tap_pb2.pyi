from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Operation(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    OP_UNSPECIFIED: _ClassVar[Operation]
    OP_HEALTH: _ClassVar[Operation]
    OP_DEVICE_INFO: _ClassVar[Operation]
    OP_PRESS_KEY: _ClassVar[Operation]
    OP_EXISTS: _ClassVar[Operation]
    OP_COUNT: _ClassVar[Operation]
    OP_SNAPSHOT: _ClassVar[Operation]
    OP_TAP: _ClassVar[Operation]
    OP_LONG_TAP: _ClassVar[Operation]
    OP_WAIT_VISIBLE: _ClassVar[Operation]
    OP_WAIT_GONE: _ClassVar[Operation]
    OP_WAIT_APP_VISIBLE: _ClassVar[Operation]
    OP_WAIT_SCREEN_STABLE: _ClassVar[Operation]
    OP_DUMP_HIERARCHY: _ClassVar[Operation]
    OP_SET_TEXT: _ClassVar[Operation]
    OP_TYPE_TEXT: _ClassVar[Operation]
    OP_CLEAR_TEXT: _ClassVar[Operation]
    OP_SWIPE: _ClassVar[Operation]
    OP_SCROLL: _ClassVar[Operation]
    OP_SCROLL_UNTIL: _ClassVar[Operation]
    OP_SCREENSHOT: _ClassVar[Operation]
    OP_SYNC_BOOTSTRAP: _ClassVar[Operation]
    OP_SYNC_STATE: _ClassVar[Operation]

class ErrorCode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ERR_UNSPECIFIED: _ClassVar[ErrorCode]
    ERR_INVALID_REQUEST: _ClassVar[ErrorCode]
    ERR_INVALID_SELECTOR: _ClassVar[ErrorCode]
    ERR_UNSUPPORTED: _ClassVar[ErrorCode]
    ERR_UNAUTHENTICATED: _ClassVar[ErrorCode]
    ERR_SESSION_MISMATCH: _ClassVar[ErrorCode]
    ERR_DUPLICATE_OR_STALE: _ClassVar[ErrorCode]
    ERR_OVERLOADED: _ClassVar[ErrorCode]
    ERR_AUT_MISMATCH: _ClassVar[ErrorCode]
    ERR_NOT_FOUND: _ClassVar[ErrorCode]
    ERR_AMBIGUOUS: _ClassVar[ErrorCode]
    ERR_NOT_INTERACTABLE: _ClassVar[ErrorCode]
    ERR_STALE_DURING_COMMAND: _ClassVar[ErrorCode]
    ERR_ACTION_REJECTED: _ClassVar[ErrorCode]
    ERR_WAIT_TIMEOUT: _ClassVar[ErrorCode]
    ERR_CANCELLED: _ClassVar[ErrorCode]
    ERR_DEADLINE_EXCEEDED: _ClassVar[ErrorCode]
    ERR_AUT_NOT_INSTALLED: _ClassVar[ErrorCode]
    ERR_AUT_CRASHED: _ClassVar[ErrorCode]
    ERR_AUT_ANR: _ClassVar[ErrorCode]
    ERR_SYNC_PROVIDER_UNAVAILABLE: _ClassVar[ErrorCode]
    ERR_DRIVER_UNHEALTHY: _ClassVar[ErrorCode]
    ERR_TRANSPORT_LOST: _ClassVar[ErrorCode]
    ERR_INDETERMINATE: _ClassVar[ErrorCode]
    ERR_ARTIFACT_TRANSFER_FAILED: _ClassVar[ErrorCode]
    ERR_PAYLOAD_TOO_LARGE: _ClassVar[ErrorCode]
    ERR_INTERNAL: _ClassVar[ErrorCode]
    ERR_UNKNOWN: _ClassVar[ErrorCode]

class Direction(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DIR_UNSPECIFIED: _ClassVar[Direction]
    DIR_UP: _ClassVar[Direction]
    DIR_DOWN: _ClassVar[Direction]
    DIR_LEFT: _ClassVar[Direction]
    DIR_RIGHT: _ClassVar[Direction]

class StabilitySignal(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    STABILITY_UNSPECIFIED: _ClassVar[StabilitySignal]
    STABILITY_TREE: _ClassVar[StabilitySignal]
    STABILITY_PIXELS: _ClassVar[StabilitySignal]
    STABILITY_ALL: _ClassVar[StabilitySignal]

class MatchMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MATCH_UNSPECIFIED: _ClassVar[MatchMode]
    MATCH_EXACT: _ClassVar[MatchMode]
    MATCH_CONTAINS: _ClassVar[MatchMode]
    MATCH_STARTS_WITH: _ClassVar[MatchMode]
    MATCH_ENDS_WITH: _ClassVar[MatchMode]
    MATCH_REGEX: _ClassVar[MatchMode]

class MatchLimit(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LIMIT_UNSPECIFIED: _ClassVar[MatchLimit]
    LIMIT_EXACTLY_ONE: _ClassVar[MatchLimit]
    LIMIT_FIRST: _ClassVar[MatchLimit]
    LIMIT_AT: _ClassVar[MatchLimit]

class TargetScope(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SCOPE_UNSPECIFIED: _ClassVar[TargetScope]
    SCOPE_AUT: _ClassVar[TargetScope]
    SCOPE_SYSTEM: _ClassVar[TargetScope]

class DeviceState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DEVICE_STATE_UNSPECIFIED: _ClassVar[DeviceState]
    DEVICE_FREE: _ClassVar[DeviceState]
    DEVICE_LEASED: _ClassVar[DeviceState]
    DEVICE_QUARANTINED: _ClassVar[DeviceState]
    DEVICE_OFFLINE: _ClassVar[DeviceState]
OP_UNSPECIFIED: Operation
OP_HEALTH: Operation
OP_DEVICE_INFO: Operation
OP_PRESS_KEY: Operation
OP_EXISTS: Operation
OP_COUNT: Operation
OP_SNAPSHOT: Operation
OP_TAP: Operation
OP_LONG_TAP: Operation
OP_WAIT_VISIBLE: Operation
OP_WAIT_GONE: Operation
OP_WAIT_APP_VISIBLE: Operation
OP_WAIT_SCREEN_STABLE: Operation
OP_DUMP_HIERARCHY: Operation
OP_SET_TEXT: Operation
OP_TYPE_TEXT: Operation
OP_CLEAR_TEXT: Operation
OP_SWIPE: Operation
OP_SCROLL: Operation
OP_SCROLL_UNTIL: Operation
OP_SCREENSHOT: Operation
OP_SYNC_BOOTSTRAP: Operation
OP_SYNC_STATE: Operation
ERR_UNSPECIFIED: ErrorCode
ERR_INVALID_REQUEST: ErrorCode
ERR_INVALID_SELECTOR: ErrorCode
ERR_UNSUPPORTED: ErrorCode
ERR_UNAUTHENTICATED: ErrorCode
ERR_SESSION_MISMATCH: ErrorCode
ERR_DUPLICATE_OR_STALE: ErrorCode
ERR_OVERLOADED: ErrorCode
ERR_AUT_MISMATCH: ErrorCode
ERR_NOT_FOUND: ErrorCode
ERR_AMBIGUOUS: ErrorCode
ERR_NOT_INTERACTABLE: ErrorCode
ERR_STALE_DURING_COMMAND: ErrorCode
ERR_ACTION_REJECTED: ErrorCode
ERR_WAIT_TIMEOUT: ErrorCode
ERR_CANCELLED: ErrorCode
ERR_DEADLINE_EXCEEDED: ErrorCode
ERR_AUT_NOT_INSTALLED: ErrorCode
ERR_AUT_CRASHED: ErrorCode
ERR_AUT_ANR: ErrorCode
ERR_SYNC_PROVIDER_UNAVAILABLE: ErrorCode
ERR_DRIVER_UNHEALTHY: ErrorCode
ERR_TRANSPORT_LOST: ErrorCode
ERR_INDETERMINATE: ErrorCode
ERR_ARTIFACT_TRANSFER_FAILED: ErrorCode
ERR_PAYLOAD_TOO_LARGE: ErrorCode
ERR_INTERNAL: ErrorCode
ERR_UNKNOWN: ErrorCode
DIR_UNSPECIFIED: Direction
DIR_UP: Direction
DIR_DOWN: Direction
DIR_LEFT: Direction
DIR_RIGHT: Direction
STABILITY_UNSPECIFIED: StabilitySignal
STABILITY_TREE: StabilitySignal
STABILITY_PIXELS: StabilitySignal
STABILITY_ALL: StabilitySignal
MATCH_UNSPECIFIED: MatchMode
MATCH_EXACT: MatchMode
MATCH_CONTAINS: MatchMode
MATCH_STARTS_WITH: MatchMode
MATCH_ENDS_WITH: MatchMode
MATCH_REGEX: MatchMode
LIMIT_UNSPECIFIED: MatchLimit
LIMIT_EXACTLY_ONE: MatchLimit
LIMIT_FIRST: MatchLimit
LIMIT_AT: MatchLimit
SCOPE_UNSPECIFIED: TargetScope
SCOPE_AUT: TargetScope
SCOPE_SYSTEM: TargetScope
DEVICE_STATE_UNSPECIFIED: DeviceState
DEVICE_FREE: DeviceState
DEVICE_LEASED: DeviceState
DEVICE_QUARANTINED: DeviceState
DEVICE_OFFLINE: DeviceState

class StringMatch(_message.Message):
    __slots__ = ("value", "mode")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    value: str
    mode: MatchMode
    def __init__(self, value: _Optional[str] = ..., mode: _Optional[_Union[MatchMode, str]] = ...) -> None: ...

class ResourceId(_message.Message):
    __slots__ = ("name", "package_name", "aut_package")
    NAME_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    name: str
    package_name: str
    aut_package: bool
    def __init__(self, name: _Optional[str] = ..., package_name: _Optional[str] = ..., aut_package: _Optional[bool] = ...) -> None: ...

class NodeSelector(_message.Message):
    __slots__ = ("text", "content_description", "hint", "class_name", "resource", "enabled", "checked", "checkable", "clickable", "focused", "focusable", "long_clickable", "scrollable", "selected", "parent", "ancestor", "child", "descendant")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    HINT_FIELD_NUMBER: _ClassVar[int]
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    CHECKED_FIELD_NUMBER: _ClassVar[int]
    CHECKABLE_FIELD_NUMBER: _ClassVar[int]
    CLICKABLE_FIELD_NUMBER: _ClassVar[int]
    FOCUSED_FIELD_NUMBER: _ClassVar[int]
    FOCUSABLE_FIELD_NUMBER: _ClassVar[int]
    LONG_CLICKABLE_FIELD_NUMBER: _ClassVar[int]
    SCROLLABLE_FIELD_NUMBER: _ClassVar[int]
    SELECTED_FIELD_NUMBER: _ClassVar[int]
    PARENT_FIELD_NUMBER: _ClassVar[int]
    ANCESTOR_FIELD_NUMBER: _ClassVar[int]
    CHILD_FIELD_NUMBER: _ClassVar[int]
    DESCENDANT_FIELD_NUMBER: _ClassVar[int]
    text: StringMatch
    content_description: StringMatch
    hint: StringMatch
    class_name: StringMatch
    resource: ResourceId
    enabled: bool
    checked: bool
    checkable: bool
    clickable: bool
    focused: bool
    focusable: bool
    long_clickable: bool
    scrollable: bool
    selected: bool
    parent: NodeSelector
    ancestor: NodeSelector
    child: NodeSelector
    descendant: NodeSelector
    def __init__(self, text: _Optional[_Union[StringMatch, _Mapping]] = ..., content_description: _Optional[_Union[StringMatch, _Mapping]] = ..., hint: _Optional[_Union[StringMatch, _Mapping]] = ..., class_name: _Optional[_Union[StringMatch, _Mapping]] = ..., resource: _Optional[_Union[ResourceId, _Mapping]] = ..., enabled: _Optional[bool] = ..., checked: _Optional[bool] = ..., checkable: _Optional[bool] = ..., clickable: _Optional[bool] = ..., focused: _Optional[bool] = ..., focusable: _Optional[bool] = ..., long_clickable: _Optional[bool] = ..., scrollable: _Optional[bool] = ..., selected: _Optional[bool] = ..., parent: _Optional[_Union[NodeSelector, _Mapping]] = ..., ancestor: _Optional[_Union[NodeSelector, _Mapping]] = ..., child: _Optional[_Union[NodeSelector, _Mapping]] = ..., descendant: _Optional[_Union[NodeSelector, _Mapping]] = ...) -> None: ...

class Selector(_message.Message):
    __slots__ = ("node", "scope", "scope_package", "limit", "index", "accept_accessibility_order")
    NODE_FIELD_NUMBER: _ClassVar[int]
    SCOPE_FIELD_NUMBER: _ClassVar[int]
    SCOPE_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    INDEX_FIELD_NUMBER: _ClassVar[int]
    ACCEPT_ACCESSIBILITY_ORDER_FIELD_NUMBER: _ClassVar[int]
    node: NodeSelector
    scope: TargetScope
    scope_package: str
    limit: MatchLimit
    index: int
    accept_accessibility_order: bool
    def __init__(self, node: _Optional[_Union[NodeSelector, _Mapping]] = ..., scope: _Optional[_Union[TargetScope, str]] = ..., scope_package: _Optional[str] = ..., limit: _Optional[_Union[MatchLimit, str]] = ..., index: _Optional[int] = ..., accept_accessibility_order: _Optional[bool] = ...) -> None: ...

class Command(_message.Message):
    __slots__ = ("operation", "timeout_ms", "selector", "container_selector", "input_text", "direction", "distance_percent", "max_scrolls", "key_code", "package_name", "observed_pid", "observed_start_token", "expected_process_start_uuid", "expected_session_identity", "stable_for_ms", "stable_signal")
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    CONTAINER_SELECTOR_FIELD_NUMBER: _ClassVar[int]
    INPUT_TEXT_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_PERCENT_FIELD_NUMBER: _ClassVar[int]
    MAX_SCROLLS_FIELD_NUMBER: _ClassVar[int]
    KEY_CODE_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    OBSERVED_PID_FIELD_NUMBER: _ClassVar[int]
    OBSERVED_START_TOKEN_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_PROCESS_START_UUID_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_SESSION_IDENTITY_FIELD_NUMBER: _ClassVar[int]
    STABLE_FOR_MS_FIELD_NUMBER: _ClassVar[int]
    STABLE_SIGNAL_FIELD_NUMBER: _ClassVar[int]
    operation: Operation
    timeout_ms: int
    selector: Selector
    container_selector: Selector
    input_text: str
    direction: Direction
    distance_percent: int
    max_scrolls: int
    key_code: int
    package_name: str
    observed_pid: int
    observed_start_token: str
    expected_process_start_uuid: str
    expected_session_identity: str
    stable_for_ms: int
    stable_signal: StabilitySignal
    def __init__(self, operation: _Optional[_Union[Operation, str]] = ..., timeout_ms: _Optional[int] = ..., selector: _Optional[_Union[Selector, _Mapping]] = ..., container_selector: _Optional[_Union[Selector, _Mapping]] = ..., input_text: _Optional[str] = ..., direction: _Optional[_Union[Direction, str]] = ..., distance_percent: _Optional[int] = ..., max_scrolls: _Optional[int] = ..., key_code: _Optional[int] = ..., package_name: _Optional[str] = ..., observed_pid: _Optional[int] = ..., observed_start_token: _Optional[str] = ..., expected_process_start_uuid: _Optional[str] = ..., expected_session_identity: _Optional[str] = ..., stable_for_ms: _Optional[int] = ..., stable_signal: _Optional[_Union[StabilitySignal, str]] = ...) -> None: ...

class SyncState(_message.Message):
    __slots__ = ("initialized", "process_id", "process_start_uuid", "session_identity", "generation", "busy_count", "last_transition_elapsed_ms", "error")
    INITIALIZED_FIELD_NUMBER: _ClassVar[int]
    PROCESS_ID_FIELD_NUMBER: _ClassVar[int]
    PROCESS_START_UUID_FIELD_NUMBER: _ClassVar[int]
    SESSION_IDENTITY_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    BUSY_COUNT_FIELD_NUMBER: _ClassVar[int]
    LAST_TRANSITION_ELAPSED_MS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    initialized: bool
    process_id: int
    process_start_uuid: str
    session_identity: str
    generation: int
    busy_count: int
    last_transition_elapsed_ms: int
    error: str
    def __init__(self, initialized: _Optional[bool] = ..., process_id: _Optional[int] = ..., process_start_uuid: _Optional[str] = ..., session_identity: _Optional[str] = ..., generation: _Optional[int] = ..., busy_count: _Optional[int] = ..., last_transition_elapsed_ms: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...

class ArtifactInfo(_message.Message):
    __slots__ = ("blob_id", "media_type", "byte_count", "sha256", "width", "height")
    BLOB_ID_FIELD_NUMBER: _ClassVar[int]
    MEDIA_TYPE_FIELD_NUMBER: _ClassVar[int]
    BYTE_COUNT_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    blob_id: str
    media_type: str
    byte_count: int
    sha256: str
    width: int
    height: int
    def __init__(self, blob_id: _Optional[str] = ..., media_type: _Optional[str] = ..., byte_count: _Optional[int] = ..., sha256: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ...) -> None: ...

class Bounds(_message.Message):
    __slots__ = ("left", "top", "right", "bottom")
    LEFT_FIELD_NUMBER: _ClassVar[int]
    TOP_FIELD_NUMBER: _ClassVar[int]
    RIGHT_FIELD_NUMBER: _ClassVar[int]
    BOTTOM_FIELD_NUMBER: _ClassVar[int]
    left: int
    top: int
    right: int
    bottom: int
    def __init__(self, left: _Optional[int] = ..., top: _Optional[int] = ..., right: _Optional[int] = ..., bottom: _Optional[int] = ...) -> None: ...

class ElementSnapshot(_message.Message):
    __slots__ = ("class_name", "package_name", "resource_name", "text", "content_description", "hint", "bounds", "checkable", "checked", "clickable", "enabled", "focusable", "focused", "long_clickable", "scrollable", "selected", "child_count")
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_NAME_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    HINT_FIELD_NUMBER: _ClassVar[int]
    BOUNDS_FIELD_NUMBER: _ClassVar[int]
    CHECKABLE_FIELD_NUMBER: _ClassVar[int]
    CHECKED_FIELD_NUMBER: _ClassVar[int]
    CLICKABLE_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    FOCUSABLE_FIELD_NUMBER: _ClassVar[int]
    FOCUSED_FIELD_NUMBER: _ClassVar[int]
    LONG_CLICKABLE_FIELD_NUMBER: _ClassVar[int]
    SCROLLABLE_FIELD_NUMBER: _ClassVar[int]
    SELECTED_FIELD_NUMBER: _ClassVar[int]
    CHILD_COUNT_FIELD_NUMBER: _ClassVar[int]
    class_name: str
    package_name: str
    resource_name: str
    text: str
    content_description: str
    hint: str
    bounds: Bounds
    checkable: bool
    checked: bool
    clickable: bool
    enabled: bool
    focusable: bool
    focused: bool
    long_clickable: bool
    scrollable: bool
    selected: bool
    child_count: int
    def __init__(self, class_name: _Optional[str] = ..., package_name: _Optional[str] = ..., resource_name: _Optional[str] = ..., text: _Optional[str] = ..., content_description: _Optional[str] = ..., hint: _Optional[str] = ..., bounds: _Optional[_Union[Bounds, _Mapping]] = ..., checkable: _Optional[bool] = ..., checked: _Optional[bool] = ..., clickable: _Optional[bool] = ..., enabled: _Optional[bool] = ..., focusable: _Optional[bool] = ..., focused: _Optional[bool] = ..., long_clickable: _Optional[bool] = ..., scrollable: _Optional[bool] = ..., selected: _Optional[bool] = ..., child_count: _Optional[int] = ...) -> None: ...

class DeviceInfo(_message.Message):
    __slots__ = ("api_level", "manufacturer", "model", "product", "display_width", "display_height", "display_rotation", "current_package")
    API_LEVEL_FIELD_NUMBER: _ClassVar[int]
    MANUFACTURER_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    PRODUCT_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_WIDTH_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_ROTATION_FIELD_NUMBER: _ClassVar[int]
    CURRENT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    api_level: int
    manufacturer: str
    model: str
    product: str
    display_width: int
    display_height: int
    display_rotation: int
    current_package: str
    def __init__(self, api_level: _Optional[int] = ..., manufacturer: _Optional[str] = ..., model: _Optional[str] = ..., product: _Optional[str] = ..., display_width: _Optional[int] = ..., display_height: _Optional[int] = ..., display_rotation: _Optional[int] = ..., current_package: _Optional[str] = ...) -> None: ...

class CommandResult(_message.Message):
    __slots__ = ("ok", "value", "text", "error_code", "detail", "message", "duration_ms", "sync_state", "artifact", "count", "snapshot", "device_info", "request_id", "session_generation")
    OK_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    ERROR_CODE_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    SYNC_STATE_FIELD_NUMBER: _ClassVar[int]
    ARTIFACT_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_GENERATION_FIELD_NUMBER: _ClassVar[int]
    ok: bool
    value: bool
    text: str
    error_code: ErrorCode
    detail: str
    message: str
    duration_ms: int
    sync_state: SyncState
    artifact: ArtifactInfo
    count: int
    snapshot: ElementSnapshot
    device_info: DeviceInfo
    request_id: int
    session_generation: int
    def __init__(self, ok: _Optional[bool] = ..., value: _Optional[bool] = ..., text: _Optional[str] = ..., error_code: _Optional[_Union[ErrorCode, str]] = ..., detail: _Optional[str] = ..., message: _Optional[str] = ..., duration_ms: _Optional[int] = ..., sync_state: _Optional[_Union[SyncState, _Mapping]] = ..., artifact: _Optional[_Union[ArtifactInfo, _Mapping]] = ..., count: _Optional[int] = ..., snapshot: _Optional[_Union[ElementSnapshot, _Mapping]] = ..., device_info: _Optional[_Union[DeviceInfo, _Mapping]] = ..., request_id: _Optional[int] = ..., session_generation: _Optional[int] = ...) -> None: ...

class OpenConnectionRequest(_message.Message):
    __slots__ = ("name",)
    NAME_FIELD_NUMBER: _ClassVar[int]
    name: str
    def __init__(self, name: _Optional[str] = ...) -> None: ...

class OpenConnectionResponse(_message.Message):
    __slots__ = ("connection_id",)
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    def __init__(self, connection_id: _Optional[str] = ...) -> None: ...

class AttachRequest(_message.Message):
    __slots__ = ("connection_id",)
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    def __init__(self, connection_id: _Optional[str] = ...) -> None: ...

class ConnectionEvent(_message.Message):
    __slots__ = ("at_epoch_ms", "message")
    AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    at_epoch_ms: int
    message: str
    def __init__(self, at_epoch_ms: _Optional[int] = ..., message: _Optional[str] = ...) -> None: ...

class CloseConnectionRequest(_message.Message):
    __slots__ = ("connection_id",)
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    def __init__(self, connection_id: _Optional[str] = ...) -> None: ...

class CloseConnectionResponse(_message.Message):
    __slots__ = ("sessions_closed",)
    SESSIONS_CLOSED_FIELD_NUMBER: _ClassVar[int]
    sessions_closed: int
    def __init__(self, sessions_closed: _Optional[int] = ...) -> None: ...

class InfoRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InfoResponse(_message.Message):
    __slots__ = ("service_version", "host_build_id", "protocol_version", "adb_executable", "state_dir", "bundled_driver")
    SERVICE_VERSION_FIELD_NUMBER: _ClassVar[int]
    HOST_BUILD_ID_FIELD_NUMBER: _ClassVar[int]
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    ADB_EXECUTABLE_FIELD_NUMBER: _ClassVar[int]
    STATE_DIR_FIELD_NUMBER: _ClassVar[int]
    BUNDLED_DRIVER_FIELD_NUMBER: _ClassVar[int]
    service_version: str
    host_build_id: str
    protocol_version: str
    adb_executable: str
    state_dir: str
    bundled_driver: bool
    def __init__(self, service_version: _Optional[str] = ..., host_build_id: _Optional[str] = ..., protocol_version: _Optional[str] = ..., adb_executable: _Optional[str] = ..., state_dir: _Optional[str] = ..., bundled_driver: _Optional[bool] = ...) -> None: ...

class DeviceEntry(_message.Message):
    __slots__ = ("serial", "state", "held_by_connection", "quarantine_reason")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    HELD_BY_CONNECTION_FIELD_NUMBER: _ClassVar[int]
    QUARANTINE_REASON_FIELD_NUMBER: _ClassVar[int]
    serial: str
    state: DeviceState
    held_by_connection: str
    quarantine_reason: str
    def __init__(self, serial: _Optional[str] = ..., state: _Optional[_Union[DeviceState, str]] = ..., held_by_connection: _Optional[str] = ..., quarantine_reason: _Optional[str] = ...) -> None: ...

class ListDevicesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListDevicesResponse(_message.Message):
    __slots__ = ("devices",)
    DEVICES_FIELD_NUMBER: _ClassVar[int]
    devices: _containers.RepeatedCompositeFieldContainer[DeviceEntry]
    def __init__(self, devices: _Optional[_Iterable[_Union[DeviceEntry, _Mapping]]] = ...) -> None: ...

class OpenSessionRequest(_message.Message):
    __slots__ = ("connection_id", "serial", "aut_package", "driver_apk", "driver_test_apk", "skip_driver_install", "sync_authority", "allowed_system_packages", "default_timeout_ms", "lease_timeout_ms")
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    DRIVER_APK_FIELD_NUMBER: _ClassVar[int]
    DRIVER_TEST_APK_FIELD_NUMBER: _ClassVar[int]
    SKIP_DRIVER_INSTALL_FIELD_NUMBER: _ClassVar[int]
    SYNC_AUTHORITY_FIELD_NUMBER: _ClassVar[int]
    ALLOWED_SYSTEM_PACKAGES_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    LEASE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    serial: str
    aut_package: str
    driver_apk: str
    driver_test_apk: str
    skip_driver_install: bool
    sync_authority: str
    allowed_system_packages: _containers.RepeatedScalarFieldContainer[str]
    default_timeout_ms: int
    lease_timeout_ms: int
    def __init__(self, connection_id: _Optional[str] = ..., serial: _Optional[str] = ..., aut_package: _Optional[str] = ..., driver_apk: _Optional[str] = ..., driver_test_apk: _Optional[str] = ..., skip_driver_install: _Optional[bool] = ..., sync_authority: _Optional[str] = ..., allowed_system_packages: _Optional[_Iterable[str]] = ..., default_timeout_ms: _Optional[int] = ..., lease_timeout_ms: _Optional[int] = ...) -> None: ...

class OpenSessionResponse(_message.Message):
    __slots__ = ("session_id", "serial", "generation", "device_info")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    serial: str
    generation: int
    device_info: DeviceInfo
    def __init__(self, session_id: _Optional[str] = ..., serial: _Optional[str] = ..., generation: _Optional[int] = ..., device_info: _Optional[_Union[DeviceInfo, _Mapping]] = ...) -> None: ...

class CloseSessionRequest(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    def __init__(self, session_id: _Optional[str] = ...) -> None: ...

class CloseSessionResponse(_message.Message):
    __slots__ = ("clean", "detail")
    CLEAN_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    clean: bool
    detail: str
    def __init__(self, clean: _Optional[bool] = ..., detail: _Optional[str] = ...) -> None: ...

class ExecuteRequest(_message.Message):
    __slots__ = ("session_id", "command")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    command: Command
    def __init__(self, session_id: _Optional[str] = ..., command: _Optional[_Union[Command, _Mapping]] = ...) -> None: ...

class ScreenshotRequest(_message.Message):
    __slots__ = ("session_id", "timeout_ms", "write_to")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    WRITE_TO_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    timeout_ms: int
    write_to: str
    def __init__(self, session_id: _Optional[str] = ..., timeout_ms: _Optional[int] = ..., write_to: _Optional[str] = ...) -> None: ...

class ScreenshotResponse(_message.Message):
    __slots__ = ("png", "artifact", "path")
    PNG_FIELD_NUMBER: _ClassVar[int]
    ARTIFACT_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    png: bytes
    artifact: ArtifactInfo
    path: str
    def __init__(self, png: _Optional[bytes] = ..., artifact: _Optional[_Union[ArtifactInfo, _Mapping]] = ..., path: _Optional[str] = ...) -> None: ...

class DriverLogRequest(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    def __init__(self, session_id: _Optional[str] = ...) -> None: ...

class DriverLogResponse(_message.Message):
    __slots__ = ("lines",)
    LINES_FIELD_NUMBER: _ClassVar[int]
    lines: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, lines: _Optional[_Iterable[str]] = ...) -> None: ...

class AppRequest(_message.Message):
    __slots__ = ("session_id", "package_name", "timeout_ms")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    package_name: str
    timeout_ms: int
    def __init__(self, session_id: _Optional[str] = ..., package_name: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class AppInstallRequest(_message.Message):
    __slots__ = ("app", "apk_path")
    APP_FIELD_NUMBER: _ClassVar[int]
    APK_PATH_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    apk_path: str
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., apk_path: _Optional[str] = ...) -> None: ...

class AppGrantRequest(_message.Message):
    __slots__ = ("app", "permission")
    APP_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    permission: str
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., permission: _Optional[str] = ...) -> None: ...

class AppLaunchRequest(_message.Message):
    __slots__ = ("app", "activity")
    APP_FIELD_NUMBER: _ClassVar[int]
    ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    activity: str
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., activity: _Optional[str] = ...) -> None: ...

class AppAwaitIdleRequest(_message.Message):
    __slots__ = ("app", "stable_for_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    STABLE_FOR_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    stable_for_ms: int
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., stable_for_ms: _Optional[int] = ...) -> None: ...

class ProcessIdentity(_message.Message):
    __slots__ = ("pid", "start_token")
    PID_FIELD_NUMBER: _ClassVar[int]
    START_TOKEN_FIELD_NUMBER: _ClassVar[int]
    pid: int
    start_token: str
    def __init__(self, pid: _Optional[int] = ..., start_token: _Optional[str] = ...) -> None: ...

class AppBool(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: bool
    def __init__(self, value: _Optional[bool] = ...) -> None: ...

class AppEmpty(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
