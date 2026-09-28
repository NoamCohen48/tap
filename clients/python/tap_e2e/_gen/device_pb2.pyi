# ruff: noqa
from . import command_pb2 as _command_pb2
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
DEVICE_STATE_UNSPECIFIED: DeviceState
DEVICE_FREE: DeviceState
DEVICE_LEASED: DeviceState
DEVICE_QUARANTINED: DeviceState
DEVICE_OFFLINE: DeviceState
DEVICE_UNAUTHORIZED: DeviceState

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
    __slots__ = ("client_connection_id", "serial", "aut_package", "skip_driver_install", "sync_authority", "default_timeout_ms", "lease_timeout_ms")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    SKIP_DRIVER_INSTALL_FIELD_NUMBER: _ClassVar[int]
    SYNC_AUTHORITY_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    LEASE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    serial: str
    aut_package: str
    skip_driver_install: bool
    sync_authority: str
    default_timeout_ms: int
    lease_timeout_ms: int
    def __init__(self, client_connection_id: _Optional[str] = ..., serial: _Optional[str] = ..., aut_package: _Optional[str] = ..., skip_driver_install: _Optional[bool] = ..., sync_authority: _Optional[str] = ..., default_timeout_ms: _Optional[int] = ..., lease_timeout_ms: _Optional[int] = ...) -> None: ...

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
