# ruff: noqa
from tap_e2e.proto import client_connection_pb2 as _client_connection_pb2
from tap_e2e.proto import device_pb2 as _device_pb2
from tap_e2e.proto import video_pb2 as _video_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ListDevicesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListDevicesResponse(_message.Message):
    __slots__ = ("devices", "connections")
    DEVICES_FIELD_NUMBER: _ClassVar[int]
    CONNECTIONS_FIELD_NUMBER: _ClassVar[int]
    devices: _containers.RepeatedCompositeFieldContainer[_device_pb2.DeviceEntry]
    connections: _containers.RepeatedCompositeFieldContainer[_client_connection_pb2.ConnectionEntry]
    def __init__(self, devices: _Optional[_Iterable[_Union[_device_pb2.DeviceEntry, _Mapping]]] = ..., connections: _Optional[_Iterable[_Union[_client_connection_pb2.ConnectionEntry, _Mapping]]] = ...) -> None: ...

class WatchEventsRequest(_message.Message):
    __slots__ = ("observed_connection_id", "after_seq")
    OBSERVED_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    AFTER_SEQ_FIELD_NUMBER: _ClassVar[int]
    observed_connection_id: str
    after_seq: int
    def __init__(self, observed_connection_id: _Optional[str] = ..., after_seq: _Optional[int] = ...) -> None: ...

class WatchEventsResponse(_message.Message):
    __slots__ = ("update",)
    UPDATE_FIELD_NUMBER: _ClassVar[int]
    update: _client_connection_pb2.WatchEventsResponse
    def __init__(self, update: _Optional[_Union[_client_connection_pb2.WatchEventsResponse, _Mapping]] = ...) -> None: ...
