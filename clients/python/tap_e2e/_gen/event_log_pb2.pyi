# ruff: noqa
from . import app_pb2 as _app_pb2
from . import command_pb2 as _command_pb2
from . import failure_pb2 as _failure_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LoggedEvent(_message.Message):
    __slots__ = ("seq", "at_epoch_ms", "duration_ms", "serial", "command", "app", "device", "error", "failure")
    SEQ_FIELD_NUMBER: _ClassVar[int]
    AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    APP_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    FAILURE_FIELD_NUMBER: _ClassVar[int]
    seq: int
    at_epoch_ms: int
    duration_ms: int
    serial: str
    command: _command_pb2.Command
    app: AppCall
    device: DeviceCall
    error: _command_pb2.Error
    failure: _failure_pb2.Failure
    def __init__(self, seq: _Optional[int] = ..., at_epoch_ms: _Optional[int] = ..., duration_ms: _Optional[int] = ..., serial: _Optional[str] = ..., command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ..., app: _Optional[_Union[AppCall, _Mapping]] = ..., device: _Optional[_Union[DeviceCall, _Mapping]] = ..., error: _Optional[_Union[_command_pb2.Error, _Mapping]] = ..., failure: _Optional[_Union[_failure_pb2.Failure, _Mapping]] = ...) -> None: ...

class AppCall(_message.Message):
    __slots__ = ("operation", "package_name", "activity", "permission", "timeout_ms", "uri", "any_app", "extras", "locales")
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    URI_FIELD_NUMBER: _ClassVar[int]
    ANY_APP_FIELD_NUMBER: _ClassVar[int]
    EXTRAS_FIELD_NUMBER: _ClassVar[int]
    LOCALES_FIELD_NUMBER: _ClassVar[int]
    operation: str
    package_name: str
    activity: str
    permission: str
    timeout_ms: int
    uri: str
    any_app: bool
    extras: _containers.RepeatedCompositeFieldContainer[_app_pb2.IntentExtra]
    locales: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, operation: _Optional[str] = ..., package_name: _Optional[str] = ..., activity: _Optional[str] = ..., permission: _Optional[str] = ..., timeout_ms: _Optional[int] = ..., uri: _Optional[str] = ..., any_app: _Optional[bool] = ..., extras: _Optional[_Iterable[_Union[_app_pb2.IntentExtra, _Mapping]]] = ..., locales: _Optional[_Iterable[str]] = ...) -> None: ...

class DeviceCall(_message.Message):
    __slots__ = ("operation", "enabled", "font_scale", "density_dpi", "airplane_mode", "wifi", "mobile_data", "locales", "latitude", "longitude", "device_path", "size_bytes", "high_contrast_text", "color_inversion", "bold_text")
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    FONT_SCALE_FIELD_NUMBER: _ClassVar[int]
    DENSITY_DPI_FIELD_NUMBER: _ClassVar[int]
    AIRPLANE_MODE_FIELD_NUMBER: _ClassVar[int]
    WIFI_FIELD_NUMBER: _ClassVar[int]
    MOBILE_DATA_FIELD_NUMBER: _ClassVar[int]
    LOCALES_FIELD_NUMBER: _ClassVar[int]
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    DEVICE_PATH_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    HIGH_CONTRAST_TEXT_FIELD_NUMBER: _ClassVar[int]
    COLOR_INVERSION_FIELD_NUMBER: _ClassVar[int]
    BOLD_TEXT_FIELD_NUMBER: _ClassVar[int]
    operation: str
    enabled: bool
    font_scale: float
    density_dpi: int
    airplane_mode: bool
    wifi: bool
    mobile_data: bool
    locales: _containers.RepeatedScalarFieldContainer[str]
    latitude: float
    longitude: float
    device_path: str
    size_bytes: int
    high_contrast_text: bool
    color_inversion: bool
    bold_text: bool
    def __init__(self, operation: _Optional[str] = ..., enabled: _Optional[bool] = ..., font_scale: _Optional[float] = ..., density_dpi: _Optional[int] = ..., airplane_mode: _Optional[bool] = ..., wifi: _Optional[bool] = ..., mobile_data: _Optional[bool] = ..., locales: _Optional[_Iterable[str]] = ..., latitude: _Optional[float] = ..., longitude: _Optional[float] = ..., device_path: _Optional[str] = ..., size_bytes: _Optional[int] = ..., high_contrast_text: _Optional[bool] = ..., color_inversion: _Optional[bool] = ..., bold_text: _Optional[bool] = ...) -> None: ...
