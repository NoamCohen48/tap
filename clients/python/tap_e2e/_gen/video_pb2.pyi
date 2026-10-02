# ruff: noqa
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class WatchVideoRequest(_message.Message):
    __slots__ = ("serial",)
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    serial: str
    def __init__(self, serial: _Optional[str] = ...) -> None: ...

class VideoHeader(_message.Message):
    __slots__ = ("stream_id", "clock_id", "width", "height", "configuration")
    STREAM_ID_FIELD_NUMBER: _ClassVar[int]
    CLOCK_ID_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    CONFIGURATION_FIELD_NUMBER: _ClassVar[int]
    stream_id: str
    clock_id: str
    width: int
    height: int
    configuration: bytes
    def __init__(self, stream_id: _Optional[str] = ..., clock_id: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., configuration: _Optional[bytes] = ...) -> None: ...

class VideoFrame(_message.Message):
    __slots__ = ("seq", "pts_us", "key_frame", "data", "received_monotonic_ns", "received_epoch_ms")
    SEQ_FIELD_NUMBER: _ClassVar[int]
    PTS_US_FIELD_NUMBER: _ClassVar[int]
    KEY_FRAME_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    seq: int
    pts_us: int
    key_frame: bool
    data: bytes
    received_monotonic_ns: int
    received_epoch_ms: int
    def __init__(self, seq: _Optional[int] = ..., pts_us: _Optional[int] = ..., key_frame: _Optional[bool] = ..., data: _Optional[bytes] = ..., received_monotonic_ns: _Optional[int] = ..., received_epoch_ms: _Optional[int] = ...) -> None: ...

class WatchVideoResponse(_message.Message):
    __slots__ = ("header", "frame")
    HEADER_FIELD_NUMBER: _ClassVar[int]
    FRAME_FIELD_NUMBER: _ClassVar[int]
    header: VideoHeader
    frame: VideoFrame
    def __init__(self, header: _Optional[_Union[VideoHeader, _Mapping]] = ..., frame: _Optional[_Union[VideoFrame, _Mapping]] = ...) -> None: ...
