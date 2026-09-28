"""Proto <-> model mapping. Enums map by name after the proto prefix, so a value added to the
schema needs a matching model constant; until then it maps to the model's "unknown" constant."""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

from . import _gen as pb
from .models import (
    AppProcess,
    Bounds,
    DeviceEntry,
    DeviceInfo,
    DeviceState,
    Direction,
    ElementSnapshot,
    ErrorCode,
    FailureReason,
    MatchMode,
    ServerDefaults,
    ServerInfo,
    StabilitySignal,
    WaitReason,
)


def _named(enum_type, proto_enum, number: int, prefix: str, fallback):
    try:
        return enum_type[proto_enum.Name(number).removeprefix(prefix)]
    except (KeyError, ValueError):
        return fallback


def match_mode(mode: MatchMode) -> int:
    return pb.MatchMode.Value(f"MATCH_{mode.name}")


def direction(value: Direction) -> int:
    return pb.Direction.Value(f"DIR_{value.name}")


def stability_signal(signal: StabilitySignal) -> int:
    return pb.StabilitySignal.Value(f"STABILITY_{signal.name}")


def error_code(number: int) -> ErrorCode:
    return _named(ErrorCode, pb.ErrorCode, number, "ERR_", ErrorCode.UNKNOWN)


def failure_reason(number: int) -> FailureReason:
    return _named(FailureReason, pb.FailureReason, number, "FAILURE_REASON_", FailureReason.UNSPECIFIED)


def device_state(number: int) -> DeviceState:
    return _named(DeviceState, pb.DeviceState, number, "DEVICE_", DeviceState.UNKNOWN)


def wait_reason(detail: str) -> WaitReason | None:
    try:
        return WaitReason[detail]
    except KeyError:
        return None


def _optional(message, field: str):
    return getattr(message, field) if message.HasField(field) else None


def element_snapshot(snapshot: pb.ElementSnapshot) -> ElementSnapshot:
    b = snapshot.bounds
    return ElementSnapshot(
        class_name=_optional(snapshot, "class_name"),
        package_name=_optional(snapshot, "package_name"),
        resource_name=_optional(snapshot, "resource_name"),
        text=_optional(snapshot, "text"),
        content_description=_optional(snapshot, "content_description"),
        hint=_optional(snapshot, "hint"),
        bounds=Bounds(b.left, b.top, b.right, b.bottom),
        checkable=snapshot.checkable,
        checked=snapshot.checked,
        clickable=snapshot.clickable,
        enabled=snapshot.enabled,
        focusable=snapshot.focusable,
        focused=snapshot.focused,
        long_clickable=snapshot.long_clickable,
        scrollable=snapshot.scrollable,
        selected=snapshot.selected,
        child_count=snapshot.child_count,
        showing_hint=snapshot.showing_hint,
    )


def device_info(info: pb.DeviceInfo) -> DeviceInfo:
    return DeviceInfo(
        api_level=info.api_level,
        manufacturer=info.manufacturer,
        model=info.model,
        product=info.product,
        display_width=info.display_width,
        display_height=info.display_height,
        display_rotation=info.display_rotation,
        current_package=_optional(info, "current_package"),
    )


def device_entry(entry: pb.DeviceEntry) -> DeviceEntry:
    return DeviceEntry(
        serial=entry.serial,
        state=device_state(entry.state),
        client_connection_id=_optional(entry, "client_connection_id"),
        quarantine_reason=_optional(entry, "quarantine_reason"),
    )


def app_process(identity: pb.ProcessIdentity) -> AppProcess:
    return AppProcess(identity.pid, identity.start_token)


def server_info(info: pb.InfoResponse) -> ServerInfo:
    d = info.defaults
    return ServerInfo(
        daemon_version=info.daemon_version,
        host_build_id=info.host_build_id,
        protocol_version=info.protocol_version,
        adb_executable=info.adb_executable,
        state_dir=info.state_dir,
        driver_available=info.driver_available,
        pid=info.pid,
        defaults=ServerDefaults(
            action=d.action_timeout_ms / 1000,
            wait=d.wait_timeout_ms / 1000,
            lifecycle=d.lifecycle_timeout_ms / 1000,
            idle_stable=d.idle_stable_ms / 1000,
            acquire=d.acquire_timeout_ms / 1000,
        ),
    )
