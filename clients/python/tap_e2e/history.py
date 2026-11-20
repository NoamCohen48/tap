"""Portable, redacted history exports shared by SDK scripts and the agent viewer."""
from __future__ import annotations

import copy
import datetime
import json
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from ._history_deaths import combined_deaths, death_rows

if TYPE_CHECKING:
    from .logs import LogEntry


@dataclass(frozen=True)
class HistoryExport:
    """A consistent history snapshot, not a replay script or proof of app correctness.

    ``data`` contains high-level ``steps`` when the caller recorded intent, durable RPC
    ``events``, independent ``deaths`` with available redacted diagnostics, capture metadata
    and optional device ``log`` entries. Missing evidence,
    redaction and indeterminate outcomes are explicit. Export does not stop capture.
    """

    data: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        """Return an independent JSON-ready copy; retain uncertainty and capture metadata."""
        result = copy.deepcopy(self.data)
        result["deaths"] = combined_deaths(result)
        return result

    def to_json(self) -> str:
        """Serialize to JSON without writing files; callers choose stdout or artifact storage."""
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=2)

    @property
    def text(self) -> str:
        """Readable chronological activity, including independent crash/ANR summaries."""
        return self.render_text()

    def render_text(self, *, details: bool = False) -> str:
        """Readable chronological activity, with commands, targets and outcomes, not a catalog.

        RPC calls carrying a displayed high-level step id are not duplicated in text; JSON
        retains them. Log lines are observations, never inferred assertions or causal proof.
        """
        rows: list[tuple[str, str]] = []
        steps = self.data.get("steps", [])
        step_ids = {step.get("id") for step in steps}
        for step in steps:
            parameters = copy.deepcopy(step.get("parameters", {}))
            targets = step.get("targets", {})
            for key, value in parameters.items():
                if isinstance(value, dict) and "selector_ref" in value:
                    parameters[key] = targets.get(value["selector_ref"], value)
            outcome = step.get("outcome", {})
            status = outcome.get("status", "unknown")
            if outcome.get("error"):
                status += f" ({outcome['error']})"
            cursor = step.get("cursor", step.get("seq", step.get("id", "?")))
            at = step.get("started_at", "")
            rows.append((at, f"{at}  {cursor}  {step.get('serial') or '?'}  {step.get('action', '?')} "
                f"{json.dumps(parameters, ensure_ascii=False, separators=(',', ':'))} → {status}"))
        if not self.data.get("log"):
            for marker in self.data.get("markers", []):
                at = marker.get("at", "")
                rows.append((at, f"{at}  ── marker {marker.get('name', '')}"))
        for event in self.data.get("events", []):
            if event.get("step") in step_ids:
                continue
            call = event.get("command") or event.get("app") or event.get("device") or {}
            outcome = "unknown" if event.get("indeterminate") else "ok" if event.get("ok") else "failed"
            failure = event.get("error") or event.get("failure") or {}
            if failure:
                outcome += f" ({failure.get('code') or failure.get('reason') or 'unknown'})"
            at = event.get("at", "")
            rows.append((at, f"{at}  {event.get('cursor', event.get('seq', '?'))}  {event.get('serial', '?')}  "
                f"{json.dumps(call, ensure_ascii=False, separators=(',', ':'))} → {outcome}"))
        for serial, capture in self.data.get("log", {}).items():
            for entry in capture.get("entries", []):
                at = entry.get("at", "")
                line = entry.get("line")
                if line is not None:
                    detail = f"{line.get('priority', '?')} {line.get('tag', '')}: {line.get('message', '')}"
                elif "gap" in entry:
                    detail = f"GAP: {entry['gap']}"
                elif "marker" in entry:
                    detail = f"marker {entry['marker']}"
                else:
                    detail = json.dumps(entry.get("action", {}), ensure_ascii=False, separators=(',', ':'))
                rows.append((at, f"{at}  {serial}  {detail}"))
        rows.extend(death_rows(self.data, details=details))
        lines = [line for _, line in sorted(rows, key=lambda row: row[0])]
        if self.data.get("recording"):
            lines.insert(0, f"{self.data['recording']} ({self.data.get('state', 'snapshot')})")
        if self.data.get("steps_truncated"):
            lines.append("WARNING: agent intent is paginated; the time-window evidence is not restricted to this step page")
        if self.data.get("dropped_steps"):
            lines.append(f"WARNING: {self.data['dropped_steps']} step cursors in this range are no longer retained")
        for capture in list(self.data.get("event_capture", [])) + list(self.data.get("log", {}).values()):
            if capture.get("capture_error"):
                lines.append(f"WARNING: {capture['capture_error']}")
            if capture.get("dropped"):
                lines.append(f"WARNING: {capture['dropped']} entries are unavailable")
            for gap in capture.get("gaps", []):
                lines.append(f"WARNING: {gap.get('at', '')} gap: {gap.get('gap', 'capture interrupted')}")
        for key in ("step_history_unavailable", "log_unavailable", "events_unavailable"):
            if self.data.get(key):
                lines.append(f"WARNING: {self.data[key]}")
        if self.data.get("next") is not None:
            lines.append(f"next: --from {self.data['next']}")
        if self.data.get("older") is not None:
            lines.append(f"older: --until {self.data['older']}")
        if not lines:
            lines.append("no retained activity")
        return "\n".join(_terminal_safe(line) for line in lines)


def log_entry_dict(entry: LogEntry) -> dict[str, Any]:
    """Serialize redacted log evidence without inventing missing fields or associations."""
    at = datetime.datetime.fromtimestamp(entry.at, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")  # noqa: UP017 -- Python 3.10
    result: dict[str, Any] = {"cursor": entry.cursor, "at": at}
    if entry.death is not None:
        result["kind"] = "death"
        result["death"] = copy.deepcopy(entry.death)
    elif entry.line is not None:
        line = entry.line
        result.update(kind="line", line={"device_time": line.device_time, "uid": line.uid, "pid": line.pid,
            "tid": line.tid, "priority": line.priority.letter if line.priority else None, "tag": line.tag,
            "message": line.message, "packages": list(line.packages)})
    elif entry.action is not None:
        action = entry.action
        result.update(kind="action", action={"operation": action.operation, "target": action.target,
            "finished": action.finished, "outcome": action.outcome, "duration": action.duration,
            "request_id": action.request_id, "step": action.step})
    elif entry.marker is not None:
        result.update(kind="marker", marker=entry.marker)
    elif entry.gap is not None:
        result.update(kind="gap", gap=entry.gap)
    elif entry.event is not None:
        result.update(kind="event", event=entry.event.to_dict())
    return result


def _terminal_safe(value: str) -> str:
    return "".join(f"\\u{ord(char):04x}" if ord(char) < 32 or 127 <= ord(char) < 160 else char for char in value)
