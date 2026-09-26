# ruff: noqa
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AppTarget(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "package_name")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    package_name: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., package_name: _Optional[str] = ...) -> None: ...

class ProcessIdentity(_message.Message):
    __slots__ = ("pid", "start_token")
    PID_FIELD_NUMBER: _ClassVar[int]
    START_TOKEN_FIELD_NUMBER: _ClassVar[int]
    pid: int
    start_token: str
    def __init__(self, pid: _Optional[int] = ..., start_token: _Optional[str] = ...) -> None: ...

class InstallHeader(_message.Message):
    __slots__ = ("app", "timeout_ms", "size_bytes")
    APP_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    timeout_ms: int
    size_bytes: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., timeout_ms: _Optional[int] = ..., size_bytes: _Optional[int] = ...) -> None: ...

class InstallRequest(_message.Message):
    __slots__ = ("header", "chunk")
    HEADER_FIELD_NUMBER: _ClassVar[int]
    CHUNK_FIELD_NUMBER: _ClassVar[int]
    header: InstallHeader
    chunk: bytes
    def __init__(self, header: _Optional[_Union[InstallHeader, _Mapping]] = ..., chunk: _Optional[bytes] = ...) -> None: ...

class InstallResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class UninstallRequest(_message.Message):
    __slots__ = ("app",)
    APP_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ...) -> None: ...

class UninstallResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class IsInstalledRequest(_message.Message):
    __slots__ = ("app",)
    APP_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ...) -> None: ...

class IsInstalledResponse(_message.Message):
    __slots__ = ("installed",)
    INSTALLED_FIELD_NUMBER: _ClassVar[int]
    installed: bool
    def __init__(self, installed: _Optional[bool] = ...) -> None: ...

class ForceStopRequest(_message.Message):
    __slots__ = ("app", "timeout_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    timeout_ms: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class ForceStopResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ClearDataRequest(_message.Message):
    __slots__ = ("app", "timeout_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    timeout_ms: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class ClearDataResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GrantPermissionRequest(_message.Message):
    __slots__ = ("app", "permission")
    APP_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    permission: str
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., permission: _Optional[str] = ...) -> None: ...

class GrantPermissionResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class LaunchRequest(_message.Message):
    __slots__ = ("app", "activity", "timeout_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    activity: str
    timeout_ms: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., activity: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class LaunchResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ColdLaunchRequest(_message.Message):
    __slots__ = ("app", "activity", "timeout_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    activity: str
    timeout_ms: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., activity: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class ColdLaunchResponse(_message.Message):
    __slots__ = ("process",)
    PROCESS_FIELD_NUMBER: _ClassVar[int]
    process: ProcessIdentity
    def __init__(self, process: _Optional[_Union[ProcessIdentity, _Mapping]] = ...) -> None: ...

class ProcessRequest(_message.Message):
    __slots__ = ("app", "timeout_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    timeout_ms: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class ProcessResponse(_message.Message):
    __slots__ = ("process",)
    PROCESS_FIELD_NUMBER: _ClassVar[int]
    process: ProcessIdentity
    def __init__(self, process: _Optional[_Union[ProcessIdentity, _Mapping]] = ...) -> None: ...

class IsRunningRequest(_message.Message):
    __slots__ = ("app",)
    APP_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ...) -> None: ...

class IsRunningResponse(_message.Message):
    __slots__ = ("running",)
    RUNNING_FIELD_NUMBER: _ClassVar[int]
    running: bool
    def __init__(self, running: _Optional[bool] = ...) -> None: ...

class AwaitIdleRequest(_message.Message):
    __slots__ = ("app", "timeout_ms", "stable_for_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    STABLE_FOR_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppTarget
    timeout_ms: int
    stable_for_ms: int
    def __init__(self, app: _Optional[_Union[AppTarget, _Mapping]] = ..., timeout_ms: _Optional[int] = ..., stable_for_ms: _Optional[int] = ...) -> None: ...

class AwaitIdleResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
