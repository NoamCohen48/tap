"""The two activity views: automatic history and named protected ranges.

The SDK owns durable evidence and its portable renderer; only agent intent is client-local.
No command output, exception text, inferred assertion or replay is added here.
"""
from __future__ import annotations

import datetime
import json
from typing import TYPE_CHECKING, NoReturn

from tap_e2e import HistoryExport, TapConnection, TapError

from .output import json_value
from .records import RecordError, UnknownHistoryError, _history_options

if TYPE_CHECKING:
    from .core import Agent


def _usage(message: str) -> NoReturn:
    from .core import EXIT_USAGE, AgentError
    raise AgentError(message, EXIT_USAGE)


def _flags(**values: bool) -> None:
    for name, value in values.items():
        if not isinstance(value, bool):
            _usage(f"{name} is a flag")


def record(agent: Agent, action: str, name: str | None, *, long: bool = False,
           logs: bool = False, as_json: bool = False) -> str:
    """Control or export named ranges; ordinary activity needs no start command."""
    _flags(long=long, logs=logs, json=as_json)
    if action not in ("start", "stop", "list", "export", "clean"):
        _usage("record action must be start, stop, list, export or clean")
    if action == "list":
        if name is not None:
            _usage("record list takes no name")
    elif not isinstance(name, str) or not name:
        _usage(f"record {action} needs NAME")
    if long and action != "list":
        _usage("-L is only for record list")
    if (logs or as_json) and action != "export":
        _usage("--logs and --json are only for record export")

    def run() -> str:
        if action == "list":
            saved = agent.client.log_recordings.list()
            if not long:
                return "\n".join(row.name for row in saved) or "no recordings"
            rows = []
            for row in saved:
                state = "live" if row.stopped_at_ms is None else "stopped"
                if row.capture_error:
                    state += f" incomplete: {row.capture_error}"
                rows.append(f"{row.name}  {state}  started={_at(row.started_at_ms)}  stopped={_at(row.stopped_at_ms)}  disk={row.retained_bytes}B")
            if rows:
                rows.append("Disk size counts retained log/event segments, which may be shared. Local steps share the SQLite catalog; deletion may reclaim less.")
            return "\n".join(rows) or "no recordings"
        assert name is not None
        recording_name = name
        if action == "start":
            connection = agent._connection()
            saved = agent.client.log_recordings.start(connection, name, agent_steps_expected=True)
            try:
                agent.records.start(name, agent.session, connection.id,
                    {r.serial: r.attached_device_id for r in saved.ranges if not r.events}, log_status=json_value(saved))
            except RecordError as error:
                raise RecordError(f"daemon recording {name!r} started but local intent history could not commit; use record stop/export/clean NAME to inspect it") from error
            return f"recording {name!r}"
        if action == "export":
            return _export_recording(agent, name, logs=logs, as_json=as_json)
        try:
            selected = agent.records.select(name, agent.session)
        except UnknownHistoryError:
            if action == "stop":
                saved = agent.client.log_recordings.stop(name)
                warning = f"; incomplete capture: {saved.capture_error}" if saved.capture_error else ""
                return f"stopped recording {name!r}{warning}"
            agent.client.log_recordings.delete(name)
            return f"cleaned recording {name!r}"
        if not selected.get("protected"):
            _usage("this is ordinary history, not a named recording")
        if action == "stop":
            stopped = agent.records.stop(selected["name"], agent.session,
                before_stop=lambda: json_value(agent.client.log_recordings.stop(recording_name)))
            try:
                status = json.loads(stopped["log_status"]) if stopped.get("log_status") else {}
                if not isinstance(status, dict):
                    raise ValueError("capture status must be an object")
            except (ValueError, TypeError) as error:
                raise RecordError("capture stopped but its saved status is invalid; inspect the recording before sharing") from error
            warning = f"\nWARNING: incomplete capture: {status.get('capture_error') or 'capture was not finalized'}" if status.get("complete") is not True else ""
            return f"stopped recording {name!r}{warning}"
        members = [row for row in agent.records.list() if row.get("protected") == name]
        if any(row["state"] != "stopped" for row in members):
            _usage("stop the recording before cleaning it")
        if any(row.name == name for row in agent.client.log_recordings.list()):
            agent.client.log_recordings.delete(name)
        agent.records.clean(selected["name"], agent.session)
        return f"cleaned recording {name!r}"
    return agent._run(run)


def _export_recording(agent: Agent, name: str, *, logs: bool, as_json: bool) -> str:
    from .core import AgentError
    agent._end_ended_histories()
    try:
        local = agent.records.export(name, agent.session)
        if not local.get("recording"):
            _usage("record export needs a named recording; use history for ordinary activity")
    except UnknownHistoryError:
        local = None
    try:
        data = agent.client.log_recordings.export(name, logs=logs).to_dict()
    except (TapError, AgentError, OSError) as error:
        if local is None:
            raise
        data = {"events": [], "event_capture": [], "complete": False,
            "events_unavailable": f"retained events were not inspected ({type(error).__name__})"}
        if logs:
            data["log_unavailable"] = "daemon evidence could not be read; no missing evidence was reconstructed"
    if local is not None:
        complete = bool(local.get("complete") and data.get("evidence_complete", data.get("complete")))
        data = {**local, **data, "steps": local["steps"], "markers": local["markers"], "complete": complete}
        data.pop("step_history_unavailable", None)
    elif data.get("log_capture", {}).get("agent_steps_expected"):
        data["step_history_unavailable"] = "daemon protection survived but no local agent intent history was committed"
        data["complete"] = False
    data["format"] = "tap-history/1"
    return _render(data, as_json)


def history(agent: Agent, *, start: str | int | None = None, until: str | int | None = None,
            limit: int = 200, logs: bool = False, as_json: bool = False) -> str:
    """A paginated view of all retained client steps, not a list of storage partitions."""
    from .core import AgentError
    _flags(logs=logs, json=as_json)
    try:
        _history_options(start, until, limit)
    except RecordError as error:
        _usage(str(error))
    def run() -> str:
        agent._end_ended_histories()
        data = agent.records.timeline(start=start, until=until, limit=limit)
        data.update(events=[], event_capture=[])
        if logs:
            data["log"] = {}
        for owner in data.pop("connections"):
            steps = [step for step in data["steps"] if step["connection_id"] == owner]
            low = min(_seconds(step["started_at"]) for step in steps)
            high = max(_seconds(step["finished_at"]) if step["finished_at"] else datetime.datetime.now().timestamp() for step in steps) + 0.001
            try:
                evidence = agent.client.log_recordings.history(owner, logs=logs,
                    step_ids=[step["id"] for step in steps if step["id"]], from_time=low, until_time=high).to_dict()
            except (TapError, AgentError, OSError) as error:
                data["complete"] = False
                data["events_unavailable"] = f"retained events were not inspected ({type(error).__name__})"
                if logs:
                    data["log_unavailable"] = "daemon evidence could not be read"
                continue
            data["events"].extend(evidence["events"])
            data["event_capture"].extend(evidence["event_capture"])
            data["complete"] = bool(data["complete"] and evidence["complete"])
            if evidence.get("events_unavailable"):
                data["events_unavailable"] = evidence["events_unavailable"]
            for serial, entries in evidence.get("log", {}).items():
                key = serial if serial not in data["log"] else f"{serial}:{owner}"
                data["log"][key] = entries
        return _render(data, as_json)
    return agent._run(run)


def release(agent: Agent) -> str:
    """Release the held connection and finalize its current protected range, without exporting files."""
    def run() -> str:
        entry = agent._held()
        if entry is None:
            return f"no session {agent.session!r}"
        TapConnection(agent.client, entry.id, name=entry.name, hold=entry.hold).close()
        serials = ", ".join(device.serial for device in entry.attached_devices)
        text = f"released session {agent.session!r}" + (f" (detached {serials})" if serials else "")
        try:
            active = agent.records.active(agent.session)
            if active is not None and active["connection_id"] == entry.id:
                agent.records.stop(active["name"], agent.session, reason="released",
                    before_stop=(lambda: json_value(agent.client.log_recordings.stop(active["protected"]))) if active.get("protected") else None)
        except (RecordError, TapError, OSError):
            text += "\nWARNING: released, but history could not be finalized; inspect the incomplete capture before sharing"
        return text
    return agent._run(run)


def _render(data: dict, as_json: bool) -> str:
    result = HistoryExport(data)
    return result.to_json() if as_json else result.text


def _at(value: int | None) -> str:
    if value is None:
        return "-"
    return datetime.datetime.fromtimestamp(value / 1000, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")  # noqa: UP017 -- Python 3.10


def _seconds(value: str) -> float:
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
