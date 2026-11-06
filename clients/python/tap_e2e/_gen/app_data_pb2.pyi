# ruff: noqa
from . import app_pb2 as _app_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetCapabilitiesRequest(_message.Message):
    __slots__ = ("app",)
    APP_FIELD_NUMBER: _ClassVar[int]
    app: _app_pb2.AppTarget
    def __init__(self, app: _Optional[_Union[_app_pb2.AppTarget, _Mapping]] = ...) -> None: ...

class GetCapabilitiesResponse(_message.Message):
    __slots__ = ("available", "reason")
    AVAILABLE_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    available: bool
    reason: str
    def __init__(self, available: _Optional[bool] = ..., reason: _Optional[str] = ...) -> None: ...

class ListFilesRequest(_message.Message):
    __slots__ = ("app", "path")
    APP_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    app: _app_pb2.AppTarget
    path: str
    def __init__(self, app: _Optional[_Union[_app_pb2.AppTarget, _Mapping]] = ..., path: _Optional[str] = ...) -> None: ...

class ListFilesResponse(_message.Message):
    __slots__ = ("entries",)
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    entries: _containers.RepeatedCompositeFieldContainer[AppDataEntry]
    def __init__(self, entries: _Optional[_Iterable[_Union[AppDataEntry, _Mapping]]] = ...) -> None: ...

class ReadFileRequest(_message.Message):
    __slots__ = ("app", "path")
    APP_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    app: _app_pb2.AppTarget
    path: str
    def __init__(self, app: _Optional[_Union[_app_pb2.AppTarget, _Mapping]] = ..., path: _Optional[str] = ...) -> None: ...

class ReadFileResponse(_message.Message):
    __slots__ = ("part",)
    PART_FIELD_NUMBER: _ClassVar[int]
    part: AppDataPart
    def __init__(self, part: _Optional[_Union[AppDataPart, _Mapping]] = ...) -> None: ...

class WriteFileRequest(_message.Message):
    __slots__ = ("part",)
    PART_FIELD_NUMBER: _ClassVar[int]
    part: AppDataPart
    def __init__(self, part: _Optional[_Union[AppDataPart, _Mapping]] = ...) -> None: ...

class WriteFileResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DeleteFileRequest(_message.Message):
    __slots__ = ("app", "path")
    APP_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    app: _app_pb2.AppTarget
    path: str
    def __init__(self, app: _Optional[_Union[_app_pb2.AppTarget, _Mapping]] = ..., path: _Optional[str] = ...) -> None: ...

class DeleteFileResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ExportDatabaseRequest(_message.Message):
    __slots__ = ("app", "name")
    APP_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    app: _app_pb2.AppTarget
    name: str
    def __init__(self, app: _Optional[_Union[_app_pb2.AppTarget, _Mapping]] = ..., name: _Optional[str] = ...) -> None: ...

class ExportDatabaseResponse(_message.Message):
    __slots__ = ("part",)
    PART_FIELD_NUMBER: _ClassVar[int]
    part: AppDataPart
    def __init__(self, part: _Optional[_Union[AppDataPart, _Mapping]] = ...) -> None: ...

class ImportDatabaseRequest(_message.Message):
    __slots__ = ("part",)
    PART_FIELD_NUMBER: _ClassVar[int]
    part: AppDataPart
    def __init__(self, part: _Optional[_Union[AppDataPart, _Mapping]] = ...) -> None: ...

class ImportDatabaseResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class AppDataEntry(_message.Message):
    __slots__ = ("name", "size_bytes", "is_directory", "is_symlink")
    NAME_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    IS_DIRECTORY_FIELD_NUMBER: _ClassVar[int]
    IS_SYMLINK_FIELD_NUMBER: _ClassVar[int]
    name: str
    size_bytes: int
    is_directory: bool
    is_symlink: bool
    def __init__(self, name: _Optional[str] = ..., size_bytes: _Optional[int] = ..., is_directory: _Optional[bool] = ..., is_symlink: _Optional[bool] = ...) -> None: ...

class AppDataHeader(_message.Message):
    __slots__ = ("app", "path", "size_bytes", "overwrite")
    APP_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    OVERWRITE_FIELD_NUMBER: _ClassVar[int]
    app: _app_pb2.AppTarget
    path: str
    size_bytes: int
    overwrite: bool
    def __init__(self, app: _Optional[_Union[_app_pb2.AppTarget, _Mapping]] = ..., path: _Optional[str] = ..., size_bytes: _Optional[int] = ..., overwrite: _Optional[bool] = ...) -> None: ...

class AppDataPart(_message.Message):
    __slots__ = ("header", "chunk")
    HEADER_FIELD_NUMBER: _ClassVar[int]
    CHUNK_FIELD_NUMBER: _ClassVar[int]
    header: AppDataHeader
    chunk: bytes
    def __init__(self, header: _Optional[_Union[AppDataHeader, _Mapping]] = ..., chunk: _Optional[bytes] = ...) -> None: ...
