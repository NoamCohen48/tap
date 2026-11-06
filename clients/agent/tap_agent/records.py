"""Private, cross-process storage for named high-level agent recordings.

This is client intent, not a copy of the daemon's bounded event history. SQLite commits an
intent before input; a separate per-recording SQLite lease is held across execution so stop,
clean and another process cannot race that step. No global database writer spans device I/O.
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
import uuid
from collections.abc import Iterator
from typing import Any

FORMAT = "tap-agent-recording/1"
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
            connection.execute("PRAGMA journal_mode=WAL")
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
            if version != 1:
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

    def start(self, name: str, session: str, connection_id: str) -> dict[str, Any]:
        """Create, never overwrite/append; pin to one held connection generation."""
        if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", name) is None:
            raise RecordError("recording name must be 1..80 ASCII letters/digits/dots/dashes/underscores, starting with a letter/digit")
        identifier = uuid.uuid4().hex
        with self._db(write=True, create=True) as connection:
            if connection.execute("SELECT 1 FROM records WHERE name=?", (name,)).fetchone():
                raise RecordError("recording name already exists; export/clean it or choose a new name")
            if connection.execute("SELECT 1 FROM records WHERE session=? AND state='recording'", (session,)).fetchone():
                raise RecordError("this session already has an active recording; stop it first")
            self._create_lease(identifier)
            connection.execute("INSERT INTO records(id,name,session,connection_id,started_at,state) VALUES(?,?,?,?,?,'recording')", (identifier, name, session, connection_id, _now()))
        return self.select(name, session)

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

    def begin(self, recording: dict[str, Any], action: str, parameters: dict[str, Any], *, kind: str = "action") -> int:
        """Caller holds its lease; commit a pending intent before any device mutation."""
        encoded = _json(parameters)
        with self._db(write=True) as connection:
            current = connection.execute("SELECT state FROM records WHERE id=?", (recording["id"],)).fetchone()
            if current is None or current[0] != "recording":
                raise RecordError("recording was stopped/removed before execution; no input was sent")
            if connection.execute("SELECT 1 FROM steps WHERE record_id=? AND status IN ('running','unknown','interrupted')", (recording["id"],)).fetchone():
                raise RecordError("recording has an unfinished step; inspect the outcome and stop it before more input; never replay blindly")
            self._capacity(connection, recording["id"], encoded, "{}")
            sequence = connection.execute("SELECT coalesce(max(seq),0)+1 FROM steps WHERE record_id=?", (recording["id"],)).fetchone()[0]
            connection.execute("INSERT INTO steps(record_id,seq,action,kind,parameters,started_at,status) VALUES(?,?,?,?,?,?,'running')", (recording["id"], sequence, action, kind, encoded, _now()))
            return sequence

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
            connection.execute("UPDATE records SET state='stopped',stopped_at=coalesce(stopped_at,?),stop_reason=coalesce(stop_reason,?) WHERE id=?", (_now(), reason, recording["id"]))
            connection.execute("UPDATE steps SET status='unknown' WHERE record_id=? AND status='running'", (recording["id"],))
        return self.select(recording["name"], session)

    def export(self, name: str | None, session: str) -> dict[str, Any]:
        recording = self.select(name, session)
        with self._db() as connection:
            current = connection.execute("SELECT * FROM records WHERE id=?", (recording["id"],)).fetchone()
            if current is None:
                raise RecordError("recording was removed during export")
            recording = dict(current)
            steps = []
            for row in connection.execute("SELECT * FROM steps WHERE record_id=? ORDER BY seq", (recording["id"],)):
                step = {key: row[key] for key in ("seq", "action", "kind", "serial", "started_at", "finished_at", "duration_ms")}
                step["parameters"] = _decode(row["parameters"])
                step["targets"] = _decode(row["targets"])
                step["outcome"] = {"status": row["status"], "error_type": row["error_type"]}
                steps.append(step)
        return {"format": FORMAT, **{key: recording[key] for key in ("name", "session", "connection_id", "state", "started_at", "stopped_at", "stop_reason")},
            "complete": recording["state"] == "stopped" and all(s["outcome"]["status"] not in ("running", "unknown", "interrupted") for s in steps), "steps": steps}

    def clean(self, name: str | None, session: str, *, all_records: bool = False) -> int:
        if not isinstance(all_records, bool) or (name is not None and not isinstance(name, str)):
            raise RecordError("clean needs a string name or explicit boolean --all")
        if all_records and name is not None:
            raise RecordError("choose a name or --all, not both")
        if not all_records and name is None:
            raise RecordError("clean needs a name or explicit --all")
        recordings = self.list() if all_records else [self.select(name, session)]
        if any(recording["state"] != "stopped" for recording in recordings):
            raise RecordError("stop active recordings before cleaning them")
        with contextlib.ExitStack() as stack:
            for recording in sorted(recordings, key=lambda row: row["id"]):
                stack.enter_context(self.lease(recording["id"]))
            if recordings:
                with self._db(write=True) as connection:
                    connection.executemany("DELETE FROM records WHERE id=? AND state='stopped'", [(row["id"],) for row in recordings])
        for recording in recordings:
            self._lease_path(recording["id"]).unlink(missing_ok=True)
        return len(recordings)
