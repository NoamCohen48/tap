# ruff: noqa
from . import event_log_pb2 as _event_log_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ConnectRequest(_message.Message):
    __slots__ = ("name", "hold")
    NAME_FIELD_NUMBER: _ClassVar[int]
    HOLD_FIELD_NUMBER: _ClassVar[int]
    name: str
    hold: Hold
    def __init__(self, name: _Optional[str] = ..., hold: _Optional[_Union[Hold, _Mapping]] = ...) -> None: ...

class Hold(_message.Message):
    __slots__ = ("idle_timeout_ms",)
    IDLE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    idle_timeout_ms: int
    def __init__(self, idle_timeout_ms: _Optional[int] = ...) -> None: ...

class ConnectResponse(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class ObserveRequest(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class Observing(_message.Message):
    __slots__ = ("client_connection_id",)
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    def __init__(self, client_connection_id: _Optional[str] = ...) -> None: ...

class Heartbeat(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Closing(_message.Message):
    __slots__ = ("reason",)
    REASON_FIELD_NUMBER: _ClassVar[int]
    reason: str
    def __init__(self, reason: _Optional[str] = ...) -> None: ...

class ObserveResponse(_message.Message):
    __slots__ = ("at_epoch_ms", "observing", "heartbeat", "closing")
    AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    OBSERVING_FIELD_NUMBER: _ClassVar[int]
    HEARTBEAT_FIELD_NUMBER: _ClassVar[int]
    CLOSING_FIELD_NUMBER: _ClassVar[int]
    at_epoch_ms: int
    observing: Observing
    heartbeat: Heartbeat
    closing: Closing
    def __init__(self, at_epoch_ms: _Optional[int] = ..., observing: _Optional[_Union[Observing, _Mapping]] = ..., heartbeat: _Optional[_Union[Heartbeat, _Mapping]] = ..., closing: _Optional[_Union[Closing, _Mapping]] = ...) -> None: ...

class DisconnectRequest(_message.Message):
    __slots__ = ("client_connection_id", "reason")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    reason: str
    def __init__(self, client_connection_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class DisconnectResponse(_message.Message):
    __slots__ = ("attached_devices_detached", "cleanup_issues")
    class CleanupIssuesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    ATTACHED_DEVICES_DETACHED_FIELD_NUMBER: _ClassVar[int]
    CLEANUP_ISSUES_FIELD_NUMBER: _ClassVar[int]
    attached_devices_detached: int
    cleanup_issues: _containers.ScalarMap[str, str]
    def __init__(self, attached_devices_detached: _Optional[int] = ..., cleanup_issues: _Optional[_Mapping[str, str]] = ...) -> None: ...

class InfoRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class InfoResponse(_message.Message):
    __slots__ = ("daemon_version", "host_build_id", "protocol_version", "adb_executable", "state_dir", "driver_available", "pid", "defaults", "api_compatibility", "startup_options", "startup_option_sources", "administrative_disconnect_supported")
    class StartupOptionsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    class StartupOptionSourcesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    DAEMON_VERSION_FIELD_NUMBER: _ClassVar[int]
    HOST_BUILD_ID_FIELD_NUMBER: _ClassVar[int]
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    ADB_EXECUTABLE_FIELD_NUMBER: _ClassVar[int]
    STATE_DIR_FIELD_NUMBER: _ClassVar[int]
    DRIVER_AVAILABLE_FIELD_NUMBER: _ClassVar[int]
    PID_FIELD_NUMBER: _ClassVar[int]
    DEFAULTS_FIELD_NUMBER: _ClassVar[int]
    API_COMPATIBILITY_FIELD_NUMBER: _ClassVar[int]
    STARTUP_OPTIONS_FIELD_NUMBER: _ClassVar[int]
    STARTUP_OPTION_SOURCES_FIELD_NUMBER: _ClassVar[int]
    ADMINISTRATIVE_DISCONNECT_SUPPORTED_FIELD_NUMBER: _ClassVar[int]
    daemon_version: str
    host_build_id: str
    protocol_version: str
    adb_executable: str
    state_dir: str
    driver_available: bool
    pid: int
    defaults: Defaults
    api_compatibility: int
    startup_options: _containers.ScalarMap[str, str]
    startup_option_sources: _containers.ScalarMap[str, str]
    administrative_disconnect_supported: bool
    def __init__(self, daemon_version: _Optional[str] = ..., host_build_id: _Optional[str] = ..., protocol_version: _Optional[str] = ..., adb_executable: _Optional[str] = ..., state_dir: _Optional[str] = ..., driver_available: _Optional[bool] = ..., pid: _Optional[int] = ..., defaults: _Optional[_Union[Defaults, _Mapping]] = ..., api_compatibility: _Optional[int] = ..., startup_options: _Optional[_Mapping[str, str]] = ..., startup_option_sources: _Optional[_Mapping[str, str]] = ..., administrative_disconnect_supported: _Optional[bool] = ...) -> None: ...

class Defaults(_message.Message):
    __slots__ = ("action_timeout_ms", "wait_timeout_ms", "lifecycle_timeout_ms", "idle_stable_ms", "acquire_timeout_ms")
    ACTION_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    WAIT_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    LIFECYCLE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    IDLE_STABLE_MS_FIELD_NUMBER: _ClassVar[int]
    ACQUIRE_TIMEOUT_MS_FIELD_NUMBER: _ClassVar[int]
    action_timeout_ms: int
    wait_timeout_ms: int
    lifecycle_timeout_ms: int
    idle_stable_ms: int
    acquire_timeout_ms: int
    def __init__(self, action_timeout_ms: _Optional[int] = ..., wait_timeout_ms: _Optional[int] = ..., lifecycle_timeout_ms: _Optional[int] = ..., idle_stable_ms: _Optional[int] = ..., acquire_timeout_ms: _Optional[int] = ...) -> None: ...

class ListConnectionsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class AttachedDeviceEntry(_message.Message):
    __slots__ = ("attached_device_id", "serial", "generation")
    ATTACHED_DEVICE_ID_FIELD_NUMBER: _ClassVar[int]
    SERIAL_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    attached_device_id: str
    serial: str
    generation: int
    def __init__(self, attached_device_id: _Optional[str] = ..., serial: _Optional[str] = ..., generation: _Optional[int] = ...) -> None: ...

class ConnectionEntry(_message.Message):
    __slots__ = ("client_connection_id", "name", "hold", "idle_ms", "attached_devices", "created_at_epoch_ms", "last_activity_at_epoch_ms", "in_flight", "observing")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    HOLD_FIELD_NUMBER: _ClassVar[int]
    IDLE_MS_FIELD_NUMBER: _ClassVar[int]
    ATTACHED_DEVICES_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTIVITY_AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    IN_FLIGHT_FIELD_NUMBER: _ClassVar[int]
    OBSERVING_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    name: str
    hold: Hold
    idle_ms: int
    attached_devices: _containers.RepeatedCompositeFieldContainer[AttachedDeviceEntry]
    created_at_epoch_ms: int
    last_activity_at_epoch_ms: int
    in_flight: int
    observing: bool
    def __init__(self, client_connection_id: _Optional[str] = ..., name: _Optional[str] = ..., hold: _Optional[_Union[Hold, _Mapping]] = ..., idle_ms: _Optional[int] = ..., attached_devices: _Optional[_Iterable[_Union[AttachedDeviceEntry, _Mapping]]] = ..., created_at_epoch_ms: _Optional[int] = ..., last_activity_at_epoch_ms: _Optional[int] = ..., in_flight: _Optional[int] = ..., observing: _Optional[bool] = ...) -> None: ...

class ListConnectionsResponse(_message.Message):
    __slots__ = ("connections",)
    CONNECTIONS_FIELD_NUMBER: _ClassVar[int]
    connections: _containers.RepeatedCompositeFieldContainer[ConnectionEntry]
    def __init__(self, connections: _Optional[_Iterable[_Union[ConnectionEntry, _Mapping]]] = ...) -> None: ...

class EventsRequest(_message.Message):
    __slots__ = ("client_connection_id", "after_seq")
    CLIENT_CONNECTION_ID_FIELD_NUMBER: _ClassVar[int]
    AFTER_SEQ_FIELD_NUMBER: _ClassVar[int]
    client_connection_id: str
    after_seq: int
    def __init__(self, client_connection_id: _Optional[str] = ..., after_seq: _Optional[int] = ...) -> None: ...

class EventsResponse(_message.Message):
    __slots__ = ("events", "dropped")
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    DROPPED_FIELD_NUMBER: _ClassVar[int]
    events: _containers.RepeatedCompositeFieldContainer[_event_log_pb2.LoggedEvent]
    dropped: int
    def __init__(self, events: _Optional[_Iterable[_Union[_event_log_pb2.LoggedEvent, _Mapping]]] = ..., dropped: _Optional[int] = ...) -> None: ...
