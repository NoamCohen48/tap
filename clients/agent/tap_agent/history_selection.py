"""Selection and controls for the single merged history reader, never a second log viewer."""
from __future__ import annotations

import datetime
import uuid
from typing import TYPE_CHECKING

from .records import RecordError

if TYPE_CHECKING:
    from .core import Agent


def serials(values) -> tuple[str, ...]:
    if isinstance(values, str):
        values = (values,)
    if not isinstance(values, (tuple, list)) or len(values) > 64 or any(
        not isinstance(value, str) or not value or len(value) > 256 for value in values
    ):
        raise RecordError("devices must be nonempty serials (at most 64)")
    return tuple(dict.fromkeys(values))


def control(agent: Agent, action: str, value: str | None, devices: tuple[str, ...], *, as_json: bool) -> str:
    from .core import AgentError
    from .output import json_text
    if not isinstance(value, str) or not value or len(value) > (512 if action == "redact" else 200) or any(ord(c) < 32 for c in value):
        raise RecordError(f"history {action} needs a valid {'pattern' if action == 'redact' else 'marker name'}")
    connection = agent._connection()
    attached = connection.attached_devices()
    available = {device.serial: device for device in attached}
    missing = set(devices) - available.keys()
    if missing or not available:
        raise RecordError("selected devices are not attached to this session: " + ", ".join(sorted(missing)))
    selected = [available[name] for name in (devices or tuple(sorted(available)))]
    batch = uuid.uuid4().hex
    completed = []
    attempted = "(none)"
    try:
        for device in selected:
            attempted = device.serial
            if action == "redact":
                device.logs.redact(value)
                completed.append({"serial": device.serial, "redaction_registered": True})
            else:
                cursor = device.logs.mark(value)
                acknowledged = {"serial": device.serial, "name": value, "cursor": f"{device.attached_device_id}:{cursor}"}
                completed.append(acknowledged)
                # Read the acknowledged original marker, not the client wall clock.
                entry = next((e for e in device.logs.read(since=cursor - 1) if e.cursor == cursor and e.marker == value), None)
                if entry is None:
                    raise RecordError("marker acknowledged but its receipt is unavailable")
                at = datetime.datetime.fromtimestamp(entry.at, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
                record = agent.records.ensure(agent.session, connection.id)
                with agent.records.lease(record["id"]):
                    agent.records.mark(record, value, device.serial, cursor, batch_id=batch,
                        attached_device_id=device.attached_device_id, at=at)
                acknowledged["at"] = at
    except Exception as error:
        raise AgentError(f"history {action}: acknowledged devices {', '.join(row['serial'] for row in completed) or '(none)'}; "
            f"inspection required on {attempted} ({type(error).__name__}); do not blindly repeat the control") from error
    if as_json:
        return json_text({"action": action, "devices": completed})
    return "\n".join(f"{action} {row['serial']}" + (f" {value!r} at {row['cursor']}" if action == "mark" else " (attachment redaction updated)") for row in completed)


def _markers(agent: Agent, name: str, devices: tuple[str, ...]):
    markers = agent.records.marker_anchor(name, devices)
    if any(not row["attached_device_id"] for row in markers):
        raise RecordError("legacy marker lacks an original receipt identity; use explicit time bounds or an opaque receipt cursor")
    return markers


def anchor(agent: Agent, *, devices: tuple[str, ...], since_marker: str | None, until_marker: str | None,
           step: str | int | None, last_action: bool, after: str | None):
    """Resolve original retained identities to host receipt bounds; never guess expired anchors."""
    low = high = None
    wanted = None
    metadata = {}
    if since_marker is not None:
        markers = _markers(agent, since_marker, devices)
        low = min(row["at"] for row in markers)
        metadata["since_marker"] = markers
    if until_marker is not None:
        markers = _markers(agent, until_marker, devices)
        high = (datetime.datetime.fromisoformat(max(row["at"] for row in markers).replace("Z", "+00:00")) + datetime.timedelta(milliseconds=1)).isoformat()
        metadata["until_marker"] = markers
    if step is not None:
        try:
            if isinstance(step, bool) or not isinstance(step, (str, int)):
                raise TypeError()
            if isinstance(step, str) and len(step) > 64:
                raise ValueError()
            value = int(step) if isinstance(step, str) and step.isdecimal() else step
            if isinstance(value, int) and not 1 <= value <= 2**63 - 1:
                raise ValueError()
        except (ValueError, TypeError) as error:
            raise RecordError("step needs a positive 64-bit global cursor or a retained step ID") from error
        row = agent.records.resolve_step(value)
        if devices and row["serial"] not in devices:
            raise RecordError("selected step does not belong to the selected devices")
        low, high, wanted = row["started_at"], row["finished_at"], row["id"]
        metadata["step"] = {"cursor": row["cursor"], "id": wanted}
    if after is not None or last_action:
        archives = agent.client.log_recordings.archives(include_events=True)
        archives = [a for a in archives if a.events or not devices or a.serial in devices]
        if after is not None:
            try:
                identity, number = after.rsplit(":", 1)
                uuid.UUID(identity)
                cursor = int(number)
                if cursor < 0 or cursor > 2**63 - 1:
                    raise ValueError()
            except (ValueError, AttributeError) as error:
                raise RecordError("--after needs ARCHIVE_UUID:CURSOR, not a global step cursor") from error
            archive = next((a for a in archives if a.attached_device_id == identity), None)
            if archive is None:
                raise RecordError("--after archive is not retained or outside the device selection")
            entries = agent.client.log_recordings.read_archive(archive)
            entry = next((e for e in entries if e.cursor == cursor), None)
            if entry is None:
                raise RecordError("--after receipt is no longer retained; its time cannot be invented")
            serial = entry.event.serial if entry.event is not None else entry.death.get("serial") if entry.death is not None else archive.serial
            if devices and serial and serial not in devices:
                raise RecordError("--after receipt is outside the device selection")
            low = datetime.datetime.fromtimestamp(entry.at, datetime.timezone.utc).isoformat()
            metadata["after"] = {"archive": identity, "cursor": cursor}
        else:
            latest = {}
            for archive in archives:
                if archive.events:
                    continue
                entries = agent.client.log_recordings.read_archive(archive)
                action = next((e for e in reversed(entries) if e.action is not None and not e.action.finished), None)
                if action is not None and (archive.serial not in latest or action.at > latest[archive.serial]):
                    latest[archive.serial] = action.at
            if not latest:
                raise RecordError("no retained action on the selected devices")
            low = datetime.datetime.fromtimestamp(min(latest.values()), datetime.timezone.utc).isoformat()
            metadata["last_actions"] = latest
    return low, high, wanted, metadata


def finish(data: dict, *, devices: tuple[str, ...], actions: bool, driver_lines: bool,
           tail: int | None, after: dict | None) -> None:
    from .core import DRIVER_PACKAGES
    merged = []
    for key, capture in data.get("log", {}).items():
        archive = capture.get("attached_device_id", "")
        kept = []
        for entry in capture["entries"]:
            if after and archive == after["archive"] and entry["cursor"] <= after["cursor"]:
                continue
            line = entry.get("line")
            if line is not None:
                packages = set(line.get("packages", []))
                if not driver_lines and packages and packages <= DRIVER_PACKAGES:
                    continue
            if not actions and "action" in entry:
                continue
            entry["entry_id"] = f"{archive}:{entry['cursor']}"
            kept.append(entry)
            merged.append((entry.get("at", ""), archive, entry["cursor"], key))
        capture["entries"] = kept
    if tail is not None:
        retained = {(key, cursor) for _, _, cursor, key in sorted(merged)[-tail:]}
        omitted = 0
        for key, capture in data.get("log", {}).items():
            original = capture["entries"]
            capture["entries"] = [e for e in original if (key, e["cursor"]) in retained or "gap" in e]
            omitted += len(original) - len(capture["entries"])
        data["log_omitted"] = omitted
        if omitted:
            data["complete"] = False
    if after:
        for field in ("events", "deaths"):
            data[field] = [entry for entry in data.get(field, []) if not (
                entry.get("cursor", "").startswith(after["archive"] + ":") and int(entry["cursor"].rsplit(":", 1)[1]) <= after["cursor"])]
    data["devices"] = list(devices)
