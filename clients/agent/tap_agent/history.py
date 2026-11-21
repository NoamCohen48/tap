"""`history` and `record`: the server's kept activity, with the agent's own steps merged in.

The server (``client.history``) keeps every call, crash, ANR and device-log line; the agent adds
only what the server cannot know: its steps (the commands it was asked to run, with their
parameters and targets) and the capture bundles it saved. Nothing here replays input.
"""
from __future__ import annotations

import argparse
import base64
import datetime
import inspect
import json
from typing import TYPE_CHECKING, Any, NoReturn

from google.protobuf import json_format

from tap_e2e import (
    FailureReason,
    HistoryIssue,
    HistoryIssueKind,
    HistoryPage,
    HistoryRecording,
    LogPriority,
    ServerError,
    TapConnection,
    TapError,
)

from tap_e2e import _gen as pb
from tap_e2e._proto import describe_selector

from .records import RecordError, UnknownHistoryError
from .targets import UsageError, parse_duration

if TYPE_CHECKING:
    from .core import Agent

FORMAT = "tap-history/2"


def _position(cursor: str, *, backward: bool, logs: bool, edge: float, devices: tuple[str, ...]) -> str:
    """What ``--after`` takes: the order and whether logs are in, the time where this page ended
    (so the next one shows exactly the agent's steps between there and its own end), the
    ``--device`` selection (serials may hold colons, so it is base64) and the server's cursor."""
    selected = base64.urlsafe_b64encode(",".join(devices).encode()).decode().rstrip("=") if devices else "-"
    return ("b" if backward else "f") + ("l" if logs else "") + f":{round(edge * 1000)}:{selected}:" + cursor


def _resumed(after: str) -> tuple[str, float, tuple[str, ...], str]:
    """``--after``'s parts: mode, edge (seconds), devices and the server's cursor."""
    mode, edge, selected, cursor = (after.split(":", 3) + ["", "", ""])[:4]
    try:
        devices = tuple(base64.urlsafe_b64decode(selected + "=" * (-len(selected) % 4)).decode().split(",")) if selected != "-" else ()
    except (ValueError, UnicodeDecodeError):
        devices = None  # type: ignore[assignment]
    if mode not in ("f", "fl", "b", "bl") or not edge.isdecimal() or devices is None or not cursor:
        _usage("--after needs the position a previous history printed after next: or older:")
    return mode, int(edge) / 1000, devices, cursor


def _usage(message: str) -> NoReturn:
    from .core import EXIT_USAGE, AgentError
    raise AgentError(message, EXIT_USAGE)


def _flags(**values: bool) -> None:
    for name, value in values.items():
        if not isinstance(value, bool):
            _usage(f"{name} is a flag")


def _at(seconds: float | None) -> str:
    if seconds is None:
        return "-"
    moment = datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc)  # noqa: UP017 -- Python 3.10
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _seconds(value: str) -> float:
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def _time(flag: str, value: str) -> float:
    try:
        parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (ValueError, TypeError):
        _usage(f"{flag} needs an ISO timestamp with a time zone, e.g. 2026-11-21T12:00:00Z")
    if parsed.tzinfo is None:
        _usage(f"{flag} needs a time zone, e.g. 2026-11-21T12:00:00Z")
    return parsed.timestamp()


# ── record ────────────────────────────────────────────────────────────────────────────────


def _named(agent: Agent, name: str) -> HistoryRecording:
    """The server recording ``name`` (or id): this session's when several connections used the name."""
    recordings = agent.client.history.recordings()
    exact = [r for r in recordings if r.id == name]
    if exact:
        return exact[0]
    found = [r for r in recordings if r.name == name]
    if len(found) > 1:
        try:
            mine = agent.records.select(name, agent.session).get("connection_id")
        except UnknownHistoryError:
            mine = None
        found = [r for r in found if r.connection_id == mine] or found
    if not found:
        _usage(f"no recording named {name!r}; `record list` shows them")
    if len(found) > 1:
        _usage(f"several connections have a recording named {name!r}: give its id ({', '.join(r.id for r in found)})")
    return found[0]


def _issues(recording: HistoryRecording) -> str:
    return "".join(f"\nWARNING: {issue}" for issue in recording.issues)


def record(agent: Agent, action: str, name: str | None, *, long: bool = False) -> str:
    """Start, stop, list or clean a named recording: a range of the session's history the server
    keeps past its retention policy. Read one with ``history --recording NAME``."""
    _flags(long=long)
    if action == "export":
        _usage("record export is now `history --recording NAME` (add --logs, --json, --details as needed)")
    if action not in ("start", "stop", "list", "clean"):
        _usage("record action must be start, stop, list or clean")
    if action == "list":
        if name is not None:
            _usage("record list takes no name")
    elif not isinstance(name, str) or not name:
        _usage(f"record {action} needs NAME")
    if long and action != "list":
        _usage("-L is only for record list")

    def run() -> str:
        if action == "list":
            rows = []
            for row in agent.client.history.recordings():
                if not long:
                    rows.append(row.name)
                    continue
                state = "recording" if row.active else "stopped"
                rows.append(f"{row.name}  {state}  id={row.id}  connection={row.connection_id}  started={_at(row.started_at)}  "
                    f"stopped={_at(row.stopped_at)}  disk={row.retained_bytes}B" + "".join(f"  [{issue}]" for issue in row.issues))
            if long and rows:
                rows.append("Disk counts the stored files a recording needs; others may share them, so cleaning it can free less.")
            return "\n".join(rows) or "no recordings"
        assert name is not None
        if action == "start":
            connection = agent._connection()
            saved = agent.client.history.start_recording(connection, name)
            try:
                agent.records.start(name, agent.session, connection.id, {}, log_status={"recording_id": saved.id})
            except RecordError as error:
                raise RecordError(f"the server is recording {name!r}, but the agent could not save its steps for it; "
                    "`record stop`/`record clean` still work") from error
            return f"recording {name!r} (devices attached later join it)"
        saved = _named(agent, name)
        if action == "stop":
            stopped: list[HistoryRecording] = []

            def stop_server() -> dict[str, Any]:
                stopped.append(agent.client.history.stop_recording(saved))
                return {"recording_id": saved.id}
            try:
                agent.records.stop(name, agent.session, before_stop=stop_server)
            except UnknownHistoryError:
                stop_server()
            return f"stopped recording {name!r}" + _issues(stopped[-1])
        if saved.active:
            _usage("stop the recording before cleaning it")
        agent.client.history.delete_recording(saved)
        try:
            agent.records.clean(name, agent.session)
        except UnknownHistoryError:
            pass
        return f"cleaned recording {name!r}"
    return agent._run(run)


# ── history ───────────────────────────────────────────────────────────────────────────────


def history(agent: Agent, action: str = "read", value: str | None = None, *, start: str | None = None,
            until: str | None = None, last: str | None = None, limit: int = 200, logs: bool = False,
            as_json: bool = False, details: bool = False, devices=(), packages=(), priority: str | None = None,
            tags=(), grep: str | None = None, actions: bool = True, driver_lines: bool = False,
            since_marker: str | None = None, until_marker: str | None = None, step: str | int | None = None,
            last_action: bool = False, recording: str | None = None, after: str | None = None) -> str:
    """Read what happened: the server's calls, crashes, ANRs and gaps (plus device logs with
    ``logs`` or any log filter), merged with the agent's steps and capture bundles. ``mark NAME``
    / ``redact REGEX`` act on this session's held devices instead."""
    from . import history_selection as selection
    _flags(logs=logs, json=as_json, details=details, actions=actions, driver_lines=driver_lines, last_action=last_action)
    try:
        devices = selection.serials(devices)
    except RecordError as error:
        _usage(str(error))
    if action not in ("read", "mark", "redact"):
        _usage("history action must be read, mark or redact")
    starts = {"--from": start, "--last": last, "--since-marker": since_marker, "--step": step,
        "--last-action": last_action or None, "--after": after}
    given = [flag for flag, bound in starts.items() if bound is not None]
    ends = [flag for flag, bound in {"--until": until, "--until-marker": until_marker}.items() if bound is not None]
    filters = bool(packages or priority or tags or grep is not None or not actions or driver_lines)
    if action != "read":
        if given or ends or recording or logs or details or filters or limit != 200:
            _usage(f"history {action} takes only {'NAME' if action == 'mark' else 'REGEX'}, --device and --json")
        return agent._run(lambda: selection.control(agent, action, value, devices, as_json=as_json))
    if value is not None:
        _usage("history read takes no value")
    if len(given) > 1:
        _usage(f"choose one start: {' or '.join(given)}")
    if len(ends) > 1:
        _usage("choose --until or --until-marker")
    if (last is not None or step is not None) and ends:
        _usage(f"{given[0]} has its own end; leave out {ends[0]}")
    if after is not None and (ends or recording or devices or filters or logs):
        _usage("--after continues an earlier read: give it only with --limit, --json and --details")
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 5000:
        _usage("--limit must be an integer from 1 to 5000")
    if priority is not None:
        try:
            LogPriority.of(priority)
        except ValueError as error:
            _usage(str(error))
    if grep is not None and (not isinstance(grep, str) or not 1 <= len(grep) <= 512):
        _usage("--grep needs a regex of 1 to 512 characters (Java syntax)")
    logs = logs or filters

    def run() -> str:
        agent._end_ended_histories()
        if after is not None:
            mode, edge, selected, cursor = _resumed(after)
            page = agent.client.history.resume(cursor, limit=limit)
            return _render(agent, page, backward=mode.startswith("b"), devices=selected, low=None, wanted_step=None,
                logs=mode.endswith("l"), as_json=as_json, details=details, edge=edge)
        since: float | None = None
        upper: float | None = None
        wanted = None
        connections: list[str] = []
        if since_marker is not None or until_marker is not None:
            # A marker name is per session: another session's (or yesterday's) marker of the same
            # name must not move this read's bounds.
            connections = agent.records.connections(agent.session)
        if start is not None:
            since = _time("--from", start)
        elif last is not None:
            try:
                seconds = parse_duration(last)
            except (UsageError, OverflowError, ValueError) as error:
                _usage(str(error))
            if seconds <= 0:
                _usage("--last must be positive")
            since = datetime.datetime.now(datetime.timezone.utc).timestamp() - seconds  # noqa: UP017 -- Python 3.10
        elif step is not None:
            row = _step(agent, step)
            if devices and row["serial"] not in devices:
                _usage("that step ran on another device than --device selects")
            wanted, connections = row["id"], [row["connection_id"]]
        elif last_action:
            newest = agent.records.timeline(limit=1, devices=devices)["steps"]
            if not newest:
                _usage("no step has run yet" + (" on the selected devices" if devices else ""))
            since = _seconds(newest[-1]["started_at"])
        if until is not None:
            upper = _time("--until", until)
        if since is not None and upper is not None and upper < since:
            _usage("--until must not precede the start")
        # A window that ends now (no start, --last, --last-action) shows its newest page first and
        # pages to older entries; an explicit start (--from, --since-marker, --step) pages forward.
        backward = start is None and since_marker is None and wanted is None
        try:
            recorded = _named(agent, recording) if recording is not None else None
            page = agent.client.history.read(connections=connections, serials=devices, recording=recorded, since=since,
                until=upper, since_marker=since_marker, until_marker=until_marker, step=wanted, logs=logs, packages=packages, min_priority=priority, tags=tags, grep=grep,
                actions=actions, driver_lines=driver_lines, newest_first=backward, limit=limit)
        except Exception as error:
            if not _unreachable(error):
                raise
            recorded = None
            page = _offline(error, upper)
        return _render(agent, page, backward=backward, devices=devices, low=since,
            wanted_step=wanted, logs=logs, as_json=as_json, details=details, recording=recorded)
    return agent._run(run)


def _unreachable(error: Exception) -> bool:
    """The server could not be asked at all (not a refusal of this read)."""
    from .core import EXIT_NO_DAEMON, AgentError
    if isinstance(error, AgentError):
        return error.exit_code == EXIT_NO_DAEMON
    if isinstance(error, ServerError):
        return error.code in ("UNAVAILABLE", "DEADLINE_EXCEEDED") and error.reason is FailureReason.UNSPECIFIED
    return isinstance(error, OSError)


def _offline(error: Exception, until: float | None) -> HistoryPage:
    """A page with no server entries, so the agent's own steps are still shown."""
    now = datetime.datetime.now(datetime.timezone.utc).timestamp()  # noqa: UP017 -- Python 3.10
    message = f"the server's history could not be read ({type(error).__name__}); only this agent's own steps are shown"
    return HistoryPage((), (), (HistoryIssue(HistoryIssueKind.UNAVAILABLE, message, None),), None, until or now)


def _step(agent: Agent, step: str | int) -> dict[str, Any]:
    if isinstance(step, bool) or not isinstance(step, (str, int)) or (isinstance(step, str) and not 1 <= len(step) <= 64):
        _usage("--step needs a step's cursor or id, as history prints them")
    value: str | int = int(step) if isinstance(step, str) and step.isdecimal() else step
    try:
        return agent.records.resolve_step(value)
    except RecordError as error:
        _usage(str(error))


def _render(agent: Agent, page: HistoryPage, *, backward: bool, devices: tuple[str, ...], low: float | None,
            wanted_step: str | None, logs: bool, as_json: bool, details: bool,
            recording: HistoryRecording | None = None, edge: float | None = None) -> str:
    """The page, oldest first, with the agent's steps and captures of the same span merged in.
    Consecutive pages own adjacent time slices (``edge`` is where the previous one ended), so a
    step without a call on any page is still shown exactly once."""
    entries = list(reversed(page.entries)) if backward else list(page.entries)
    times = [entry.at for entry in entries]
    if backward:
        span_low = min(times) if page.next_cursor and times else low
        span_high = edge if edge is not None else page.through
    else:
        span_low = edge if edge is not None else low if low is not None else (min(times) if times else None)
        span_high = max(times) + 0.001 if page.next_cursor and times else page.through
    if recording is not None:  # a recording owns only the steps taken while it was recording
        span_low = recording.started_at if span_low is None else max(span_low, recording.started_at)
        if recording.stopped_at is not None:
            span_high = min(span_high, recording.stopped_at)
    owners = {source.connection_id for source in page.sources}
    local = agent.records.timeline(start=_at(span_low) if span_low is not None else None, until=_at(span_high),
        limit=5000, devices=devices)
    called = list(dict.fromkeys(e.call.step for e in entries if e.call is not None and e.call.step))
    steps = {s["id"]: s for s in local["steps"] if s["id"] in called or _inside(s, span_high)}
    for identifier in called:  # a step that started before the page but made a call on it
        if identifier not in steps:
            try:
                steps[identifier] = agent.records.resolve_step(identifier)
            except RecordError:
                pass  # a call of another client's step, or of one no longer kept
    steps = sorted((s for s in steps.values() if (not owners or s["connection_id"] in owners)
        and (wanted_step is None or s["id"] == wanted_step)), key=lambda s: s["cursor"])
    markers = [] if logs else [m for m in local["markers"] if (not owners or m.get("connection_id") in owners)
        and (wanted_step is None or any(_during(m, s) for s in steps))]
    artifacts = [a for a in agent.records.artifact_references(start=_at(span_low) if span_low is not None else None,
        until=_at(span_high), devices=devices) if not owners or a["connection_id"] in owners]
    position = None
    if page.next_cursor:
        position = _position(page.next_cursor, backward=backward, logs=logs,
            edge=(span_low if backward else span_high) or page.through, devices=devices)
    if as_json:
        from .output import json_text
        document = page.to_dict()
        del document["next_cursor"]  # `next` is what --after takes
        document["entries"] = [entry.to_dict() for entry in entries]
        document.update(steps=steps, markers=markers, artifacts=artifacts, next=position,
            order="oldest_first", recording=recording.to_dict() if recording is not None else None)
        return json_text(document)
    by_id = {step["id"]: step for step in steps if step.get("id")}
    rows: list[tuple[float, int, str]] = []  # (time, server order, text)
    placed: set[str] = set()
    for order, entry in enumerate(entries):
        step = by_id.get(entry.call.step) if entry.call is not None and entry.call.step else None
        if step is None:
            rows.append((entry.at, order, entry.text(details=details)))
        elif step["id"] not in placed:  # a step replaces its calls, at the first of them
            placed.add(step["id"])
            rows.append((entry.at, order, _step_text(step)))
    for step in steps:
        if step.get("id") not in placed:  # it made no call (or none is kept)
            rows.append((_seconds(step["started_at"]), -1, _step_text(step)))
    for marker in markers:
        rows.append((_seconds(marker["at"]), -1, f"{marker['at']}  {marker.get('serial') or '-'}  ── marker {marker['name']}"))
    for artifact in artifacts:
        failures = artifact.get("failures", {})
        missing = f"; unavailable: {', '.join(sorted(failures))}" if failures else ""
        rows.append((_seconds(artifact["at"]), -1, f"{artifact['at']}  {artifact['serial']}  CAPTURE saved to {artifact['directory']}{missing}"))
    rows.sort(key=lambda row: (row[0], row[1]))
    lines = [text for _, _, text in rows]
    if recording is not None:
        lines.insert(0, f"recording {recording.name} ({'recording' if recording.active else 'stopped'})")
    lines += [f"WARNING: {issue}" for issue in page.issues]
    if recording is not None:
        lines += [f"WARNING: {issue}" for issue in recording.issues]
    if position is not None:
        lines.append(f"{'older' if backward else 'next'}: --after {position}")
    return "\n".join(lines) if lines else "nothing happened in this range (or none of it is kept)"


def _inside(step: dict[str, Any], span_high: float | None) -> bool:
    """A step with no call on the page is shown on it only when it ended inside the page: one
    that ran on past the page's end has its calls, and its line, on the next page."""
    end = step.get("finished_at")
    return span_high is None or (end is not None and _seconds(end) < span_high)


def _during(marker: dict[str, Any], step: dict[str, Any]) -> bool:
    """The marker was written while the step ran (a step still running has no end yet)."""
    at = _seconds(marker["at"])
    return _seconds(step["started_at"]) <= at and (not step.get("finished_at") or at <= _seconds(step["finished_at"]))


def _step_text(step: dict[str, Any]) -> str:
    """``AT  SERIAL  step N: ACTION ARGS → OUTCOME``: required arguments by value, options only
    when they differ from the command's default, targets as the selector a test would write."""
    outcome = step.get("outcome", {})
    status = outcome.get("status", "unknown") + (f" ({outcome['error']})" if outcome.get("error") else "")
    arguments = _arguments(step["action"], step.get("parameters", {}), step.get("targets", {}))
    return (f"{step['started_at']}  {step.get('serial') or '-'}  step {step['cursor']}: "
        f"{' '.join([step['action'], *arguments])} → {status}")


def _positionals(action: str) -> frozenset[str]:
    """The CLI verb's positional arguments, printed by value even when they are optional."""
    if action not in _POSITIONALS:
        from .cli import parser
        root = parser()
        verbs = next(a for a in root._actions if isinstance(a, argparse._SubParsersAction))
        verb = verbs.choices.get(action)
        _POSITIONALS[action] = frozenset(a.dest for a in verb._actions if not a.option_strings) if verb else frozenset()
    return _POSITIONALS[action]


_POSITIONALS: dict[str, frozenset[str]] = {}


def _arguments(action: str, parameters: dict[str, Any], targets: dict[str, Any]) -> list[str]:
    from .core import Agent
    method = getattr(Agent, action.replace("-", "_"), None)
    try:
        signature = inspect.signature(method) if callable(method) else None
    except (TypeError, ValueError):
        signature = None
    defaults = {name: p.default for name, p in signature.parameters.items()} if signature else {}
    result = []
    for name, value in parameters.items():
        if isinstance(value, dict) and "selector_ref" in value:
            value = _selector(targets.get(value["selector_ref"]))
        else:
            value = _value(value)
            if name in defaults and defaults[name] is not inspect.Parameter.empty and _value(defaults[name]) == value:
                continue
        default = defaults.get(name, inspect.Parameter.empty)
        if value == "null" and default is inspect.Parameter.empty:
            continue  # an optional positional the call left out
        option = name.replace("_", "-")
        if default is inspect.Parameter.empty or name in _positionals(action):
            result.append(value)
        elif default is False and value == "true":
            result.append(option)
        else:
            result.append(f"{option}={value}")
    return result


def _selector(target: Any) -> str:
    if not isinstance(target, dict):
        return "(target not resolved)"
    if target.get("redacted"):
        return "<redacted>"
    try:
        return describe_selector(json_format.ParseDict(target, pb.Selector()))
    except json_format.ParseError:
        return json.dumps(target, ensure_ascii=False, separators=(",", ":"))


def _value(value: Any) -> str:
    if isinstance(value, dict) and value.get("redacted"):
        return "<redacted>"
    if isinstance(value, str):
        return value if value and value.isprintable() and " " not in value else json.dumps(value, ensure_ascii=False)
    if isinstance(value, (list, tuple)) and not value:
        return "[]"
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def release(agent: Agent) -> str:
    """Release the held connection and stop the session's recording, if one is running."""
    def run() -> str:
        entry = agent._held()
        if entry is None:
            return f"no session {agent.session!r}"
        active = agent.records.active(agent.session)
        TapConnection(agent.client, entry.id, name=entry.name, hold=entry.hold).close()
        serials = ", ".join(device.serial for device in entry.attached_devices)
        text = f"released session {agent.session!r}" + (f" (detached {serials})" if serials else "")
        if active is None or active["connection_id"] != entry.id:
            return text
        try:
            status = json.loads(active.get("log_status") or "{}")
            recording_id = status.get("recording_id") if isinstance(status, dict) else None
            agent.records.stop(active["name"], agent.session, reason="released",
                before_stop=(lambda: {"recording_id": recording_id, "stopped": agent.client.history.stop_recording(recording_id).id})
                if recording_id else None)
        except (RecordError, TapError, OSError, ValueError):
            text += "\nWARNING: released, but the session's recording could not be stopped; `record stop NAME` stops it"
        return text
    return agent._run(run)
