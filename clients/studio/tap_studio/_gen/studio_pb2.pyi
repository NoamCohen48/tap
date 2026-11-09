# ruff: noqa
import datetime

from tap_e2e.proto import command_pb2 as _command_pb2
from tap_e2e.proto import device_pb2 as _device_pb2
from tap_e2e.proto import event_log_pb2 as _event_log_pb2
from tap_e2e.proto import failure_pb2 as _failure_pb2
from google.protobuf import timestamp_pb2 as _timestamp_pb2
from tap_e2e.proto import selector_pb2 as _selector_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AppQuery(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    APP_QUERY_UNSPECIFIED: _ClassVar[AppQuery]
    APP_QUERY_INSTALLED: _ClassVar[AppQuery]
    APP_QUERY_RUNNING: _ClassVar[AppQuery]
    APP_QUERY_PROCESS: _ClassVar[AppQuery]
    APP_QUERY_LOCALES: _ClassVar[AppQuery]
    APP_QUERY_PERMISSION: _ClassVar[AppQuery]

class PrivateDataOperation(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PRIVATE_DATA_OPERATION_UNSPECIFIED: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_CAPABILITIES: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_LIST: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_READ: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_WRITE: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_DELETE: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_EXPORT_DATABASE: _ClassVar[PrivateDataOperation]
    PRIVATE_DATA_IMPORT_DATABASE: _ClassVar[PrivateDataOperation]

class DiagnosticKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DIAGNOSTIC_UNSPECIFIED: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_SCREENSHOT: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_HIERARCHY: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_DEVICE_LOGS: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_DRIVER_LOG: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_EVENTS: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_SERVER_INFO: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_DEVICE_INFO: _ClassVar[DiagnosticKind]
    DIAGNOSTIC_DEVICES: _ClassVar[DiagnosticKind]

class RunStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RUN_STATUS_UNSPECIFIED: _ClassVar[RunStatus]
    RUN_STATUS_RUNNING: _ClassVar[RunStatus]
    RUN_STATUS_PASSED: _ClassVar[RunStatus]
    RUN_STATUS_FAILED: _ClassVar[RunStatus]
    RUN_STATUS_STOPPED: _ClassVar[RunStatus]

class SelectorOrigin(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SELECTOR_ORIGIN_UNSPECIFIED: _ClassVar[SelectorOrigin]
    SELECTOR_ORIGIN_SYNTHESIZED: _ClassVar[SelectorOrigin]
    SELECTOR_ORIGIN_ALTERNATIVE: _ClassVar[SelectorOrigin]
    SELECTOR_ORIGIN_EDITED: _ClassVar[SelectorOrigin]

class Condition(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CONDITION_UNSPECIFIED: _ClassVar[Condition]
    CONDITION_VISIBLE: _ClassVar[Condition]
    CONDITION_ONE: _ClassVar[Condition]
    CONDITION_GONE: _ClassVar[Condition]
    CONDITION_ENABLED: _ClassVar[Condition]
    CONDITION_DISABLED: _ClassVar[Condition]
    CONDITION_CHECKED: _ClassVar[Condition]
    CONDITION_UNCHECKED: _ClassVar[Condition]
    CONDITION_FOCUSED: _ClassVar[Condition]
    CONDITION_TEXT_EQUALS: _ClassVar[Condition]
    CONDITION_TEXT_CONTAINS: _ClassVar[Condition]
    CONDITION_COUNT: _ClassVar[Condition]

class Check(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CHECK_UNSPECIFIED: _ClassVar[Check]
    CHECK_EXISTS: _ClassVar[Check]
    CHECK_COUNT: _ClassVar[Check]
    CHECK_TEXT_EQUALS: _ClassVar[Check]
    CHECK_TEXT_CONTAINS: _ClassVar[Check]
    CHECK_ENABLED: _ClassVar[Check]
    CHECK_DISABLED: _ClassVar[Check]
    CHECK_CHECKED: _ClassVar[Check]
    CHECK_UNCHECKED: _ClassVar[Check]
    CHECK_FOCUSED: _ClassVar[Check]

class DeviceCheck(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DEVICE_CHECK_UNSPECIFIED: _ClassVar[DeviceCheck]
    DEVICE_CHECK_FOREGROUND_ACTIVITY: _ClassVar[DeviceCheck]
    DEVICE_CHECK_KEYBOARD_SHOWN: _ClassVar[DeviceCheck]
    DEVICE_CHECK_KEYBOARD_HIDDEN: _ClassVar[DeviceCheck]
    DEVICE_CHECK_CLIPBOARD_EQUALS: _ClassVar[DeviceCheck]
APP_QUERY_UNSPECIFIED: AppQuery
APP_QUERY_INSTALLED: AppQuery
APP_QUERY_RUNNING: AppQuery
APP_QUERY_PROCESS: AppQuery
APP_QUERY_LOCALES: AppQuery
APP_QUERY_PERMISSION: AppQuery
PRIVATE_DATA_OPERATION_UNSPECIFIED: PrivateDataOperation
PRIVATE_DATA_CAPABILITIES: PrivateDataOperation
PRIVATE_DATA_LIST: PrivateDataOperation
PRIVATE_DATA_READ: PrivateDataOperation
PRIVATE_DATA_WRITE: PrivateDataOperation
PRIVATE_DATA_DELETE: PrivateDataOperation
PRIVATE_DATA_EXPORT_DATABASE: PrivateDataOperation
PRIVATE_DATA_IMPORT_DATABASE: PrivateDataOperation
DIAGNOSTIC_UNSPECIFIED: DiagnosticKind
DIAGNOSTIC_SCREENSHOT: DiagnosticKind
DIAGNOSTIC_HIERARCHY: DiagnosticKind
DIAGNOSTIC_DEVICE_LOGS: DiagnosticKind
DIAGNOSTIC_DRIVER_LOG: DiagnosticKind
DIAGNOSTIC_EVENTS: DiagnosticKind
DIAGNOSTIC_SERVER_INFO: DiagnosticKind
DIAGNOSTIC_DEVICE_INFO: DiagnosticKind
DIAGNOSTIC_DEVICES: DiagnosticKind
RUN_STATUS_UNSPECIFIED: RunStatus
RUN_STATUS_RUNNING: RunStatus
RUN_STATUS_PASSED: RunStatus
RUN_STATUS_FAILED: RunStatus
RUN_STATUS_STOPPED: RunStatus
SELECTOR_ORIGIN_UNSPECIFIED: SelectorOrigin
SELECTOR_ORIGIN_SYNTHESIZED: SelectorOrigin
SELECTOR_ORIGIN_ALTERNATIVE: SelectorOrigin
SELECTOR_ORIGIN_EDITED: SelectorOrigin
CONDITION_UNSPECIFIED: Condition
CONDITION_VISIBLE: Condition
CONDITION_ONE: Condition
CONDITION_GONE: Condition
CONDITION_ENABLED: Condition
CONDITION_DISABLED: Condition
CONDITION_CHECKED: Condition
CONDITION_UNCHECKED: Condition
CONDITION_FOCUSED: Condition
CONDITION_TEXT_EQUALS: Condition
CONDITION_TEXT_CONTAINS: Condition
CONDITION_COUNT: Condition
CHECK_UNSPECIFIED: Check
CHECK_EXISTS: Check
CHECK_COUNT: Check
CHECK_TEXT_EQUALS: Check
CHECK_TEXT_CONTAINS: Check
CHECK_ENABLED: Check
CHECK_DISABLED: Check
CHECK_CHECKED: Check
CHECK_UNCHECKED: Check
CHECK_FOCUSED: Check
DEVICE_CHECK_UNSPECIFIED: DeviceCheck
DEVICE_CHECK_FOREGROUND_ACTIVITY: DeviceCheck
DEVICE_CHECK_KEYBOARD_SHOWN: DeviceCheck
DEVICE_CHECK_KEYBOARD_HIDDEN: DeviceCheck
DEVICE_CHECK_CLIPBOARD_EQUALS: DeviceCheck

class LibraryEntry(_message.Message):
    __slots__ = ("id", "name", "revision", "updated_at", "steps")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    UPDATED_AT_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    revision: int
    updated_at: _timestamp_pb2.Timestamp
    steps: int
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., revision: _Optional[int] = ..., updated_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., steps: _Optional[int] = ...) -> None: ...

class ListLibraryRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListLibraryResponse(_message.Message):
    __slots__ = ("entries", "unreadable")
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    UNREADABLE_FIELD_NUMBER: _ClassVar[int]
    entries: _containers.RepeatedCompositeFieldContainer[LibraryEntry]
    unreadable: int
    def __init__(self, entries: _Optional[_Iterable[_Union[LibraryEntry, _Mapping]]] = ..., unreadable: _Optional[int] = ...) -> None: ...

class SaveToLibraryRequest(_message.Message):
    __slots__ = ("name", "as_new")
    NAME_FIELD_NUMBER: _ClassVar[int]
    AS_NEW_FIELD_NUMBER: _ClassVar[int]
    name: str
    as_new: bool
    def __init__(self, name: _Optional[str] = ..., as_new: _Optional[bool] = ...) -> None: ...

class SaveToLibraryResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class OpenFromLibraryRequest(_message.Message):
    __slots__ = ("id",)
    ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    def __init__(self, id: _Optional[str] = ...) -> None: ...

class OpenFromLibraryResponse(_message.Message):
    __slots__ = ("session", "recording", "missing_secrets")
    SESSION_FIELD_NUMBER: _ClassVar[int]
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    session: Session
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ..., recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class RenameInLibraryRequest(_message.Message):
    __slots__ = ("id", "name", "revision")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    revision: int
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., revision: _Optional[int] = ...) -> None: ...

class RenameInLibraryResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class DeleteFromLibraryRequest(_message.Message):
    __slots__ = ("id", "revision")
    ID_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    id: str
    revision: int
    def __init__(self, id: _Optional[str] = ..., revision: _Optional[int] = ...) -> None: ...

class DeleteFromLibraryResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class SetAutosaveRequest(_message.Message):
    __slots__ = ("enabled",)
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    enabled: bool
    def __init__(self, enabled: _Optional[bool] = ...) -> None: ...

class SetAutosaveResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class QueryAppRequest(_message.Message):
    __slots__ = ("package_name", "query", "permission", "timeout_ms")
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    QUERY_FIELD_NUMBER: _ClassVar[int]
    PERMISSION_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    package_name: str
    query: AppQuery
    permission: str
    timeout_ms: int
    def __init__(self, package_name: _Optional[str] = ..., query: _Optional[_Union[AppQuery, str]] = ..., permission: _Optional[str] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class QueryAppResponse(_message.Message):
    __slots__ = ("json",)
    JSON_FIELD_NUMBER: _ClassVar[int]
    json: str
    def __init__(self, json: _Optional[str] = ...) -> None: ...

class DatabaseBundle(_message.Message):
    __slots__ = ("format", "name", "database", "wal", "journal")
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DATABASE_FIELD_NUMBER: _ClassVar[int]
    WAL_FIELD_NUMBER: _ClassVar[int]
    JOURNAL_FIELD_NUMBER: _ClassVar[int]
    format: str
    name: str
    database: bytes
    wal: bytes
    journal: bytes
    def __init__(self, format: _Optional[str] = ..., name: _Optional[str] = ..., database: _Optional[bytes] = ..., wal: _Optional[bytes] = ..., journal: _Optional[bytes] = ...) -> None: ...

class PrivateDataRequest(_message.Message):
    __slots__ = ("package_name", "operation", "path", "name", "data", "bundle", "overwrite", "confirmed", "timeout_ms")
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    BUNDLE_FIELD_NUMBER: _ClassVar[int]
    OVERWRITE_FIELD_NUMBER: _ClassVar[int]
    CONFIRMED_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    package_name: str
    operation: PrivateDataOperation
    path: str
    name: str
    data: bytes
    bundle: DatabaseBundle
    overwrite: bool
    confirmed: bool
    timeout_ms: int
    def __init__(self, package_name: _Optional[str] = ..., operation: _Optional[_Union[PrivateDataOperation, str]] = ..., path: _Optional[str] = ..., name: _Optional[str] = ..., data: _Optional[bytes] = ..., bundle: _Optional[_Union[DatabaseBundle, _Mapping]] = ..., overwrite: _Optional[bool] = ..., confirmed: _Optional[bool] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class PrivateDataResponse(_message.Message):
    __slots__ = ("json", "data", "bundle")
    JSON_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    BUNDLE_FIELD_NUMBER: _ClassVar[int]
    json: str
    data: bytes
    bundle: DatabaseBundle
    def __init__(self, json: _Optional[str] = ..., data: _Optional[bytes] = ..., bundle: _Optional[_Union[DatabaseBundle, _Mapping]] = ...) -> None: ...

class DiagnosticRequest(_message.Message):
    __slots__ = ("kind", "after_seq")
    KIND_FIELD_NUMBER: _ClassVar[int]
    AFTER_SEQ_FIELD_NUMBER: _ClassVar[int]
    kind: DiagnosticKind
    after_seq: int
    def __init__(self, kind: _Optional[_Union[DiagnosticKind, str]] = ..., after_seq: _Optional[int] = ...) -> None: ...

class DiagnosticResponse(_message.Message):
    __slots__ = ("data", "filename", "content_type")
    DATA_FIELD_NUMBER: _ClassVar[int]
    FILENAME_FIELD_NUMBER: _ClassVar[int]
    CONTENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    data: bytes
    filename: str
    content_type: str
    def __init__(self, data: _Optional[bytes] = ..., filename: _Optional[str] = ..., content_type: _Optional[str] = ...) -> None: ...

class InfoRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InfoResponse(_message.Message):
    __slots__ = ("recorder", "format")
    RECORDER_FIELD_NUMBER: _ClassVar[int]
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    recorder: str
    format: str
    def __init__(self, recorder: _Optional[str] = ..., format: _Optional[str] = ...) -> None: ...

class AttachedDevice(_message.Message):
    __slots__ = ("serial", "api_level", "manufacturer", "model", "display_width", "display_height", "quarantine_reason")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    API_LEVEL_FIELD_NUMBER: _ClassVar[int]
    MANUFACTURER_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_WIDTH_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    QUARANTINE_REASON_FIELD_NUMBER: _ClassVar[int]
    serial: str
    api_level: int
    manufacturer: str
    model: str
    display_width: int
    display_height: int
    quarantine_reason: str
    def __init__(self, serial: _Optional[str] = ..., api_level: _Optional[int] = ..., manufacturer: _Optional[str] = ..., model: _Optional[str] = ..., display_width: _Optional[int] = ..., display_height: _Optional[int] = ..., quarantine_reason: _Optional[str] = ...) -> None: ...

class Session(_message.Message):
    __slots__ = ("device", "recording", "steps", "controller_tab", "operation", "version", "recording_revision", "can_undo", "can_redo", "library")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    CONTROLLER_TAB_FIELD_NUMBER: _ClassVar[int]
    OPERATION_FIELD_NUMBER: _ClassVar[int]
    VERSION_FIELD_NUMBER: _ClassVar[int]
    RECORDING_REVISION_FIELD_NUMBER: _ClassVar[int]
    CAN_UNDO_FIELD_NUMBER: _ClassVar[int]
    CAN_REDO_FIELD_NUMBER: _ClassVar[int]
    LIBRARY_FIELD_NUMBER: _ClassVar[int]
    device: AttachedDevice
    recording: bool
    steps: int
    controller_tab: str
    operation: str
    version: int
    recording_revision: int
    can_undo: bool
    can_redo: bool
    library: LibraryLink
    def __init__(self, device: _Optional[_Union[AttachedDevice, _Mapping]] = ..., recording: _Optional[bool] = ..., steps: _Optional[int] = ..., controller_tab: _Optional[str] = ..., operation: _Optional[str] = ..., version: _Optional[int] = ..., recording_revision: _Optional[int] = ..., can_undo: _Optional[bool] = ..., can_redo: _Optional[bool] = ..., library: _Optional[_Union[LibraryLink, _Mapping]] = ...) -> None: ...

class LibraryLink(_message.Message):
    __slots__ = ("id", "name", "revision", "unsaved", "autosave", "autosave_error")
    ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    REVISION_FIELD_NUMBER: _ClassVar[int]
    UNSAVED_FIELD_NUMBER: _ClassVar[int]
    AUTOSAVE_FIELD_NUMBER: _ClassVar[int]
    AUTOSAVE_ERROR_FIELD_NUMBER: _ClassVar[int]
    id: str
    name: str
    revision: int
    unsaved: bool
    autosave: bool
    autosave_error: str
    def __init__(self, id: _Optional[str] = ..., name: _Optional[str] = ..., revision: _Optional[int] = ..., unsaved: _Optional[bool] = ..., autosave: _Optional[bool] = ..., autosave_error: _Optional[str] = ...) -> None: ...

class GetSessionRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetSessionResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class WatchSessionRequest(_message.Message):
    __slots__ = ("tab",)
    TAB_FIELD_NUMBER: _ClassVar[int]
    tab: str
    def __init__(self, tab: _Optional[str] = ...) -> None: ...

class WatchSessionResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class TakeControlRequest(_message.Message):
    __slots__ = ("tab",)
    TAB_FIELD_NUMBER: _ClassVar[int]
    tab: str
    def __init__(self, tab: _Optional[str] = ...) -> None: ...

class TakeControlResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class ListDevicesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DeviceChoice(_message.Message):
    __slots__ = ("serial", "state", "attached", "quarantine_reason", "api_level", "model", "emulator", "retry_on_attach", "under_inspection")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_FIELD_NUMBER: _ClassVar[int]
    QUARANTINE_REASON_FIELD_NUMBER: _ClassVar[int]
    API_LEVEL_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    EMULATOR_FIELD_NUMBER: _ClassVar[int]
    RETRY_ON_ATTACH_FIELD_NUMBER: _ClassVar[int]
    UNDER_INSPECTION_FIELD_NUMBER: _ClassVar[int]
    serial: str
    state: _device_pb2.DeviceState
    attached: bool
    quarantine_reason: str
    api_level: int
    model: str
    emulator: bool
    retry_on_attach: bool
    under_inspection: bool
    def __init__(self, serial: _Optional[str] = ..., state: _Optional[_Union[_device_pb2.DeviceState, str]] = ..., attached: _Optional[bool] = ..., quarantine_reason: _Optional[str] = ..., api_level: _Optional[int] = ..., model: _Optional[str] = ..., emulator: _Optional[bool] = ..., retry_on_attach: _Optional[bool] = ..., under_inspection: _Optional[bool] = ...) -> None: ...

class ListDevicesResponse(_message.Message):
    __slots__ = ("devices",)
    DEVICES_FIELD_NUMBER: _ClassVar[int]
    devices: _containers.RepeatedCompositeFieldContainer[DeviceChoice]
    def __init__(self, devices: _Optional[_Iterable[_Union[DeviceChoice, _Mapping]]] = ...) -> None: ...

class AttachRequest(_message.Message):
    __slots__ = ("serial", "inspect_quarantined")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    INSPECT_QUARANTINED_FIELD_NUMBER: _ClassVar[int]
    serial: str
    inspect_quarantined: bool
    def __init__(self, serial: _Optional[str] = ..., inspect_quarantined: _Optional[bool] = ...) -> None: ...

class UnquarantineRequest(_message.Message):
    __slots__ = ("serial",)
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    serial: str
    def __init__(self, serial: _Optional[str] = ...) -> None: ...

class UnquarantineResponse(_message.Message):
    __slots__ = ("quarantine_reason", "cleaned", "remaining")
    QUARANTINE_REASON_FIELD_NUMBER: _ClassVar[int]
    CLEANED_FIELD_NUMBER: _ClassVar[int]
    REMAINING_FIELD_NUMBER: _ClassVar[int]
    quarantine_reason: str
    cleaned: _containers.RepeatedScalarFieldContainer[str]
    remaining: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, quarantine_reason: _Optional[str] = ..., cleaned: _Optional[_Iterable[str]] = ..., remaining: _Optional[_Iterable[str]] = ...) -> None: ...

class AttachResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class ReleaseRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ReleaseResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class FramesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FramesResponse(_message.Message):
    __slots__ = ("sequence", "taken_at", "png", "width", "height", "rotation", "snapshot_id", "nodes", "moving")
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    TAKEN_AT_FIELD_NUMBER: _ClassVar[int]
    PNG_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_ID_FIELD_NUMBER: _ClassVar[int]
    NODES_FIELD_NUMBER: _ClassVar[int]
    MOVING_FIELD_NUMBER: _ClassVar[int]
    sequence: int
    taken_at: _timestamp_pb2.Timestamp
    png: bytes
    width: int
    height: int
    rotation: int
    snapshot_id: int
    nodes: _containers.RepeatedCompositeFieldContainer[_device_pb2.ScreenNode]
    moving: bool
    def __init__(self, sequence: _Optional[int] = ..., taken_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., png: _Optional[bytes] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., rotation: _Optional[int] = ..., snapshot_id: _Optional[int] = ..., nodes: _Optional[_Iterable[_Union[_device_pb2.ScreenNode, _Mapping]]] = ..., moving: _Optional[bool] = ...) -> None: ...

class CountRequest(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class CountResponse(_message.Message):
    __slots__ = ("count",)
    COUNT_FIELD_NUMBER: _ClassVar[int]
    count: int
    def __init__(self, count: _Optional[int] = ...) -> None: ...

class DescribeElementRequest(_message.Message):
    __slots__ = ("selector",)
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ...) -> None: ...

class DescribeElementResponse(_message.Message):
    __slots__ = ("actions", "custom_actions", "range")
    ACTIONS_FIELD_NUMBER: _ClassVar[int]
    CUSTOM_ACTIONS_FIELD_NUMBER: _ClassVar[int]
    RANGE_FIELD_NUMBER: _ClassVar[int]
    actions: _containers.RepeatedScalarFieldContainer[_command_pb2.StandardAction]
    custom_actions: _containers.RepeatedScalarFieldContainer[str]
    range: _command_pb2.Range
    def __init__(self, actions: _Optional[_Iterable[_Union[_command_pb2.StandardAction, str]]] = ..., custom_actions: _Optional[_Iterable[str]] = ..., range: _Optional[_Union[_command_pb2.Range, _Mapping]] = ...) -> None: ...

class GetDeviceStatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetDeviceStatusResponse(_message.Message):
    __slots__ = ("info", "foreground_package", "foreground_activity")
    INFO_FIELD_NUMBER: _ClassVar[int]
    FOREGROUND_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    FOREGROUND_ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    info: _command_pb2.DeviceInfo
    foreground_package: str
    foreground_activity: str
    def __init__(self, info: _Optional[_Union[_command_pb2.DeviceInfo, _Mapping]] = ..., foreground_package: _Optional[str] = ..., foreground_activity: _Optional[str] = ...) -> None: ...

class ListNotificationsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListNotificationsResponse(_message.Message):
    __slots__ = ("notifications",)
    NOTIFICATIONS_FIELD_NUMBER: _ClassVar[int]
    notifications: _containers.RepeatedCompositeFieldContainer[_command_pb2.DeviceNotification]
    def __init__(self, notifications: _Optional[_Iterable[_Union[_command_pb2.DeviceNotification, _Mapping]]] = ...) -> None: ...

class PerformRequest(_message.Message):
    __slots__ = ("step", "secret_value", "before_step_id", "skip_recording", "precondition_timeout_ms")
    STEP_FIELD_NUMBER: _ClassVar[int]
    SECRET_VALUE_FIELD_NUMBER: _ClassVar[int]
    BEFORE_STEP_ID_FIELD_NUMBER: _ClassVar[int]
    SKIP_RECORDING_FIELD_NUMBER: _ClassVar[int]
    PRECONDITION_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    step: Step
    secret_value: str
    before_step_id: str
    skip_recording: bool
    precondition_timeout_ms: int
    def __init__(self, step: _Optional[_Union[Step, _Mapping]] = ..., secret_value: _Optional[str] = ..., before_step_id: _Optional[str] = ..., skip_recording: _Optional[bool] = ..., precondition_timeout_ms: _Optional[int] = ...) -> None: ...

class PerformResponse(_message.Message):
    __slots__ = ("step", "recorded", "message")
    STEP_FIELD_NUMBER: _ClassVar[int]
    RECORDED_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    step: Step
    recorded: bool
    message: str
    def __init__(self, step: _Optional[_Union[Step, _Mapping]] = ..., recorded: _Optional[bool] = ..., message: _Optional[str] = ...) -> None: ...

class AddStepRequest(_message.Message):
    __slots__ = ("step", "secret_value", "before_step_id")
    STEP_FIELD_NUMBER: _ClassVar[int]
    SECRET_VALUE_FIELD_NUMBER: _ClassVar[int]
    BEFORE_STEP_ID_FIELD_NUMBER: _ClassVar[int]
    step: Step
    secret_value: str
    before_step_id: str
    def __init__(self, step: _Optional[_Union[Step, _Mapping]] = ..., secret_value: _Optional[str] = ..., before_step_id: _Optional[str] = ...) -> None: ...

class AddStepResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class UpdateStepRequest(_message.Message):
    __slots__ = ("step", "secret_value")
    STEP_FIELD_NUMBER: _ClassVar[int]
    SECRET_VALUE_FIELD_NUMBER: _ClassVar[int]
    step: Step
    secret_value: str
    def __init__(self, step: _Optional[_Union[Step, _Mapping]] = ..., secret_value: _Optional[str] = ...) -> None: ...

class UpdateStepResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class DeleteStepRequest(_message.Message):
    __slots__ = ("step_id",)
    STEP_ID_FIELD_NUMBER: _ClassVar[int]
    step_id: str
    def __init__(self, step_id: _Optional[str] = ...) -> None: ...

class DeleteStepResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class MoveStepRequest(_message.Message):
    __slots__ = ("step_id", "before_step_id")
    STEP_ID_FIELD_NUMBER: _ClassVar[int]
    BEFORE_STEP_ID_FIELD_NUMBER: _ClassVar[int]
    step_id: str
    before_step_id: str
    def __init__(self, step_id: _Optional[str] = ..., before_step_id: _Optional[str] = ...) -> None: ...

class MoveStepResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class DuplicateStepRequest(_message.Message):
    __slots__ = ("step_id",)
    STEP_ID_FIELD_NUMBER: _ClassVar[int]
    step_id: str
    def __init__(self, step_id: _Optional[str] = ...) -> None: ...

class DuplicateStepResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class UndoRecordingRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class UndoRecordingResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class RedoRecordingRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RedoRecordingResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class SetParametersRequest(_message.Message):
    __slots__ = ("parameters",)
    PARAMETERS_FIELD_NUMBER: _ClassVar[int]
    parameters: _containers.RepeatedCompositeFieldContainer[Parameter]
    def __init__(self, parameters: _Optional[_Iterable[_Union[Parameter, _Mapping]]] = ...) -> None: ...

class SetParametersResponse(_message.Message):
    __slots__ = ("recording", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class OpenRecordingRequest(_message.Message):
    __slots__ = ("document",)
    DOCUMENT_FIELD_NUMBER: _ClassVar[int]
    document: str
    def __init__(self, document: _Optional[str] = ...) -> None: ...

class OpenRecordingResponse(_message.Message):
    __slots__ = ("session", "recording", "missing_secrets")
    SESSION_FIELD_NUMBER: _ClassVar[int]
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    session: Session
    recording: Recording
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ..., recording: _Optional[_Union[Recording, _Mapping]] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class ReplayRequest(_message.Message):
    __slots__ = ("from_step_id", "only", "secret_values")
    class SecretValuesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    FROM_STEP_ID_FIELD_NUMBER: _ClassVar[int]
    ONLY_FIELD_NUMBER: _ClassVar[int]
    SECRET_VALUES_FIELD_NUMBER: _ClassVar[int]
    from_step_id: str
    only: bool
    secret_values: _containers.ScalarMap[str, str]
    def __init__(self, from_step_id: _Optional[str] = ..., only: _Optional[bool] = ..., secret_values: _Optional[_Mapping[str, str]] = ...) -> None: ...

class ReplayResponse(_message.Message):
    __slots__ = ("step_id", "outcome", "message", "run_id")
    STEP_ID_FIELD_NUMBER: _ClassVar[int]
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    RUN_ID_FIELD_NUMBER: _ClassVar[int]
    step_id: str
    outcome: Outcome
    message: str
    run_id: str
    def __init__(self, step_id: _Optional[str] = ..., outcome: _Optional[_Union[Outcome, _Mapping]] = ..., message: _Optional[str] = ..., run_id: _Optional[str] = ...) -> None: ...

class ExportPytestRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ExportPytestResponse(_message.Message):
    __slots__ = ("source",)
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    source: str
    def __init__(self, source: _Optional[str] = ...) -> None: ...

class ListRunsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListRunsResponse(_message.Message):
    __slots__ = ("runs",)
    RUNS_FIELD_NUMBER: _ClassVar[int]
    runs: _containers.RepeatedCompositeFieldContainer[RunResult]
    def __init__(self, runs: _Optional[_Iterable[_Union[RunResult, _Mapping]]] = ...) -> None: ...

class GetRunRequest(_message.Message):
    __slots__ = ("id",)
    ID_FIELD_NUMBER: _ClassVar[int]
    id: str
    def __init__(self, id: _Optional[str] = ...) -> None: ...

class GetRunResponse(_message.Message):
    __slots__ = ("run", "document")
    RUN_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_FIELD_NUMBER: _ClassVar[int]
    run: RunResult
    document: str
    def __init__(self, run: _Optional[_Union[RunResult, _Mapping]] = ..., document: _Optional[str] = ...) -> None: ...

class RunResult(_message.Message):
    __slots__ = ("format", "id", "started_at", "finished_at", "status", "serial", "planned", "ran", "failed_step_id", "parameters", "steps", "truncated")
    class ParametersEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    STARTED_AT_FIELD_NUMBER: _ClassVar[int]
    FINISHED_AT_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    PLANNED_FIELD_NUMBER: _ClassVar[int]
    RAN_FIELD_NUMBER: _ClassVar[int]
    FAILED_STEP_ID_FIELD_NUMBER: _ClassVar[int]
    PARAMETERS_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    TRUNCATED_FIELD_NUMBER: _ClassVar[int]
    format: str
    id: str
    started_at: _timestamp_pb2.Timestamp
    finished_at: _timestamp_pb2.Timestamp
    status: RunStatus
    serial: str
    planned: int
    ran: int
    failed_step_id: str
    parameters: _containers.ScalarMap[str, str]
    steps: _containers.RepeatedCompositeFieldContainer[StepResult]
    truncated: bool
    def __init__(self, format: _Optional[str] = ..., id: _Optional[str] = ..., started_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., finished_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., status: _Optional[_Union[RunStatus, str]] = ..., serial: _Optional[str] = ..., planned: _Optional[int] = ..., ran: _Optional[int] = ..., failed_step_id: _Optional[str] = ..., parameters: _Optional[_Mapping[str, str]] = ..., steps: _Optional[_Iterable[_Union[StepResult, _Mapping]]] = ..., truncated: _Optional[bool] = ...) -> None: ...

class StepResult(_message.Message):
    __slots__ = ("step_id", "outcome", "message")
    STEP_ID_FIELD_NUMBER: _ClassVar[int]
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    step_id: str
    outcome: Outcome
    message: str
    def __init__(self, step_id: _Optional[str] = ..., outcome: _Optional[_Union[Outcome, _Mapping]] = ..., message: _Optional[str] = ...) -> None: ...

class SetRecordingRequest(_message.Message):
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: bool
    def __init__(self, recording: _Optional[bool] = ...) -> None: ...

class SetRecordingResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class NewRecordingRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class NewRecordingResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class GetRecordingRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetRecordingResponse(_message.Message):
    __slots__ = ("recording", "document", "missing_secrets")
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_FIELD_NUMBER: _ClassVar[int]
    MISSING_SECRETS_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    document: str
    missing_secrets: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ..., document: _Optional[str] = ..., missing_secrets: _Optional[_Iterable[str]] = ...) -> None: ...

class Recording(_message.Message):
    __slots__ = ("format", "recorded_at", "recorder", "device", "secrets", "steps", "parameters")
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    RECORDED_AT_FIELD_NUMBER: _ClassVar[int]
    RECORDER_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    SECRETS_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    PARAMETERS_FIELD_NUMBER: _ClassVar[int]
    format: str
    recorded_at: _timestamp_pb2.Timestamp
    recorder: str
    device: RecordedDevice
    secrets: _containers.RepeatedScalarFieldContainer[str]
    steps: _containers.RepeatedCompositeFieldContainer[Step]
    parameters: _containers.RepeatedCompositeFieldContainer[Parameter]
    def __init__(self, format: _Optional[str] = ..., recorded_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., recorder: _Optional[str] = ..., device: _Optional[_Union[RecordedDevice, _Mapping]] = ..., secrets: _Optional[_Iterable[str]] = ..., steps: _Optional[_Iterable[_Union[Step, _Mapping]]] = ..., parameters: _Optional[_Iterable[_Union[Parameter, _Mapping]]] = ...) -> None: ...

class Parameter(_message.Message):
    __slots__ = ("name", "value", "description")
    NAME_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    name: str
    value: str
    description: str
    def __init__(self, name: _Optional[str] = ..., value: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class RecordedDevice(_message.Message):
    __slots__ = ("serial", "api_level", "manufacturer", "model")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    API_LEVEL_FIELD_NUMBER: _ClassVar[int]
    MANUFACTURER_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    serial: str
    api_level: int
    manufacturer: str
    model: str
    def __init__(self, serial: _Optional[str] = ..., api_level: _Optional[int] = ..., manufacturer: _Optional[str] = ..., model: _Optional[str] = ...) -> None: ...

class Step(_message.Message):
    __slots__ = ("id", "note", "outcome", "app", "action", "type", "wait", "assertion", "scroll_until", "app_wait", "device_wait", "device", "device_assertion")
    ID_FIELD_NUMBER: _ClassVar[int]
    NOTE_FIELD_NUMBER: _ClassVar[int]
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    APP_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    WAIT_FIELD_NUMBER: _ClassVar[int]
    ASSERTION_FIELD_NUMBER: _ClassVar[int]
    SCROLL_UNTIL_FIELD_NUMBER: _ClassVar[int]
    APP_WAIT_FIELD_NUMBER: _ClassVar[int]
    DEVICE_WAIT_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    DEVICE_ASSERTION_FIELD_NUMBER: _ClassVar[int]
    id: str
    note: str
    outcome: Outcome
    app: _event_log_pb2.AppCall
    action: ActionStep
    type: TypeStep
    wait: WaitStep
    assertion: AssertionStep
    scroll_until: ScrollUntilStep
    app_wait: AppWaitStep
    device_wait: DeviceWaitStep
    device: _event_log_pb2.DeviceCall
    device_assertion: DeviceAssertionStep
    def __init__(self, id: _Optional[str] = ..., note: _Optional[str] = ..., outcome: _Optional[_Union[Outcome, _Mapping]] = ..., app: _Optional[_Union[_event_log_pb2.AppCall, _Mapping]] = ..., action: _Optional[_Union[ActionStep, _Mapping]] = ..., type: _Optional[_Union[TypeStep, _Mapping]] = ..., wait: _Optional[_Union[WaitStep, _Mapping]] = ..., assertion: _Optional[_Union[AssertionStep, _Mapping]] = ..., scroll_until: _Optional[_Union[ScrollUntilStep, _Mapping]] = ..., app_wait: _Optional[_Union[AppWaitStep, _Mapping]] = ..., device_wait: _Optional[_Union[DeviceWaitStep, _Mapping]] = ..., device: _Optional[_Union[_event_log_pb2.DeviceCall, _Mapping]] = ..., device_assertion: _Optional[_Union[DeviceAssertionStep, _Mapping]] = ...) -> None: ...

class ActionStep(_message.Message):
    __slots__ = ("command", "wait", "secret", "selector_origin", "parameter")
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    WAIT_FIELD_NUMBER: _ClassVar[int]
    SECRET_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    PARAMETER_FIELD_NUMBER: _ClassVar[int]
    command: _command_pb2.Command
    wait: _command_pb2.Command
    secret: str
    selector_origin: SelectorOrigin
    parameter: str
    def __init__(self, command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ..., wait: _Optional[_Union[_command_pb2.Command, _Mapping]] = ..., secret: _Optional[str] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ..., parameter: _Optional[str] = ...) -> None: ...

class TypeStep(_message.Message):
    __slots__ = ("selector", "text", "secret", "parameter", "skip_focus_wait", "selector_origin", "timeout_ms", "precondition_timeout_ms", "focus_timeout_ms")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SECRET_FIELD_NUMBER: _ClassVar[int]
    PARAMETER_FIELD_NUMBER: _ClassVar[int]
    SKIP_FOCUS_WAIT_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    PRECONDITION_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    FOCUS_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    text: str
    secret: str
    parameter: str
    skip_focus_wait: bool
    selector_origin: SelectorOrigin
    timeout_ms: int
    precondition_timeout_ms: int
    focus_timeout_ms: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., text: _Optional[str] = ..., secret: _Optional[str] = ..., parameter: _Optional[str] = ..., skip_focus_wait: _Optional[bool] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ..., timeout_ms: _Optional[int] = ..., precondition_timeout_ms: _Optional[int] = ..., focus_timeout_ms: _Optional[int] = ...) -> None: ...

class WaitStep(_message.Message):
    __slots__ = ("selector", "condition", "text", "count", "parameter", "selector_origin", "timeout_ms")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    CONDITION_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    PARAMETER_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    condition: Condition
    text: str
    count: int
    parameter: str
    selector_origin: SelectorOrigin
    timeout_ms: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., condition: _Optional[_Union[Condition, str]] = ..., text: _Optional[str] = ..., count: _Optional[int] = ..., parameter: _Optional[str] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class AssertionStep(_message.Message):
    __slots__ = ("selector", "check", "text", "count", "parameter", "selector_origin", "timeout_ms")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    CHECK_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    PARAMETER_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    check: Check
    text: str
    count: int
    parameter: str
    selector_origin: SelectorOrigin
    timeout_ms: int
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., check: _Optional[_Union[Check, str]] = ..., text: _Optional[str] = ..., count: _Optional[int] = ..., parameter: _Optional[str] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ..., timeout_ms: _Optional[int] = ...) -> None: ...

class ScrollUntilStep(_message.Message):
    __slots__ = ("container", "target", "direction", "max_scrolls", "distance_percent", "selector_origin", "timeout_ms", "precondition_timeout_ms")
    CONTAINER_FIELD_NUMBER: _ClassVar[int]
    TARGET_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    MAX_SCROLLS_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_PERCENT_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    PRECONDITION_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    container: _selector_pb2.Selector
    target: _selector_pb2.Selector
    direction: _command_pb2.Direction
    max_scrolls: int
    distance_percent: int
    selector_origin: SelectorOrigin
    timeout_ms: int
    precondition_timeout_ms: int
    def __init__(self, container: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., target: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., direction: _Optional[_Union[_command_pb2.Direction, str]] = ..., max_scrolls: _Optional[int] = ..., distance_percent: _Optional[int] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ..., timeout_ms: _Optional[int] = ..., precondition_timeout_ms: _Optional[int] = ...) -> None: ...

class AppWaitStep(_message.Message):
    __slots__ = ("command",)
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    command: _command_pb2.Command
    def __init__(self, command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ...) -> None: ...

class DeviceWaitStep(_message.Message):
    __slots__ = ("command",)
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    command: _command_pb2.Command
    def __init__(self, command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ...) -> None: ...

class DeviceAssertionStep(_message.Message):
    __slots__ = ("check", "text")
    CHECK_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    check: DeviceCheck
    text: str
    def __init__(self, check: _Optional[_Union[DeviceCheck, str]] = ..., text: _Optional[str] = ...) -> None: ...

class Outcome(_message.Message):
    __slots__ = ("duration_ms", "error", "failure", "mismatch")
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    FAILURE_FIELD_NUMBER: _ClassVar[int]
    MISMATCH_FIELD_NUMBER: _ClassVar[int]
    duration_ms: int
    error: _command_pb2.Error
    failure: _failure_pb2.Failure
    mismatch: str
    def __init__(self, duration_ms: _Optional[int] = ..., error: _Optional[_Union[_command_pb2.Error, _Mapping]] = ..., failure: _Optional[_Union[_failure_pb2.Failure, _Mapping]] = ..., mismatch: _Optional[str] = ...) -> None: ...
