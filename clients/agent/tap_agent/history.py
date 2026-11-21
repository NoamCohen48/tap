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
from .targets import UsageError, parse_duration

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
           logs: bool = False, as_json: bool = False, details: bool = False) -> str:
    """Control or export named ranges; ordinary activity needs no start command."""
    _flags(long=long, logs=logs, json=as_json, details=details)
    if action not in ("start", "stop", "list", "export", "clean"):
        _usage("record action must be start, stop, list, export or clean")
    if action == "list":
        if name is not None:
            _usage("record list takes no name")
    elif not isinstance(name, str) or not name:
        _usage(f"record {action} needs NAME")
    if long and action != "list":
        _usage("-L is only for record list")
    if (logs or as_json or details) and action != "export":
        _usage("--logs, --json and --details are only for record export")

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
            return _export_recording(agent, name, logs=logs, as_json=as_json, details=details)
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


def _export_recording(agent: Agent, name: str, *, logs: bool, as_json: bool, details: bool = False) -> str:
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
    return _render(data, as_json, details=details)


def history(agent: Agent, action: str = "read", value: str | None = None, *, start: str | int | None = None, until: str | int | None = None,
            last: str | None = None, limit: int = 200, logs: bool = False, as_json: bool = False,
            details: bool = False, devices=(), packages=(), priority: str | None = None, tags=(), grep: str | None = None,
            actions: bool = True, driver_lines: bool = False, since_marker: str | None = None, until_marker: str | None = None,
            step: str | int | None = None, last_action: bool = False, tail: int | None = None, after: str | None = None) -> str:
    """Read full time-window evidence independently of paginated agent intent."""
    from . import history_selection as selection
    from .core import AgentError
    _flags(logs=logs, json=as_json, details=details, actions=actions, driver_lines=driver_lines, last_action=last_action)
    try:
        devices = selection.serials(devices)
        if priority is not None:
            from tap_e2e import LogPriority
            LogPriority.of(priority)
        if grep is not None and (not isinstance(grep, str) or len(grep) > 512):
            _usage("--grep needs a regex of at most 512 characters (Java syntax)")
    except (RecordError, ValueError) as error:
        _usage(str(error))
    if action not in ("read", "mark", "redact"):
        _usage("history action must be read, mark or redact")
    selectors = (start is not None, last is not None, since_marker is not None, step is not None, last_action, after is not None)
    if sum(selectors) > 1:
        if last is not None:
            _usage("--last cannot be combined with other start bounds")
        _usage("choose one history start: --from, --last, --since-marker, --step, --last-action or --after")
    if step is not None and (until is not None or until_marker is not None):
        _usage("--step already has its own end; do not combine it with --until")
    if until is not None and until_marker is not None:
        _usage("choose --until or --until-marker")
    if tail is not None and (isinstance(tail, bool) or not isinstance(tail, int) or not 1 <= tail <= 100_000):
        _usage("--tail must be an integer from 1 to 100000")
    if action != "read":
        if any(selectors) or until is not None or until_marker is not None or logs or details or packages or priority or tags or grep or not actions or driver_lines or tail is not None or limit != 200:
            _usage("history mark/redact take only NAME/REGEX, device/session selection and --json")
        return agent._run(lambda: selection.control(agent, action, value, devices, as_json=as_json))
    if value is not None:
        _usage("history read takes no value; use explicit selection flags")
    logs = bool(logs or packages or priority or tags or grep or not actions or driver_lines or since_marker or until_marker or last_action or after or tail is not None)
    if last is not None:
        if start is not None or until is not None or until_marker is not None:
            _usage("--last cannot be combined with --from or --until")
        try:
            seconds = parse_duration(last)
            if seconds <= 0:
                _usage("--last must be positive")
            end = datetime.datetime.now(datetime.timezone.utc)
            start = (end - datetime.timedelta(seconds=seconds)).isoformat()
            until = end.isoformat()
        except (UsageError, OverflowError, ValueError) as error:
            _usage(str(error))
    try:
        lower, upper = _history_options(start, until, limit)
    except RecordError as error:
        _usage(str(error))
    time_view = any(bound is not None and bound[0] == "started_at" for bound in (lower, upper)) or any((since_marker, until_marker, step, last_action, after)) or tail is not None

    def run() -> str:
        anchor_low, anchor_high, wanted, anchor_metadata = selection.anchor(agent, devices=devices, since_marker=since_marker,
            until_marker=until_marker, step=step, last_action=last_action, after=after)
        selected_start, selected_until = anchor_low or start, anchor_high or until
        agent._end_ended_histories()
        step_cursor = anchor_metadata.get("step", {}).get("cursor")
        data = agent.records.timeline(start=step_cursor if wanted else selected_start,
            until=step_cursor + 1 if wanted else selected_until, limit=limit, devices=devices)
        if wanted is not None:
            data["steps"] = [row for row in data["steps"] if row["id"] == wanted]
        data.update(events=[], event_capture=[], deaths=[], selection=anchor_metadata)
        data["complete"] = data["complete"] if data["steps"] else True
        if logs:
            data["log"] = {}
        bounds = []
        for bound in _history_options(selected_start, selected_until, limit):
            if bound is None:
                bounds.append(None)
            elif bound[0] == "started_at":
                bounds.append(_seconds(bound[1]))
            else:
                at = agent.records.cursor_time(bound[1])
                if at is None:
                    data["complete"] = False
                    data["events_unavailable"] = "a step boundary is no longer retained; its evidence time cannot be reconstructed"
                    if logs:
                        data["log_unavailable"] = data["events_unavailable"]
                    return _render(data, as_json, details=details)
                bounds.append(_seconds(at))
        low, high = bounds
        data["range"] = {"from_time": low, "until_time": high, "clock": "host_receipt",
            "activity": "all" if time_view else "selected_steps_and_independent_evidence",
            "log_scope": "original_step_action_span" if wanted else "host_receipt_window"}
        owners = set(data.pop("connections"))
        try:
            discovered = agent.client.log_recordings.archives(include_events=True)
            selected_archives = [archive for archive in discovered if archive.events or not devices or archive.serial in devices]
            if packages and any(archive.closed and not archive.events for archive in selected_archives):
                _usage("--package requires original live attachment ownership; use --tag/--priority/--grep for archived logs")
            owners.update(archive.connection_id for archive in selected_archives)
        except (TapError, AgentError, OSError) as error:
            if isinstance(error, AgentError) and error.exit_code == 2:
                raise
            data["complete"] = False
            data["events_unavailable"] = f"retained archive discovery failed ({type(error).__name__})"
            if logs:
                data["log_unavailable"] = "daemon evidence could not be discovered"
        for owner in sorted(owners):
            steps = [step for step in data["steps"] if step["connection_id"] == owner]
            try:
                evidence = agent.client.log_recordings.history(owner, logs=logs,
                    step_ids=[wanted] if wanted is not None else None if time_view else [row["id"] for row in steps if row["id"]],
                    from_time=low, until_time=high, devices=devices, packages=packages, tags=tags,
                    min_priority=priority, grep=grep, actions=actions, log_step=wanted).to_dict()
            except (TapError, AgentError, OSError) as error:
                data["complete"] = False
                data["events_unavailable"] = f"retained events were not inspected ({type(error).__name__})"
                if logs:
                    data["log_unavailable"] = "daemon evidence could not be read"
                continue
            data["events"].extend(evidence["events"])
            data["deaths"].extend(evidence.get("deaths", []))
            data["event_capture"].extend(evidence["event_capture"])
            data["complete"] = bool(data["complete"] and evidence["complete"])
            if evidence.get("events_unavailable"):
                data["events_unavailable"] = evidence["events_unavailable"]
            for serial, entries in evidence.get("log", {}).items():
                key = serial if serial not in data["log"] else f"{serial}:{owner}"
                data["log"][key] = entries
        data["artifacts"] = agent.records.artifact_references(start=_at(round(low * 1000)) if low is not None else None,
            until=_at(round(high * 1000)) if high is not None else None, devices=devices)
        selection.finish(data, devices=devices, actions=actions, driver_lines=driver_lines,
            tail=tail, after=anchor_metadata.get("after"))
        if time_view and (data.get("next") is not None or data.get("older") is not None):
            data["steps_truncated"] = True
            data["complete"] = False
        if not owners:
            data["complete"] = False
        return _render(data, as_json, details=details)
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


def _render(data: dict, as_json: bool, *, details: bool = False) -> str:
    result = HistoryExport(data)
    return result.to_json() if as_json else result.render_text(details=details)


def _at(value: int | None) -> str:
    if value is None:
        return "-"
    return datetime.datetime.fromtimestamp(value / 1000, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")  # noqa: UP017 -- Python 3.10


def _seconds(value: str) -> float:
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
