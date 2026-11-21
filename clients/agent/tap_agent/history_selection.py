"""Device selection for `history`, and its `mark` / `redact` controls on held attachments."""
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
