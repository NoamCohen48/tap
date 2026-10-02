# ruff: noqa
from . import command_pb2 as _command_pb2
from . import selector_pb2 as _selector_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class DeviceState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DEVICE_STATE_UNSPECIFIED: _ClassVar[DeviceState]
    DEVICE_FREE: _ClassVar[DeviceState]
    DEVICE_LEASED: _ClassVar[DeviceState]
    DEVICE_QUARANTINED: _ClassVar[DeviceState]
    DEVICE_OFFLINE: _ClassVar[DeviceState]
    DEVICE_UNAUTHORIZED: _ClassVar[DeviceState]

class SelectorKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SELECTOR_KIND_UNSPECIFIED: _ClassVar[SelectorKind]
    SELECTOR_KIND_PLAIN: _ClassVar[SelectorKind]
    SELECTOR_KIND_COMBINED: _ClassVar[SelectorKind]
    SELECTOR_KIND_ANCESTOR: _ClassVar[SelectorKind]
    SELECTOR_KIND_BY_INDEX: _ClassVar[SelectorKind]

class NodeChange(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    NODE_CHANGE_UNSPECIFIED: _ClassVar[NodeChange]
    NODE_ADDED: _ClassVar[NodeChange]
    NODE_UNCHANGED: _ClassVar[NodeChange]
    NODE_REMOVED: _ClassVar[NodeChange]
DEVICE_STATE_UNSPECIFIED: DeviceState
DEVICE_FREE: DeviceState
DEVICE_LEASED: DeviceState
DEVICE_QUARANTINED: DeviceState
DEVICE_OFFLINE: DeviceState
DEVICE_UNAUTHORIZED: DeviceState
SELECTOR_KIND_UNSPECIFIED: SelectorKind
SELECTOR_KIND_PLAIN: SelectorKind
SELECTOR_KIND_COMBINED: SelectorKind
SELECTOR_KIND_ANCESTOR: SelectorKind
SELECTOR_KIND_BY_INDEX: SelectorKind
NODE_CHANGE_UNSPECIFIED: NodeChange
NODE_ADDED: NodeChange
NODE_UNCHANGED: NodeChange
NODE_REMOVED: NodeChange

class DeviceEntry(_message.Message):
    __slots__ = ("serial", "state", "client_connection_id", "quarantine_reason")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    QUARANTINE_REASON_FIELD_NUMBER: _ClassVar[int]
    serial: str
    state: DeviceState
    client_connection_id: str
    quarantine_reason: str
    def __init__(self, serial: _Optional[str] = ..., state: _Optional[_Union[DeviceState, str]] = ..., client_connection_id: _Optional[str] = ..., quarantine_reason: _Optional[str] = ...) -> None: ...

class ListDevicesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListDevicesResponse(_message.Message):
    __slots__ = ("devices",)
    DEVICES_FIELD_NUMBER: _ClassVar[int]
    devices: _containers.RepeatedCompositeFieldContainer[DeviceEntry]
    def __init__(self, devices: _Optional[_Iterable[_Union[DeviceEntry, _Mapping]]] = ...) -> None: ...

class AttachRequest(_message.Message):
    __slots__ = ("client_connection_id", "serial", "skip_driver_install", "default_timeout_ms", "lease_timeout_ms")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    SKIP_DRIVER_INSTALL_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    LEASE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    serial: str
    skip_driver_install: bool
    default_timeout_ms: int
    lease_timeout_ms: int
    def __init__(self, client_connection_id: _Optional[str] = ..., serial: _Optional[str] = ..., skip_driver_install: _Optional[bool] = ..., default_timeout_ms: _Optional[int] = ..., lease_timeout_ms: _Optional[int] = ...) -> None: ...

class AttachResponse(_message.Message):
    __slots__ = ("attached_device_id", "serial", "generation", "device_info")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    generation: int
    device_info: _command_pb2.DeviceInfo
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., generation: _Optional[int] = ..., device_info: _Optional[_Union[_command_pb2.DeviceInfo, _Mapping]] = ...) -> None: ...

class DetachRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ...) -> None: ...

class DetachResponse(_message.Message):
    __slots__ = ("clean", "detail")
    CLEAN_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    clean: bool
    detail: str
    def __init__(self, clean: _Optional[bool] = ..., detail: _Optional[str] = ...) -> None: ...

class ExecuteRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "command")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    command: _command_pb2.Command
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ...) -> None: ...

class ExecuteResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: _command_pb2.CommandResult
    def __init__(self, result: _Optional[_Union[_command_pb2.CommandResult, _Mapping]] = ...) -> None: ...

class ScreenshotRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "timeout_ms")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    timeout_ms: int
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class ScreenshotResponse(_message.Message):
    __slots__ = ("png", "sha256", "width", "height")
    PNG_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    png: bytes
    sha256: str
    width: int
    height: int
    def __init__(self, png: _Optional[bytes] = ..., sha256: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ...) -> None: ...

class StartRecordingRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "video", "audio_source", "max_seconds")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    VIDEO_FIELD_NUMBER: _ClassVar[int]
    AUDIO_SOURCE_FIELD_NUMBER: _ClassVar[int]
    MAX_SECONDS_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    video: bool
    audio_source: str
    max_seconds: int
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., video: _Optional[bool] = ..., audio_source: _Optional[str] = ..., max_seconds: _Optional[int] = ...) -> None: ...

class StartRecordingResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StopRecordingRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ...) -> None: ...

class StopRecordingResponse(_message.Message):
    __slots__ = ("data", "format", "sha256")
    DATA_FIELD_NUMBER: _ClassVar[int]
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    data: bytes
    format: str
    sha256: str
    def __init__(self, data: _Optional[bytes] = ..., format: _Optional[str] = ..., sha256: _Optional[str] = ...) -> None: ...

class DriverLogRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ...) -> None: ...

class DriverLogResponse(_message.Message):
    __slots__ = ("lines",)
    LINES_FIELD_NUMBER: _ClassVar[int]
    lines: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, lines: _Optional[_Iterable[str]] = ...) -> None: ...

class ScreenSnapshotRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "timeout_ms", "selector_candidates")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_CANDIDATES_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    timeout_ms: int
    selector_candidates: bool
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., timeout_ms: _Optional[int] = ..., selector_candidates: _Optional[bool] = ...) -> None: ...

class SelectorCandidate(_message.Message):
    __slots__ = ("selector", "kind")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    kind: SelectorKind
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., kind: _Optional[_Union[SelectorKind, str]] = ...) -> None: ...

class ScreenNode(_message.Message):
    __slots__ = ("ref", "depth", "window_package", "class_name", "resource_name", "text", "content_description", "hint", "bounds", "flags", "password", "interactive", "selector", "by_index", "change", "candidates")
    REF_FIELD_NUMBER: _ClassVar[int]
    DEPTH_FIELD_NUMBER: _ClassVar[int]
    WINDOW_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    CLASS_NAME_FIELD_NUMBER: _ClassVar[int]
    RESOURCE_NAME_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    CONTENT_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    HINT_FIELD_NUMBER: _ClassVar[int]
    BOUNDS_FIELD_NUMBER: _ClassVar[int]
    FLAGS_FIELD_NUMBER: _ClassVar[int]
    PASSWORD_FIELD_NUMBER: _ClassVar[int]
    INTERACTIVE_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    BY_INDEX_FIELD_NUMBER: _ClassVar[int]
    CHANGE_FIELD_NUMBER: _ClassVar[int]
    CANDIDATES_FIELD_NUMBER: _ClassVar[int]
    ref: str
    depth: int
    window_package: str
    class_name: str
    resource_name: str
    text: str
    content_description: str
    hint: str
    bounds: _command_pb2.Bounds
    flags: _containers.RepeatedScalarFieldContainer[_selector_pb2.NodeFlag]
    password: bool
    interactive: bool
    selector: _selector_pb2.Selector
    by_index: bool
    change: NodeChange
    candidates: _containers.RepeatedCompositeFieldContainer[SelectorCandidate]
    def __init__(self, ref: _Optional[str] = ..., depth: _Optional[int] = ..., window_package: _Optional[str] = ..., class_name: _Optional[str] = ..., resource_name: _Optional[str] = ..., text: _Optional[str] = ..., content_description: _Optional[str] = ..., hint: _Optional[str] = ..., bounds: _Optional[_Union[_command_pb2.Bounds, _Mapping]] = ..., flags: _Optional[_Iterable[_Union[_selector_pb2.NodeFlag, str]]] = ..., password: _Optional[bool] = ..., interactive: _Optional[bool] = ..., selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., by_index: _Optional[bool] = ..., change: _Optional[_Union[NodeChange, str]] = ..., candidates: _Optional[_Iterable[_Union[SelectorCandidate, _Mapping]]] = ...) -> None: ...

class ScreenSnapshotResponse(_message.Message):
    __slots__ = ("snapshot_id", "nodes", "removed", "rotation")
    SNAPSHOT_ID_FIELD_NUMBER: _ClassVar[int]
    NODES_FIELD_NUMBER: _ClassVar[int]
    REMOVED_FIELD_NUMBER: _ClassVar[int]
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    snapshot_id: int
    nodes: _containers.RepeatedCompositeFieldContainer[ScreenNode]
    removed: _containers.RepeatedCompositeFieldContainer[ScreenNode]
    rotation: int
    def __init__(self, snapshot_id: _Optional[int] = ..., nodes: _Optional[_Iterable[_Union[ScreenNode, _Mapping]]] = ..., removed: _Optional[_Iterable[_Union[ScreenNode, _Mapping]]] = ..., rotation: _Optional[int] = ...) -> None: ...

class ResolveRefRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "ref")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    REF_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    ref: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., ref: _Optional[str] = ...) -> None: ...

class ResolveRefResponse(_message.Message):
    __slots__ = ("selector", "by_index", "snapshot_id")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    BY_INDEX_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_ID_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    by_index: bool
    snapshot_id: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., by_index: _Optional[bool] = ..., snapshot_id: _Optional[int] = ...) -> None: ...

class SetAnimationsRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "enabled")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    enabled: bool
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., enabled: _Optional[bool] = ...) -> None: ...

class SetAnimationsResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetDarkModeRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "enabled")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    enabled: bool
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., enabled: _Optional[bool] = ...) -> None: ...

class SetDarkModeResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetFontScaleRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "scale")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SCALE_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    scale: float
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., scale: _Optional[float] = ...) -> None: ...

class SetFontScaleResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetDensityRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "dpi")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    DPI_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    dpi: int
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., dpi: _Optional[int] = ...) -> None: ...

class SetDensityResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetNetworkRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "airplane_mode", "wifi", "mobile_data")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    AIRPLANE_MODE_FIELD_NUMBER: _ClassVar[int]
    WIFI_FIELD_NUMBER: _ClassVar[int]
    MOBILE_DATA_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    airplane_mode: bool
    wifi: bool
    mobile_data: bool
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., airplane_mode: _Optional[bool] = ..., wifi: _Optional[bool] = ..., mobile_data: _Optional[bool] = ...) -> None: ...

class SetNetworkResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
