# ruff: noqa
from . import command_pb2 as _command_pb2
from . import device_log_pb2 as _device_log_pb2
from . import event_log_pb2 as _event_log_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class HistoryIssueKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    HISTORY_ISSUE_KIND_UNSPECIFIED: _ClassVar[HistoryIssueKind]
    HISTORY_ISSUE_KIND_EXPIRED: _ClassVar[HistoryIssueKind]
    HISTORY_ISSUE_KIND_UNAVAILABLE: _ClassVar[HistoryIssueKind]
    HISTORY_ISSUE_KIND_INCOMPLETE: _ClassVar[HistoryIssueKind]
    HISTORY_ISSUE_KIND_PACKAGE_UNKNOWN: _ClassVar[HistoryIssueKind]
HISTORY_ISSUE_KIND_UNSPECIFIED: HistoryIssueKind
HISTORY_ISSUE_KIND_EXPIRED: HistoryIssueKind
HISTORY_ISSUE_KIND_UNAVAILABLE: HistoryIssueKind
HISTORY_ISSUE_KIND_INCOMPLETE: HistoryIssueKind
HISTORY_ISSUE_KIND_PACKAGE_UNKNOWN: HistoryIssueKind

class HistoryDeviceSource(_message.Message):
    __slots__ = ("connection_id", "attached_device_id", "serial")
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    attached_device_id: str
    serial: str
    def __init__(self, connection_id: _Optional[str] = ..., attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ...) -> None: ...

class HistoryConnectionSource(_message.Message):
    __slots__ = ("connection_id",)
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    def __init__(self, connection_id: _Optional[str] = ...) -> None: ...

class HistorySource(_message.Message):
    __slots__ = ("id", "device_log", "connection", "clock_id", "closed", "last_sequence", "retained_bytes", "issues")
    ID_FIELD_NUMBER: _ClassVar[int]
    DEVICE_LOG_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_FIELD_NUMBER: _ClassVar[int]
    CLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    CLOSED_FIELD_NUMBER: _ClassVar[int]
    LAST_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    RETAINED_BYTES_FIELD_NUMBER: _ClassVar[int]
    ISSUES_FIELD_NUMBER: _ClassVar[int]
    id: str
    device_log: HistoryDeviceSource
    connection: HistoryConnectionSource
    clock_id: str
    closed: bool
    last_sequence: int
    retained_bytes: int
    issues: _containers.RepeatedCompositeFieldContainer[HistoryIssue]
    def __init__(self, id: _Optional[str] = ..., device_log: _Optional[_Union[HistoryDeviceSource, _Mapping]] = ..., connection: _Optional[_Union[HistoryConnectionSource, _Mapping]] = ..., clock_id: _Optional[str] = ..., closed: _Optional[bool] = ..., last_sequence: _Optional[int] = ..., retained_bytes: _Optional[int] = ..., issues: _Optional[_Iterable[_Union[HistoryIssue, _Mapping]]] = ...) -> None: ...

class HistorySelection(_message.Message):
    __slots__ = ("connection_ids", "source_ids", "serials", "recording_id")
    CONNECTION_IDS_FIELD_NUMBER: _ClassVar[int]
    SOURCE_IDS_FIELD_NUMBER: _ClassVar[int]
    SERIALS_FIELD_NUMBER: _ClassVar[int]
    RECORDING_ID_FIELD_NUMBER: _ClassVar[int]
    connection_ids: _containers.RepeatedScalarFieldContainer[str]
    source_ids: _containers.RepeatedScalarFieldContainer[str]
    serials: _containers.RepeatedScalarFieldContainer[str]
    recording_id: str
    def __init__(self, connection_ids: _Optional[_Iterable[str]] = ..., source_ids: _Optional[_Iterable[str]] = ..., serials: _Optional[_Iterable[str]] = ..., recording_id: _Optional[str] = ...) -> None: ...

class HistoryLogFilter(_message.Message):
    __slots__ = ("packages", "min_priority", "tags", "message_regex", "exclude_actions", "include_driver_lines")
    PACKAGES_FIELD_NUMBER: _ClassVar[int]
    MIN_PRIORITY_FIELD_NUMBER: _ClassVar[int]
    TAGS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_REGEX_FIELD_NUMBER: _ClassVar[int]
    EXCLUDE_ACTIONS_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_DRIVER_LINES_FIELD_NUMBER: _ClassVar[int]
    packages: _containers.RepeatedScalarFieldContainer[str]
    min_priority: _device_log_pb2.LogPriority
    tags: _containers.RepeatedScalarFieldContainer[str]
    message_regex: str
    exclude_actions: bool
    include_driver_lines: bool
    def __init__(self, packages: _Optional[_Iterable[str]] = ..., min_priority: _Optional[_Union[_device_log_pb2.LogPriority, str]] = ..., tags: _Optional[_Iterable[str]] = ..., message_regex: _Optional[str] = ..., exclude_actions: _Optional[bool] = ..., include_driver_lines: _Optional[bool] = ...) -> None: ...

class ReadHistoryRequest(_message.Message):
    __slots__ = ("selection", "from_time_ms", "from_marker", "until_time_ms", "until_marker", "step", "include_logs", "log_filter", "newest_first", "limit", "cursor")
    SELECTION_FIELD_NUMBER: _ClassVar[int]
    FROM_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    FROM_MARKER_FIELD_NUMBER: _ClassVar[int]
    UNTIL_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    UNTIL_MARKER_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_LOGS_FIELD_NUMBER: _ClassVar[int]
    LOG_FILTER_FIELD_NUMBER: _ClassVar[int]
    NEWEST_FIRST_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    selection: HistorySelection
    from_time_ms: int
    from_marker: str
    until_time_ms: int
    until_marker: str
    step: str
    include_logs: bool
    log_filter: HistoryLogFilter
    newest_first: bool
    limit: int
    cursor: str
    def __init__(self, selection: _Optional[_Union[HistorySelection, _Mapping]] = ..., from_time_ms: _Optional[int] = ..., from_marker: _Optional[str] = ..., until_time_ms: _Optional[int] = ..., until_marker: _Optional[str] = ..., step: _Optional[str] = ..., include_logs: _Optional[bool] = ..., log_filter: _Optional[_Union[HistoryLogFilter, _Mapping]] = ..., newest_first: _Optional[bool] = ..., limit: _Optional[int] = ..., cursor: _Optional[str] = ...) -> None: ...

class HistoryDeath(_message.Message):
    __slots__ = ("attached_device_id", "serial", "death")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    DEATH_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    death: _command_pb2.AppDeath
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., death: _Optional[_Union[_command_pb2.AppDeath, _Mapping]] = ...) -> None: ...

class HistoryEntry(_message.Message):
    __slots__ = ("source_id", "sequence", "received_at_ms", "received_monotonic_ns", "line", "action", "marker", "call", "death", "gap")
    SOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    LINE_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    MARKER_FIELD_NUMBER: _ClassVar[int]
    CALL_FIELD_NUMBER: _ClassVar[int]
    DEATH_FIELD_NUMBER: _ClassVar[int]
    GAP_FIELD_NUMBER: _ClassVar[int]
    source_id: str
    sequence: int
    received_at_ms: int
    received_monotonic_ns: int
    line: _device_log_pb2.LogLine
    action: _device_log_pb2.LogAction
    marker: str
    call: _event_log_pb2.LoggedEvent
    death: HistoryDeath
    gap: str
    def __init__(self, source_id: _Optional[str] = ..., sequence: _Optional[int] = ..., received_at_ms: _Optional[int] = ..., received_monotonic_ns: _Optional[int] = ..., line: _Optional[_Union[_device_log_pb2.LogLine, _Mapping]] = ..., action: _Optional[_Union[_device_log_pb2.LogAction, _Mapping]] = ..., marker: _Optional[str] = ..., call: _Optional[_Union[_event_log_pb2.LoggedEvent, _Mapping]] = ..., death: _Optional[_Union[HistoryDeath, _Mapping]] = ..., gap: _Optional[str] = ...) -> None: ...

class HistoryIssue(_message.Message):
    __slots__ = ("source_id", "kind", "message", "missing_entries")
    SOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    MISSING_ENTRIES_FIELD_NUMBER: _ClassVar[int]
    source_id: str
    kind: HistoryIssueKind
    message: str
    missing_entries: int
    def __init__(self, source_id: _Optional[str] = ..., kind: _Optional[_Union[HistoryIssueKind, str]] = ..., message: _Optional[str] = ..., missing_entries: _Optional[int] = ...) -> None: ...

class HistoryPageInfo(_message.Message):
    __slots__ = ("next_cursor", "through_time_ms")
    NEXT_CURSOR_FIELD_NUMBER: _ClassVar[int]
    THROUGH_TIME_MS_FIELD_NUMBER: _ClassVar[int]
    next_cursor: str
    through_time_ms: int
    def __init__(self, next_cursor: _Optional[str] = ..., through_time_ms: _Optional[int] = ...) -> None: ...

class ReadHistoryResponse(_message.Message):
    __slots__ = ("sources", "entries", "issues", "page")
    SOURCES_FIELD_NUMBER: _ClassVar[int]
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    ISSUES_FIELD_NUMBER: _ClassVar[int]
    PAGE_FIELD_NUMBER: _ClassVar[int]
    sources: _containers.RepeatedCompositeFieldContainer[HistorySource]
    entries: _containers.RepeatedCompositeFieldContainer[HistoryEntry]
    issues: _containers.RepeatedCompositeFieldContainer[HistoryIssue]
    page: HistoryPageInfo
    def __init__(self, sources: _Optional[_Iterable[_Union[HistorySource, _Mapping]]] = ..., entries: _Optional[_Iterable[_Union[HistoryEntry, _Mapping]]] = ..., issues: _Optional[_Iterable[_Union[HistoryIssue, _Mapping]]] = ..., page: _Optional[_Union[HistoryPageInfo, _Mapping]] = ...) -> None: ...

class ListHistorySourcesRequest(_message.Message):
    __slots__ = ("selection",)
    SELECTION_FIELD_NUMBER: _ClassVar[int]
    selection: HistorySelection
    def __init__(self, selection: _Optional[_Union[HistorySelection, _Mapping]] = ...) -> None: ...

class ListHistorySourcesResponse(_message.Message):
    __slots__ = ("sources",)
    SOURCES_FIELD_NUMBER: _ClassVar[int]
    sources: _containers.RepeatedCompositeFieldContainer[HistorySource]
    def __init__(self, sources: _Optional[_Iterable[_Union[HistorySource, _Mapping]]] = ...) -> None: ...

class HistorySpan(_message.Message):
    __slots__ = ("source_id", "after_sequence", "through_sequence")
    SOURCE_ID_FIELD_NUMBER: _ClassVar[int]
    AFTER_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    THROUGH_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    source_id: str
    after_sequence: int
    through_sequence: int
    def __init__(self, source_id: _Optional[str] = ..., after_sequence: _Optional[int] = ..., through_sequence: _Optional[int] = ...) -> None: ...

class HistoryRecording(_message.Message):
    __slots__ = ("id", "connection_id", "name", "started_at_ms", "stopped_at_ms", "spans", "issues", "retained_bytes")
    ID_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    STOPPED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    SPANS_FIELD_NUMBER: _ClassVar[int]
    ISSUES_FIELD_NUMBER: _ClassVar[int]
    RETAINED_BYTES_FIELD_NUMBER: _ClassVar[int]
    id: str
    connection_id: str
    name: str
    started_at_ms: int
    stopped_at_ms: int
    spans: _containers.RepeatedCompositeFieldContainer[HistorySpan]
    issues: _containers.RepeatedCompositeFieldContainer[HistoryIssue]
    retained_bytes: int
    def __init__(self, id: _Optional[str] = ..., connection_id: _Optional[str] = ..., name: _Optional[str] = ..., started_at_ms: _Optional[int] = ..., stopped_at_ms: _Optional[int] = ..., spans: _Optional[_Iterable[_Union[HistorySpan, _Mapping]]] = ..., issues: _Optional[_Iterable[_Union[HistoryIssue, _Mapping]]] = ..., retained_bytes: _Optional[int] = ...) -> None: ...

class StartHistoryRecordingRequest(_message.Message):
    __slots__ = ("connection_id", "name")
    CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    connection_id: str
    name: str
    def __init__(self, connection_id: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class StartHistoryRecordingResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: HistoryRecording
    def __init__(self, recording: _Optional[_Union[HistoryRecording, _Mapping]] = ...) -> None: ...

class StopHistoryRecordingRequest(_message.Message):
    __slots__ = ("recording_id",)
    RECORDING_ID_FIELD_NUMBER: _ClassVar[int]
    recording_id: str
    def __init__(self, recording_id: _Optional[str] = ...) -> None: ...

class StopHistoryRecordingResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: HistoryRecording
    def __init__(self, recording: _Optional[_Union[HistoryRecording, _Mapping]] = ...) -> None: ...

class ListHistoryRecordingsRequest(_message.Message):
    __slots__ = ("connection_ids",)
    CONNECTION_IDS_FIELD_NUMBER: _ClassVar[int]
    connection_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, connection_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class ListHistoryRecordingsResponse(_message.Message):
    __slots__ = ("recordings",)
    RECORDINGS_FIELD_NUMBER: _ClassVar[int]
    recordings: _containers.RepeatedCompositeFieldContainer[HistoryRecording]
    def __init__(self, recordings: _Optional[_Iterable[_Union[HistoryRecording, _Mapping]]] = ...) -> None: ...

class DeleteHistoryRecordingRequest(_message.Message):
    __slots__ = ("recording_id",)
    RECORDING_ID_FIELD_NUMBER: _ClassVar[int]
    recording_id: str
    def __init__(self, recording_id: _Optional[str] = ...) -> None: ...

class DeleteHistoryRecordingResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
