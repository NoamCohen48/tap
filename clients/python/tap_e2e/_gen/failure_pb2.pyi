# ruff: noqa
from . import command_pb2 as _command_pb2
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class FailureReason(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    FAILURE_REASON_UNSPECIFIED: _ClassVar[FailureReason]
    FAILURE_REASON_INTERNAL: _ClassVar[FailureReason]
    FAILURE_REASON_INVALID_ARGUMENT: _ClassVar[FailureReason]
    FAILURE_REASON_UNAUTHENTICATED: _ClassVar[FailureReason]
    FAILURE_REASON_UNKNOWN_CLIENT_CONNECTION: _ClassVar[FailureReason]
    FAILURE_REASON_UNKNOWN_ATTACHED_DEVICE: _ClassVar[FailureReason]
    FAILURE_REASON_NOT_OWNER: _ClassVar[FailureReason]
    FAILURE_REASON_DEVICE_BUSY: _ClassVar[FailureReason]
    FAILURE_REASON_DEVICE_QUARANTINED: _ClassVar[FailureReason]
    FAILURE_REASON_HOST_WAIT_TIMEOUT: _ClassVar[FailureReason]
    FAILURE_REASON_ADB_TIMEOUT: _ClassVar[FailureReason]
    FAILURE_REASON_ADB_FAILED: _ClassVar[FailureReason]
    FAILURE_REASON_ADB_REAP_UNCERTAIN: _ClassVar[FailureReason]
    FAILURE_REASON_APP_LIFECYCLE: _ClassVar[FailureReason]
    FAILURE_REASON_DRIVER_BUILD_MISMATCH: _ClassVar[FailureReason]
    FAILURE_REASON_DRIVER_START_FAILED: _ClassVar[FailureReason]
    FAILURE_REASON_DRIVER_TRANSPORT: _ClassVar[FailureReason]
    FAILURE_REASON_DRIVER_COMMAND: _ClassVar[FailureReason]
    FAILURE_REASON_SESSION_UNUSABLE: _ClassVar[FailureReason]
    FAILURE_REASON_DAEMON_PRECONDITION: _ClassVar[FailureReason]
    FAILURE_REASON_UNKNOWN_REF: _ClassVar[FailureReason]
    FAILURE_REASON_REF_NOT_ADDRESSABLE: _ClassVar[FailureReason]
    FAILURE_REASON_UNSUPPORTED_API: _ClassVar[FailureReason]
    FAILURE_REASON_DEVICE_SETTING: _ClassVar[FailureReason]
    FAILURE_REASON_DEVICE_FILE: _ClassVar[FailureReason]
FAILURE_REASON_UNSPECIFIED: FailureReason
FAILURE_REASON_INTERNAL: FailureReason
FAILURE_REASON_INVALID_ARGUMENT: FailureReason
FAILURE_REASON_UNAUTHENTICATED: FailureReason
FAILURE_REASON_UNKNOWN_CLIENT_CONNECTION: FailureReason
FAILURE_REASON_UNKNOWN_ATTACHED_DEVICE: FailureReason
FAILURE_REASON_NOT_OWNER: FailureReason
FAILURE_REASON_DEVICE_BUSY: FailureReason
FAILURE_REASON_DEVICE_QUARANTINED: FailureReason
FAILURE_REASON_HOST_WAIT_TIMEOUT: FailureReason
FAILURE_REASON_ADB_TIMEOUT: FailureReason
FAILURE_REASON_ADB_FAILED: FailureReason
FAILURE_REASON_ADB_REAP_UNCERTAIN: FailureReason
FAILURE_REASON_APP_LIFECYCLE: FailureReason
FAILURE_REASON_DRIVER_BUILD_MISMATCH: FailureReason
FAILURE_REASON_DRIVER_START_FAILED: FailureReason
FAILURE_REASON_DRIVER_TRANSPORT: FailureReason
FAILURE_REASON_DRIVER_COMMAND: FailureReason
FAILURE_REASON_SESSION_UNUSABLE: FailureReason
FAILURE_REASON_DAEMON_PRECONDITION: FailureReason
FAILURE_REASON_UNKNOWN_REF: FailureReason
FAILURE_REASON_REF_NOT_ADDRESSABLE: FailureReason
FAILURE_REASON_UNSUPPORTED_API: FailureReason
FAILURE_REASON_DEVICE_SETTING: FailureReason
FAILURE_REASON_DEVICE_FILE: FailureReason

class Failure(_message.Message):
    __slots__ = ("reason", "serial", "waited_ms", "error_code", "detail")
    REASON_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    WAITED_MS_FIELD_NUMBER: _ClassVar[int]
    ERROR_CODE_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    reason: FailureReason
    serial: str
    waited_ms: int
    error_code: _command_pb2.ErrorCode
    detail: str
    def __init__(self, reason: _Optional[_Union[FailureReason, str]] = ..., serial: _Optional[str] = ..., waited_ms: _Optional[int] = ..., error_code: _Optional[_Union[_command_pb2.ErrorCode, str]] = ..., detail: _Optional[str] = ...) -> None: ...
