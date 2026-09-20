from . import command_pb2 as _command_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class OpenSessionRequest(_message.Message):
    __slots__ = ("connection_id", "serial", "aut_package", "driver_apk", "driver_test_apk", "skip_driver_install", "sync_authority", "allowed_system_packages", "default_timeout_ms", "lease_timeout_ms")
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    DRIVER_APK_FIELD_NUMBER: _ClassVar[int]
    DRIVER_TEST_APK_FIELD_NUMBER: _ClassVar[int]
    SKIP_DRIVER_INSTALL_FIELD_NUMBER: _ClassVar[int]
    SYNC_AUTHORITY_FIELD_NUMBER: _ClassVar[int]
    ALLOWED_SYSTEM_PACKAGES_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    LEASE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    serial: str
    aut_package: str
    driver_apk: str
    driver_test_apk: str
    skip_driver_install: bool
    sync_authority: str
    allowed_system_packages: _containers.RepeatedScalarFieldContainer[str]
    default_timeout_ms: int
    lease_timeout_ms: int
    def __init__(self, connection_id: _Optional[str] = ..., serial: _Optional[str] = ..., aut_package: _Optional[str] = ..., driver_apk: _Optional[str] = ..., driver_test_apk: _Optional[str] = ..., skip_driver_install: _Optional[bool] = ..., sync_authority: _Optional[str] = ..., allowed_system_packages: _Optional[_Iterable[str]] = ..., default_timeout_ms: _Optional[int] = ..., lease_timeout_ms: _Optional[int] = ...) -> None: ...

class OpenSessionResponse(_message.Message):
    __slots__ = ("session_id", "serial", "generation", "device_info")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    DEVICE_INFO_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    serial: str
    generation: int
    device_info: _command_pb2.DeviceInfo
    def __init__(self, session_id: _Optional[str] = ..., serial: _Optional[str] = ..., generation: _Optional[int] = ..., device_info: _Optional[_Union[_command_pb2.DeviceInfo, _Mapping]] = ...) -> None: ...

class CloseSessionRequest(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    def __init__(self, session_id: _Optional[str] = ...) -> None: ...

class CloseSessionResponse(_message.Message):
    __slots__ = ("clean", "detail")
    CLEAN_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    clean: bool
    detail: str
    def __init__(self, clean: _Optional[bool] = ..., detail: _Optional[str] = ...) -> None: ...

class ExecuteRequest(_message.Message):
    __slots__ = ("session_id", "command")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    command: _command_pb2.Command
    def __init__(self, session_id: _Optional[str] = ..., command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ...) -> None: ...

class ScreenshotRequest(_message.Message):
    __slots__ = ("session_id", "timeout_ms", "write_to")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    WRITE_TO_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    timeout_ms: int
    write_to: str
    def __init__(self, session_id: _Optional[str] = ..., timeout_ms: _Optional[int] = ..., write_to: _Optional[str] = ...) -> None: ...

class ScreenshotResponse(_message.Message):
    __slots__ = ("png", "artifact", "path")
    PNG_FIELD_NUMBER: _ClassVar[int]
    ARTIFACT_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    png: bytes
    artifact: _command_pb2.ArtifactInfo
    path: str
    def __init__(self, png: _Optional[bytes] = ..., artifact: _Optional[_Union[_command_pb2.ArtifactInfo, _Mapping]] = ..., path: _Optional[str] = ...) -> None: ...

class DriverLogRequest(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    def __init__(self, session_id: _Optional[str] = ...) -> None: ...

class DriverLogResponse(_message.Message):
    __slots__ = ("lines",)
    LINES_FIELD_NUMBER: _ClassVar[int]
    lines: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, lines: _Optional[_Iterable[str]] = ...) -> None: ...
