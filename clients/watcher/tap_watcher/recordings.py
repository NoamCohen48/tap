"""The local recording library: one directory per recording or clip.

`<id>/video-1.mp4` (and `video-2.mp4`… when the stream changed), `<id>/steps.json` and
`<id>/recording.json` (the `tap.watcher.v1.Recording` as JSON). An entry is written under a
`.saving-*` directory and renamed into place, so a listed entry is always complete. The writer
holds a lock on that directory until it is committed or discarded, and [sweep] removes only
the ones nobody holds: what an interrupted save left behind, never a recording in progress in
this or another watcher sharing the library. A full library refuses new saves. Entries are
deleted only by the user, or after `keep_days` when that is set.
"""

from __future__ import annotations

import contextlib
import os
import pathlib
import re
import shutil
import tempfile
import time
import uuid
import zipfile

from google.protobuf import json_format

from ._gen import watcher_pb2 as pb

try:
    import fcntl
except ImportError:  # Windows: no flock; the sweep goes by age instead.
    fcntl = None

MAX_LIBRARY_BYTES = 2 * 1024 * 1024 * 1024
_FILE = re.compile(r"video-[1-9][0-9]{0,3}\.mp4|steps\.json")
_DAY_MS = 24 * 60 * 60 * 1000
# Without flock, a staging directory this old is abandoned: far beyond the longest recording.
_STALE_STAGING_S = 24 * 60 * 60


class LibraryFull(Exception):
    def __init__(self) -> None:
        super().__init__(
            "The recording library reached 2 GiB; delete recordings before saving more"
        )


def default_directory() -> pathlib.Path:
    """TAP_WATCHER_RECORDINGS, otherwise ~/.tap/recordings/watcher."""
    return pathlib.Path(
        os.environ.get("TAP_WATCHER_RECORDINGS")
        or pathlib.Path.home() / ".tap/recordings/watcher"
    ).expanduser()


class Library:
    def __init__(self, directory: pathlib.Path, keep_days: int = 0) -> None:
        self.directory = directory.absolute()
        self.keep_days = keep_days
        # The open lock on each staging directory this process is writing.
        self._locks: dict[pathlib.Path, int] = {}

    def sweep(self) -> list[str]:
        """Removes what interrupted saves and downloads left (`.saving-*`, `.zip-*`: never a
        playable entry) and, with [keep_days], entries older than that. The ids it deleted."""
        if not self.directory.exists():
            return []
        for leftover in self.directory.glob(".saving-*"):
            if leftover not in self._locks and _abandoned(leftover):
                shutil.rmtree(leftover, ignore_errors=True)
        for leftover in self.directory.glob(".zip-*"):
            # A download may still be streaming one; an hour old, it is a leftover.
            with contextlib.suppress(OSError):
                if time.time() - leftover.stat().st_mtime > 60 * 60:
                    leftover.unlink()
        if self.keep_days <= 0:
            return []
        cutoff = int(time.time() * 1000) - self.keep_days * _DAY_MS
        expired = [r.id for r in self.list() if r.created_epoch_ms < cutoff]
        self.delete(expired)
        return expired

    def used_bytes(self) -> int:
        if not self.directory.exists():
            return 0
        return sum(
            file.stat().st_size for file in self.directory.glob("*/*") if file.is_file()
        )

    def has_room(self, extra: int = 0) -> bool:
        return self.used_bytes() + extra <= MAX_LIBRARY_BYTES

    def begin(self) -> pathlib.Path:
        """A private directory to write a new entry into; [commit] or [discard] it."""
        if not self.has_room():
            raise LibraryFull()
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        staging = pathlib.Path(tempfile.mkdtemp(prefix=".saving-", dir=self.directory))
        if fcntl is not None:
            # The lock is on the directory itself, so it holds across the rename in [commit].
            lock = os.open(staging, os.O_RDONLY)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._locks[staging] = lock
        return staging

    def commit(self, staging: pathlib.Path, record: pb.Recording) -> pb.Recording:
        record.id = uuid.uuid4().hex
        record.bytes = sum(file.stat().st_size for file in staging.iterdir())
        (staging / "recording.json").write_text(json_format.MessageToJson(record))
        staging.rename(self.directory / record.id)
        self._unlock(staging)
        return record

    def discard(self, staging: pathlib.Path) -> None:
        shutil.rmtree(staging, ignore_errors=True)
        self._unlock(staging)

    def _unlock(self, staging: pathlib.Path) -> None:
        lock = self._locks.pop(staging, None)
        if lock is not None:
            os.close(lock)

    def list(self) -> list[pb.Recording]:
        """Newest first. Entries that cannot be read are skipped, never removed."""
        found = []
        if not self.directory.exists():
            return found
        for file in self.directory.glob("*/recording.json"):
            try:
                record = json_format.Parse(
                    file.read_text(), pb.Recording(), ignore_unknown_fields=True
                )
            except (OSError, ValueError, json_format.ParseError):
                continue
            # Entries from before `id` was written by commit name themselves by directory.
            record.id = file.parent.name
            if (file.parent / "video-1.mp4").is_file():
                found.append(record)
        return sorted(found, key=lambda record: record.created_epoch_ms, reverse=True)

    def path(self, identifier: str) -> pathlib.Path:
        if len(identifier) != 32 or any(
            char not in "0123456789abcdef" for char in identifier
        ):
            raise KeyError(identifier)
        entry = self.directory / identifier
        if not (entry / "recording.json").is_file():
            raise KeyError(identifier)
        return entry

    def file(self, identifier: str, name: str) -> pathlib.Path:
        if not _FILE.fullmatch(name):
            raise KeyError(name)
        path = self.path(identifier) / name
        if not path.is_file():
            raise KeyError(name)
        return path

    def files(self, identifier: str) -> list[pathlib.Path]:
        """The entry's downloadable files: its parts in order, then `steps.json`."""
        entry = self.path(identifier)
        found = [f for f in entry.iterdir() if f.is_file() and _FILE.fullmatch(f.name)]
        return sorted(
            found,
            key=lambda f: (f.name == "steps.json", len(f.name), f.name),
        )

    def delete(self, identifiers: list[str]) -> None:
        """Deletes the entries that exist; others (already gone, or not ids) are ignored."""
        for identifier in identifiers:
            try:
                entry = self.path(identifier)
            except KeyError:
                continue
            shutil.rmtree(entry)

    def zip(self, identifier: str) -> pathlib.Path:
        """A temporary ZIP of the entry's files (stored, not compressed); the caller removes it."""
        entry = self.path(identifier)
        handle, name = tempfile.mkstemp(
            prefix=".zip-", suffix=".zip", dir=self.directory
        )
        os.close(handle)
        with zipfile.ZipFile(name, "w", compression=zipfile.ZIP_STORED) as archive:
            for file in self.files(entry.name):
                archive.write(file, file.name)
        return pathlib.Path(name)


def _abandoned(staging: pathlib.Path) -> bool:
    """No writer holds it: its lock is free (the kernel drops a dead process's lock), or,
    without flock, nothing in it changed for a day."""
    if fcntl is None:
        try:
            newest = max(
                [staging.stat().st_mtime]
                + [file.stat().st_mtime for file in staging.iterdir()]
            )
        except OSError:
            return False
        return time.time() - newest > _STALE_STAGING_S
    try:
        handle = os.open(staging, os.O_RDONLY)
    except OSError:
        return False
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        return False
    finally:
        os.close(handle)
    return True
