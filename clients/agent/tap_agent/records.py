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
from collections.abc import Iterator
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
VERSION = 2


class RecordError(Exception):
    """Recording state/storage failed; never use it as a reason to replay device input."""


class _NoCatalog(RecordError):
    """The catalog file exists but no recording was ever started in it (another process created
    the file and has not committed its schema yet, or a first start failed)."""

    def __init__(self) -> None:
        super().__init__("no saved recordings")


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
            if version == 1:
                # A catalog an earlier tap-agent wrote: its recordings stay, as stopped histories.
                connection.execute("BEGIN IMMEDIATE")
                version = connection.execute("PRAGMA user_version").fetchone()[0]
                if version == 1:
                    for statement in _SCHEMA_2:
                        connection.execute(statement)
                    connection.execute(f"PRAGMA user_version={VERSION}")
                    version = VERSION
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
            raise RecordError("no saved recordings")
        with self._db() as connection:
            if name is not None:
                row = connection.execute("SELECT * FROM records WHERE name=?", (name,)).fetchone()
            else:
                row = connection.execute("SELECT * FROM records WHERE session=? ORDER BY state='recording' DESC, started_at DESC, rowid DESC LIMIT 1", (session,)).fetchone()
            if row is None:
                raise RecordError("recording not found")
            return dict(row)

    def ensure(self, session: str, connection_id: str) -> dict[str, Any]:
        """The history of this session's held connection, started if there is none.

        A history left active by a connection that has ended is stopped first, and one that
        reached its size limit is stopped and followed by a new one, so recording never has to
        be switched on and never blocks input for being full. Starting a history removes the
        stopped ones beyond the newest ``KEEP_STOPPED``."""
        removed: list[str] = []
        with self._db(write=True, create=True) as connection:
            row = connection.execute("SELECT * FROM records WHERE session=? AND state='recording'", (session,)).fetchone()
            if row is not None:
                used = connection.execute("SELECT count(*), coalesce(sum(length(CAST(parameters AS BLOB))+length(CAST(targets AS BLOB))+1024),0) FROM steps WHERE record_id=?", (row["id"],)).fetchone()
                full = used[0] >= MAX_STEPS or used[1] + MAX_STEP_BYTES > MAX_RECORD_BYTES
                if row["connection_id"] == connection_id and not full:
                    return dict(row)
                self._stop(connection, row["id"], "full" if row["connection_id"] == connection_id else "connection ended")
            identifier = uuid.uuid4().hex
            base = re.sub(r"[^A-Za-z0-9_.-]", "-", session).strip("-.")[:40] or "session"
            base = f"{base}-{datetime.datetime.now(datetime.timezone.utc):%Y%m%d-%H%M%S}"
            name, suffix = base, 1
            while connection.execute("SELECT 1 FROM records WHERE name=?", (name,)).fetchone():
                suffix += 1
                name = f"{base}-{suffix}"
            self._create_lease(identifier)
            connection.execute("INSERT INTO records(id,name,session,connection_id,started_at,state) VALUES(?,?,?,?,?,'recording')", (identifier, name, session, connection_id, _now()))
            old = [r[0] for r in connection.execute("SELECT id FROM records WHERE state='stopped' ORDER BY started_at DESC, rowid DESC LIMIT -1 OFFSET ?", (KEEP_STOPPED,))]
            for stale in old:
                connection.execute("DELETE FROM records WHERE id=?", (stale,))
            removed = old
            created = dict(connection.execute("SELECT * FROM records WHERE id=?", (identifier,)).fetchone())
        for stale in removed:
            self._lease_path(stale).unlink(missing_ok=True)
        return created

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
            connection.execute("INSERT INTO steps(record_id,seq,action,kind,parameters,started_at,status,step_id) VALUES(?,?,?,?,?,?,'running',?)", (recording["id"], sequence, action, kind, encoded, _now(), step_id))
            return sequence

    def mark(self, recording: dict[str, Any], name: str, serial: str | None, cursor: int | None) -> None:
        """A marker after the history's last step (``cursor``: its entry in the device log)."""
        with self._db(write=True) as connection:
            after = connection.execute("SELECT coalesce(max(seq),0) FROM steps WHERE record_id=?", (recording["id"],)).fetchone()[0]
            connection.execute("INSERT INTO markers(record_id,after_seq,name,at,serial,cursor) VALUES(?,?,?,?,?,?)", (recording["id"], after, name, _now(), serial, cursor))

    def marker_names(self) -> dict[str, list[str]]:
        """Every history's marker names by history id, oldest first."""
        if not self._exists():
            return {}
        try:
            with self._db() as connection:
                names: dict[str, list[str]] = {}
                for row in connection.execute("SELECT record_id,name FROM markers ORDER BY rowid"):
                    names.setdefault(row[0], []).append(row[1])
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

    def device(self, identifier: str, sequence: int, serial: str) -> None:
        with self._db(write=True) as connection:
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

    def stop(self, name: str | None, session: str, *, reason: str = "explicit") -> dict[str, Any]:
        recording = self.select(name, session) if name is not None else self.active(session)
        if recording is None:
            raise RecordError("this session has no active recording")
        with self.lease(recording["id"]), self._db(write=True) as connection:
            self._stop(connection, recording["id"], reason)
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
            steps = []
            for row in connection.execute("SELECT * FROM steps WHERE record_id=? ORDER BY seq", (recording["id"],)):
                step = {"seq": row["seq"], "id": row["step_id"], **{key: row[key] for key in ("action", "kind", "serial", "started_at", "finished_at", "duration_ms")}}
                step["parameters"] = _decode(row["parameters"])
                step["targets"] = _decode(row["targets"])
                step["outcome"] = {"status": row["status"], "error": row["error_type"]}
                steps.append(step)
            markers = [dict(row) for row in connection.execute("SELECT name,at,after_seq,serial,cursor FROM markers WHERE record_id=? ORDER BY rowid", (recording["id"],))]
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
        return {"format": FORMAT, "markers": markers, **({"range": {key: {"name": marker["name"], "cursor": marker["cursor"]} for key, marker in bounds.items()}} if bounds else {}), **{key: recording[key] for key in ("name", "session", "connection_id", "state", "started_at", "stopped_at", "stop_reason")},
            "complete": recording["state"] == "stopped" and all(s["outcome"]["status"] not in ("running", "unknown", "interrupted") for s in steps), "steps": steps}

    def clean(self, name: str | None, session: str, *, all_records: bool = False) -> int:
        if not isinstance(all_records, bool) or (name is not None and not isinstance(name, str)):
            raise RecordError("clean needs a string name or explicit boolean --all")
        if all_records and name is not None:
            raise RecordError("choose a name or --all, not both")
        if not all_records and name is None:
            raise RecordError("clean needs a name or explicit --all")
        recordings = [row for row in self.list() if row["state"] == "stopped"] if all_records else [self.select(name, session)]
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
