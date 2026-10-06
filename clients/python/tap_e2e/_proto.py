"""Proto <-> model mapping. Enums map by name after the proto prefix, so a value added to the
schema needs a matching model constant; until then it maps to the model's "unknown" constant."""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

import datetime
from collections.abc import Mapping

from google.protobuf import json_format

from . import _gen as pb
from .models import (
    AppProcess,
    AttachedDeviceEntry,
    Bounds,
    ConnectionEntry,
    DeviceEntry,
    DeviceInfo,
    ForegroundActivity,
    Notification,
    DeviceState,
    Direction,
    DisplayRotation,
    ElementSnapshot,
    ErrorCode,
    EventLog,
    FailureReason,
    LoggedEvent,
    Long,
    MatchMode,
    NodeChange,
    NodeFlag,
    Orientation,
    LocationAccuracy,
    PermissionChoice,
    PermissionPrompt,
    Range,
    RangeType,
    StandardAction,
    ScreenNode,
    ScreenSnapshot,
    SelectorCandidate,
    SelectorKind,
    ServerDefaults,
    ServerInfo,
    StabilitySignal,
    Toast,
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


def orientation(value: Orientation) -> int:
    return pb.Orientation.Value(f"ORIENTATION_{value.name}")


def display_rotation(value: DisplayRotation) -> int:
    return pb.DisplayRotation.Value(f"DISPLAY_ROTATION_{value.name}")


def permission_choice(value: PermissionChoice) -> int:
    return pb.PermissionChoice.Value(f"PERMISSION_{value.name}")


def permission_prompt(prompt: pb.PermissionPrompt) -> PermissionPrompt:
    """Choices this client version does not know are left out."""
    known = (_named(PermissionChoice, pb.PermissionChoice, c, "PERMISSION_", None) for c in prompt.choices)
    accuracies = (_named(LocationAccuracy, pb.LocationAccuracy, a, "LOCATION_", None) for a in prompt.accuracies)
    return PermissionPrompt(
        package_name=prompt.package_name,
        choices=tuple(c for c in known if c is not None),
        accuracies=tuple(a for a in accuracies if a is not None),
    )


def location_accuracy(value: LocationAccuracy) -> int:
    return pb.LocationAccuracy.Value(f"LOCATION_{value.name}")


def standard_action(value: StandardAction) -> int:
    return pb.StandardAction.Value(f"A11Y_{value.name}")


def stability_signal(signal: StabilitySignal) -> int:
    return pb.StabilitySignal.Value(f"STABILITY_{signal.name}")


def error_code(number: int) -> ErrorCode:
    return _named(ErrorCode, pb.ErrorCode, number, "ERR_", ErrorCode.UNKNOWN)


def failure_reason(number: int) -> FailureReason:
    return _named(FailureReason, pb.FailureReason, number, "FAILURE_REASON_", FailureReason.UNSPECIFIED)


def device_state(number: int) -> DeviceState:
    return _named(DeviceState, pb.DeviceState, number, "DEVICE_", DeviceState.UNKNOWN)


def node_flag(number: int) -> NodeFlag | None:
    return _named(NodeFlag, pb.NodeFlag, number, "FLAG_", None)


def node_change(number: int) -> NodeChange:
    return _named(NodeChange, pb.NodeChange, number, "NODE_", NodeChange.NONE)


def selector_kind(number: int) -> SelectorKind:
    return _named(SelectorKind, pb.SelectorKind, number, "SELECTOR_KIND_", SelectorKind.UNKNOWN)


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
        # Actions this client version does not know are left out.
        actions=tuple(
            a for a in (_named(StandardAction, pb.StandardAction, n, "A11Y_", None) for n in snapshot.actions) if a is not None
        ),
        custom_actions=tuple(snapshot.custom_actions),
        range=(
            Range(
                type=_named(RangeType, pb.RangeType, snapshot.range.type, "RANGE_", RangeType.UNKNOWN),
                min=round(snapshot.range.min, 6),
                max=round(snapshot.range.max, 6),
                current=round(snapshot.range.current, 6),
            )
            if snapshot.HasField("range")
            else None
        ),
    )


def device_info(info: pb.DeviceInfo) -> DeviceInfo:
    return DeviceInfo(
        api_level=info.api_level,
        manufacturer=info.manufacturer,
        model=info.model,
        product=info.product,
        display_width=info.display_width,
        display_height=info.display_height,
        display_rotation=list(DisplayRotation)[info.display_rotation & 3],
        current_package=_optional(info, "current_package"),
        screen_on=info.screen_on,
        keyguard_locked=info.keyguard_locked,
        keyguard_secure=info.keyguard_secure,
        keyboard_shown=info.keyboard_shown,
        auto_rotate=info.auto_rotate,
        animations_enabled=info.animations_enabled,
        dark_mode=info.dark_mode,
        font_scale=round(info.font_scale, 6),
        density_dpi=info.density_dpi,
        airplane_mode=info.airplane_mode,
        wifi_enabled=info.wifi_enabled,
        mobile_data_enabled=info.mobile_data_enabled,
        system_locales=tuple(info.system_locales),
        stay_awake=info.stay_awake,
        high_contrast_text=_optional(info, "high_contrast_text"),
        color_inversion=_optional(info, "color_inversion"),
        bold_text=info.bold_text,
    )


def notification(value: pb.DeviceNotification) -> Notification:
    return Notification(
        package_name=value.package_name,
        title=_optional(value, "title"),
        text=_optional(value, "text"),
        actions=tuple(value.actions),
        clearable=value.clearable,
        posted_at=datetime.datetime.fromtimestamp(value.posted_at_ms / 1000, tz=datetime.timezone.utc),
    )


def foreground_activity(response: pb.GetForegroundActivityResponse) -> ForegroundActivity | None:
    if not response.HasField("package_name") or not response.HasField("activity"):
        return None
    return ForegroundActivity(package_name=response.package_name, class_name=response.activity)


def toast(value: pb.Toast) -> Toast:
    return Toast(text=value.text, package_name=value.package_name)


def intent_extras(extras: Mapping[str, object]) -> list[pb.IntentExtra]:
    """``am start`` extras: ``str``, ``bool``, ``int`` (32-bit), ``float`` (32-bit) or ``Long``;
    anything else, or an ``int`` outside 32 bits, fails before the call."""
    out = []
    for key, value in extras.items():
        extra = pb.IntentExtra(key=key)
        if isinstance(value, str):
            extra.string_value = value
        elif isinstance(value, bool):
            extra.bool_value = value
        elif isinstance(value, Long):
            extra.long_value = value.value
        elif isinstance(value, int):
            if not -(2**31) <= value < 2**31:
                raise ValueError(f"intent extra {key!r} = {value} does not fit in 32 bits; use tap_e2e.Long({value})")
            extra.int_value = value
        elif isinstance(value, float):
            extra.float_value = value
        else:
            raise TypeError(
                f"intent extra {key!r} is a {type(value).__name__}; use str, bool, int, float or tap_e2e.Long"
            )
        out.append(extra)
    return out


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


def connection_entry(entry: pb.ConnectionEntry) -> ConnectionEntry:
    return ConnectionEntry(
        id=entry.client_connection_id,
        name=entry.name,
        hold=entry.hold.idle_timeout_ms / 1000 if entry.HasField("hold") else None,
        idle=entry.idle_ms / 1000,
        attached_devices=tuple(
            AttachedDeviceEntry(d.attached_device_id, d.serial, d.generation)
            for d in entry.attached_devices
        ),
    )


def _json(message) -> dict:
    return json_format.MessageToDict(message, preserving_proto_field_name=True)


def logged_event(event: pb.LoggedEvent) -> LoggedEvent:
    return LoggedEvent(
        seq=event.seq,
        at=event.at_epoch_ms / 1000,
        duration=event.duration_ms / 1000,
        serial=event.serial,
        command=_json(event.command) if event.HasField("command") else None,
        app=_json(event.app) if event.HasField("app") else None,
        error=_json(event.error) if event.HasField("error") else None,
        failure=_json(event.failure) if event.HasField("failure") else None,
    )


def event_log(response: pb.EventsResponse) -> EventLog:
    return EventLog(tuple(logged_event(e) for e in response.events), response.dropped)


def screen_node(node: pb.ScreenNode) -> ScreenNode:
    from .selectors import Selector  # selectors imports this module

    b = node.bounds
    return ScreenNode(
        ref=node.ref,
        depth=node.depth,
        window_package=node.window_package,
        class_name=_optional(node, "class_name"),
        resource_name=_optional(node, "resource_name"),
        text=_optional(node, "text"),
        content_description=_optional(node, "content_description"),
        hint=_optional(node, "hint"),
        bounds=Bounds(b.left, b.top, b.right, b.bottom),
        flags=frozenset(f for f in map(node_flag, node.flags) if f is not None),
        password=node.password,
        interactive=node.interactive,
        selector=Selector(node.selector) if node.HasField("selector") else None,
        by_index=node.by_index,
        change=node_change(node.change),
        candidates=tuple(SelectorCandidate(Selector(c.selector), selector_kind(c.kind)) for c in node.candidates),
        showing_hint=node.showing_hint,
        content_invalid=node.content_invalid,
        error=_optional(node, "error"),
    )


def screen_snapshot(response: pb.ScreenSnapshotResponse) -> ScreenSnapshot:
    return ScreenSnapshot(
        snapshot_id=response.snapshot_id,
        nodes=tuple(map(screen_node, response.nodes)),
        removed=tuple(map(screen_node, response.removed)),
        rotation=response.rotation,
    )
