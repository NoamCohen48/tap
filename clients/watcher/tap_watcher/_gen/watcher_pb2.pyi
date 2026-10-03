# ruff: noqa
from tap_e2e.proto import device_pb2 as _device_pb2
from tap_e2e.proto import watch_pb2 as _watch_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class RecordingKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RECORDING_KIND_UNSPECIFIED: _ClassVar[RecordingKind]
    RECORDING_KIND_RECORDING: _ClassVar[RecordingKind]
    RECORDING_KIND_CLIP: _ClassVar[RecordingKind]
RECORDING_KIND_UNSPECIFIED: RecordingKind
RECORDING_KIND_RECORDING: RecordingKind
RECORDING_KIND_CLIP: RecordingKind

class StatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StatusResponse(_message.Message):
    __slots__ = ("daemon_connected", "daemon_error", "recordings", "history")
    DAEMON_CONNECTED_FIELD_NUMBER: _ClassVar[int]
    DAEMON_ERROR_FIELD_NUMBER: _ClassVar[int]
    RECORDINGS_FIELD_NUMBER: _ClassVar[int]
    HISTORY_FIELD_NUMBER: _ClassVar[int]
    daemon_connected: bool
    daemon_error: str
    recordings: _containers.RepeatedCompositeFieldContainer[ActiveRecording]
    history: VideoHistory
    def __init__(self, daemon_connected: _Optional[bool] = ..., daemon_error: _Optional[str] = ..., recordings: _Optional[_Iterable[_Union[ActiveRecording, _Mapping]]] = ..., history: _Optional[_Union[VideoHistory, _Mapping]] = ...) -> None: ...

class VideoHistory(_message.Message):
    __slots__ = ("max_seconds", "max_bytes")
    MAX_SECONDS_FIELD_NUMBER: _ClassVar[int]
    MAX_BYTES_FIELD_NUMBER: _ClassVar[int]
    max_seconds: int
    max_bytes: int
    def __init__(self, max_seconds: _Optional[int] = ..., max_bytes: _Optional[int] = ...) -> None: ...

class ActiveRecording(_message.Message):
    __slots__ = ("serial", "started_epoch_ms", "bytes", "seconds")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    STARTED_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    SECONDS_FIELD_NUMBER: _ClassVar[int]
    serial: str
    started_epoch_ms: int
    bytes: int
    seconds: float
    def __init__(self, serial: _Optional[str] = ..., started_epoch_ms: _Optional[int] = ..., bytes: _Optional[int] = ..., seconds: _Optional[float] = ...) -> None: ...

class Recording(_message.Message):
    __slots__ = ("id", "kind", "serial", "created_epoch_ms", "duration_seconds", "bytes", "connection_names", "actions", "failures", "ended", "parts")
    ID_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    CREATED_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    DURATION_SECONDS_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    CONNECTION_NAMES_FIELD_NUMBER: _ClassVar[int]
    ACTIONS_FIELD_NUMBER: _ClassVar[int]
    FAILURES_FIELD_NUMBER: _ClassVar[int]
    ENDED_FIELD_NUMBER: _ClassVar[int]
    PARTS_FIELD_NUMBER: _ClassVar[int]
    id: str
    kind: RecordingKind
    serial: str
    created_epoch_ms: int
    duration_seconds: float
    bytes: int
    connection_names: _containers.RepeatedScalarFieldContainer[str]
    actions: int
    failures: int
    ended: str
    parts: int
    def __init__(self, id: _Optional[str] = ..., kind: _Optional[_Union[RecordingKind, str]] = ..., serial: _Optional[str] = ..., created_epoch_ms: _Optional[int] = ..., duration_seconds: _Optional[float] = ..., bytes: _Optional[int] = ..., connection_names: _Optional[_Iterable[str]] = ..., actions: _Optional[int] = ..., failures: _Optional[int] = ..., ended: _Optional[str] = ..., parts: _Optional[int] = ...) -> None: ...

class StartRecordingRequest(_message.Message):
    __slots__ = ("serial",)
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    serial: str
    def __init__(self, serial: _Optional[str] = ...) -> None: ...

class StartRecordingResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: ActiveRecording
    def __init__(self, recording: _Optional[_Union[ActiveRecording, _Mapping]] = ...) -> None: ...

class StopRecordingRequest(_message.Message):
    __slots__ = ("serial",)
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    serial: str
    def __init__(self, serial: _Optional[str] = ...) -> None: ...

class StopRecordingResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ...) -> None: ...

class SaveClipRequest(_message.Message):
    __slots__ = ("serial", "from_stream_id", "from_seq", "to_stream_id", "to_seq")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    FROM_STREAM_ID_FIELD_NUMBER: _ClassVar[int]
    FROM_SEQ_FIELD_NUMBER: _ClassVar[int]
    TO_STREAM_ID_FIELD_NUMBER: _ClassVar[int]
    TO_SEQ_FIELD_NUMBER: _ClassVar[int]
    serial: str
    from_stream_id: str
    from_seq: int
    to_stream_id: str
    to_seq: int
    def __init__(self, serial: _Optional[str] = ..., from_stream_id: _Optional[str] = ..., from_seq: _Optional[int] = ..., to_stream_id: _Optional[str] = ..., to_seq: _Optional[int] = ...) -> None: ...

class SaveClipResponse(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ...) -> None: ...

class ListRecordingsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListRecordingsResponse(_message.Message):
    __slots__ = ("recordings", "directory", "used_bytes", "max_bytes", "keep_days")
    RECORDINGS_FIELD_NUMBER: _ClassVar[int]
    DIRECTORY_FIELD_NUMBER: _ClassVar[int]
    USED_BYTES_FIELD_NUMBER: _ClassVar[int]
    MAX_BYTES_FIELD_NUMBER: _ClassVar[int]
    KEEP_DAYS_FIELD_NUMBER: _ClassVar[int]
    recordings: _containers.RepeatedCompositeFieldContainer[Recording]
    directory: str
    used_bytes: int
    max_bytes: int
    keep_days: int
    def __init__(self, recordings: _Optional[_Iterable[_Union[Recording, _Mapping]]] = ..., directory: _Optional[str] = ..., used_bytes: _Optional[int] = ..., max_bytes: _Optional[int] = ..., keep_days: _Optional[int] = ...) -> None: ...

class DeleteRecordingsRequest(_message.Message):
    __slots__ = ("ids",)
    IDS_FIELD_NUMBER: _ClassVar[int]
    ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, ids: _Optional[_Iterable[str]] = ...) -> None: ...

class DeleteRecordingsResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
