"""The server's history (``client.history``): what happened, kept after detach and restart.

The server keeps every connection's calls, the crashes and ANRs on its devices and those
devices' logs, under its retention policy (``tap config logging-policy``). A history recording
keeps one connection's range past that policy until it is deleted.
"""
from __future__ import annotations

import builtins
import datetime
import enum
import json
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, cast

from . import _gen as pb
from google.protobuf import json_format
from google.protobuf.descriptor import FieldDescriptor

from ._proto import app_death, describe_selector, logged_event
from .client import mapped_errors
from .logs import LogAction, LogLine, LogPriority, log_action, log_line
from .models import AppDeath, AppDeathKind, Artifact, LoggedEvent

if TYPE_CHECKING:
    from .client import TapClient, TapConnection

FORMAT = "tap-history/2"
"""The ``format`` of ``HistoryPage.to_dict()`` and ``to_json()``."""


def _iso(seconds: float) -> str:
    moment = datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc)  # noqa: UP017 -- Python 3.10
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


class HistoryIssueKind(enum.Enum):
    """What evidence is missing (``HistoryIssue.kind``)."""

    EXPIRED = "expired"
    """The server's retention removed entries the read asked for: the range starts before what
    is kept, entries in it passed the retention age, or entries went while paging. A read
    without ``since`` starts at the oldest kept entry and so reports none for that."""
    UNAVAILABLE = "unavailable"
    """A requested or recorded source is not kept or could not be read."""
    INCOMPLETE = "incomplete"
    """The server could not keep all of the source (a storage limit or failure, a torn write),
    or withholds its kept entries because a redaction could not be applied to them."""
    PACKAGE_UNKNOWN = "package_unknown"
    """Some lines were stored without their app, so a package filter returned them unfiltered."""
    UNKNOWN = "unknown"
    """A kind this client does not know yet."""


_ISSUE_KINDS = {
    pb.HISTORY_ISSUE_KIND_EXPIRED: HistoryIssueKind.EXPIRED,
    pb.HISTORY_ISSUE_KIND_UNAVAILABLE: HistoryIssueKind.UNAVAILABLE,
    pb.HISTORY_ISSUE_KIND_INCOMPLETE: HistoryIssueKind.INCOMPLETE,
    pb.HISTORY_ISSUE_KIND_PACKAGE_UNKNOWN: HistoryIssueKind.PACKAGE_UNKNOWN,
}


@dataclass(frozen=True)
class HistoryIssue:
    """Evidence that is missing without a place in time. Where capture stopped and resumed is
    a ``gap`` entry instead."""

    kind: HistoryIssueKind
    message: str
    """Short and readable, never a trace or a command's output."""
    source_id: str | None
    """The source it is about; None for a recording's own storage problem."""
    missing_entries: int = 0
    """For ``EXPIRED``: how many of the source's entries were removed, counted before filters; 0
    when the server does not know."""

    def __str__(self) -> str:
        return f"{self.kind.value}: {self.message}"

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {"kind": self.kind.value, "message": self.message, "source_id": self.source_id}
        if self.missing_entries:
            result["missing_entries"] = self.missing_entries
        return result


def _issue(value: pb.HistoryIssue) -> HistoryIssue:
    return HistoryIssue(_ISSUE_KINDS.get(value.kind, HistoryIssueKind.UNKNOWN), value.message,
        value.source_id or None, value.missing_entries)


@dataclass(frozen=True)
class HistorySource:
    """Something the server keeps history of: one attachment's device log
    (``attached_device_id`` and ``serial`` set), or one connection's calls and the crashes and
    ANRs on its devices (both None)."""

    id: str
    connection_id: str
    attached_device_id: str | None
    serial: str | None
    clock_id: str
    """The server run that captured it: compare ``HistoryEntry.monotonic_ns`` only within it."""
    closed: bool
    """The connection ended or the device was detached: nothing more will be added."""
    last_sequence: int
    """From ``History.sources``, the newest entry's sequence; in a ``HistoryPage``, the newest one
    the read covers, fixed by its first page."""
    retained_bytes: int
    """Bytes on disk, some possibly shared with other sources."""
    issues: tuple[HistoryIssue, ...] = ()
    """From ``History.sources`` only; a ``HistoryPage`` has every issue in ``HistoryPage.issues``."""

    @property
    def is_device_log(self) -> bool:
        return self.attached_device_id is not None

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "kind": "device_log" if self.is_device_log else "connection",
            "connection_id": self.connection_id, "attached_device_id": self.attached_device_id,
            "serial": self.serial, "clock_id": self.clock_id, "closed": self.closed,
            "last_sequence": self.last_sequence, "retained_bytes": self.retained_bytes,
            "issues": [issue.to_dict() for issue in self.issues]}


def _source(value: pb.HistorySource) -> HistorySource:
    kind = value.WhichOneof("kind")
    device = value.device_log if kind == "device_log" else None
    connection = device.connection_id if device is not None else value.connection.connection_id
    return HistorySource(value.id, connection, device.attached_device_id if device else None,
        device.serial if device else None, value.clock_id, value.closed, value.last_sequence,
        value.retained_bytes, tuple(_issue(i) for i in value.issues))


@dataclass(frozen=True)
class HistoryEntry:
    """One thing that happened: exactly one of ``line``, ``action``, ``marker`` (a device log),
    ``call``, ``death`` (a connection) and ``gap`` (either) is set."""

    source_id: str
    sequence: int
    """1, 2, 3, … per source, in the order the server received the entries."""
    at: float
    """When the server received it, in Unix seconds: for a call, when it ended (``call.at`` is
    when it began). Pages are ordered by this, then by source, then by sequence."""
    monotonic_ns: int
    """The server's monotonic clock at receipt, comparable within one ``HistorySource.clock_id``."""
    serial: str | None
    """The device: the source's, a call's or a death's; None for a gap of a connection."""
    attached_device_id: str | None
    """The attachment it belongs to, when there is one."""
    line: LogLine | None = None
    action: LogAction | None = None
    marker: str | None = None
    call: LoggedEvent | None = None
    death: AppDeath | None = None
    gap: str | None = None
    """Capture stopped here and entries may be missing until it resumed: why."""

    @property
    def kind(self) -> str:
        """``line``, ``action``, ``marker``, ``call``, ``death`` or ``gap``."""
        for name in ("line", "action", "marker", "call", "death", "gap"):
            if getattr(self, name) is not None:
                return name
        return "unknown"

    @property
    def summary(self) -> str:
        """The entry as one line without its time or device: ``W Tag: message``, ``── tap  …``,
        ``── marker NAME``, ``call exists text("Pay") → ok``, ``CRASH com.shop (pid 4) …``,
        ``GAP: why``."""
        if self.line is not None:
            letter = self.line.priority.letter if self.line.priority else "?"
            return f"{letter} {self.line.tag}: {self.line.message}"
        if self.action is not None:
            action = self.action
            text = f"── {action.operation}"
            if action.target is not None:
                text += f"  {action.target}"
            if action.finished:
                text += f"  → {action.outcome}"
            if action.duration is not None:
                text += f"  {round(action.duration * 1000)} ms"
            return text
        if self.marker is not None:
            return f"── marker {self.marker}"
        if self.call is not None:
            call = self.call
            outcome = "unknown" if call.indeterminate else "ok" if call.ok else "failed"
            failure = call.error or call.failure or {}
            if failure:
                outcome += f" ({failure.get('code') or failure.get('reason') or 'unknown'})"
            return f"call {_call_text(call)} → {outcome}"
        if self.death is not None:
            death = self.death
            what = "ANR" if death.kind is AppDeathKind.ANR else "CRASH"
            context = ": ".join(part for part in (death.exception_class, death.message) if part)
            return f"{what} {death.package_name} (pid {death.pid})" + (f": {context}" if context else "")
        if self.gap is not None:
            return f"GAP: {self.gap}"
        return ""

    def text(self, *, details: bool = False) -> str:
        """``AT  SERIAL  SUMMARY``; with ``details`` a crash's stack trace follows, one ``|`` line
        each."""
        result = f"{_iso(self.at)}  {self.serial or '-'}  {self.summary}"
        if details and self.death is not None:
            trace = self.death.stack_trace
            lines = trace.splitlines() if trace else ["(no stack trace was captured)"]
            result += "".join(f"\n    | {line}" for line in lines)
        return _terminal_safe(result)

    def __str__(self) -> str:
        return self.text()

    def to_dict(self) -> dict[str, Any]:
        """JSON-ready: identity, times (``at`` ISO 8601 UTC, ``monotonic_ns`` a string) and the
        entry's own fields under its ``kind``."""
        result: dict[str, Any] = {"kind": self.kind, "source_id": self.source_id, "sequence": self.sequence,
            "at": _iso(self.at), "monotonic_ns": str(self.monotonic_ns),
            "serial": self.serial, "attached_device_id": self.attached_device_id}
        if self.line is not None:
            line = self.line
            result["line"] = {"device_time": _iso(line.device_time), "uid": line.uid, "pid": line.pid,
                "tid": line.tid, "priority": line.priority.letter if line.priority else None, "tag": line.tag,
                "message": line.message, "packages": list(line.packages),
                "packages_observed_at": None if line.packages_observed_at is None else _iso(line.packages_observed_at)}
        elif self.action is not None:
            action = self.action
            result["action"] = {"operation": action.operation, "target": action.target, "finished": action.finished,
                "outcome": action.outcome, "duration_ms": None if action.duration is None else round(action.duration * 1000),
                "request_id": action.request_id, "step": action.step}
        elif self.marker is not None:
            result["marker"] = self.marker
        elif self.call is not None:
            result["call"] = self.call.to_dict()
        elif self.death is not None:
            death = self.death
            result["death"] = {"kind": death.kind.value, "sequence": death.sequence, "package_name": death.package_name,
                "process_name": death.process_name, "pid": death.pid, "device_time": _iso(death.at) if death.at else None,
                "exception_class": death.exception_class, "message": death.message, "stack_trace": death.stack_trace,
                "watched": death.watched, "handled": death.handled}
        elif self.gap is not None:
            result["gap"] = self.gap
        return result


def _call_text(call: LoggedEvent) -> str:
    """A call as one short line: the operation, its target as builder code and its other
    fields as ``name=value`` (``exists text("Pay")``, ``app launch com.shop``)."""
    if call.command:
        try:
            command = json_format.ParseDict(call.command, pb.Command(), ignore_unknown_fields=True)
        except json_format.ParseError:
            return _compact(call.command)
        operation = command.WhichOneof("op")
        if operation is None:
            return "command"
        return " ".join([operation.replace("_", "-"), *_fields(getattr(command, operation))])
    body = dict(call.app or call.device or {})
    if not body:
        return "call"
    words = ["app" if call.app else "device", str(body.pop("operation", "?")).replace("_", "-")]
    if "package_name" in body:
        words.append(str(body.pop("package_name")))
    words += [f"{name}={value if isinstance(value, str) and value and ' ' not in value else _compact(value)}" for name, value in body.items()]
    return " ".join(words)


def _fields(message: Any) -> list[str]:
    words = []
    for field, value in message.ListFields():
        if field.message_type is not None and field.message_type.full_name == "tap.v1.Selector" and not field.is_repeated:
            words.append(describe_selector(value))
        elif field.message_type is not None:
            words.append(f"{field.name}={_compact(json_format.MessageToDict(value) if not field.is_repeated else [json_format.MessageToDict(v) for v in value])}")
        elif field.enum_type is not None:
            words.append(f"{field.name}={field.enum_type.values_by_number[value].name if value in field.enum_type.values_by_number else value}")
        elif field.type == FieldDescriptor.TYPE_BOOL:
            words.append(field.name)
        else:
            words.append(f"{field.name}={value if isinstance(value, (int, float)) else _compact(value)}")
    return words


def _compact(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _entry(value: pb.HistoryEntry, sources: dict[str, HistorySource]) -> HistoryEntry:
    source = sources.get(value.source_id)
    kind = value.WhichOneof("kind")
    serial = source.serial if source is not None else None
    attached = source.attached_device_id if source is not None else None
    call = death = None
    if kind == "call":
        call = logged_event(value.call)
        serial, attached = call.serial or None, call.attached_device_id or None
    elif kind == "death":
        death = app_death(value.death.death, value.death.serial)
        serial, attached = value.death.serial or None, value.death.attached_device_id or None
    return HistoryEntry(value.source_id, value.sequence, value.received_at_ms / 1000, value.received_monotonic_ns,
        serial, attached,
        line=log_line(value.line) if kind == "line" else None,
        action=log_action(value.action) if kind == "action" else None,
        marker=value.marker if kind == "marker" else None,
        call=call, death=death,
        gap=value.gap if kind == "gap" else None)


@dataclass(frozen=True)
class HistorySpan:
    """The part of one source a recording keeps: after ``after_sequence``, through
    ``through_sequence`` (None while recording)."""

    source_id: str
    after_sequence: int
    through_sequence: int | None


@dataclass(frozen=True)
class HistoryRecording:
    """A range of one connection's history the server keeps past its retention policy, until
    ``History.delete_recording``. It covers the connection's devices, also those attached after
    it started."""

    id: str
    """Names it in ``read(recording=…)`` and every control, also after its connection ended."""
    connection_id: str
    name: str
    """Unique among the connection's recordings, not across the server."""
    started_at: float
    stopped_at: float | None
    """None while recording."""
    spans: tuple[HistorySpan, ...]
    issues: tuple[HistoryIssue, ...]
    retained_bytes: int
    """Bytes on disk its spans need; others may share them, so deleting it can free less."""

    @property
    def active(self) -> bool:
        """Still recording: not stopped yet."""
        return self.stopped_at is None

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "connection_id": self.connection_id, "name": self.name,
            "started_at": _iso(self.started_at), "stopped_at": None if self.stopped_at is None else _iso(self.stopped_at),
            "spans": [{"source_id": s.source_id, "after_sequence": s.after_sequence, "through_sequence": s.through_sequence} for s in self.spans],
            "issues": [issue.to_dict() for issue in self.issues], "retained_bytes": self.retained_bytes}


def _recording(value: pb.HistoryRecording) -> HistoryRecording:
    return HistoryRecording(value.id, value.connection_id, value.name, value.started_at_ms / 1000,
        value.stopped_at_ms / 1000 if value.HasField("stopped_at_ms") else None,
        tuple(HistorySpan(s.source_id, s.after_sequence, s.through_sequence if s.HasField("through_sequence") else None) for s in value.spans),
        tuple(_issue(i) for i in value.issues), value.retained_bytes)


@dataclass(frozen=True)
class HistoryPage(Artifact):
    """One page of ``History.read``: its entries in the order read, the sources it covered and
    the evidence that is missing. Iterating it yields the entries; ``next_page()`` reads the
    next page. As an ``Artifact`` its ``bytes`` are ``to_json()``."""

    entries: tuple[HistoryEntry, ...]
    sources: tuple[HistorySource, ...]
    issues: tuple[HistoryIssue, ...]
    """Every issue of the read, each once, the sources' own included."""
    next_cursor: str | None
    """None when this is the last page."""
    through: float
    """The read's end (exclusive), in Unix seconds, fixed by its first page."""
    _history: History | None = field(default=None, repr=False, compare=False)
    _limit: int = field(default=200, repr=False, compare=False)
    _timeout: float = field(default=30.0, repr=False, compare=False)

    media_type = "application/json"
    extension = "json"

    def __iter__(self) -> Iterator[HistoryEntry]:
        return iter(self.entries)

    def __len__(self) -> int:
        return len(self.entries)

    def next_page(self, limit: int | None = None) -> HistoryPage | None:
        """The next page of the same read, or None after the last."""
        if self.next_cursor is None or self._history is None:
            return None
        return self._history.resume(self.next_cursor, limit=limit or self._limit, timeout=self._timeout)

    @property
    def text(self) -> str:
        """The entries one per line (``HistoryEntry.text``), then a ``WARNING:`` line per issue."""
        return self.render_text()

    def render_text(self, *, details: bool = False) -> str:
        """``text``; with ``details`` crash stack traces too."""
        lines = [entry.text(details=details) for entry in self.entries]
        lines += [f"WARNING: {_terminal_safe(str(issue))}" for issue in self.issues]
        return "\n".join(lines) if lines else "nothing happened in this range (or none of it is kept)"

    def to_dict(self) -> dict[str, Any]:
        """JSON-ready, as ``tap-history/2``: ``entries``, ``sources``, ``issues``, ``through``,
        ``next_cursor``."""
        return {"format": FORMAT, "through": _iso(self.through), "next_cursor": self.next_cursor,
            "sources": [s.to_dict() for s in self.sources], "issues": [i.to_dict() for i in self.issues],
            "entries": [e.to_dict() for e in self.entries]}

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=2)

    @property
    def bytes(self) -> builtins.bytes:
        return self.to_json().encode()


def _connection_id(value: TapConnection | str) -> str:
    return value if isinstance(value, str) else value.id


def _recording_id(value: HistoryRecording | str) -> str:
    return value if isinstance(value, str) else value.id


class History:
    """The server's history (``client.history``). It needs no live connection or attachment:
    read it from a test's teardown, after a crash or from another process.

    Typical use, what one test did, with the app's warnings::

        page = client.history.read(connections=[connection], since_marker="test checkout",
                                   logs=True, min_priority="warn")
        print(page.text)

    The server keeps history under its retention policy; keep a range past it with
    ``start_recording`` / ``stop_recording``."""

    def __init__(self, client: TapClient):
        self._client = client

    def read(
        self,
        *,
        connections: Iterable[TapConnection | str] = (),
        sources: Iterable[HistorySource | str] = (),
        serials: Iterable[str] = (),
        recording: HistoryRecording | str | None = None,
        since: float | datetime.datetime | str | None = None,
        until: float | datetime.datetime | str | None = None,
        since_marker: str | None = None,
        until_marker: str | None = None,
        step: str | None = None,
        logs: bool = False,
        packages: Iterable[str] = (),
        min_priority: LogPriority | str | None = None,
        tags: Iterable[str] = (),
        grep: str | None = None,
        actions: bool = True,
        driver_lines: bool = False,
        newest_first: bool = False,
        limit: int = 200,
        timeout: float = 30.0,
    ) -> HistoryPage:
        """One page of history, oldest first (``newest_first``: the most recent ``limit``
        entries of the range first).

        Selection (each given one must match): ``connections`` (``TapConnection`` or id),
        ``sources``, device ``serials`` (their logs, calls and deaths) and a ``recording``'s
        range. Nothing selects all the server keeps.

        ``since`` / ``until``: a time, as Unix seconds, an aware ``datetime`` or an ISO 8601
        string with its offset (``"2026-11-21T12:00:00Z"``); ``since`` is included, ``until`` not.
        ``since_marker`` / ``until_marker`` instead: from or through the newest marker with that
        name on each selected device, the marker included (``ServerError``
        ``UNKNOWN_LOG_MARKER`` when none). Select ``connections`` too to leave out other
        connections' markers of the same name. Without an end the read ends when it starts, so
        its pages never move.

        ``step`` keeps what happened in that step (``tap_e2e.step``): its calls, the deaths and
        gaps between the start of its first call and the end of its last, and each device log
        from its first action to its last; a name used again after another step adds a second
        such stretch. The bounds still apply.

        Calls, deaths and gaps are always read; ``logs=True`` adds the device logs, which
        ``packages``, ``min_priority``, ``tags``, ``grep`` (Java regex), ``actions`` and
        ``driver_lines`` filter (they never drop gaps, calls or deaths)."""
        if not 1 <= limit <= 5000:
            raise ValueError("limit must be 1 to 5000")
        request = pb.ReadHistoryRequest(limit=limit, newest_first=newest_first, include_logs=logs)
        request.selection.connection_ids.extend(_connection_id(c) for c in connections)
        request.selection.source_ids.extend(s if isinstance(s, str) else s.id for s in sources)
        request.selection.serials.extend(serials)
        if recording is not None:
            request.selection.recording_id = _recording_id(recording)
        for name, moment, marker in (("from", since, since_marker), ("until", until, until_marker)):
            keyword = "since" if name == "from" else "until"
            if moment is not None and marker is not None:
                raise ValueError(f"give {keyword} or {keyword}_marker, not both")
            if marker is not None:
                setattr(request, f"{name}_marker", marker)
            elif moment is not None:
                setattr(request, f"{name}_time_ms", round(_seconds(keyword, moment) * 1000))
        if step is not None:
            request.step = step
        log_filter = pb.HistoryLogFilter(packages=list(packages), tags=list(tags), exclude_actions=not actions,
            include_driver_lines=driver_lines)
        if min_priority is not None:
            log_filter.min_priority = cast(pb.LogPriority, int(LogPriority.of(min_priority)))
        if grep is not None:
            log_filter.message_regex = grep
        if log_filter != pb.HistoryLogFilter():
            if not logs:
                raise ValueError("log filters need logs=True")
            request.log_filter.CopyFrom(log_filter)
        return self._read(request, timeout)

    def resume(self, cursor: str, *, limit: int = 200, timeout: float = 30.0) -> HistoryPage:
        """The page after ``cursor`` (a ``HistoryPage.next_cursor``), of the read it came from: same selection, bounds, filters and order. A cursor keeps
        working in another process and after a server restart, not across server versions."""
        if not 1 <= limit <= 5000:
            raise ValueError("limit must be 1 to 5000")
        return self._read(pb.ReadHistoryRequest(cursor=cursor, limit=limit), timeout)

    def pages(self, **read_arguments: Any) -> Iterator[HistoryPage]:
        """Every page of a ``read`` with these arguments, in order."""
        page: HistoryPage | None = self.read(**read_arguments)
        while page is not None:
            yield page
            page = page.next_page()

    def _read(self, request: pb.ReadHistoryRequest, timeout: float) -> HistoryPage:
        with mapped_errors():
            response = self._client.history_stub.ReadHistory(request, timeout=timeout)
        sources = {s.id: _source(s) for s in response.sources}
        return HistoryPage(tuple(_entry(e, sources) for e in response.entries), tuple(sources.values()),
            tuple(_issue(i) for i in response.issues), response.page.next_cursor or None,
            response.page.through_time_ms / 1000, self, request.limit or 200, timeout)

    def sources(
        self,
        *,
        connections: Iterable[TapConnection | str] = (),
        sources: Iterable[HistorySource | str] = (),
        serials: Iterable[str] = (),
        recording: HistoryRecording | str | None = None,
        timeout: float = 30.0,
    ) -> tuple[HistorySource, ...]:
        """The kept sources, of ended connections and detached devices too, selected as in
        ``read``."""
        selection = pb.HistorySelection(connection_ids=[_connection_id(c) for c in connections],
            source_ids=[s if isinstance(s, str) else s.id for s in sources], serials=list(serials))
        if recording is not None:
            selection.recording_id = _recording_id(recording)
        with mapped_errors():
            response = self._client.history_stub.ListHistorySources(pb.ListHistorySourcesRequest(selection=selection), timeout=timeout)
        return tuple(_source(s) for s in response.sources)

    def start_recording(self, connection: TapConnection | str, name: str, *, timeout: float = 30.0) -> HistoryRecording:
        """Keeps ``connection``'s history from now on, devices it attaches later included,
        until the recording is stopped and deleted. It stops by itself when the connection
        closes. A device whose capture already failed is included with an ``INCOMPLETE``
        issue. ``name``: 1 to 80 ASCII letters, digits,
        dots, dashes and underscores, unique among the connection's recordings."""
        with mapped_errors():
            return _recording(self._client.history_stub.StartHistoryRecording(
                pb.StartHistoryRecordingRequest(connection_id=_connection_id(connection), name=name), timeout=timeout).recording)

    def stop_recording(self, recording: HistoryRecording | str, *, timeout: float = 30.0) -> HistoryRecording:
        """Ends the recording's range, also after its connection ended; stopping it again
        returns it unchanged. Calls still running then are a gap at its end."""
        with mapped_errors():
            return _recording(self._client.history_stub.StopHistoryRecording(
                pb.StopHistoryRecordingRequest(recording_id=_recording_id(recording)), timeout=timeout).recording)

    def recordings(self, *, connections: Iterable[TapConnection | str] = (), timeout: float = 30.0) -> tuple[HistoryRecording, ...]:
        """The recordings of these connections (all when none is given), stopped or not."""
        with mapped_errors():
            response = self._client.history_stub.ListHistoryRecordings(
                pb.ListHistoryRecordingsRequest(connection_ids=[_connection_id(c) for c in connections]), timeout=timeout)
        return tuple(_recording(r) for r in response.recordings)

    def delete_recording(self, recording: HistoryRecording | str, *, timeout: float = 30.0) -> None:
        """Deletes a stopped recording; retention may then remove its entries. Files saved
        separately are not touched."""
        with mapped_errors():
            self._client.history_stub.DeleteHistoryRecording(
                pb.DeleteHistoryRecordingRequest(recording_id=_recording_id(recording)), timeout=timeout)


def _seconds(keyword: str, value: float | datetime.datetime | str) -> float:
    if isinstance(value, datetime.datetime):
        moment = value
    elif isinstance(value, str):
        try:
            moment = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError(f"{keyword} is a time; for a marker name use {keyword}_marker=") from None
    else:
        return float(value)
    if moment.tzinfo is None:
        raise ValueError(f"{keyword} needs a time zone (a trailing Z or an offset such as +02:00)")
    return moment.timestamp()


def _terminal_safe(value: str) -> str:
    """Control characters (other than newlines) shown as ``\\uXXXX``, so an app's log line
    cannot move the terminal's cursor or change its colours."""
    return "".join(char if char == "\n" or not (ord(char) < 32 or 127 <= ord(char) < 160)
        else f"\\u{ord(char):04x}" for char in value)
