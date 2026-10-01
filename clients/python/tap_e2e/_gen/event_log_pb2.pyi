# ruff: noqa
from . import command_pb2 as _command_pb2
from . import failure_pb2 as _failure_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LoggedEvent(_message.Message):
    __slots__ = ("seq", "at_epoch_ms", "duration_ms", "serial", "command", "app", "error", "failure")
    SEQ_FIELD_NUMBER: _ClassVar[int]
    AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    APP_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    FAILURE_FIELD_NUMBER: _ClassVar[int]
    seq: int
    at_epoch_ms: int
    duration_ms: int
    serial: str
    command: _command_pb2.Command
    app: AppCall
    error: _command_pb2.Error
    failure: _failure_pb2.Failure
    def __init__(self, seq: _Optional[int] = ..., at_epoch_ms: _Optional[int] = ..., duration_ms: _Optional[int] = ..., serial: _Optional[str] = ..., command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ..., app: _Optional[_Union[AppCall, _Mapping]] = ..., error: _Optional[_Union[_command_pb2.Error, _Mapping]] = ..., failure: _Optional[_Union[_failure_pb2.Failure, _Mapping]] = ...) -> None: ...

class AppCall(_message.Message):
    __slots__ = ("operation", "package_name", "activity", "permission", "timeout_ms")
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    operation: str
    package_name: str
    activity: str
    permission: str
    timeout_ms: int
    def __init__(self, operation: _Optional[str] = ..., package_name: _Optional[str] = ..., activity: _Optional[str] = ..., permission: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...
