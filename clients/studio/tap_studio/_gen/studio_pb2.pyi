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
    __slots__ = ("serial", "aut_package", "api_level", "manufacturer", "model", "display_width", "display_height")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    API_LEVEL_FIELD_NUMBER: _ClassVar[int]
    MANUFACTURER_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_WIDTH_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    serial: str
    aut_package: str
    api_level: int
    manufacturer: str
    model: str
    display_width: int
    display_height: int
    def __init__(self, serial: _Optional[str] = ..., aut_package: _Optional[str] = ..., api_level: _Optional[int] = ..., manufacturer: _Optional[str] = ..., model: _Optional[str] = ..., display_width: _Optional[int] = ..., display_height: _Optional[int] = ...) -> None: ...

class Session(_message.Message):
    __slots__ = ("device", "recording", "steps", "recording_package")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    RECORDING_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    device: AttachedDevice
    recording: bool
    steps: int
    recording_package: str
    def __init__(self, device: _Optional[_Union[AttachedDevice, _Mapping]] = ..., recording: _Optional[bool] = ..., steps: _Optional[int] = ..., recording_package: _Optional[str] = ...) -> None: ...

class GetSessionRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetSessionResponse(_message.Message):
    __slots__ = ("session",)
    SESSION_FIELD_NUMBER: _ClassVar[int]
    session: Session
    def __init__(self, session: _Optional[_Union[Session, _Mapping]] = ...) -> None: ...

class ListDevicesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DeviceChoice(_message.Message):
    __slots__ = ("serial", "state", "attached", "quarantine_reason")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_FIELD_NUMBER: _ClassVar[int]
    QUARANTINE_REASON_FIELD_NUMBER: _ClassVar[int]
    serial: str
    state: _device_pb2.DeviceState
    attached: bool
    quarantine_reason: str
    def __init__(self, serial: _Optional[str] = ..., state: _Optional[_Union[_device_pb2.DeviceState, str]] = ..., attached: _Optional[bool] = ..., quarantine_reason: _Optional[str] = ...) -> None: ...

class ListDevicesResponse(_message.Message):
    __slots__ = ("devices",)
    DEVICES_FIELD_NUMBER: _ClassVar[int]
    devices: _containers.RepeatedCompositeFieldContainer[DeviceChoice]
    def __init__(self, devices: _Optional[_Iterable[_Union[DeviceChoice, _Mapping]]] = ...) -> None: ...

class AttachRequest(_message.Message):
    __slots__ = ("serial", "aut_package")
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    serial: str
    aut_package: str
    def __init__(self, serial: _Optional[str] = ..., aut_package: _Optional[str] = ...) -> None: ...

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

class PerformRequest(_message.Message):
    __slots__ = ("step", "secret_value")
    STEP_FIELD_NUMBER: _ClassVar[int]
    SECRET_VALUE_FIELD_NUMBER: _ClassVar[int]
    step: Step
    secret_value: str
    def __init__(self, step: _Optional[_Union[Step, _Mapping]] = ..., secret_value: _Optional[str] = ...) -> None: ...

class PerformResponse(_message.Message):
    __slots__ = ("step", "recorded", "message")
    STEP_FIELD_NUMBER: _ClassVar[int]
    RECORDED_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    step: Step
    recorded: bool
    message: str
    def __init__(self, step: _Optional[_Union[Step, _Mapping]] = ..., recorded: _Optional[bool] = ..., message: _Optional[str] = ...) -> None: ...

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
    __slots__ = ("recording",)
    RECORDING_FIELD_NUMBER: _ClassVar[int]
    recording: Recording
    def __init__(self, recording: _Optional[_Union[Recording, _Mapping]] = ...) -> None: ...

class Recording(_message.Message):
    __slots__ = ("format", "recorded_at", "recorder", "device", "aut_package", "secrets", "steps")
    FORMAT_FIELD_NUMBER: _ClassVar[int]
    RECORDED_AT_FIELD_NUMBER: _ClassVar[int]
    RECORDER_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    AUT_PACKAGE_FIELD_NUMBER: _ClassVar[int]
    SECRETS_FIELD_NUMBER: _ClassVar[int]
    STEPS_FIELD_NUMBER: _ClassVar[int]
    format: str
    recorded_at: _timestamp_pb2.Timestamp
    recorder: str
    device: RecordedDevice
    aut_package: str
    secrets: _containers.RepeatedScalarFieldContainer[str]
    steps: _containers.RepeatedCompositeFieldContainer[Step]
    def __init__(self, format: _Optional[str] = ..., recorded_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., recorder: _Optional[str] = ..., device: _Optional[_Union[RecordedDevice, _Mapping]] = ..., aut_package: _Optional[str] = ..., secrets: _Optional[_Iterable[str]] = ..., steps: _Optional[_Iterable[_Union[Step, _Mapping]]] = ...) -> None: ...

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
    __slots__ = ("id", "note", "outcome", "app", "action", "type", "assertion")
    ID_FIELD_NUMBER: _ClassVar[int]
    NOTE_FIELD_NUMBER: _ClassVar[int]
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    APP_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    ASSERTION_FIELD_NUMBER: _ClassVar[int]
    id: str
    note: str
    outcome: Outcome
    app: _event_log_pb2.AppCall
    action: ActionStep
    type: TypeStep
    assertion: AssertionStep
    def __init__(self, id: _Optional[str] = ..., note: _Optional[str] = ..., outcome: _Optional[_Union[Outcome, _Mapping]] = ..., app: _Optional[_Union[_event_log_pb2.AppCall, _Mapping]] = ..., action: _Optional[_Union[ActionStep, _Mapping]] = ..., type: _Optional[_Union[TypeStep, _Mapping]] = ..., assertion: _Optional[_Union[AssertionStep, _Mapping]] = ...) -> None: ...

class ActionStep(_message.Message):
    __slots__ = ("command", "wait", "secret", "selector_origin")
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    WAIT_FIELD_NUMBER: _ClassVar[int]
    SECRET_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    command: _command_pb2.Command
    wait: _command_pb2.Command
    secret: str
    selector_origin: SelectorOrigin
    def __init__(self, command: _Optional[_Union[_command_pb2.Command, _Mapping]] = ..., wait: _Optional[_Union[_command_pb2.Command, _Mapping]] = ..., secret: _Optional[str] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ...) -> None: ...

class TypeStep(_message.Message):
    __slots__ = ("selector", "text", "secret", "skip_focus_wait", "selector_origin")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SECRET_FIELD_NUMBER: _ClassVar[int]
    SKIP_FOCUS_WAIT_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    text: str
    secret: str
    skip_focus_wait: bool
    selector_origin: SelectorOrigin
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., text: _Optional[str] = ..., secret: _Optional[str] = ..., skip_focus_wait: _Optional[bool] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ...) -> None: ...

class AssertionStep(_message.Message):
    __slots__ = ("selector", "condition", "text", "count", "selector_origin")
    SELECTOR_FIELD_NUMBER: _ClassVar[int]
    CONDITION_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    SELECTOR_ORIGIN_FIELD_NUMBER: _ClassVar[int]
    selector: _selector_pb2.Selector
    condition: Condition
    text: str
    count: int
    selector_origin: SelectorOrigin
    def __init__(self, selector: _Optional[_Union[_selector_pb2.Selector, _Mapping]] = ..., condition: _Optional[_Union[Condition, str]] = ..., text: _Optional[str] = ..., count: _Optional[int] = ..., selector_origin: _Optional[_Union[SelectorOrigin, str]] = ...) -> None: ...

class Outcome(_message.Message):
    __slots__ = ("duration_ms", "error", "failure")
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    FAILURE_FIELD_NUMBER: _ClassVar[int]
    duration_ms: int
    error: _command_pb2.Error
    failure: _failure_pb2.Failure
    def __init__(self, duration_ms: _Optional[int] = ..., error: _Optional[_Union[_command_pb2.Error, _Mapping]] = ..., failure: _Optional[_Union[_failure_pb2.Failure, _Mapping]] = ...) -> None: ...
