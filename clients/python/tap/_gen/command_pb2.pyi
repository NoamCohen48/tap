# ruff: noqa
from . import selector_pb2 as _selector_pb2
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

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

class DeviceInfoQuery(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PressKey(_message.Message):
    __slots__ = ("key_code",)
    KEY_CODE_FIELD_NUMBER: _ClassVar[int]
    key_code: int
    def __init__(self, key_code: _Optional[int] = ...) -> None: ...

class DumpHierarchy(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Exists(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class Count(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class Snapshot(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class WaitVisible(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class WaitGone(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class WaitAppVisible(_message.Message):
    __slots__ = ("package_name",)
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    package_name: str
    def __init__(self, package_name: _Optional[str] = ...) -> None: ...

class WaitScreenStable(_message.Message):
    __slots__ = ("package_name", "stable_for_ms", "signal")
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    STABLE_FOR_MS_FIELD_NUMBER: _ClassVar[int]
    SIGNAL_FIELD_NUMBER: _ClassVar[int]
    package_name: str
    stable_for_ms: int
    signal: StabilitySignal
    def __init__(self, package_name: _Optional[str] = ..., stable_for_ms: _Optional[int] = ..., signal: _Optional[_Union[StabilitySignal, str]] = ...) -> None: ...

class Tap(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class LongTap(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class SetText(_message.Message):
    __slots__ = ("selector", "text")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    text: str
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., text: _Optional[str] = ...) -> None: ...

class TypeText(_message.Message):
    __slots__ = ("selector", "text")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    text: str
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., text: _Optional[str] = ...) -> None: ...

class ClearText(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class Swipe(_message.Message):
    __slots__ = ("selector", "direction", "distance_percent")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_PERCENT_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    direction: Direction
    distance_percent: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., direction: _Optional[_Union[Direction, str]] = ..., distance_percent: _Optional[int] = ...) -> None: ...

class Scroll(_message.Message):
    __slots__ = ("selector", "direction", "distance_percent")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_PERCENT_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    direction: Direction
    distance_percent: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., direction: _Optional[_Union[Direction, str]] = ..., distance_percent: _Optional[int] = ...) -> None: ...

class ScrollUntil(_message.Message):
    __slots__ = ("selector", "container", "direction", "distance_percent", "max_scrolls")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    CONTAINER_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_PERCENT_FIELD_NUMBER: _ClassVar[int]
    MAX_SCROLLS_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    container: _selector_pb2.Selector
    direction: Direction
    distance_percent: int
    max_scrolls: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., container: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., direction: _Optional[_Union[Direction, str]] = ..., distance_percent: _Optional[int] = ..., max_scrolls: _Optional[int] = ...) -> None: ...

class Command(_message.Message):
    __slots__ = ("timeout_ms", "device_info", "press_key", "dump_hierarchy", "exists", "count", "snapshot", "wait_visible", "wait_gone", "wait_app_visible", "wait_screen_stable", "tap", "long_tap", "set_text", "type_text", "clear_text", "swipe", "scroll", "scroll_until")
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    PRESS_KEY_FIELD_NUMBER: _ClassVar[int]
    DUMP_HIERARCHY_FIELD_NUMBER: _ClassVar[int]
    EXISTS_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    WAIT_VISIBLE_FIELD_NUMBER: _ClassVar[int]
    WAIT_GONE_FIELD_NUMBER: _ClassVar[int]
    WAIT_APP_VISIBLE_FIELD_NUMBER: _ClassVar[int]
    WAIT_SCREEN_STABLE_FIELD_NUMBER: _ClassVar[int]
    TAP_FIELD_NUMBER: _ClassVar[int]
    LONG_TAP_FIELD_NUMBER: _ClassVar[int]
    SET_TEXT_FIELD_NUMBER: _ClassVar[int]
    TYPE_TEXT_FIELD_NUMBER: _ClassVar[int]
    CLEAR_TEXT_FIELD_NUMBER: _ClassVar[int]
    SWIPE_FIELD_NUMBER: _ClassVar[int]
    SCROLL_FIELD_NUMBER: _ClassVar[int]
    SCROLL_UNTIL_FIELD_NUMBER: _ClassVar[int]
    timeout_ms: int
    device_info: DeviceInfoQuery
    press_key: PressKey
    dump_hierarchy: DumpHierarchy
    exists: Exists
    count: Count
    snapshot: Snapshot
    wait_visible: WaitVisible
    wait_gone: WaitGone
    wait_app_visible: WaitAppVisible
    wait_screen_stable: WaitScreenStable
    tap: Tap
    long_tap: LongTap
    set_text: SetText
    type_text: TypeText
    clear_text: ClearText
    swipe: Swipe
    scroll: Scroll
    scroll_until: ScrollUntil
    def __init__(self, timeout_ms: _Optional[int] = ..., device_info: _Optional[_Union[DeviceInfoQuery, _Mapping]] = ..., press_key: _Optional[_Union[PressKey, _Mapping]] = ..., dump_hierarchy: _Optional[_Union[DumpHierarchy, _Mapping]] = ..., exists: _Optional[_Union[Exists, _Mapping]] = ..., count: _Optional[_Union[Count, _Mapping]] = ..., snapshot: _Optional[_Union[Snapshot, _Mapping]] = ..., wait_visible: _Optional[_Union[WaitVisible, _Mapping]] = ..., wait_gone: _Optional[_Union[WaitGone, _Mapping]] = ..., wait_app_visible: _Optional[_Union[WaitAppVisible, _Mapping]] = ..., wait_screen_stable: _Optional[_Union[WaitScreenStable, _Mapping]] = ..., tap: _Optional[_Union[Tap, _Mapping]] = ..., long_tap: _Optional[_Union[LongTap, _Mapping]] = ..., set_text: _Optional[_Union[SetText, _Mapping]] = ..., type_text: _Optional[_Union[TypeText, _Mapping]] = ..., clear_text: _Optional[_Union[ClearText, _Mapping]] = ..., swipe: _Optional[_Union[Swipe, _Mapping]] = ..., scroll: _Optional[_Union[Scroll, _Mapping]] = ..., scroll_until: _Optional[_Union[ScrollUntil, _Mapping]] = ...) -> None: ...

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

class Done(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Error(_message.Message):
    __slots__ = ("code", "detail", "message")
    CODE_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    code: ErrorCode
    detail: str
    message: str
    def __init__(self, code: _Optional[_Union[ErrorCode, str]] = ..., detail: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class CommandResult(_message.Message):
    __slots__ = ("duration_ms", "request_id", "session_generation", "done", "bool", "moved", "count", "text", "snapshot", "device_info", "error")
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_GENERATION_FIELD_NUMBER: _ClassVar[int]
    DONE_FIELD_NUMBER: _ClassVar[int]
    BOOL_FIELD_NUMBER: _ClassVar[int]
    MOVED_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    duration_ms: int
    request_id: int
    session_generation: int
    done: Done
    bool: bool
    moved: bool
    count: int
    text: str
    snapshot: ElementSnapshot
    device_info: DeviceInfo
    error: Error
    def __init__(self, duration_ms: _Optional[int] = ..., request_id: _Optional[int] = ..., session_generation: _Optional[int] = ..., done: _Optional[_Union[Done, _Mapping]] = ..., bool: _Optional[bool] = ..., moved: _Optional[bool] = ..., count: _Optional[int] = ..., text: _Optional[str] = ..., snapshot: _Optional[_Union[ElementSnapshot, _Mapping]] = ..., device_info: _Optional[_Union[DeviceInfo, _Mapping]] = ..., error: _Optional[_Union[Error, _Mapping]] = ...) -> None: ...
