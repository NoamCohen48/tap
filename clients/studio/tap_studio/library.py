"""The private library: named recordings under ``~/.tap/studio/recordings``, one file each.

Definitions only. A saved recording has no outcomes (a result is about a run, and its messages can
quote what was typed) and never holds a secret's value, only its name. A file reopened after a crash
is the recording as last saved. It says nothing about what ran or whether input reached the device.

Every change takes a process lock, writes a new private file and renames it into place, so a reader
never sees half a file and a crash leaves the old one. Each entry has a revision: a change names the
one it read, and two Studios cannot overwrite each other unnoticed. POSIX only (``fcntl``); other
systems get an error rather than a guarantee nobody checked.
"""

from __future__ import annotations

import contextlib
import json
import os
import pathlib
import re
import secrets as random
import stat
import unicodedata
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime, timezone

from ._gen import studio_pb2 as studio
from .recording import RecordingError, dumps, loads

try:
    import fcntl
except ImportError:  # pragma: no cover - not a POSIX system
    fcntl = None  # type: ignore[assignment]

FORMAT = "tap-studio-library/1"
MAX_ENTRY_BYTES = 8 * 1024 * 1024
MAX_ENTRIES = 1000
MAX_NAME = 128
_ID = re.compile(r"[0-9a-f]{32}")
_SUFFIX = ".json"


class LibraryError(Exception):
    """The store cannot be used, or a file in it is not readable."""


class EntryNotFound(LibraryError):
    pass


class RevisionConflict(LibraryError):
    """Someone else changed the entry since it was read."""


class NameTaken(LibraryError):
    pass


class InvalidName(LibraryError):
    pass


class LibraryFull(LibraryError):
    pass


_UPDATED_AT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z")


@dataclass(frozen=True)
class Entry:
    id: str
    name: str
    revision: int
    updated_at: str
    steps: int


def default_root() -> pathlib.Path:
    return pathlib.Path.home() / ".tap" / "studio" / "recordings"


def check_name(name: str) -> str:
    cleaned = name.strip()
    if not 1 <= len(cleaned) <= MAX_NAME:
        raise InvalidName(f"a name is 1 to {MAX_NAME} characters")
    if any(unicodedata.category(c).startswith("C") for c in cleaned):
        raise InvalidName("a name has no control characters")
    return cleaned


class Library:
    def __init__(self, root: pathlib.Path | None = None) -> None:
        self.root = root or default_root()

    def list(self) -> tuple[list[Entry], int]:
        """The entries, newest first, and how many files could not be read (left in place)."""
        with self._locked():
            entries, unreadable = self._read_all()
        return sorted(entries, key=lambda e: (e.updated_at, e.id), reverse=True), unreadable

    def read(self, entry_id: str) -> tuple[Entry, studio.Recording]:
        with self._locked():
            data = self._load(entry_id)
        try:
            recording = loads(data["document"])
        except RecordingError as error:
            raise LibraryError(f"{data['name']!r} is not a valid recording: {'; '.join(error.problems)}") from None
        return _entry(data, recording), recording

    def save(self, recording: studio.Recording, name: str, entry_id: str | None = None, expected_revision: int | None = None) -> Entry:
        """A new entry, or with ``entry_id`` a new revision of that one."""
        name = check_name(name)
        document = _document(recording)
        with self._locked():
            entries, _ = self._read_all()
            if any(e.name == name and e.id != entry_id for e in entries):
                raise NameTaken(f"a recording is already called {name!r}")
            if entry_id is None:
                if len(entries) >= MAX_ENTRIES:
                    raise LibraryFull(f"the library holds at most {MAX_ENTRIES} recordings")
                entry_id, revision = random.token_hex(16), 1
            else:
                revision = self._next_revision(entry_id, expected_revision)
            return self._write(entry_id, name, revision, document, recording)

    def rename(self, entry_id: str, name: str, expected_revision: int) -> Entry:
        name = check_name(name)
        with self._locked():
            entries, _ = self._read_all()
            if any(e.name == name and e.id != entry_id for e in entries):
                raise NameTaken(f"a recording is already called {name!r}")
            revision = self._next_revision(entry_id, expected_revision)
            document = self._load(entry_id)["document"]
            try:
                recording = loads(document)
            except RecordingError as error:
                raise LibraryError(f"that entry is not a valid recording: {'; '.join(error.problems)}") from None
            return self._write(entry_id, name, revision, document, recording)

    def delete(self, entry_id: str, expected_revision: int) -> None:
        with self._locked():
            self._next_revision(entry_id, expected_revision)
            self._path(entry_id).unlink()
            _sync_dir(self.root)

    # --- the store -------------------------------------------------------------------------------

    def _path(self, entry_id: str) -> pathlib.Path:
        if not _ID.fullmatch(entry_id):
            raise EntryNotFound(f"no recording {entry_id!r} in the library")
        return self.root / f"{entry_id}{_SUFFIX}"

    def _next_revision(self, entry_id: str, expected: int | None) -> int:
        current = self._load(entry_id)["revision"]
        if expected != current:
            raise RevisionConflict("another Studio changed this recording since it was read")
        return current + 1

    def _load(self, entry_id: str) -> dict:
        path = self._path(entry_id)
        try:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        except FileNotFoundError:
            raise EntryNotFound(f"no recording {entry_id!r} in the library") from None
        except OSError as error:
            raise LibraryError(f"cannot read {path.name}: {error.strerror}") from None
        with os.fdopen(fd, "rb") as file:
            if os.fstat(file.fileno()).st_size > MAX_ENTRY_BYTES:
                raise LibraryError(f"{path.name} is over {MAX_ENTRY_BYTES} bytes")
            raw = file.read()
        try:
            data = json.loads(raw)
            if not isinstance(data, dict) or data["format"] != FORMAT or data["id"] != entry_id:
                raise ValueError("not a library entry")
            if not isinstance(data["revision"], int) or isinstance(data["revision"], bool) or data["revision"] < 1:
                raise ValueError("not a library entry")
            if not isinstance(data["name"], str) or not isinstance(data["document"], str):
                raise ValueError("not a library entry")
            check_name(data["name"])
            if not isinstance(data["updated_at"], str) or not _UPDATED_AT.fullmatch(data["updated_at"]):
                raise ValueError("not a library entry")
        except (ValueError, KeyError, TypeError, InvalidName) as error:
            raise LibraryError(f"{path.name} is not a library entry: {error}") from None
        return data

    def _read_all(self) -> tuple[list[Entry], int]:
        entries: list[Entry] = []
        unreadable = 0
        for path in self.root.iterdir():
            if path.suffix != _SUFFIX or not _ID.fullmatch(path.stem):
                continue
            try:
                data = self._load(path.stem)
                entries.append(_entry(data, loads(data["document"])))
            except (LibraryError, RecordingError):
                unreadable += 1
        return entries, unreadable

    def _write(self, entry_id: str, name: str, revision: int, document: str, recording: studio.Recording) -> Entry:
        updated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        data = {"format": FORMAT, "id": entry_id, "name": name, "revision": revision, "updated_at": updated_at, "document": document}
        raw = json.dumps(data, ensure_ascii=False).encode()
        if len(raw) > MAX_ENTRY_BYTES:
            raise LibraryFull(f"this recording is over the {MAX_ENTRY_BYTES} bytes a library entry may hold")
        temporary = self.root / f".{random.token_hex(8)}.tmp"
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            with os.fdopen(fd, "wb") as file:
                file.write(raw)
                file.flush()
                os.fsync(file.fileno())
            os.replace(temporary, self._path(entry_id))
        except BaseException:
            with contextlib.suppress(OSError):
                temporary.unlink()
            raise
        _sync_dir(self.root)
        return _entry(data, recording)

    @contextlib.contextmanager
    def _locked(self) -> Iterator[None]:
        if fcntl is None:  # pragma: no cover - not a POSIX system
            raise LibraryError("the recording library needs a POSIX system")
        self._prepare()
        # A full disk, a read-only file system or lost permission is a library failure like any
        # other: the caller reports it, and autosave turns off saying why.
        try:
            fd = os.open(self.root / ".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        except OSError as error:
            raise LibraryError(f"cannot use {self.root}: {error.strerror}") from None
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        except OSError as error:
            raise LibraryError(f"the library could not be written: {error.strerror or error}") from None
        finally:
            os.close(fd)

    def _prepare(self) -> None:
        try:
            self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
            info = os.lstat(self.root)
        except OSError as error:
            raise LibraryError(f"cannot use {self.root}: {error.strerror}") from None
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            raise LibraryError(f"{self.root} is not a directory of yours: refusing to keep recordings there")
        if stat.S_IMODE(info.st_mode) != 0o700:
            try:
                os.chmod(self.root, 0o700)
            except OSError as error:
                raise LibraryError(f"cannot make {self.root} private: {error.strerror}") from None


def _document(recording: studio.Recording) -> str:
    """The recording as saved: no outcomes. Refuses an invalid one, as an export does."""
    copy = studio.Recording()
    copy.CopyFrom(recording)
    for step in copy.steps:
        step.ClearField("outcome")
    try:
        return dumps(copy)
    except RecordingError as error:
        raise LibraryError("; ".join(error.problems)) from None


def _entry(data: dict, recording: studio.Recording) -> Entry:
    return Entry(id=data["id"], name=data["name"], revision=data["revision"], updated_at=data["updated_at"], steps=len(recording.steps))


def _sync_dir(path: pathlib.Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
