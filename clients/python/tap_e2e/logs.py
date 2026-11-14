"""The device log of an attachment: logcat lines, Tap's actions and a test's markers."""
# pyright: reportAttributeAccessIssue=false, reportArgumentType=false, reportCallIssue=false

from __future__ import annotations

import enum
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from typing import TYPE_CHECKING

import grpc

from . import _gen as pb
from .client import mapped_errors

if TYPE_CHECKING:
    from typing_extensions import Self

    from .device import Device


class LogPriority(enum.IntEnum):
    """A logcat priority; a higher value is more severe."""

    VERBOSE = 1
    DEBUG = 2
    INFO = 3
    WARN = 4
    ERROR = 5
    FATAL = 6

    @property
    def letter(self) -> str:
        """The letter logcat prints: ``V``, ``D``, ``I``, ``W``, ``E`` or ``F``."""
        return self.name[0]

    @classmethod
    def of(cls, value: LogPriority | str) -> LogPriority:
        """``value`` itself, or the priority a name or letter stands for (``"warn"``, ``"W"``)."""
        if isinstance(value, LogPriority):
            return value
        wanted = value.strip().upper()
        for priority in cls:
            if wanted in (priority.name, priority.letter, "WARNING" if priority is cls.WARN else ""):
                return priority
        raise ValueError(f"unknown log priority {value!r}: use one of {', '.join(p.name.lower() for p in cls)}")


@dataclass(frozen=True)
class LogLine:
    """One logcat line. A message of several lines arrives as several entries."""

    device_time: float
    """The device's clock when the line was logged (Unix seconds); it need not agree with the
    host's."""
    uid: int | None
    """The Linux uid of the process that logged it; ``None`` when logcat printed no number."""
    pid: int
    tid: int
    priority: LogPriority | None
    tag: str
    message: str
    packages: tuple[str, ...] = ()
    """The apps installed under ``uid``: one for an app, several for apps sharing a uid, none for
    a system process or an app no longer installed."""


@dataclass(frozen=True)
class LogAction:
    """Something Tap did on the device: one entry when it starts and one when it finishes.

    A command the driver runs (a tap, typed text, a wait) is logged by the driver itself, so its
    entries are ordered exactly among the app's lines; queries (``exists``, ``count``, a
    snapshot) are left out. A call the host runs over ADB (an app launch, a device setting) is
    written by the server when the call starts and ends."""

    operation: str
    """The operation in snake_case: ``tap``, ``wait_visible``, ``launch``, ``set_network``."""
    target: str | None
    """What it acted on: a command's selector, an app call's package. Never typed text."""
    finished: bool
    outcome: str | None
    """With ``finished``: ``ok``, a command error code (``NOT_FOUND``) or a failure reason."""
    duration: float | None
    """With ``finished``: how long it took, in seconds."""
    request_id: int | None
    """Driver commands: the request the driver answered."""
    step: str | None = None
    """The step the call that caused it was made in (``tap_e2e.step``), or None."""


@dataclass(frozen=True)
class LogEntry:
    """One entry of the device log: exactly one of ``line``, ``marker``, ``action`` and ``gap``
    is set. ``str(entry)`` is the entry as one line of text."""

    cursor: int
    """1, 2, 3, … per attachment, in the order the server got the entries. Pass it as ``since``
    to read what came after."""
    at: float
    """The server host's clock when it got the entry (Unix seconds), the clock of
    ``LoggedEvent.at``."""
    line: LogLine | None = None
    marker: str | None = None
    """The name given to ``DeviceLog.mark``."""
    action: LogAction | None = None
    gap: str | None = None
    """The capture was interrupted here (the device disconnected, logcat ended) and lines may be
    missing: why."""

    def __str__(self) -> str:
        if self.line is not None:
            line = self.line
            millis = round(line.device_time * 1000)
            uid = "?" if line.uid is None else str(line.uid)
            letter = line.priority.letter if line.priority else "?"
            return (
                f"{millis // 1000}.{millis % 1000:03d} {uid:>5} {line.pid:>5} {line.tid:>5} "
                f"{letter} {line.tag}: {line.message}"
            )
        if self.marker is not None:
            return f"── marker {self.marker}"
        if self.gap is not None:
            return f"── gap {self.gap}"
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
        return ""


class LogEntries(list[LogEntry]):
    """What ``DeviceLog.read`` returns: the entries, oldest first."""

    dropped: int = 0
    """How many entries older than the starting point the server had already forgotten (it keeps
    the newest 16 MiB per attachment). Always 0 for a ``tail`` read."""

    @property
    def text(self) -> str:
        """The entries as text, one per line."""
        return "".join(f"{entry}\n" for entry in self)

    @property
    def lines(self) -> list[LogLine]:
        """The logcat lines among the entries."""
        return [entry.line for entry in self if entry.line is not None]

    @property
    def messages(self) -> list[str]:
        """The message of each logcat line."""
        return [line.message for line in self.lines]


def _entry(entry: pb.LogEntry) -> LogEntry:
    kind = entry.WhichOneof("kind")
    line = action = None
    if kind == "line":
        value = entry.line
        line = LogLine(
            device_time=value.device_time_ms / 1000,
            uid=value.uid if value.HasField("uid") else None,
            pid=value.pid,
            tid=value.tid,
            priority=LogPriority(value.priority) if value.priority in iter(LogPriority) else None,
            tag=value.tag,
            message=value.message,
            packages=tuple(value.packages),
        )
    elif kind == "action":
        value = entry.action
        action = LogAction(
            operation=value.operation,
            target=value.target if value.HasField("target") else None,
            finished=value.finished,
            outcome=value.outcome if value.HasField("outcome") else None,
            duration=value.duration_ms / 1000 if value.HasField("duration_ms") else None,
            request_id=value.request_id if value.HasField("request_id") else None,
            step=value.step if value.HasField("step") else None,
        )
    return LogEntry(
        cursor=entry.cursor,
        at=entry.host_time_ms / 1000,
        line=line,
        marker=entry.marker if kind == "marker" else None,
        action=action,
        gap=entry.gap if kind == "gap" else None,
    )


class LogFollower:
    """The live device log (``DeviceLog.follow``): iterate it for entries as the server gets
    them. Iteration blocks until the next entry and ends when the device is detached or
    ``close()`` is called (from any thread); use it as a context manager to close it."""

    def __init__(self, call, serial: str):
        self._call = call
        self._serial = serial
        self._closed = False
        self.dropped: int = 0
        """How many entries older than the starting point the server had already forgotten;
        known once the first entry has been read."""

    def __iter__(self) -> Iterator[LogEntry]:
        try:
            with mapped_errors(self._serial):
                for response in self._call:
                    self.dropped += response.dropped
                    for entry in response.entries:
                        yield _entry(entry)
        except Exception:
            # A closed follower ends quietly: the cancellation is its own doing.
            if not (self._closed or self._call.code() is grpc.StatusCode.CANCELLED):
                raise

    def close(self) -> None:
        """Stops following; an iteration in progress ends."""
        self._closed = True
        self._call.cancel()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc) -> None:
        self.close()


class DeviceLog:
    """The device log since the attachment opened (``Device.logs``): every logcat line of the
    main, system and crash buffers, with the actions Tap ran and the markers a test wrote in
    between, in the order they happened.

    The server captures it from the moment the device is attached, unless the attachment turned
    that off (``attach_device(capture_logs=False)``; every call here then raises ``ServerError``
    with ``FailureReason.DAEMON_PRECONDITION``). It keeps the newest 16 MiB and forgets them at
    ``detach``. Secrets are replaced by ``<redacted>`` in everything read: see ``redact``.

    Typical use, the app's warnings during one step::

        device.logs.mark("checkout")
        pay.tap()
        errors = device.logs.read(since="checkout", packages=["com.shop"], min_priority="warn")
        assert not errors.lines, errors.text
    """

    def __init__(self, device: Device):
        self._device = device

    def _request(self, since, tail, since_last_action, follow, packages, min_priority, tags, grep, actions, step=None):
        if sum((since is not None, tail is not None, since_last_action)) > 1:
            raise ValueError("pass only one of since, tail and since_last_action")
        if isinstance(packages, str) or isinstance(tags, str):
            raise TypeError("packages and tags are lists of strings")
        request = pb.ReadLogRequest(
            client_connection_id=self._device.owner_connection.id,
            attached_device_id=self._device.attached_device_id,
            follow=follow,
            packages=list(packages),
            tags=list(tags),
            exclude_actions=not actions,
        )
        if isinstance(since, LogEntry):
            request.after_cursor = since.cursor
        elif isinstance(since, bool):
            raise TypeError("since is a marker name, a cursor or a LogEntry")
        elif isinstance(since, int):
            request.after_cursor = since
        elif isinstance(since, str):
            request.marker = since
        elif since is not None:
            raise TypeError("since is a marker name, a cursor or a LogEntry")
        if tail is not None:
            request.tail = tail
        if since_last_action:
            request.last_action = True
        if min_priority is not None:
            request.min_priority = int(LogPriority.of(min_priority))
        if grep is not None:
            request.message_regex = grep
        if step is not None:
            request.step = step
        return request

    def read(
        self,
        *,
        since: str | int | LogEntry | None = None,
        tail: int | None = None,
        since_last_action: bool = False,
        packages: Iterable[str] = (),
        min_priority: LogPriority | str | None = None,
        tags: Iterable[str] = (),
        grep: str | None = None,
        actions: bool = True,
        step: str | None = None,
        timeout: float = 30.0,
    ) -> LogEntries:
        """The entries so far, oldest first.

        Where to start (at most one; none = the oldest entry the server still has): ``since`` a
        marker's name (from the newest marker with that name, raising ``ServerError`` with
        ``FailureReason.UNKNOWN_LOG_MARKER`` when there is none), a cursor or a ``LogEntry``
        (what came after it); ``tail`` the newest N entries that pass the filters;
        ``since_last_action`` from the start of the newest action.

        Filters, applied to logcat lines only (markers, actions and gaps always pass):
        ``packages`` keeps the lines of these apps, ``min_priority`` lines at least this severe
        (``LogPriority`` or a name such as ``"warn"``), ``tags`` lines with one of these tags,
        ``grep`` lines whose message contains a match of this regular expression (Java syntax,
        tested after redaction). ``actions=False`` leaves Tap's action entries out.

        ``step`` narrows the read to one step (the id ``tap_e2e.step`` yielded): the entries
        from the step's first action to its last, nothing when it ran no action."""
        self._device._ensure_usable("logs.read")
        request = self._request(since, tail, since_last_action, False, packages, min_priority, tags, grep, actions, step)
        entries = LogEntries()
        with mapped_errors(self._device.serial):
            for response in self._device.client.device_log.ReadLog(request, timeout=timeout):
                entries.dropped += response.dropped
                entries.extend(_entry(entry) for entry in response.entries)
        return entries

    def follow(
        self,
        *,
        since: str | int | LogEntry | None = None,
        tail: int | None = None,
        since_last_action: bool = False,
        packages: Iterable[str] = (),
        min_priority: LogPriority | str | None = None,
        tags: Iterable[str] = (),
        grep: str | None = None,
        actions: bool = True,
    ) -> LogFollower:
        """The entries from the starting point and then every new one as it is logged, until
        the returned ``LogFollower`` is closed or the device detached. Same starting points and
        filters as ``read``; with none, it starts at the oldest entry (``tail=1`` starts near
        now). Read it on its own thread, or to wait for a line::

            with device.logs.follow(since="checkout", tags=["Payments"]) as log:
                done = next(e for e in log if e.line and "settled" in e.line.message)

        A follower that reads too slowly is ended with ``ServerError`` (``RESOURCE_EXHAUSTED``);
        follow again ``since`` the last entry it got."""
        self._device._ensure_usable("logs.follow")
        request = self._request(since, tail, since_last_action, True, packages, min_priority, tags, grep, actions)
        return LogFollower(self._device.client.device_log.ReadLog(request), self._device.serial)

    def mark(self, name: str, timeout: float = 30.0) -> int:
        """Writes a named marker into the log, at the time the server gets the call, and
        returns its cursor. Read from it with ``read(since=name)``; a later marker with the
        same name replaces it as the starting point. ``name`` is 1 to 200 characters on one
        line."""
        self._device._ensure_usable("logs.mark")
        with mapped_errors(self._device.serial):
            return self._device.client.device_log.MarkLog(
                pb.MarkLogRequest(
                    client_connection_id=self._device.owner_connection.id,
                    attached_device_id=self._device.attached_device_id,
                    name=name,
                ),
                timeout=timeout,
            ).cursor

    def redact(self, *patterns: str, timeout: float = 30.0) -> None:
        """Adds regular expressions (Java syntax) whose matches are replaced by ``<redacted>``
        in everything read from now on, lines already captured included: by ``read`` and
        ``follow``, ``Device.device_logs()`` and the failure artifacts. A match is replaced
        whole; a pattern with a capturing group has only its first group replaced
        (``r"session=(\\w+)"`` keeps ``session=``).

        Redacted without any call: ``Authorization`` / ``Cookie`` style headers, ``password=``
        / ``token=`` style values, bearer tokens, JWTs, and the text of every ``sensitive``
        ``set_text`` / ``type_text`` on this device. At most 64 patterns per attachment."""
        self._device._ensure_usable("logs.redact")
        with mapped_errors(self._device.serial):
            self._device.client.device_log.RedactLog(
                pb.RedactLogRequest(
                    client_connection_id=self._device.owner_connection.id,
                    attached_device_id=self._device.attached_device_id,
                    patterns=list(patterns),
                ),
                timeout=timeout,
            )
