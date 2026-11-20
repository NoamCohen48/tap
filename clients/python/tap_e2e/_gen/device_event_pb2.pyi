# ruff: noqa
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class DeviceEventType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DEVICE_EVENT_TYPE_UNSPECIFIED: _ClassVar[DeviceEventType]
    DEVICE_EVENT_TOAST: _ClassVar[DeviceEventType]
    DEVICE_EVENT_WINDOW_CHANGED: _ClassVar[DeviceEventType]
    DEVICE_EVENT_CONTENT_CHANGED: _ClassVar[DeviceEventType]
    DEVICE_EVENT_VIEW_CLICKED: _ClassVar[DeviceEventType]
    DEVICE_EVENT_VIEW_SCROLLED: _ClassVar[DeviceEventType]
    DEVICE_EVENT_TEXT_CHANGED: _ClassVar[DeviceEventType]

class WindowChange(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    WINDOW_CHANGE_UNSPECIFIED: _ClassVar[WindowChange]
    WINDOW_CHANGE_STATE: _ClassVar[WindowChange]
    WINDOW_CHANGE_ADDED: _ClassVar[WindowChange]
    WINDOW_CHANGE_REMOVED: _ClassVar[WindowChange]
    WINDOW_CHANGE_TITLE: _ClassVar[WindowChange]
    WINDOW_CHANGE_BOUNDS: _ClassVar[WindowChange]
    WINDOW_CHANGE_LAYER: _ClassVar[WindowChange]
    WINDOW_CHANGE_ACTIVE: _ClassVar[WindowChange]
    WINDOW_CHANGE_FOCUSED: _ClassVar[WindowChange]
    WINDOW_CHANGE_ACCESSIBILITY_FOCUSED: _ClassVar[WindowChange]
    WINDOW_CHANGE_PARENT: _ClassVar[WindowChange]
    WINDOW_CHANGE_CHILDREN: _ClassVar[WindowChange]
    WINDOW_CHANGE_PIP: _ClassVar[WindowChange]

class ContentChange(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CONTENT_CHANGE_UNSPECIFIED: _ClassVar[ContentChange]
    CONTENT_CHANGE_SUBTREE: _ClassVar[ContentChange]
    CONTENT_CHANGE_TEXT: _ClassVar[ContentChange]
    CONTENT_CHANGE_CONTENT_DESCRIPTION: _ClassVar[ContentChange]
    CONTENT_CHANGE_STATE_DESCRIPTION: _ClassVar[ContentChange]
    CONTENT_CHANGE_PANE_TITLE: _ClassVar[ContentChange]
    CONTENT_CHANGE_PANE_APPEARED: _ClassVar[ContentChange]
    CONTENT_CHANGE_PANE_DISAPPEARED: _ClassVar[ContentChange]
    CONTENT_CHANGE_DRAG_STARTED: _ClassVar[ContentChange]
    CONTENT_CHANGE_DRAG_DROPPED: _ClassVar[ContentChange]
    CONTENT_CHANGE_DRAG_CANCELLED: _ClassVar[ContentChange]
    CONTENT_CHANGE_CONTENT_INVALID: _ClassVar[ContentChange]
    CONTENT_CHANGE_ERROR: _ClassVar[ContentChange]
    CONTENT_CHANGE_ENABLED: _ClassVar[ContentChange]
DEVICE_EVENT_TYPE_UNSPECIFIED: DeviceEventType
DEVICE_EVENT_TOAST: DeviceEventType
DEVICE_EVENT_WINDOW_CHANGED: DeviceEventType
DEVICE_EVENT_CONTENT_CHANGED: DeviceEventType
DEVICE_EVENT_VIEW_CLICKED: DeviceEventType
DEVICE_EVENT_VIEW_SCROLLED: DeviceEventType
DEVICE_EVENT_TEXT_CHANGED: DeviceEventType
WINDOW_CHANGE_UNSPECIFIED: WindowChange
WINDOW_CHANGE_STATE: WindowChange
WINDOW_CHANGE_ADDED: WindowChange
WINDOW_CHANGE_REMOVED: WindowChange
WINDOW_CHANGE_TITLE: WindowChange
WINDOW_CHANGE_BOUNDS: WindowChange
WINDOW_CHANGE_LAYER: WindowChange
WINDOW_CHANGE_ACTIVE: WindowChange
WINDOW_CHANGE_FOCUSED: WindowChange
WINDOW_CHANGE_ACCESSIBILITY_FOCUSED: WindowChange
WINDOW_CHANGE_PARENT: WindowChange
WINDOW_CHANGE_CHILDREN: WindowChange
WINDOW_CHANGE_PIP: WindowChange
CONTENT_CHANGE_UNSPECIFIED: ContentChange
CONTENT_CHANGE_SUBTREE: ContentChange
CONTENT_CHANGE_TEXT: ContentChange
CONTENT_CHANGE_CONTENT_DESCRIPTION: ContentChange
CONTENT_CHANGE_STATE_DESCRIPTION: ContentChange
CONTENT_CHANGE_PANE_TITLE: ContentChange
CONTENT_CHANGE_PANE_APPEARED: ContentChange
CONTENT_CHANGE_PANE_DISAPPEARED: ContentChange
CONTENT_CHANGE_DRAG_STARTED: ContentChange
CONTENT_CHANGE_DRAG_DROPPED: ContentChange
CONTENT_CHANGE_DRAG_CANCELLED: ContentChange
CONTENT_CHANGE_CONTENT_INVALID: ContentChange
CONTENT_CHANGE_ERROR: ContentChange
CONTENT_CHANGE_ENABLED: ContentChange

class DeviceEvent(_message.Message):
    __slots__ = ("sequence", "uptime_ms", "package_name", "toast_shown", "window_changed", "content_changed", "view_clicked", "view_scrolled", "text_changed")
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    UPTIME_MS_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    TOAST_SHOWN_FIELD_NUMBER: _ClassVar[int]
    WINDOW_CHANGED_FIELD_NUMBER: _ClassVar[int]
    CONTENT_CHANGED_FIELD_NUMBER: _ClassVar[int]
    VIEW_CLICKED_FIELD_NUMBER: _ClassVar[int]
    VIEW_SCROLLED_FIELD_NUMBER: _ClassVar[int]
    TEXT_CHANGED_FIELD_NUMBER: _ClassVar[int]
    sequence: int
    uptime_ms: int
    package_name: str
    toast_shown: ToastShown
    window_changed: WindowChanged
    content_changed: ContentChanged
    view_clicked: ViewClicked
    view_scrolled: ViewScrolled
    text_changed: TextChanged
    def __init__(self, sequence: _Optional[int] = ..., uptime_ms: _Optional[int] = ..., package_name: _Optional[str] = ..., toast_shown: _Optional[_Union[ToastShown, _Mapping]] = ..., window_changed: _Optional[_Union[WindowChanged, _Mapping]] = ..., content_changed: _Optional[_Union[ContentChanged, _Mapping]] = ..., view_clicked: _Optional[_Union[ViewClicked, _Mapping]] = ..., view_scrolled: _Optional[_Union[ViewScrolled, _Mapping]] = ..., text_changed: _Optional[_Union[TextChanged, _Mapping]] = ...) -> None: ...

class ToastShown(_message.Message):
    __slots__ = ("text",)
    TEXT_FIELD_NUMBER: _ClassVar[int]
    text: str
    def __init__(self, text: _Optional[str] = ...) -> None: ...

class WindowChanged(_message.Message):
    __slots__ = ("class_name", "title", "window_id", "changes")
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    WINDOW_ID_FIELD_NUMBER: _ClassVar[int]
    CHANGES_FIELD_NUMBER: _ClassVar[int]
    class_name: str
    title: str
    window_id: int
    changes: _containers.RepeatedScalarFieldContainer[WindowChange]
    def __init__(self, class_name: _Optional[str] = ..., title: _Optional[str] = ..., window_id: _Optional[int] = ..., changes: _Optional[_Iterable[_Union[WindowChange, str]]] = ...) -> None: ...

class ContentChanged(_message.Message):
    __slots__ = ("class_name", "window_id", "changes")
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    WINDOW_ID_FIELD_NUMBER: _ClassVar[int]
    CHANGES_FIELD_NUMBER: _ClassVar[int]
    class_name: str
    window_id: int
    changes: _containers.RepeatedScalarFieldContainer[ContentChange]
    def __init__(self, class_name: _Optional[str] = ..., window_id: _Optional[int] = ..., changes: _Optional[_Iterable[_Union[ContentChange, str]]] = ...) -> None: ...

class ViewClicked(_message.Message):
    __slots__ = ("class_name", "label", "window_id")
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    LABEL_FIELD_NUMBER: _ClassVar[int]
    WINDOW_ID_FIELD_NUMBER: _ClassVar[int]
    class_name: str
    label: str
    window_id: int
    def __init__(self, class_name: _Optional[str] = ..., label: _Optional[str] = ..., window_id: _Optional[int] = ...) -> None: ...

class ViewScrolled(_message.Message):
    __slots__ = ("class_name", "window_id", "scroll_x", "scroll_y", "max_scroll_x", "max_scroll_y", "delta_x", "delta_y", "from_index", "to_index", "item_count")
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    WINDOW_ID_FIELD_NUMBER: _ClassVar[int]
    SCROLL_X_FIELD_NUMBER: _ClassVar[int]
    SCROLL_Y_FIELD_NUMBER: _ClassVar[int]
    MAX_SCROLL_X_FIELD_NUMBER: _ClassVar[int]
    MAX_SCROLL_Y_FIELD_NUMBER: _ClassVar[int]
    DELTA_X_FIELD_NUMBER: _ClassVar[int]
    DELTA_Y_FIELD_NUMBER: _ClassVar[int]
    FROM_INDEX_FIELD_NUMBER: _ClassVar[int]
    TO_INDEX_FIELD_NUMBER: _ClassVar[int]
    ITEM_COUNT_FIELD_NUMBER: _ClassVar[int]
    class_name: str
    window_id: int
    scroll_x: int
    scroll_y: int
    max_scroll_x: int
    max_scroll_y: int
    delta_x: int
    delta_y: int
    from_index: int
    to_index: int
    item_count: int
    def __init__(self, class_name: _Optional[str] = ..., window_id: _Optional[int] = ..., scroll_x: _Optional[int] = ..., scroll_y: _Optional[int] = ..., max_scroll_x: _Optional[int] = ..., max_scroll_y: _Optional[int] = ..., delta_x: _Optional[int] = ..., delta_y: _Optional[int] = ..., from_index: _Optional[int] = ..., to_index: _Optional[int] = ..., item_count: _Optional[int] = ...) -> None: ...

class TextChanged(_message.Message):
    __slots__ = ("class_name", "text", "before_text", "from_index", "added_count", "removed_count", "password", "window_id")
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    BEFORE_TEXT_FIELD_NUMBER: _ClassVar[int]
    FROM_INDEX_FIELD_NUMBER: _ClassVar[int]
    ADDED_COUNT_FIELD_NUMBER: _ClassVar[int]
    REMOVED_COUNT_FIELD_NUMBER: _ClassVar[int]
    PASSWORD_FIELD_NUMBER: _ClassVar[int]
    WINDOW_ID_FIELD_NUMBER: _ClassVar[int]
    class_name: str
    text: str
    before_text: str
    from_index: int
    added_count: int
    removed_count: int
    password: bool
    window_id: int
    def __init__(self, class_name: _Optional[str] = ..., text: _Optional[str] = ..., before_text: _Optional[str] = ..., from_index: _Optional[int] = ..., added_count: _Optional[int] = ..., removed_count: _Optional[int] = ..., password: _Optional[bool] = ..., window_id: _Optional[int] = ...) -> None: ...

class DeviceEventBatch(_message.Message):
    __slots__ = ("events", "dropped")
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    DROPPED_FIELD_NUMBER: _ClassVar[int]
    events: _containers.RepeatedCompositeFieldContainer[DeviceEvent]
    dropped: int
    def __init__(self, events: _Optional[_Iterable[_Union[DeviceEvent, _Mapping]]] = ..., dropped: _Optional[int] = ...) -> None: ...

class WatchDeviceEventsRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "types", "package_name")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    TYPES_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    types: _containers.RepeatedScalarFieldContainer[DeviceEventType]
    package_name: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., types: _Optional[_Iterable[_Union[DeviceEventType, str]]] = ..., package_name: _Optional[str] = ...) -> None: ...

class WatchDeviceEventsResponse(_message.Message):
    __slots__ = ("ready", "batch")
    READY_FIELD_NUMBER: _ClassVar[int]
    BATCH_FIELD_NUMBER: _ClassVar[int]
    ready: bool
    batch: DeviceEventBatch
    def __init__(self, ready: _Optional[bool] = ..., batch: _Optional[_Union[DeviceEventBatch, _Mapping]] = ...) -> None: ...
