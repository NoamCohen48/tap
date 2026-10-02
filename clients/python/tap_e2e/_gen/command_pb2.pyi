# ruff: noqa
from . import selector_pb2 as _selector_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
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

class SystemPanel(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SYSTEM_PANEL_UNSPECIFIED: _ClassVar[SystemPanel]
    SYSTEM_PANEL_NOTIFICATIONS: _ClassVar[SystemPanel]
    SYSTEM_PANEL_QUICK_SETTINGS: _ClassVar[SystemPanel]

class Orientation(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ORIENTATION_UNSPECIFIED: _ClassVar[Orientation]
    ORIENTATION_PORTRAIT: _ClassVar[Orientation]
    ORIENTATION_LANDSCAPE: _ClassVar[Orientation]

class DisplayRotation(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DISPLAY_ROTATION_UNSPECIFIED: _ClassVar[DisplayRotation]
    DISPLAY_ROTATION_NATURAL: _ClassVar[DisplayRotation]
    DISPLAY_ROTATION_LEFT: _ClassVar[DisplayRotation]
    DISPLAY_ROTATION_UPSIDE_DOWN: _ClassVar[DisplayRotation]
    DISPLAY_ROTATION_RIGHT: _ClassVar[DisplayRotation]

class PinchDirection(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PINCH_UNSPECIFIED: _ClassVar[PinchDirection]
    PINCH_OPEN: _ClassVar[PinchDirection]
    PINCH_CLOSE: _ClassVar[PinchDirection]

class PermissionChoice(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PERMISSION_CHOICE_UNSPECIFIED: _ClassVar[PermissionChoice]
    PERMISSION_ALLOW: _ClassVar[PermissionChoice]
    PERMISSION_ALLOW_FOREGROUND_ONLY: _ClassVar[PermissionChoice]
    PERMISSION_ALLOW_ONE_TIME: _ClassVar[PermissionChoice]
    PERMISSION_ALLOW_ALWAYS: _ClassVar[PermissionChoice]
    PERMISSION_ALLOW_SELECTED: _ClassVar[PermissionChoice]
    PERMISSION_ALLOW_ALL: _ClassVar[PermissionChoice]
    PERMISSION_DENY: _ClassVar[PermissionChoice]
    PERMISSION_DENY_AND_DONT_ASK_AGAIN: _ClassVar[PermissionChoice]
    PERMISSION_KEEP_FOREGROUND_ONLY: _ClassVar[PermissionChoice]
    PERMISSION_KEEP_ONE_TIME: _ClassVar[PermissionChoice]
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
SYSTEM_PANEL_UNSPECIFIED: SystemPanel
SYSTEM_PANEL_NOTIFICATIONS: SystemPanel
SYSTEM_PANEL_QUICK_SETTINGS: SystemPanel
ORIENTATION_UNSPECIFIED: Orientation
ORIENTATION_PORTRAIT: Orientation
ORIENTATION_LANDSCAPE: Orientation
DISPLAY_ROTATION_UNSPECIFIED: DisplayRotation
DISPLAY_ROTATION_NATURAL: DisplayRotation
DISPLAY_ROTATION_LEFT: DisplayRotation
DISPLAY_ROTATION_UPSIDE_DOWN: DisplayRotation
DISPLAY_ROTATION_RIGHT: DisplayRotation
PINCH_UNSPECIFIED: PinchDirection
PINCH_OPEN: PinchDirection
PINCH_CLOSE: PinchDirection
PERMISSION_CHOICE_UNSPECIFIED: PermissionChoice
PERMISSION_ALLOW: PermissionChoice
PERMISSION_ALLOW_FOREGROUND_ONLY: PermissionChoice
PERMISSION_ALLOW_ONE_TIME: PermissionChoice
PERMISSION_ALLOW_ALWAYS: PermissionChoice
PERMISSION_ALLOW_SELECTED: PermissionChoice
PERMISSION_ALLOW_ALL: PermissionChoice
PERMISSION_DENY: PermissionChoice
PERMISSION_DENY_AND_DONT_ASK_AGAIN: PermissionChoice
PERMISSION_KEEP_FOREGROUND_ONLY: PermissionChoice
PERMISSION_KEEP_ONE_TIME: PermissionChoice

class DeviceInfoQuery(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PressKey(_message.Message):
    __slots__ = ("key_code",)
    KEY_CODE_FIELD_NUMBER: _ClassVar[int]
    key_code: int
    def __init__(self, key_code: _Optional[int] = ...) -> None: ...

class OpenSystemPanel(_message.Message):
    __slots__ = ("panel",)
    PANEL_FIELD_NUMBER: _ClassVar[int]
    panel: SystemPanel
    def __init__(self, panel: _Optional[_Union[SystemPanel, str]] = ...) -> None: ...

class SetOrientation(_message.Message):
    __slots__ = ("orientation",)
    ORIENTATION_FIELD_NUMBER: _ClassVar[int]
    orientation: Orientation
    def __init__(self, orientation: _Optional[_Union[Orientation, str]] = ...) -> None: ...

class SetDisplayRotation(_message.Message):
    __slots__ = ("rotation",)
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    rotation: DisplayRotation
    def __init__(self, rotation: _Optional[_Union[DisplayRotation, str]] = ...) -> None: ...

class UnfreezeRotation(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DismissKeyguard(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

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
    __slots__ = ("selector", "exactly_one")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    EXACTLY_ONE_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    exactly_one: bool
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., exactly_one: _Optional[bool] = ...) -> None: ...

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
    __slots__ = ("text",)
    TEXT_FIELD_NUMBER: _ClassVar[int]
    text: str
    def __init__(self, text: _Optional[str] = ...) -> None: ...

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

class DoubleTap(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class Drag(_message.Message):
    __slots__ = ("selector", "target")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    TARGET_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    target: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., target: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class Pinch(_message.Message):
    __slots__ = ("selector", "direction", "percent")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    PERCENT_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    direction: PinchDirection
    percent: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., direction: _Optional[_Union[PinchDirection, str]] = ..., percent: _Optional[int] = ...) -> None: ...

class Fling(_message.Message):
    __slots__ = ("selector", "direction")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    direction: Direction
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., direction: _Optional[_Union[Direction, str]] = ...) -> None: ...

class WaitPermissionPrompt(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ChoosePermission(_message.Message):
    __slots__ = ("choice",)
    CHOICE_FIELD_NUMBER: _ClassVar[int]
    choice: PermissionChoice
    def __init__(self, choice: _Optional[_Union[PermissionChoice, str]] = ...) -> None: ...

class HideKeyboard(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PerformImeAction(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class SetClipboard(_message.Message):
    __slots__ = ("text",)
    TEXT_FIELD_NUMBER: _ClassVar[int]
    text: str
    def __init__(self, text: _Optional[str] = ...) -> None: ...

class GetClipboard(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class AwaitToast(_message.Message):
    __slots__ = ("text", "mode", "package_name")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    text: str
    mode: _selector_pb2.MatchMode
    package_name: str
    def __init__(self, text: _Optional[str] = ..., mode: _Optional[_Union[_selector_pb2.MatchMode, str]] = ..., package_name: _Optional[str] = ...) -> None: ...

class Command(_message.Message):
    __slots__ = ("timeout_ms", "device_info", "press_key", "dump_hierarchy", "exists", "count", "snapshot", "wait_visible", "wait_gone", "wait_app_visible", "wait_screen_stable", "tap", "long_tap", "set_text", "type_text", "clear_text", "swipe", "scroll", "open_system_panel", "set_orientation", "set_display_rotation", "unfreeze_rotation", "dismiss_keyguard", "double_tap", "drag", "pinch", "fling", "wait_permission_prompt", "choose_permission", "hide_keyboard", "perform_ime_action", "set_clipboard", "get_clipboard", "await_toast")
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
    OPEN_SYSTEM_PANEL_FIELD_NUMBER: _ClassVar[int]
    SET_ORIENTATION_FIELD_NUMBER: _ClassVar[int]
    SET_DISPLAY_ROTATION_FIELD_NUMBER: _ClassVar[int]
    UNFREEZE_ROTATION_FIELD_NUMBER: _ClassVar[int]
    DISMISS_KEYGUARD_FIELD_NUMBER: _ClassVar[int]
    DOUBLE_TAP_FIELD_NUMBER: _ClassVar[int]
    DRAG_FIELD_NUMBER: _ClassVar[int]
    PINCH_FIELD_NUMBER: _ClassVar[int]
    FLING_FIELD_NUMBER: _ClassVar[int]
    WAIT_PERMISSION_PROMPT_FIELD_NUMBER: _ClassVar[int]
    CHOOSE_PERMISSION_FIELD_NUMBER: _ClassVar[int]
    HIDE_KEYBOARD_FIELD_NUMBER: _ClassVar[int]
    PERFORM_IME_ACTION_FIELD_NUMBER: _ClassVar[int]
    SET_CLIPBOARD_FIELD_NUMBER: _ClassVar[int]
    GET_CLIPBOARD_FIELD_NUMBER: _ClassVar[int]
    AWAIT_TOAST_FIELD_NUMBER: _ClassVar[int]
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
    open_system_panel: OpenSystemPanel
    set_orientation: SetOrientation
    set_display_rotation: SetDisplayRotation
    unfreeze_rotation: UnfreezeRotation
    dismiss_keyguard: DismissKeyguard
    double_tap: DoubleTap
    drag: Drag
    pinch: Pinch
    fling: Fling
    wait_permission_prompt: WaitPermissionPrompt
    choose_permission: ChoosePermission
    hide_keyboard: HideKeyboard
    perform_ime_action: PerformImeAction
    set_clipboard: SetClipboard
    get_clipboard: GetClipboard
    await_toast: AwaitToast
    def __init__(self, timeout_ms: _Optional[int] = ..., device_info: _Optional[_Union[DeviceInfoQuery, _Mapping]] = ..., press_key: _Optional[_Union[PressKey, _Mapping]] = ..., dump_hierarchy: _Optional[_Union[DumpHierarchy, _Mapping]] = ..., exists: _Optional[_Union[Exists, _Mapping]] = ..., count: _Optional[_Union[Count, _Mapping]] = ..., snapshot: _Optional[_Union[Snapshot, _Mapping]] = ..., wait_visible: _Optional[_Union[WaitVisible, _Mapping]] = ..., wait_gone: _Optional[_Union[WaitGone, _Mapping]] = ..., wait_app_visible: _Optional[_Union[WaitAppVisible, _Mapping]] = ..., wait_screen_stable: _Optional[_Union[WaitScreenStable, _Mapping]] = ..., tap: _Optional[_Union[Tap, _Mapping]] = ..., long_tap: _Optional[_Union[LongTap, _Mapping]] = ..., set_text: _Optional[_Union[SetText, _Mapping]] = ..., type_text: _Optional[_Union[TypeText, _Mapping]] = ..., clear_text: _Optional[_Union[ClearText, _Mapping]] = ..., swipe: _Optional[_Union[Swipe, _Mapping]] = ..., scroll: _Optional[_Union[Scroll, _Mapping]] = ..., open_system_panel: _Optional[_Union[OpenSystemPanel, _Mapping]] = ..., set_orientation: _Optional[_Union[SetOrientation, _Mapping]] = ..., set_display_rotation: _Optional[_Union[SetDisplayRotation, _Mapping]] = ..., unfreeze_rotation: _Optional[_Union[UnfreezeRotation, _Mapping]] = ..., dismiss_keyguard: _Optional[_Union[DismissKeyguard, _Mapping]] = ..., double_tap: _Optional[_Union[DoubleTap, _Mapping]] = ..., drag: _Optional[_Union[Drag, _Mapping]] = ..., pinch: _Optional[_Union[Pinch, _Mapping]] = ..., fling: _Optional[_Union[Fling, _Mapping]] = ..., wait_permission_prompt: _Optional[_Union[WaitPermissionPrompt, _Mapping]] = ..., choose_permission: _Optional[_Union[ChoosePermission, _Mapping]] = ..., hide_keyboard: _Optional[_Union[HideKeyboard, _Mapping]] = ..., perform_ime_action: _Optional[_Union[PerformImeAction, _Mapping]] = ..., set_clipboard: _Optional[_Union[SetClipboard, _Mapping]] = ..., get_clipboard: _Optional[_Union[GetClipboard, _Mapping]] = ..., await_toast: _Optional[_Union[AwaitToast, _Mapping]] = ...) -> None: ...

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
    __slots__ = ("class_name", "package_name", "resource_name", "text", "content_description", "hint", "bounds", "checkable", "checked", "clickable", "enabled", "focusable", "focused", "long_clickable", "scrollable", "selected", "child_count", "showing_hint")
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
    SHOWING_HINT_FIELD_NUMBER: _ClassVar[int]
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
    showing_hint: bool
    def __init__(self, class_name: _Optional[str] = ..., package_name: _Optional[str] = ..., resource_name: _Optional[str] = ..., text: _Optional[str] = ..., content_description: _Optional[str] = ..., hint: _Optional[str] = ..., bounds: _Optional[_Union[Bounds, _Mapping]] = ..., checkable: _Optional[bool] = ..., checked: _Optional[bool] = ..., clickable: _Optional[bool] = ..., enabled: _Optional[bool] = ..., focusable: _Optional[bool] = ..., focused: _Optional[bool] = ..., long_clickable: _Optional[bool] = ..., scrollable: _Optional[bool] = ..., selected: _Optional[bool] = ..., child_count: _Optional[int] = ..., showing_hint: _Optional[bool] = ...) -> None: ...

class DeviceInfo(_message.Message):
    __slots__ = ("api_level", "manufacturer", "model", "product", "display_width", "display_height", "display_rotation", "current_package", "screen_on", "keyguard_locked", "keyguard_secure", "keyboard_shown", "auto_rotate", "animations_enabled", "dark_mode", "font_scale", "density_dpi", "airplane_mode", "wifi_enabled", "mobile_data_enabled")
    API_LEVEL_FIELD_NUMBER: _ClassVar[int]
    MANUFACTURER_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    PRODUCT_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_WIDTH_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_ROTATION_FIELD_NUMBER: _ClassVar[int]
    CURRENT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    SCREEN_ON_FIELD_NUMBER: _ClassVar[int]
    KEYGUARD_LOCKED_FIELD_NUMBER: _ClassVar[int]
    KEYGUARD_SECURE_FIELD_NUMBER: _ClassVar[int]
    KEYBOARD_SHOWN_FIELD_NUMBER: _ClassVar[int]
    AUTO_ROTATE_FIELD_NUMBER: _ClassVar[int]
    ANIMATIONS_ENABLED_FIELD_NUMBER: _ClassVar[int]
    DARK_MODE_FIELD_NUMBER: _ClassVar[int]
    FONT_SCALE_FIELD_NUMBER: _ClassVar[int]
    DENSITY_DPI_FIELD_NUMBER: _ClassVar[int]
    AIRPLANE_MODE_FIELD_NUMBER: _ClassVar[int]
    WIFI_ENABLED_FIELD_NUMBER: _ClassVar[int]
    MOBILE_DATA_ENABLED_FIELD_NUMBER: _ClassVar[int]
    api_level: int
    manufacturer: str
    model: str
    product: str
    display_width: int
    display_height: int
    display_rotation: int
    current_package: str
    screen_on: bool
    keyguard_locked: bool
    keyguard_secure: bool
    keyboard_shown: bool
    auto_rotate: bool
    animations_enabled: bool
    dark_mode: bool
    font_scale: float
    density_dpi: int
    airplane_mode: bool
    wifi_enabled: bool
    mobile_data_enabled: bool
    def __init__(self, api_level: _Optional[int] = ..., manufacturer: _Optional[str] = ..., model: _Optional[str] = ..., product: _Optional[str] = ..., display_width: _Optional[int] = ..., display_height: _Optional[int] = ..., display_rotation: _Optional[int] = ..., current_package: _Optional[str] = ..., screen_on: _Optional[bool] = ..., keyguard_locked: _Optional[bool] = ..., keyguard_secure: _Optional[bool] = ..., keyboard_shown: _Optional[bool] = ..., auto_rotate: _Optional[bool] = ..., animations_enabled: _Optional[bool] = ..., dark_mode: _Optional[bool] = ..., font_scale: _Optional[float] = ..., density_dpi: _Optional[int] = ..., airplane_mode: _Optional[bool] = ..., wifi_enabled: _Optional[bool] = ..., mobile_data_enabled: _Optional[bool] = ...) -> None: ...

class Toast(_message.Message):
    __slots__ = ("text", "package_name")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    text: str
    package_name: str
    def __init__(self, text: _Optional[str] = ..., package_name: _Optional[str] = ...) -> None: ...

class PermissionPrompt(_message.Message):
    __slots__ = ("package_name", "choices")
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    CHOICES_FIELD_NUMBER: _ClassVar[int]
    package_name: str
    choices: _containers.RepeatedScalarFieldContainer[PermissionChoice]
    def __init__(self, package_name: _Optional[str] = ..., choices: _Optional[_Iterable[_Union[PermissionChoice, str]]] = ...) -> None: ...

class Done(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Error(_message.Message):
    __slots__ = ("code", "detail", "message", "match_count")
    CODE_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    MATCH_COUNT_FIELD_NUMBER: _ClassVar[int]
    code: ErrorCode
    detail: str
    message: str
    match_count: int
    def __init__(self, code: _Optional[_Union[ErrorCode, str]] = ..., detail: _Optional[str] = ..., message: _Optional[str] = ..., match_count: _Optional[int] = ...) -> None: ...

class CommandResult(_message.Message):
    __slots__ = ("duration_ms", "request_id", "session_generation", "done", "bool", "count", "text", "snapshot", "device_info", "error", "permission_prompt", "toast")
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_GENERATION_FIELD_NUMBER: _ClassVar[int]
    DONE_FIELD_NUMBER: _ClassVar[int]
    BOOL_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_PROMPT_FIELD_NUMBER: _ClassVar[int]
    TOAST_FIELD_NUMBER: _ClassVar[int]
    duration_ms: int
    request_id: int
    session_generation: int
    done: Done
    bool: bool
    count: int
    text: str
    snapshot: ElementSnapshot
    device_info: DeviceInfo
    error: Error
    permission_prompt: PermissionPrompt
    toast: Toast
    def __init__(self, duration_ms: _Optional[int] = ..., request_id: _Optional[int] = ..., session_generation: _Optional[int] = ..., done: _Optional[_Union[Done, _Mapping]] = ..., bool: _Optional[bool] = ..., count: _Optional[int] = ..., text: _Optional[str] = ..., snapshot: _Optional[_Union[ElementSnapshot, _Mapping]] = ..., device_info: _Optional[_Union[DeviceInfo, _Mapping]] = ..., error: _Optional[_Union[Error, _Mapping]] = ..., permission_prompt: _Optional[_Union[PermissionPrompt, _Mapping]] = ..., toast: _Optional[_Union[Toast, _Mapping]] = ...) -> None: ...
