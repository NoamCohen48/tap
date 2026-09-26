# ruff: noqa
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ConnectRequest(_message.Message):
    __slots__ = ("name",)
    NAME_FIELD_NUMBER: _ClassVar[int]
    name: str
    def __init__(self, name: _Optional[str] = ...) -> None: ...

class ConnectResponse(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class ObserveRequest(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class Observing(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class Heartbeat(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Closing(_message.Message):
    __slots__ = ("reason",)
    REASON_FIELD_NUMBER: _ClassVar[int]
    reason: str
    def __init__(self, reason: _Optional[str] = ...) -> None: ...

class ObserveResponse(_message.Message):
    __slots__ = ("at_epoch_ms", "observing", "heartbeat", "closing")
    AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    OBSERVING_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    CLOSING_FIELD_NUMBER: _ClassVar[int]
    at_epoch_ms: int
    observing: Observing
    heartbeat: Heartbeat
    closing: Closing
    def __init__(self, at_epoch_ms: _Optional[int] = ..., observing: _Optional[_Union[Observing, _Mapping]] = ..., heartbeat: _Optional[_Union[Heartbeat, _Mapping]] = ..., closing: _Optional[_Union[Closing, _Mapping]] = ...) -> None: ...

class DisconnectRequest(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class DisconnectResponse(_message.Message):
    __slots__ = ("attached_devices_detached",)
    ATTACHED_DEVICES_DETACHED_FIELD_NUMBER: _ClassVar[int]
    attached_devices_detached: int
    def __init__(self, attached_devices_detached: _Optional[int] = ...) -> None: ...

class InfoRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InfoResponse(_message.Message):
    __slots__ = ("daemon_version", "host_build_id", "protocol_version", "adb_executable", "state_dir", "driver_available", "pid")
    DAEMON_VERSION_FIELD_NUMBER: _ClassVar[int]
    HOST_BUILD_ID_FIELD_NUMBER: _ClassVar[int]
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    ADB_EXECUTABLE_FIELD_NUMBER: _ClassVar[int]
    STATE_DIR_FIELD_NUMBER: _ClassVar[int]
    DRIVER_AVAILABLE_FIELD_NUMBER: _ClassVar[int]
    PID_FIELD_NUMBER: _ClassVar[int]
    daemon_version: str
    host_build_id: str
    protocol_version: str
    adb_executable: str
    state_dir: str
    driver_available: bool
    pid: int
    def __init__(self, daemon_version: _Optional[str] = ..., host_build_id: _Optional[str] = ..., protocol_version: _Optional[str] = ..., adb_executable: _Optional[str] = ..., state_dir: _Optional[str] = ..., driver_available: _Optional[bool] = ..., pid: _Optional[int] = ...) -> None: ...
