"""Daemon-owned protected log ranges, retained across detach and daemon restart."""
from __future__ import annotations

import math
from collections.abc import Callable, Iterable, Sequence
from dataclasses import asdict, dataclass
from typing import TYPE_CHECKING, cast

from . import proto as pb
from ._history_deaths import death_document
from .client import mapped_errors
from .history import HistoryExport, log_entry_dict
from .logs import LogEntries, LogPriority, _entry

if TYPE_CHECKING:
    from .client import TapClient, TapConnection


@dataclass(frozen=True)
class RetainedLog:
    """Original attachment identity for an ordinary retained log archive."""

    connection_id: str
    attached_device_id: str
    serial: str
    last_cursor: int
    closed: bool
    capture_error: str
    clock_id: str = ""
    events: bool = False
    retained_bytes: int = 0


@dataclass(frozen=True)
class LogRecordingRange:
    """One original attachment's cursor range; an open range has no end cursor."""

    attached_device_id: str
    serial: str
    after_cursor: int
    through_cursor: int | None
    events: bool = False


@dataclass(frozen=True)
class LogRecording:
    """A protected range. Complete means finalized capture, not a passing test."""

    name: str
    connection_id: str
    started_at_ms: int
    stopped_at_ms: int | None
    ranges: tuple[LogRecordingRange, ...]
    complete: bool
    capture_error: str
    retained_bytes: int = 0
    agent_steps_expected: bool = False


def _recording(value) -> LogRecording:
    return LogRecording(value.name, value.client_connection_id, value.started_at_ms,
        value.stopped_at_ms if value.HasField("stopped_at_ms") else None,
        tuple(LogRecordingRange(r.attached_device_id, r.serial, r.after_cursor,
            r.through_cursor if r.HasField("through_cursor") else None, r.events) for r in value.ranges),
        value.complete, value.capture_error, value.retained_bytes, value.agent_steps_expected)


class LogRecordings:
    """Named daemon log recordings (``client.log_recordings``).

    Start pins the current attachment set without restarting capture. Rolling policy never
    evicts protected ranges, but hard disk limits still apply and are reported as capture errors.
    Controls/read need a running daemon, not a live original connection. Deletion is explicit.
    """

    def __init__(self, client: TapClient):
        self._client = client

    def archives(self, connection_id: str = "", *, include_events: bool = False, timeout: float = 30) -> tuple[RetainedLog, ...]:
        """Discover retained ordinary history; an empty connection id lists all owned daemon logs."""
        with mapped_errors():
            return tuple(RetainedLog(r.client_connection_id, r.attached_device_id, r.serial, r.last_cursor, r.closed, r.capture_error, r.clock_id, r.events, r.retained_bytes)
                for r in self._client.device_log.ListRetainedLogs(pb.ListRetainedLogsRequest(client_connection_id=connection_id), timeout=timeout).logs
                if include_events or not r.events)

    def read_archive(self, archive: RetainedLog, *, tags: Iterable[str] = (), min_priority: LogPriority | str | None = None,
                     grep: str | None = None, timeout: float = 30) -> LogEntries:
        """Read retained ordinary history without a live original connection; gaps are explicit."""
        request = pb.ReadLogRequest(client_connection_id=archive.connection_id,
            attached_device_id=archive.attached_device_id, tags=list(tags))
        if min_priority is not None:
            request.min_priority = cast(pb.LogPriority, int(LogPriority.of(min_priority)))
        if grep is not None:
            request.message_regex = grep
        entries = LogEntries()
        with mapped_errors(archive.serial):
            for response in self._client.device_log.ReadLog(request, timeout=timeout):
                entries.dropped += response.dropped
                entries.capture_error = response.capture_error or entries.capture_error
                entries.clock_id = response.clock_id or entries.clock_id
                entries.extend(_entry(e) for e in response.entries)
        return entries

    def start(self, connection: TapConnection, name: str, *, agent_steps_expected: bool = False, timeout: float = 30) -> LogRecording:
        """Protect logs from now on for the connection's current devices; names never overwrite."""
        with mapped_errors():
            return _recording(self._client.device_log.StartLogRecording(
                pb.StartLogRecordingRequest(client_connection_id=connection.id, name=name, agent_steps_expected=agent_steps_expected), timeout=timeout).recording)

    def stop(self, name: str, *, timeout: float = 30) -> LogRecording:
        """Finalize the named range; repeated stop returns the same final boundary."""
        with mapped_errors():
            return _recording(self._client.device_log.StopLogRecording(pb.StopLogRecordingRequest(name=name), timeout=timeout).recording)

    def list(self, *, timeout: float = 30) -> tuple[LogRecording, ...]:
        """List retained protected ranges, including incomplete/open recordings."""
        with mapped_errors():
            return tuple(_recording(r) for r in self._client.device_log.ListLogRecordings(
                pb.ListLogRecordingsRequest(), timeout=timeout).recordings)

    def delete(self, name: str, *, timeout: float = 30) -> None:
        """Remove protection from a stopped range; rolling eviction may then remove its logs."""
        with mapped_errors():
            self._client.device_log.DeleteLogRecording(pb.DeleteLogRecordingRequest(name=name), timeout=timeout)

    def read(self, name: str, *, serial: str | None = None, tags: Iterable[str] = (), min_priority: LogPriority | str | None = None,
             grep: str | None = None, timeout: float = 30) -> dict[str, LogEntries]:
        """Read a recording's redacted logs by serial, even after release/restart.

        Filters apply to log lines, not actions/markers/gaps. Package-name filtering is not
        available after detach; use tag, priority or message filters. Capture errors remain explicit.
        """
        recording = next((r for r in self.list(timeout=timeout) if r.name == name), None)
        if recording is None:
            raise ValueError(f"no log recording named {name!r}")
        selected = [r for r in recording.ranges if not r.events and (serial is None or r.serial == serial)]
        if serial is not None and not selected:
            raise ValueError(f"{serial!r} is not part of recording {name!r}")
        result = {}
        for r in selected:
            request = pb.ReadLogRequest(client_connection_id=recording.connection_id,
                attached_device_id=r.attached_device_id, recording=name, tags=list(tags))
            if min_priority is not None:
                request.min_priority = cast(pb.LogPriority, int(LogPriority.of(min_priority)))
            if grep is not None:
                request.message_regex = grep
            entries = LogEntries()
            with mapped_errors(r.serial):
                for response in self._client.device_log.ReadLog(request, timeout=timeout):
                    entries.dropped += response.dropped
                    entries.capture_error = response.capture_error or entries.capture_error
                    entries.clock_id = response.clock_id or entries.clock_id
                    entries.extend(_entry(e) for e in response.entries)
            result[r.serial] = entries
        return result

    def export(self, name: str, *, logs: bool = False, timeout: float = 30) -> HistoryExport:
        """Export a named range without stopping it, including durable redacted RPC events.

        Device logs are optional. Works after the original connection closes or the daemon
        restarts. Return structured data/text/JSON, never a file or a replay program.
        """
        recording = next((r for r in self.list(timeout=timeout) if r.name == name), None)
        if recording is None:
            raise ValueError(f"no recording named {name!r}")
        sources = [(RetainedLog(recording.connection_id, r.attached_device_id, r.serial,
            r.through_cursor or r.after_cursor, recording.stopped_at_ms is not None, "", events=r.events), name)
            for r in recording.ranges if r.events or logs]
        data = self._export_sources(recording.connection_id, sources, logs=logs, timeout=timeout)
        data.update(name=name, recording=name, log_capture=asdict(recording),
            state="stopped" if recording.stopped_at_ms is not None else "live")
        data["evidence_complete"] = data["complete"] and recording.complete
        data["complete"] = data["evidence_complete"] and not recording.agent_steps_expected
        if recording.agent_steps_expected:
            data["step_history_unavailable"] = "agent intent requires the original local agent history"
        return HistoryExport(data)

    def history(self, connection_id: str, *, logs: bool = False, step_ids: Iterable[str] | None = None,
                from_time: float | None = None, until_time: float | None = None, timeout: float = 30) -> HistoryExport:
        """Export retained SDK calls for an original connection, optionally with device logs.

        Call from script cleanup or a fresh client after release/restart. ``from_time`` is
        inclusive and ``until_time`` exclusive, in finite host-receipt epoch seconds, for all
        entry kinds. Calls that started earlier but completed in the window remain visible.
        Independent crashes/ANRs and their redacted diagnostics are included without ``logs``.
        ``step_ids=None`` includes all calls; an explicit empty collection includes no calls,
        but never removes independent death evidence. Ordinary retention may have expired
        evidence; missing capture is explicit, not an empty successful result.
        """
        if not connection_id:
            raise ValueError("history needs the original connection id")
        for bound in (from_time, until_time):
            if bound is not None and (isinstance(bound, bool) or not isinstance(bound, (int, float)) or not math.isfinite(bound)):
                raise ValueError("history time bounds must be finite epoch seconds")
        if from_time is not None and until_time is not None and until_time < from_time:
            raise ValueError("until_time must not precede from_time")
        sources = [(archive, None) for archive in self.archives(connection_id, include_events=True, timeout=timeout)
            if archive.events or logs]
        wanted = frozenset(step_ids) if step_ids is not None else None
        def selected(entry: pb.LogEntry) -> bool:
            # Receipt time is common to RPC completions, independent deaths and log lines.
            at = entry.host_time_ms / 1000
            if (from_time is not None and at < from_time) or (until_time is not None and at >= until_time):
                return False
            if wanted is not None and entry.HasField("event"):
                return entry.event.HasField("step") and entry.event.step in wanted
            if wanted is not None and entry.HasField("action") and entry.action.HasField("step"):
                return entry.action.step in wanted
            return True
        data = self._export_sources(connection_id, sources, logs=logs, timeout=timeout, select=selected)
        if wanted is not None or from_time is not None or until_time is not None:
            data["range"] = {"step_ids": sorted(wanted) if wanted is not None else None,
                "from_time": from_time, "until_time": until_time, "clock": "host_receipt"}
        return HistoryExport(data)

    def _export_sources(self, connection_id: str, sources: Sequence[tuple[RetainedLog, str | None]], *, logs: bool, timeout: float,
                        select: Callable[[pb.LogEntry], bool] | None = None) -> dict:
        data = {"format": "tap-history/1", "connection_id": connection_id, "steps": [], "events": [],
            "event_capture": [], "deaths": [], "complete": bool(sources)}
        if logs:
            data["log"] = {}
        for archive, recording in sources:
            request = pb.ReadLogRequest(client_connection_id=connection_id, attached_device_id=archive.attached_device_id)
            if recording is not None:
                request.recording = recording
            entries = LogEntries()
            with mapped_errors(archive.serial or None):
                for response in self._client.device_log.ReadLog(request, timeout=timeout):
                    entries.dropped += response.dropped
                    entries.capture_error = response.capture_error or entries.capture_error
                    entries.clock_id = response.clock_id or entries.clock_id
                    entries.extend(_entry(entry) for entry in response.entries if select is None or select(entry))
            capture = {"attached_device_id": archive.attached_device_id, "dropped": entries.dropped,
                "capture_error": entries.capture_error, "clock_id": entries.clock_id,
                "capture_scope": "recording" if recording is not None else "connection"}
            if archive.events:
                data["event_capture"].append(capture)
                for entry in entries:
                    if entry.event is not None:
                        event = entry.event.to_dict()
                        event["cursor"] = f"{archive.attached_device_id}:{entry.cursor}"
                        event["received_at"] = log_entry_dict(entry)["at"]
                        event["received_monotonic_ns"] = str(entry.host_monotonic_ns)
                        data["events"].append(event)
                        if entry.event.indeterminate:
                            data["complete"] = False
                    elif entry.death is not None:
                        data["deaths"].append(death_document(entry, connection_id, entries.clock_id))
                    elif entry.gap is not None:
                        capture.setdefault("gaps", []).append(log_entry_dict(entry))
            else:
                key = archive.serial
                if key in data["log"]:
                    key = f"{key}:{archive.attached_device_id}"
                data["log"][key] = dict(capture, entries=[log_entry_dict(entry) for entry in entries])
            if not archive.closed or entries.dropped or entries.capture_error or any(entry.gap is not None for entry in entries):
                data["complete"] = False
        if not data["event_capture"]:
            data["events_unavailable"] = "no retained RPC event stream for this range"
            data["complete"] = False
        data["events"].sort(key=lambda event: (event["at"], event["seq"]))
        data["evidence_complete"] = data["complete"]
        return data
