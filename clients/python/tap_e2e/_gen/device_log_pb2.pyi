# ruff: noqa
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LogPriority(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LOG_PRIORITY_UNSPECIFIED: _ClassVar[LogPriority]
    LOG_PRIORITY_VERBOSE: _ClassVar[LogPriority]
    LOG_PRIORITY_DEBUG: _ClassVar[LogPriority]
    LOG_PRIORITY_INFO: _ClassVar[LogPriority]
    LOG_PRIORITY_WARN: _ClassVar[LogPriority]
    LOG_PRIORITY_ERROR: _ClassVar[LogPriority]
    LOG_PRIORITY_FATAL: _ClassVar[LogPriority]
LOG_PRIORITY_UNSPECIFIED: LogPriority
LOG_PRIORITY_VERBOSE: LogPriority
LOG_PRIORITY_DEBUG: LogPriority
LOG_PRIORITY_INFO: LogPriority
LOG_PRIORITY_WARN: LogPriority
LOG_PRIORITY_ERROR: LogPriority
LOG_PRIORITY_FATAL: LogPriority

class ReadLogRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "after_cursor", "marker", "tail", "last_action", "follow", "packages", "min_priority", "tags", "message_regex", "exclude_actions", "step")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    AFTER_CURSOR_FIELD_NUMBER: _ClassVar[int]
    MARKER_FIELD_NUMBER: _ClassVar[int]
    TAIL_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTION_FIELD_NUMBER: _ClassVar[int]
    FOLLOW_FIELD_NUMBER: _ClassVar[int]
    PACKAGES_FIELD_NUMBER: _ClassVar[int]
    MIN_PRIORITY_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_REGEX_FIELD_NUMBER: _ClassVar[int]
    EXCLUDE_ACTIONS_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    after_cursor: int
    marker: str
    tail: int
    last_action: bool
    follow: bool
    packages: _containers.RepeatedScalarFieldContainer[str]
    min_priority: LogPriority
    tags: _containers.RepeatedScalarFieldContainer[str]
    message_regex: str
    exclude_actions: bool
    step: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., after_cursor: _Optional[int] = ..., marker: _Optional[str] = ..., tail: _Optional[int] = ..., last_action: _Optional[bool] = ..., follow: _Optional[bool] = ..., packages: _Optional[_Iterable[str]] = ..., min_priority: _Optional[_Union[LogPriority, str]] = ..., tags: _Optional[_Iterable[str]] = ..., message_regex: _Optional[str] = ..., exclude_actions: _Optional[bool] = ..., step: _Optional[str] = ...) -> None: ...

class ReadLogResponse(_message.Message):
    __slots__ = ("entries", "dropped")
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    DROPPED_FIELD_NUMBER: _ClassVar[int]
    entries: _containers.RepeatedCompositeFieldContainer[LogEntry]
    dropped: int
    def __init__(self, entries: _Optional[_Iterable[_Union[LogEntry, _Mapping]]] = ..., dropped: _Optional[int] = ...) -> None: ...

class LogEntry(_message.Message):
    __slots__ = ("cursor", "host_time_ms", "line", "marker", "action", "gap", "host_monotonic_ns")
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    HOST_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    LINE_FIELD_NUMBER: _ClassVar[int]
    MARKER_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    GAP_FIELD_NUMBER: _ClassVar[int]
    HOST_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    cursor: int
    host_time_ms: int
    line: LogLine
    marker: str
    action: LogAction
    gap: str
    host_monotonic_ns: int
    def __init__(self, cursor: _Optional[int] = ..., host_time_ms: _Optional[int] = ..., line: _Optional[_Union[LogLine, _Mapping]] = ..., marker: _Optional[str] = ..., action: _Optional[_Union[LogAction, _Mapping]] = ..., gap: _Optional[str] = ..., host_monotonic_ns: _Optional[int] = ...) -> None: ...

class LogLine(_message.Message):
    __slots__ = ("device_time_ms", "uid", "pid", "tid", "priority", "tag", "message", "packages")
    DEVICE_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    UID_FIELD_NUMBER: _ClassVar[int]
    PID_FIELD_NUMBER: _ClassVar[int]
    TID_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    TAG_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    PACKAGES_FIELD_NUMBER: _ClassVar[int]
    device_time_ms: int
    uid: int
    pid: int
    tid: int
    priority: LogPriority
    tag: str
    message: str
    packages: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, device_time_ms: _Optional[int] = ..., uid: _Optional[int] = ..., pid: _Optional[int] = ..., tid: _Optional[int] = ..., priority: _Optional[_Union[LogPriority, str]] = ..., tag: _Optional[str] = ..., message: _Optional[str] = ..., packages: _Optional[_Iterable[str]] = ...) -> None: ...

class LogAction(_message.Message):
    __slots__ = ("operation", "target", "finished", "outcome", "duration_ms", "request_id", "device_time_ms", "step")
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    TARGET_FIELD_NUMBER: _ClassVar[int]
    FINISHED_FIELD_NUMBER: _ClassVar[int]
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    DEVICE_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    operation: str
    target: str
    finished: bool
    outcome: str
    duration_ms: int
    request_id: int
    device_time_ms: int
    step: str
    def __init__(self, operation: _Optional[str] = ..., target: _Optional[str] = ..., finished: _Optional[bool] = ..., outcome: _Optional[str] = ..., duration_ms: _Optional[int] = ..., request_id: _Optional[int] = ..., device_time_ms: _Optional[int] = ..., step: _Optional[str] = ...) -> None: ...

class MarkLogRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "name")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    name: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class MarkLogResponse(_message.Message):
    __slots__ = ("cursor",)
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    cursor: int
    def __init__(self, cursor: _Optional[int] = ...) -> None: ...

class RedactLogRequest(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "patterns")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    PATTERNS_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    patterns: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., patterns: _Optional[_Iterable[str]] = ...) -> None: ...

class RedactLogResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
