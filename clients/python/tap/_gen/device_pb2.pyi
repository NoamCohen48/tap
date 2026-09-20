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
DEVICE_STATE_UNSPECIFIED: DeviceState
DEVICE_FREE: DeviceState
DEVICE_LEASED: DeviceState
DEVICE_QUARANTINED: DeviceState
DEVICE_OFFLINE: DeviceState

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
