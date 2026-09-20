from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AppRequest(_message.Message):
    __slots__ = ("session_id", "package_name", "timeout_ms")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    package_name: str
    timeout_ms: int
    def __init__(self, session_id: _Optional[str] = ..., package_name: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class AppInstallRequest(_message.Message):
    __slots__ = ("app", "apk_path")
    APP_FIELD_NUMBER: _ClassVar[int]
    APK_PATH_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    apk_path: str
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., apk_path: _Optional[str] = ...) -> None: ...

class AppGrantRequest(_message.Message):
    __slots__ = ("app", "permission")
    APP_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    permission: str
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., permission: _Optional[str] = ...) -> None: ...

class AppLaunchRequest(_message.Message):
    __slots__ = ("app", "activity")
    APP_FIELD_NUMBER: _ClassVar[int]
    ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    activity: str
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., activity: _Optional[str] = ...) -> None: ...

class AppAwaitIdleRequest(_message.Message):
    __slots__ = ("app", "stable_for_ms")
    APP_FIELD_NUMBER: _ClassVar[int]
    STABLE_FOR_MS_FIELD_NUMBER: _ClassVar[int]
    app: AppRequest
    stable_for_ms: int
    def __init__(self, app: _Optional[_Union[AppRequest, _Mapping]] = ..., stable_for_ms: _Optional[int] = ...) -> None: ...

class ProcessIdentity(_message.Message):
    __slots__ = ("pid", "start_token")
    PID_FIELD_NUMBER: _ClassVar[int]
    START_TOKEN_FIELD_NUMBER: _ClassVar[int]
    pid: int
    start_token: str
    def __init__(self, pid: _Optional[int] = ..., start_token: _Optional[str] = ...) -> None: ...

class AppBool(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: bool
    def __init__(self, value: _Optional[bool] = ...) -> None: ...

class AppEmpty(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
