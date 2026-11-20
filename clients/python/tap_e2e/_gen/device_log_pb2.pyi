# ruff: noqa
from . import command_pb2 as _command_pb2
from . import event_log_pb2 as _event_log_pb2
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
    __slots__ = ("client_connection_id", "attached_device_id", "after_cursor", "marker", "tail", "last_action", "follow", "packages", "min_priority", "tags", "message_regex", "exclude_actions", "step", "recording")
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
    RECORDING_FIELD_NUMBER: _ClassVar[int]
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
    recording: str
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., after_cursor: _Optional[int] = ..., marker: _Optional[str] = ..., tail: _Optional[int] = ..., last_action: _Optional[bool] = ..., follow: _Optional[bool] = ..., packages: _Optional[_Iterable[str]] = ..., min_priority: _Optional[_Union[LogPriority, str]] = ..., tags: _Optional[_Iterable[str]] = ..., message_regex: _Optional[str] = ..., exclude_actions: _Optional[bool] = ..., step: _Optional[str] = ..., recording: _Optional[str] = ...) -> None: ...

class ReadLogResponse(_message.Message):
    __slots__ = ("entries", "dropped", "capture_error", "clock_id")
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    DROPPED_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_ERROR_FIELD_NUMBER: _ClassVar[int]
    CLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    entries: _containers.RepeatedCompositeFieldContainer[LogEntry]
    dropped: int
    capture_error: str
    clock_id: str
    def __init__(self, entries: _Optional[_Iterable[_Union[LogEntry, _Mapping]]] = ..., dropped: _Optional[int] = ..., capture_error: _Optional[str] = ..., clock_id: _Optional[str] = ...) -> None: ...

class LogEntry(_message.Message):
    __slots__ = ("cursor", "host_time_ms", "line", "marker", "action", "gap", "event", "death", "host_monotonic_ns")
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    HOST_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    LINE_FIELD_NUMBER: _ClassVar[int]
    MARKER_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    GAP_FIELD_NUMBER: _ClassVar[int]
    EVENT_FIELD_NUMBER: _ClassVar[int]
    DEATH_FIELD_NUMBER: _ClassVar[int]
    HOST_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    cursor: int
    host_time_ms: int
    line: LogLine
    marker: str
    action: LogAction
    gap: str
    event: _event_log_pb2.LoggedEvent
    death: RecordedAppDeath
    host_monotonic_ns: int
    def __init__(self, cursor: _Optional[int] = ..., host_time_ms: _Optional[int] = ..., line: _Optional[_Union[LogLine, _Mapping]] = ..., marker: _Optional[str] = ..., action: _Optional[_Union[LogAction, _Mapping]] = ..., gap: _Optional[str] = ..., event: _Optional[_Union[_event_log_pb2.LoggedEvent, _Mapping]] = ..., death: _Optional[_Union[RecordedAppDeath, _Mapping]] = ..., host_monotonic_ns: _Optional[int] = ...) -> None: ...

class RecordedAppDeath(_message.Message):
    __slots__ = ("attached_device_id", "serial", "death")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    DEATH_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    death: _command_pb2.AppDeath
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., death: _Optional[_Union[_command_pb2.AppDeath, _Mapping]] = ...) -> None: ...

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

class ListRetainedLogsRequest(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class ListRetainedLogsResponse(_message.Message):
    __slots__ = ("logs",)
    LOGS_FIELD_NUMBER: _ClassVar[int]
    logs: _containers.RepeatedCompositeFieldContainer[RetainedLog]
    def __init__(self, logs: _Optional[_Iterable[_Union[RetainedLog, _Mapping]]] = ...) -> None: ...

class RetainedLog(_message.Message):
    __slots__ = ("client_connection_id", "attached_device_id", "serial", "last_cursor", "closed", "capture_error", "clock_id", "events", "retained_bytes")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    LAST_CURSOR_FIELD_NUMBER: _ClassVar[int]
    CLOSED_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_ERROR_FIELD_NUMBER: _ClassVar[int]
    CLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    RETAINED_BYTES_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    attached_device_id: str
    serial: str
    last_cursor: int
    closed: bool
    capture_error: str
    clock_id: str
    events: bool
    retained_bytes: int
    def __init__(self, client_connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., last_cursor: _Optional[int] = ..., closed: _Optional[bool] = ..., capture_error: _Optional[str] = ..., clock_id: _Optional[str] = ..., events: _Optional[bool] = ..., retained_bytes: _Optional[int] = ...) -> None: ...

class StartLogRecordingRequest(_message.Message):
    __slots__ = ("client_connection_id", "name", "agent_steps_expected")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    AGENT_STEPS_EXPECTED_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    name: str
    agent_steps_expected: bool
    def __init__(self, client_connection_id: _Optional[str] = ..., name: _Optional[str] = ..., agent_steps_expected: _Optional[bool] = ...) -> None: ...

class StartLogRecordingResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: LogRecording
    def __init__(self, recording: _Optional[_Union[LogRecording, _Mapping]] = ...) -> None: ...

class StopLogRecordingRequest(_message.Message):
    __slots__ = ("name",)
    NAME_FIELD_NUMBER: _ClassVar[int]
    name: str
    def __init__(self, name: _Optional[str] = ...) -> None: ...

class StopLogRecordingResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: LogRecording
    def __init__(self, recording: _Optional[_Union[LogRecording, _Mapping]] = ...) -> None: ...

class DeleteLogRecordingRequest(_message.Message):
    __slots__ = ("name",)
    NAME_FIELD_NUMBER: _ClassVar[int]
    name: str
    def __init__(self, name: _Optional[str] = ...) -> None: ...

class DeleteLogRecordingResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListLogRecordingsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListLogRecordingsResponse(_message.Message):
    __slots__ = ("recordings",)
    RECORDINGS_FIELD_NUMBER: _ClassVar[int]
    recordings: _containers.RepeatedCompositeFieldContainer[LogRecording]
    def __init__(self, recordings: _Optional[_Iterable[_Union[LogRecording, _Mapping]]] = ...) -> None: ...

class LogRecording(_message.Message):
    __slots__ = ("name", "client_connection_id", "started_at_ms", "stopped_at_ms", "ranges", "complete", "capture_error", "retained_bytes", "agent_steps_expected")
    NAME_FIELD_NUMBER: _ClassVar[int]
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    STOPPED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    RANGES_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_ERROR_FIELD_NUMBER: _ClassVar[int]
    RETAINED_BYTES_FIELD_NUMBER: _ClassVar[int]
    AGENT_STEPS_EXPECTED_FIELD_NUMBER: _ClassVar[int]
    name: str
    client_connection_id: str
    started_at_ms: int
    stopped_at_ms: int
    ranges: _containers.RepeatedCompositeFieldContainer[LogRecordingRange]
    complete: bool
    capture_error: str
    retained_bytes: int
    agent_steps_expected: bool
    def __init__(self, name: _Optional[str] = ..., client_connection_id: _Optional[str] = ..., started_at_ms: _Optional[int] = ..., stopped_at_ms: _Optional[int] = ..., ranges: _Optional[_Iterable[_Union[LogRecordingRange, _Mapping]]] = ..., complete: _Optional[bool] = ..., capture_error: _Optional[str] = ..., retained_bytes: _Optional[int] = ..., agent_steps_expected: _Optional[bool] = ...) -> None: ...

class LogRecordingRange(_message.Message):
    __slots__ = ("attached_device_id", "serial", "after_cursor", "through_cursor", "events")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    AFTER_CURSOR_FIELD_NUMBER: _ClassVar[int]
    THROUGH_CURSOR_FIELD_NUMBER: _ClassVar[int]
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    after_cursor: int
    through_cursor: int
    events: bool
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., after_cursor: _Optional[int] = ..., through_cursor: _Optional[int] = ..., events: _Optional[bool] = ...) -> None: ...
