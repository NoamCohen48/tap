from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

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
