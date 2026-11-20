"""Private, cross-process storage for the step history of every agent session.

A history is what one held connection did, as the agent's own steps (its commands with their
parameters), and the markers written between them. It starts by itself with the session's first
step and stops when the session is released; nothing has to be switched on. This is client
intent, not a copy of the daemon's bounded event history. SQLite commits an intent before input;
a separate per-history SQLite lease is held across execution so clean and another process cannot
race that step. No global database writer spans device I/O.

Each step has an id, which the agent sends with the step's calls (``tap_e2e.step``): the server
writes it on the device log's action entries, so a step and what it did on the device are
matched by it.
"""
from __future__ import annotations

import contextlib
import datetime
import json
import os
import pathlib
import re
import sqlite3
import stat
import time
import uuid
from collections.abc import Callable, Iterator
from typing import Any

FORMAT = "tap-agent-recording/2"
# Stopped histories kept when a new one starts; older ones are removed.
KEEP_STOPPED = 20
MAX_STEPS = 10_000
MAX_STEP_BYTES = 1024 * 1024
MAX_RECORD_BYTES = 32 * 1024 * 1024

_SCHEMA = (
    """CREATE TABLE records (
 id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, session TEXT NOT NULL,
 connection_id TEXT NOT NULL, started_at TEXT NOT NULL, stopped_at TEXT,
 state TEXT NOT NULL, stop_reason TEXT
)""",
    "CREATE UNIQUE INDEX one_active_per_session ON records(session) WHERE state='recording'",
    """CREATE TABLE steps (
 record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
 seq INTEGER NOT NULL, action TEXT NOT NULL, kind TEXT NOT NULL, parameters TEXT NOT NULL,
 targets TEXT NOT NULL DEFAULT '{}', serial TEXT, started_at TEXT NOT NULL,
 finished_at TEXT, duration_ms INTEGER, status TEXT NOT NULL, error_type TEXT,
 PRIMARY KEY(record_id, seq)
)""",
)
# Version 2: a step's id, and the markers between steps (``after_seq``: the last step before it).
_SCHEMA_2 = (
    "ALTER TABLE steps ADD COLUMN step_id TEXT",
    """CREATE TABLE markers (
 record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
 after_seq INTEGER NOT NULL, name TEXT NOT NULL, at TEXT NOT NULL, serial TEXT, cursor INTEGER
)""",
)
# Version 3: the app deaths each step reported; steps of earlier versions reported none.
_SCHEMA_3 = ("ALTER TABLE steps ADD COLUMN deaths TEXT NOT NULL DEFAULT '[]'",)
_SCHEMA_4 = ("ALTER TABLE records ADD COLUMN protected TEXT", "ALTER TABLE records ADD COLUMN log_devices TEXT NOT NULL DEFAULT '{}'", "ALTER TABLE records ADD COLUMN log_status TEXT",)
_SCHEMA_5 = (
    "CREATE TABLE history_cursors(cursor INTEGER PRIMARY KEY AUTOINCREMENT, step_key TEXT)",
    "ALTER TABLE steps ADD COLUMN cursor INTEGER",
    "INSERT INTO history_cursors(step_key) SELECT record_id || ':' || seq FROM steps ORDER BY started_at,record_id,seq",
    "UPDATE steps SET cursor=(SELECT cursor FROM history_cursors WHERE step_key=steps.record_id || ':' || steps.seq)",
    "CREATE UNIQUE INDEX step_cursor ON steps(cursor)",
    "CREATE INDEX markers_record ON markers(record_id)",
    "DELETE FROM history_cursors",  # sqlite_sequence survives pruning; committed cursors are never reused.
)
_MIGRATIONS = {1: _SCHEMA_2, 2: _SCHEMA_3, 3: _SCHEMA_4, 4: _SCHEMA_5}
VERSION = 5


class RecordError(Exception):
    """Recording state/storage failed; never use it as a reason to replay device input."""


class UnknownHistoryError(RecordError):
    """No local history exists; unlike unavailable/corrupt storage, remote log-only recovery is safe."""


class _NoCatalog(UnknownHistoryError):
    """The catalog file exists but no recording was ever started in it (another process created
    the file and has not committed its schema yet, or a first start failed)."""

    def __init__(self) -> None:
        super().__init__("no step histories are saved yet; every session records its steps from its first one")


def _now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _json(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    except (ValueError, TypeError, OverflowError) as error:
        raise RecordError("recording arguments are not finite JSON data; no input was sent") from error


def _decode(value: str) -> dict[str, Any]:
    try:
        result = json.loads(value)
    except (ValueError, TypeError) as error:
        raise RecordError("recording contains invalid stored JSON") from error
    if not isinstance(result, dict):
        raise RecordError("recording contains invalid stored step data")
    return result


def _bytes(value: str) -> int:
    try:
        return len(value.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise RecordError("recording data must contain valid Unicode; no input was sent") from error


def _history_bound(value: str | int | None) -> tuple[str, Any] | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise RecordError("history bounds need a step cursor or ISO timestamp")
    if isinstance(value, int) or (isinstance(value, str) and value.isdecimal()):
        if isinstance(value, str) and len(value) > 19:
            raise RecordError("step cursor must be a positive 64-bit integer")
        cursor = int(value)
        if not 1 <= cursor <= 2**63 - 1:
            raise RecordError("step cursor must be a positive 64-bit integer")
        return "cursor", cursor
    try:
        parsed = datetime.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timezone required")
        return "started_at", parsed.astimezone(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    except (ValueError, TypeError, OverflowError) as error:
        raise RecordError("history time bounds need an ISO timestamp with timezone, e.g. 2026-11-17T12:00:00Z") from error


def _history_options(start: str | int | None, until: str | int | None, limit: int):
    if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 5000:
        raise RecordError("limit must be an integer from 1 to 5000")
    lower, upper = _history_bound(start), _history_bound(until)
    if lower is not None and upper is not None and lower[0] == upper[0] and lower[1] > upper[1]:
        raise RecordError("--until must not precede --from")
    return lower, upper


def _step_document(row: sqlite3.Row, *, sequence: int | None = None) -> dict[str, Any]:
    step = {"seq": row["seq"] if sequence is None else sequence, "cursor": row["cursor"], "id": row["step_id"],
        **{key: row[key] for key in ("action", "kind", "serial", "started_at", "finished_at", "duration_ms")}}
    step["parameters"] = _decode(row["parameters"])
    step["targets"] = _decode(row["targets"])
    step["outcome"] = {"status": row["status"], "error": row["error_type"]}
    try:
        step["app_deaths"] = json.loads(row["deaths"])
    except ValueError as error:
        raise RecordError("recording contains invalid stored app-death data") from error
    return step


class RecordStore:
    """Named archives under one private client directory; construction has no filesystem I/O."""

    def __init__(self, directory: str | os.PathLike[str]):
        self.directory = pathlib.Path(directory)
        self.path = self.directory / "catalog.sqlite3"

    def _private_root(self, create: bool) -> None:
        if create:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or (hasattr(os, "getuid") and info.st_uid != os.getuid()):
            raise RecordError("recording directory must be a real directory owned by this user")
        if stat.S_IMODE(info.st_mode) & 0o077:
            raise RecordError("recording directory must have owner-only permissions (0700)")

    @staticmethod
    def _private_file(path: pathlib.Path, create: bool) -> None:
        if create:
            try:
                descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            except FileExistsError:
                pass
            else:
                os.close(descriptor)
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or (hasattr(os, "getuid") and info.st_uid != os.getuid()):
            raise RecordError("recording storage must be a regular file owned by this user")
        if stat.S_IMODE(info.st_mode) & 0o077:
            raise RecordError("recording storage must have owner-only permissions (0600)")

    @contextlib.contextmanager
    def _db(self, *, write: bool = False, create: bool = False) -> Iterator[sqlite3.Connection]:
        connection = None
        try:
            self._private_root(create)
            self._private_file(self.path, create)
            connection = sqlite3.connect(self.path, timeout=5, isolation_level=None)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA synchronous=FULL")
            # Switching journal mode may return SQLITE_BUSY immediately instead of invoking
            # SQLite's busy timeout when simultaneous first opens need to upgrade locks.
            # Retry only this idempotent setup, never a transaction or device command.
            deadline = time.monotonic() + 5
            while True:
                try:
                    connection.execute("PRAGMA journal_mode=WAL")
                    break
                except sqlite3.OperationalError as error:
                    code = getattr(error, "sqlite_errorcode", None)
                    # sqlite_errorcode was added in Python 3.11; 5 is SQLITE_BUSY.
                    busy = (code & 0xFF == 5) if isinstance(code, int) else str(error) == "database is locked"
                    if not busy or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.01)
            version = connection.execute("PRAGMA user_version").fetchone()[0]
            if version == 0 and create:
                # The schema and its version commit together, so a crash leaves version 0 and no
                # tables, and of two first starts the second sees the first one's schema.
                connection.execute("BEGIN IMMEDIATE")
                version = connection.execute("PRAGMA user_version").fetchone()[0]
                if version == 0:
                    for statement in _SCHEMA:
                        connection.execute(statement)
                    connection.execute("PRAGMA user_version=1")
                    version = 1
                connection.execute("COMMIT")
            if version == 0:
                raise _NoCatalog()
            if version in _MIGRATIONS:
                # Up to VERSION: a catalog just created, or one an earlier tap-agent wrote (its
                # recordings stay, as stopped histories).
                connection.execute("BEGIN IMMEDIATE")
                version = connection.execute("PRAGMA user_version").fetchone()[0]
                while version in _MIGRATIONS:
                    for statement in _MIGRATIONS[version]:
                        connection.execute(statement)
                    version += 1
                    connection.execute(f"PRAGMA user_version={version}")
                connection.execute("COMMIT")
            if version != VERSION:
                raise RecordError("unknown agent recording catalog version")
            connection.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield connection
            connection.commit()
        except (sqlite3.Error, OSError) as error:
            raise RecordError(f"recording storage unavailable: {error}") from error
        finally:
            if connection is not None:
                connection.close()  # uncommitted work is rolled back

    def _exists(self) -> bool:
        return os.path.lexists(self.path)

    def _lease_path(self, identifier: str) -> pathlib.Path:
        if re.fullmatch(r"[0-9a-f]{32}", identifier) is None:
            raise RecordError("invalid recording identity")
        return self.directory / f"{identifier}.lease.sqlite3"

    def _create_lease(self, identifier: str) -> None:
        path = self._lease_path(identifier)
        self._private_file(path, True)
        with contextlib.closing(sqlite3.connect(path)) as connection:
            connection.execute("CREATE TABLE lease (id INTEGER)")
            connection.commit()

    @contextlib.contextmanager
    def lease(self, identifier: str) -> Iterator[None]:
        """Non-blocking execution/control lease, released by SQLite even after process death."""
        connection = None
        try:
            self._private_root(False)
            path = self._lease_path(identifier)
            self._private_file(path, False)
            connection = sqlite3.connect(path, timeout=0, isolation_level=None)
            connection.execute("BEGIN IMMEDIATE")
            yield
        except sqlite3.OperationalError as error:
            raise RecordError("recording is busy; another step/control may be in progress; no new input was sent") from error
        except (sqlite3.Error, OSError) as error:
            raise RecordError(f"recording lease unavailable: {error}") from error
        finally:
            if connection is not None:
                connection.close()

    def active(self, session: str) -> dict[str, Any] | None:
        """Last locally known active recording; no daemon RPC and no store creation."""
        if not self._exists():
            return None
        try:
            with self._db() as connection:
                row = connection.execute("SELECT * FROM records WHERE session=? AND state='recording'", (session,)).fetchone()
                return dict(row) if row is not None else None
        except _NoCatalog:
            return None

    def select(self, name: str | None, session: str) -> dict[str, Any]:
        if not self._exists():
            raise UnknownHistoryError("no step histories are saved yet; every session records its steps from its first one")
        with self._db() as connection:
            if name is not None:
                row = connection.execute("SELECT * FROM records WHERE name=?", (name,)).fetchone()
            else:
                row = connection.execute("SELECT * FROM records WHERE session=? ORDER BY state='recording' DESC, started_at DESC, rowid DESC LIMIT 1", (session,)).fetchone()
            if row is None:
                raise UnknownHistoryError(f"no step history named {name!r}; `history` lists them" if name is not None else f"session {session!r} has no step history yet")
            return dict(row)

    def ensure(self, session: str, connection_id: str) -> dict[str, Any]:
        """The history of this session's held connection, started if there is none.

        A history left active by a connection that has ended is stopped first, and one that
        reached its size limit is stopped and followed by a new one, so recording never has to
        be switched on and never blocks input for being full. Starting a history removes the
        stopped ones beyond the newest ``KEEP_STOPPED``."""
        removed: list[str] = []
        protection = None
        log_devices = "{}"
        with self._db(write=True, create=True) as connection:
            row = connection.execute("SELECT * FROM records WHERE session=? AND state='recording'", (session,)).fetchone()
            if row is not None:
                used = connection.execute("SELECT count(*), coalesce(sum(length(CAST(parameters AS BLOB))+length(CAST(targets AS BLOB))+1024),0) FROM steps WHERE record_id=?", (row["id"],)).fetchone()
                full = used[0] >= MAX_STEPS or used[1] + MAX_STEP_BYTES > MAX_RECORD_BYTES
                if row["connection_id"] == connection_id and not full:
                    return dict(row)
                if row["connection_id"] == connection_id:
                    protection = row["protected"]
                    log_devices = row["log_devices"]
                self._stop(connection, row["id"], "full" if row["connection_id"] == connection_id else "connection ended")
            identifier = uuid.uuid4().hex
            base = re.sub(r"[^A-Za-z0-9_.-]", "-", session).strip("-.")[:40] or "session"
            # Local time, as `history` shows a history's times.
            base = f"{base}-{datetime.datetime.now():%Y%m%d-%H%M%S}"
            name, suffix = base, 1
            while connection.execute("SELECT 1 FROM records WHERE name=?", (name,)).fetchone():
                suffix += 1
                name = f"{base}-{suffix}"
            self._create_lease(identifier)
            connection.execute("INSERT INTO records(id,name,session,connection_id,started_at,state,protected,log_devices) VALUES(?,?,?,?,?,'recording',?,?)", (identifier, name, session, connection_id, _now(), protection, log_devices))
            old = [r[0] for r in connection.execute("SELECT id FROM records WHERE state='stopped' AND protected IS NULL ORDER BY started_at DESC, rowid DESC LIMIT -1 OFFSET ?", (KEEP_STOPPED,))]
            for stale in old:
                connection.execute("DELETE FROM records WHERE id=?", (stale,))
            removed = old
            created = dict(connection.execute("SELECT * FROM records WHERE id=?", (identifier,)).fetchone())
        for stale in removed:
            self._lease_path(stale).unlink(missing_ok=True)
        return created

    def start(self, name: str, session: str, connection_id: str, devices: dict[str, str], *, log_status: dict[str, Any] | None = None) -> dict[str, Any]:
        """Start a protected range in the same history store; full partitions stay protected."""
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", name):
            raise RecordError("recording name must be 1..80 ASCII letters/digits/dots/dashes/underscores")
        active = self.active(session)
        with contextlib.ExitStack() as stack:
            if active is not None:
                stack.enter_context(self.lease(active["id"]))
                if active.get("protected"):
                    raise RecordError("stop the current named recording before starting another")
            with self._db(write=True, create=True) as connection:
                if connection.execute("SELECT 1 FROM records WHERE name=? OR protected=?", (name, name)).fetchone():
                    raise RecordError(f"recording {name!r} already exists")
                if active is not None:
                    self._stop(connection, active["id"], "recording started")
                identifier = uuid.uuid4().hex
                self._create_lease(identifier)
                connection.execute("INSERT INTO records(id,name,session,connection_id,started_at,state,protected,log_devices) VALUES(?,?,?,?,?,'recording',?,?)", (identifier, name, session, connection_id, _now(), name, _json(devices)))
                connection.execute("UPDATE records SET log_status=? WHERE id=?", (_json(log_status) if log_status is not None else None, identifier))
        return self.select(name, session)

    @staticmethod
    def _stop(connection: sqlite3.Connection, identifier: str, reason: str) -> None:
        connection.execute("UPDATE records SET state='stopped',stopped_at=coalesce(stopped_at,?),stop_reason=coalesce(stop_reason,?) WHERE id=?", (_now(), reason, identifier))
        connection.execute("UPDATE steps SET status='unknown' WHERE record_id=? AND status='running'", (identifier,))

    def list(self) -> list[dict[str, Any]]:
        if not self._exists():
            return []
        try:
            with self._db() as connection:
                return [dict(row) for row in connection.execute("SELECT records.*, (SELECT count(*) FROM steps WHERE record_id=records.id) AS steps FROM records ORDER BY started_at,rowid")]
        except _NoCatalog:
            return []

    @staticmethod
    def _capacity(connection: sqlite3.Connection, identifier: str, parameters: str, targets: str, *, replacing: int | None = None) -> None:
        size = _bytes(parameters) + _bytes(targets) + 1024  # fixed metadata/outcome headroom
        row = connection.execute("SELECT count(*), coalesce(sum(length(CAST(parameters AS BLOB))+length(CAST(targets AS BLOB))+1024),0) FROM steps WHERE record_id=? AND seq!=?", (identifier, replacing or 0)).fetchone()
        if size > MAX_STEP_BYTES or row[0] >= MAX_STEPS or row[1] + size > MAX_RECORD_BYTES:
            raise RecordError("recording size/step limit reached; stop/export it before more input; no new input was sent")

    def begin(self, recording: dict[str, Any], action: str, parameters: dict[str, Any], *, kind: str = "action", step_id: str | None = None) -> int:
        """Caller holds its lease; commit a pending intent before any device mutation.

        A step an earlier process left ``running`` (it died mid-step) becomes ``unknown``: the
        lease is held, so nothing else is running it. An unknown outcome does not block later
        steps; it stays in the history and makes its export ``complete: false``."""
        encoded = _json(parameters)
        with self._db(write=True) as connection:
            current = connection.execute("SELECT state FROM records WHERE id=?", (recording["id"],)).fetchone()
            if current is None or current[0] != "recording":
                raise RecordError("the session's history was stopped/removed before execution; no input was sent")
            connection.execute("UPDATE steps SET status='unknown' WHERE record_id=? AND status='running'", (recording["id"],))
            self._capacity(connection, recording["id"], encoded, "{}")
            sequence = connection.execute("SELECT coalesce(max(seq),0)+1 FROM steps WHERE record_id=?", (recording["id"],)).fetchone()[0]
            cursor = connection.execute("INSERT INTO history_cursors DEFAULT VALUES").lastrowid
            connection.execute("DELETE FROM history_cursors")
            connection.execute("INSERT INTO steps(record_id,seq,action,kind,parameters,started_at,status,step_id,cursor) VALUES(?,?,?,?,?,?,'running',?,?)", (recording["id"], sequence, action, kind, encoded, _now(), step_id, cursor))
            return sequence

    def mark(self, recording: dict[str, Any], name: str, serial: str | None, cursor: int | None) -> None:
        """A marker after the history's last step (``cursor``: its entry in the device log)."""
        with self._db(write=True) as connection:
            after = connection.execute("SELECT coalesce(max(seq),0) FROM steps WHERE record_id=?", (recording["id"],)).fetchone()[0]
            connection.execute("INSERT INTO markers(record_id,after_seq,name,at,serial,cursor) VALUES(?,?,?,?,?,?)", (recording["id"], after, name, _now(), serial, cursor))

    def marker_names(self) -> dict[str, list[str]]:
        """Every history's markers as ``name@cursor`` by history id, oldest first."""
        if not self._exists():
            return {}
        try:
            with self._db() as connection:
                names: dict[str, list[str]] = {}
                for row in connection.execute("SELECT record_id,name,cursor FROM markers ORDER BY rowid"):
                    names.setdefault(row[0], []).append(row[1] if row[2] is None else f"{row[1]}@{row[2]}")
                return names
        except _NoCatalog:
            return {}

    def markers(self, session: str, serial: str) -> list[dict[str, Any]]:
        """The markers of this session's active history on ``serial`` (``name``, ``cursor``), oldest first."""
        recording = self.active(session)
        if recording is None:
            return []
        with self._db() as connection:
            return [dict(row) for row in connection.execute("SELECT name,cursor FROM markers WHERE record_id=? AND serial=? ORDER BY rowid", (recording["id"], serial))]

    def steps(self, session: str) -> dict[str, dict[str, Any]]:
        """The steps of this session's active history by step id (``seq``, ``action``); empty when there is none."""
        recording = self.active(session)
        if recording is None:
            return {}
        with self._db() as connection:
            return {row["step_id"]: {"seq": row["seq"], "action": row["action"]} for row in connection.execute("SELECT seq,action,step_id FROM steps WHERE record_id=? AND step_id IS NOT NULL", (recording["id"],))}

    def device(self, identifier: str, sequence: int, serial: str, attached_device_id: str | None = None) -> None:
        with self._db(write=True) as connection:
            recording = connection.execute("SELECT protected,log_devices FROM records WHERE id=?", (identifier,)).fetchone()
            if recording is not None and recording["protected"] and _decode(recording["log_devices"]).get(serial) != attached_device_id:
                raise RecordError("device is not in this recording's start set; stop/start a recording with that device attached; no input was sent")
            previous = connection.execute("SELECT serial FROM steps WHERE record_id=? AND seq=?", (identifier, sequence)).fetchone()
            if previous is None or (previous[0] is not None and previous[0] != serial):
                raise RecordError("one recorded agent step cannot span devices; no new input was sent")
            connection.execute("UPDATE steps SET serial=? WHERE record_id=? AND seq=?", (serial, identifier, sequence))

    def target(self, identifier: str, sequence: int, slot: str, selector: dict[str, Any]) -> None:
        with self._db(write=True) as connection:
            row = connection.execute("SELECT parameters,targets FROM steps WHERE record_id=? AND seq=?", (identifier, sequence)).fetchone()
            if row is None:
                raise RecordError("recorded intent disappeared; no new input was sent")
            targets = _decode(row[1])
            targets[slot] = selector
            encoded = _json(targets)
            self._capacity(connection, identifier, row[0], encoded, replacing=sequence)
            connection.execute("UPDATE steps SET targets=? WHERE record_id=? AND seq=?", (encoded, identifier, sequence))

    def finish(self, identifier: str, sequence: int, status: str, duration_ms: int, error_type: str | None = None) -> None:
        """Record outcome only; never store command output or exception text."""
        with self._db(write=True) as connection:
            changed = connection.execute("UPDATE steps SET status=?,finished_at=?,duration_ms=?,error_type=? WHERE record_id=? AND seq=? AND status='running'", (status, _now(), duration_ms, error_type, identifier, sequence)).rowcount
            if changed != 1:
                raise RecordError("recording outcome could not be committed")

    def deaths(self, identifier: str, sequence: int, deaths: list[dict[str, Any]]) -> None:
        """Adds the app deaths noticed at the end of a finished step: identity and whether the
        step expected them, never the exception message or stack trace (app output)."""
        with self._db(write=True) as connection:
            row = connection.execute("SELECT deaths FROM steps WHERE record_id=? AND seq=?", (identifier, sequence)).fetchone()
            if row is None:
                raise RecordError("recorded step disappeared")
            try:
                previous = json.loads(row[0])
                if not isinstance(previous, list) or any(not isinstance(item, dict) for item in previous):
                    raise ValueError("invalid app-death metadata")
                encoded = json.dumps(previous + deaths, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
            except (ValueError, TypeError, OverflowError) as error:
                raise RecordError("app-death metadata could not be stored; the original action outcome remains recorded") from error
            connection.execute("UPDATE steps SET deaths=? WHERE record_id=? AND seq=?", (encoded, identifier, sequence))

    def stop(self, name: str | None, session: str, *, reason: str = "explicit", before_stop: Callable[[], object] | None = None) -> dict[str, Any]:
        recording = self.select(name, session) if name is not None else self.active(session)
        if recording is None:
            raise RecordError(f"session {session!r} has no live step history")
        if recording.get("protected"):
            with self._db() as connection:
                row = connection.execute("SELECT * FROM records WHERE protected=? ORDER BY rowid DESC LIMIT 1", (recording["protected"],)).fetchone()
                recording = dict(row)
        with self.lease(recording["id"]):
            log_status = before_stop() if before_stop is not None else None
            with self._db(write=True) as connection:
                self._stop(connection, recording["id"], reason)
                if isinstance(log_status, dict):
                    connection.execute("UPDATE records SET log_status=? WHERE id=?", (_json(log_status), recording["id"]))
        return self.select(recording["name"], session)

    def export(self, name: str | None, session: str, *, since: str | int | None = None, until: str | int | None = None) -> dict[str, Any]:
        """The history as a document: its steps (each with its ``id``) and markers. ``since`` /
        ``until`` cut it to the steps between two markers, each given by its name (the newest
        marker with it) or its cursor."""
        recording = self.select(name, session)
        with self._db() as connection:
            current = connection.execute("SELECT * FROM records WHERE id=?", (recording["id"],)).fetchone()
            if current is None:
                raise RecordError("recording was removed during export")
            recording = dict(current)
            members = [recording]
            if recording.get("protected"):
                members = [dict(row) for row in connection.execute("SELECT * FROM records WHERE protected=? ORDER BY rowid", (recording["protected"],))]
                recording = {**members[0], **{key: members[-1][key] for key in ("state", "stopped_at", "stop_reason")}, "name": recording["protected"]}
            steps, markers = [], []
            for member in members:
                offset = len(steps)
                for row in connection.execute("SELECT * FROM steps WHERE record_id=? ORDER BY seq", (member["id"],)):
                    steps.append(_step_document(row, sequence=offset + row["seq"]))
                markers.extend({**dict(row), "after_seq": offset + row["after_seq"]} for row in connection.execute("SELECT name,at,after_seq,serial,cursor FROM markers WHERE record_id=? ORDER BY rowid", (member["id"],)))
        log_status = next((_decode(row["log_status"]) for row in reversed(members) if row.get("log_status")), None)
        bounds = {}
        for key, wanted in (("since", since), ("until", until)):
            if wanted is None:
                continue
            found = [marker for marker in markers if (marker["cursor"] == wanted if isinstance(wanted, int) else marker["name"] == wanted)]
            if not found:
                described = f"at cursor {wanted}" if isinstance(wanted, int) else f"named {wanted!r}"
                raise RecordError(f"no marker {described} in history {recording['name']!r}")
            bounds[key] = found[-1]
        if "since" in bounds and "until" in bounds and markers.index(bounds["until"]) < markers.index(bounds["since"]):
            raise RecordError("the --until marker was written before the --since marker")
        if bounds:
            low = bounds["since"]["after_seq"] if "since" in bounds else 0
            high = bounds["until"]["after_seq"] if "until" in bounds else float("inf")
            first = markers.index(bounds["since"]) if "since" in bounds else 0
            last = markers.index(bounds["until"]) if "until" in bounds else len(markers) - 1
            steps = [step for step in steps if low < step["seq"] <= high]
            markers = markers[first:last + 1]
        return {"format": FORMAT, "recording": recording.get("protected"), **({"log_capture": log_status} if log_status is not None else {}), "markers": markers, **({"range": {key: {"name": marker["name"], "cursor": marker["cursor"]} for key, marker in bounds.items()}} if bounds else {}), **{key: recording[key] for key in ("name", "session", "connection_id", "started_at", "stopped_at", "stop_reason")},
            "state": "live" if recording["state"] == "recording" else "stopped",
            "complete": recording["state"] == "stopped" and (log_status is None or log_status.get("complete") is True) and all(s["outcome"]["status"] not in ("running", "unknown", "interrupted") for s in steps), "steps": steps}

    def cursor_time(self, cursor: int) -> str | None:
        """Resolve an exact retained step boundary, never invent time for an expired cursor."""
        if not self._exists():
            return None
        with self._db() as connection:
            row = connection.execute("SELECT started_at FROM steps WHERE cursor=?", (cursor,)).fetchone()
            return row[0] if row is not None else None

    def timeline(self, *, start: str | int | None = None, until: str | int | None = None, limit: int = 200) -> dict[str, Any]:
        """A bounded page of all retained client steps, using stable store-wide cursors.

        Bounds are inclusive from/exclusive until, as positive step cursors or ISO timestamps
        with a timezone. Default is the latest page, shown oldest first; from pages forward.
        """
        lower, upper = _history_options(start, until, limit)
        result: dict[str, Any] = {"format": "tap-history/1", "steps": [], "markers": [], "complete": False,
            "next": None, "older": None, "connections": [], "retention": "Only retained activity is available; missing activity is not proof nothing happened."}
        if not self._exists():
            return result
        cursor_low = lower[1] if lower is not None and lower[0] == "cursor" else 1
        cursor_high = upper[1] - 1 if upper is not None and upper[0] == "cursor" else 2**63 - 1
        time_low = lower[1] if lower is not None and lower[0] == "started_at" else ""
        time_high = upper[1] if upper is not None and upper[0] == "started_at" else "\uffff"
        parameters = (cursor_low, cursor_high, time_low, time_high, limit + 1)
        ascending = lower is not None
        with self._db() as connection:
            if ascending:
                rows = list(connection.execute("""SELECT s.*,r.connection_id,r.session,r.state AS record_state
                    FROM steps s JOIN records r ON r.id=s.record_id
                    WHERE s.cursor>=? AND s.cursor<=? AND s.started_at>=? AND s.started_at<?
                    ORDER BY s.cursor ASC LIMIT ?""", parameters))
            else:
                rows = list(connection.execute("""SELECT s.*,r.connection_id,r.session,r.state AS record_state
                    FROM steps s JOIN records r ON r.id=s.record_id
                    WHERE s.cursor>=? AND s.cursor<=? AND s.started_at>=? AND s.started_at<?
                    ORDER BY s.cursor DESC LIMIT ?""", parameters))
            more = len(rows) > limit
            rows = rows[:limit]
            if not ascending:
                rows.reverse()
            result["steps"] = [{**_step_document(row), "connection_id": row["connection_id"], "session": row["session"]} for row in rows]
            result["connections"] = list(dict.fromkeys(row["connection_id"] for row in rows))
            for record_id in dict.fromkeys(row["record_id"] for row in rows):
                members = [row for row in rows if row["record_id"] == record_id]
                low_seq, high_seq = min(row["seq"] for row in members), max(row["seq"] for row in members)
                for marker in connection.execute("SELECT name,at,serial,cursor,after_seq FROM markers WHERE record_id=? ORDER BY rowid", (record_id,)):
                    if not low_seq - 1 <= marker["after_seq"] <= high_seq:
                        continue
                    if lower is not None and lower[0] == "started_at" and marker["at"] < lower[1]:
                        continue
                    if upper is not None and upper[0] == "started_at" and marker["at"] >= upper[1]:
                        continue
                    anchor = connection.execute("SELECT cursor FROM steps WHERE record_id=? AND seq=?", (record_id, marker["after_seq"])).fetchone()
                    result["markers"].append({**dict(marker), "after_cursor": anchor[0] if anchor else None,
                        "connection_id": members[0]["connection_id"]})
            result["complete"] = bool(rows) and all(row["record_state"] == "stopped" and row["status"] not in ("running", "unknown", "interrupted") for row in rows)
            if rows:
                if ascending and more:
                    result["next"] = rows[-1]["cursor"] + 1
                if not ascending and more:
                    result["older"] = rows[0]["cursor"]
                # Only integer bounds identify exact missing cursor intervals; time filtering
                # can intentionally omit other steps and must not invent loss counts.
                if all(bound is None or bound[0] == "cursor" for bound in (lower, upper)):
                    first = lower[1] if lower is not None else rows[0]["cursor"]
                    result["dropped_steps"] = max(0, rows[-1]["cursor"] - first + 1 - len(rows))
                    if result["dropped_steps"]:
                        result["complete"] = False
        return result

    def clean(self, name: str | None, session: str, *, all_records: bool = False) -> int:
        if not isinstance(all_records, bool) or (name is not None and not isinstance(name, str)):
            raise RecordError("clean needs a string name or explicit boolean --all")
        if all_records and name is not None:
            raise RecordError("choose a name or --all, not both")
        if not all_records and name is None:
            raise RecordError("clean needs a name or explicit --all")
        recordings = [row for row in self.list() if row["state"] == "stopped" and not row.get("protected")] if all_records else [self.select(name, session)]
        if not all_records and recordings[0].get("protected"):
            protection = recordings[0]["protected"]
            recordings = [row for row in self.list() if row.get("protected") == protection]
        if any(recording["state"] != "stopped" for recording in recordings):
            raise RecordError("a live session's history cannot be cleaned; release the session first")
        with contextlib.ExitStack() as stack:
            for recording in sorted(recordings, key=lambda row: row["id"]):
                stack.enter_context(self.lease(recording["id"]))
            if recordings:
                with self._db(write=True) as connection:
                    connection.executemany("DELETE FROM records WHERE id=? AND state='stopped'", [(row["id"],) for row in recordings])
        for recording in recordings:
            self._lease_path(recording["id"]).unlink(missing_ok=True)
        return len(recordings)
