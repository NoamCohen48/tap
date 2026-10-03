# ruff: noqa
from . import client_connection_pb2 as _client_connection_pb2
from . import event_log_pb2 as _event_log_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class WatchRequest(_message.Message):
    __slots__ = ("after_seq",)
    AFTER_SEQ_FIELD_NUMBER: _ClassVar[int]
    after_seq: int
    def __init__(self, after_seq: _Optional[int] = ...) -> None: ...

class WatchResponse(_message.Message):
    __slots__ = ("connections", "activities", "dropped")
    CONNECTIONS_FIELD_NUMBER: _ClassVar[int]
    ACTIVITIES_FIELD_NUMBER: _ClassVar[int]
    DROPPED_FIELD_NUMBER: _ClassVar[int]
    connections: _containers.RepeatedCompositeFieldContainer[_client_connection_pb2.ConnectionEntry]
    activities: _containers.RepeatedCompositeFieldContainer[Activity]
    dropped: int
    def __init__(self, connections: _Optional[_Iterable[_Union[_client_connection_pb2.ConnectionEntry, _Mapping]]] = ..., activities: _Optional[_Iterable[_Union[Activity, _Mapping]]] = ..., dropped: _Optional[int] = ...) -> None: ...

class Activity(_message.Message):
    __slots__ = ("seq", "at_epoch_ms", "client_connection_id", "connection_opened", "connection_closed", "device_attached", "device_detached", "event")
    SEQ_FIELD_NUMBER: _ClassVar[int]
    AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_OPENED_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_CLOSED_FIELD_NUMBER: _ClassVar[int]
    DEVICE_ATTACHED_FIELD_NUMBER: _ClassVar[int]
    DEVICE_DETACHED_FIELD_NUMBER: _ClassVar[int]
    EVENT_FIELD_NUMBER: _ClassVar[int]
    seq: int
    at_epoch_ms: int
    client_connection_id: str
    connection_opened: ConnectionOpened
    connection_closed: ConnectionClosed
    device_attached: DeviceAttached
    device_detached: DeviceDetached
    event: _event_log_pb2.LoggedEvent
    def __init__(self, seq: _Optional[int] = ..., at_epoch_ms: _Optional[int] = ..., client_connection_id: _Optional[str] = ..., connection_opened: _Optional[_Union[ConnectionOpened, _Mapping]] = ..., connection_closed: _Optional[_Union[ConnectionClosed, _Mapping]] = ..., device_attached: _Optional[_Union[DeviceAttached, _Mapping]] = ..., device_detached: _Optional[_Union[DeviceDetached, _Mapping]] = ..., event: _Optional[_Union[_event_log_pb2.LoggedEvent, _Mapping]] = ...) -> None: ...

class ConnectionOpened(_message.Message):
    __slots__ = ("name", "held")
    NAME_FIELD_NUMBER: _ClassVar[int]
    HELD_FIELD_NUMBER: _ClassVar[int]
    name: str
    held: bool
    def __init__(self, name: _Optional[str] = ..., held: _Optional[bool] = ...) -> None: ...

class ConnectionClosed(_message.Message):
    __slots__ = ("reason",)
    REASON_FIELD_NUMBER: _ClassVar[int]
    reason: str
    def __init__(self, reason: _Optional[str] = ...) -> None: ...

class DeviceAttached(_message.Message):
    __slots__ = ("attached_device_id", "serial", "generation")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    generation: int
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., generation: _Optional[int] = ...) -> None: ...

class DeviceDetached(_message.Message):
    __slots__ = ("attached_device_id", "serial", "reason")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    reason: str
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class WatchVideoRequest(_message.Message):
    __slots__ = ("serial",)
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    serial: str
    def __init__(self, serial: _Optional[str] = ...) -> None: ...

class VideoHeader(_message.Message):
    __slots__ = ("stream_id", "clock_id", "width", "height", "configuration")
    STREAM_ID_FIELD_NUMBER: _ClassVar[int]
    CLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    CONFIGURATION_FIELD_NUMBER: _ClassVar[int]
    stream_id: str
    clock_id: str
    width: int
    height: int
    configuration: bytes
    def __init__(self, stream_id: _Optional[str] = ..., clock_id: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., configuration: _Optional[bytes] = ...) -> None: ...

class VideoFrame(_message.Message):
    __slots__ = ("seq", "pts_us", "key_frame", "data", "received_monotonic_ns", "received_epoch_ms")
    SEQ_FIELD_NUMBER: _ClassVar[int]
    PTS_US_FIELD_NUMBER: _ClassVar[int]
    KEY_FRAME_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    seq: int
    pts_us: int
    key_frame: bool
    data: bytes
    received_monotonic_ns: int
    received_epoch_ms: int
    def __init__(self, seq: _Optional[int] = ..., pts_us: _Optional[int] = ..., key_frame: _Optional[bool] = ..., data: _Optional[bytes] = ..., received_monotonic_ns: _Optional[int] = ..., received_epoch_ms: _Optional[int] = ...) -> None: ...

class WatchVideoResponse(_message.Message):
    __slots__ = ("header", "frame")
    HEADER_FIELD_NUMBER: _ClassVar[int]
    FRAME_FIELD_NUMBER: _ClassVar[int]
    header: VideoHeader
    frame: VideoFrame
    def __init__(self, header: _Optional[_Union[VideoHeader, _Mapping]] = ..., frame: _Optional[_Union[VideoFrame, _Mapping]] = ...) -> None: ...
