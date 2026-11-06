"""Debuggable-app private data, owned and transferred by the Tap server (never local ADB)."""
from __future__ import annotations

from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from typing import TYPE_CHECKING

from . import _gen as pb
from .client import mapped_errors

if TYPE_CHECKING:
    from .app import App

MAX_BYTES = 64 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class AppDataAccess:
    """Actual run-as capability for the current Android user; not just a debug-build label."""

    available: bool
    reason: str | None = None


@dataclass(frozen=True)
class PrivateFileEntry:
    """One immediate child. Symlinks can be listed but data operations never follow them."""

    name: str
    size_bytes: int
    is_directory: bool
    is_symlink: bool


@dataclass(frozen=True)
class DatabaseBundle:
    """Opaque SQLite base file plus persistent WAL/rollback journal, never rebuildable SHM.

    Import needs the app's own schema, keys and migrations. No SQL/decryption is performed.
    ``name`` is a database basename, not a path. Together the bytes may be at most 64 MiB.
    """

    name: str
    database: bytes
    wal: bytes | None = None
    journal: bytes | None = None


class AppData:
    """Private files under a debuggable app's credential-encrypted data directory.

    Obtain from ``app.data``. Except ``capabilities``, access requires **all processes under the
    app UID to be stopped**, including secondary/native/shared-UID processes. Call
    ``app.force_stop()`` explicitly; Tap never stops/relaunches/restores app data implicitly.
    Do not concurrently drive the app through UI, notifications, another ADB client or an
    external actor. This is not an OS filesystem lock. Paths are normalized relative UTF-8,
    without traversal, symlink components, control characters or reserved ``.tap-private-``
    components. Transfers are binary-safe and bounded to 64 MiB; contents and paths are omitted
    from event records. Transport loss during mutation is not a reason to retry blindly.
    """

    def __init__(self, app: App):
        self._app = app

    def _call(self, method: str, request, timeout: float | None = None):
        self._app.device._ensure_usable(f"private data of {self._app.package_name}")
        seconds = self._app.device.timeouts.lifecycle if timeout is None else timeout
        return getattr(self._app.device.client.app_data, method)(request, timeout=seconds + 60.0)

    def capabilities(self) -> AppDataAccess:
        """Probe actual run-as access; safe while the app runs. No root fallback."""
        with mapped_errors(self._app.device.serial):
            result = self._call("GetCapabilities", pb.GetCapabilitiesRequest(app=self._app._target()))
        return AppDataAccess(result.available, result.reason if result.HasField("reason") else None)

    def list(self, path: str = "") -> list[PrivateFileEntry]:
        """List immediate children (at most 1024); empty ``path`` lists the private root."""
        with mapped_errors(self._app.device.serial):
            result = self._call("ListFiles", pb.ListFilesRequest(app=self._app._target(), path=path))
        return [PrivateFileEntry(e.name, e.size_bytes, e.is_directory, e.is_symlink) for e in result.entries]

    def read(self, path: str, *, timeout: float | None = None) -> bytes:
        """Read one regular file, at most 64 MiB, with server-side size/SHA-256 verification."""
        with mapped_errors(self._app.device.serial):
            parts = self._call("ReadFile", pb.ReadFileRequest(app=self._app._target(), path=path), timeout)
            return self._receive((response.part for response in parts), [path], path)[path]

    def write(self, path: str, data: bytes, *, overwrite: bool = False, timeout: float | None = None) -> None:
        """Write at most 64 MiB. Create missing parents; refuse an existing file by default.

        Verify staged bytes before replacement. Changes are not restored on session release.
        """
        if len(data) > MAX_BYTES:
            raise ValueError("private-data file exceeds 64 MiB")
        with mapped_errors(self._app.device.serial):
            self._call("WriteFile", (pb.WriteFileRequest(part=p) for p in self._parts([(path, data)], overwrite)), timeout)

    def delete(self, path: str) -> None:
        """Delete one regular file; no directory/root/recursive deletion. App must be stopped."""
        with mapped_errors(self._app.device.serial):
            self._call("DeleteFile", pb.DeleteFileRequest(app=self._app._target(), path=path))

    def export_database(self, name: str, *, timeout: float | None = None) -> DatabaseBundle:
        """Export ``databases/name`` with existing WAL/journal, at most 64 MiB total.

        A stopped app may retain committed WAL; copying the base database alone is not a backup.
        """
        base = f"databases/{name}"
        with mapped_errors(self._app.device.serial):
            parts = self._call("ExportDatabase", pb.ExportDatabaseRequest(app=self._app._target(), name=name), timeout)
            files = self._receive((response.part for response in parts), [base, base + "-wal", base + "-journal"], base)
        return DatabaseBundle(name, files[base], files.get(base + "-wal"), files.get(base + "-journal"))

    def import_database(self, bundle: DatabaseBundle, *, name: str | None = None, overwrite: bool = False, timeout: float | None = None) -> None:
        """Stage and import a database bundle, optionally under another basename.

        Discard old sidecars, including SHM. Refuse existing files unless ``overwrite=True``.
        Multi-file replacement is not an OS transaction: after interrupted commit, do not launch
        or repeat blindly. Inspect/repair the retained private backup or explicitly clear data.
        No app restart, SQL validation, schema conversion or decryption is performed.
        """
        base = f"databases/{bundle.name if name is None else name}"
        files = [(base, bundle.database)]
        if bundle.wal is not None:
            files.append((base + "-wal", bundle.wal))
        if bundle.journal is not None:
            files.append((base + "-journal", bundle.journal))
        if not bundle.database or sum(len(data) for _, data in files) > MAX_BYTES:
            raise ValueError("database bundle must be nonempty and at most 64 MiB")
        with mapped_errors(self._app.device.serial):
            self._call("ImportDatabase", (pb.ImportDatabaseRequest(part=p) for p in self._parts(files, overwrite)), timeout)

    def _parts(self, files: Iterable[tuple[str, bytes]], overwrite: bool) -> Iterator[pb.AppDataPart]:
        for path, data in files:
            yield pb.AppDataPart(header=pb.AppDataHeader(app=self._app._target(), path=path, size_bytes=len(data), overwrite=overwrite))
            for start in range(0, len(data), CHUNK_BYTES):
                yield pb.AppDataPart(chunk=data[start:start + CHUNK_BYTES])

    def _receive(self, parts: Iterable[pb.AppDataPart], allowed: list[str], required: str) -> dict[str, bytes]:
        files: dict[str, bytearray] = {}
        current: str | None = None
        expected = 0
        declared = 0
        for part in parts:
            kind = part.WhichOneof("part")
            if kind == "header":
                if current is not None and len(files[current]) != expected:
                    raise ValueError("private-data response ended before size_bytes")
                h = part.header
                if h.app != self._app._target() or h.path not in allowed or h.path in files:
                    raise ValueError("unexpected private-data response header")
                if current is None and h.path != required:
                    raise ValueError("required private-data file must be first")
                declared += h.size_bytes
                if h.size_bytes < 0 or declared > MAX_BYTES:
                    raise ValueError("private-data response exceeds 64 MiB")
                current, expected = h.path, h.size_bytes
                files[current] = bytearray()
            elif kind == "chunk" and current is not None:
                if len(part.chunk) > CHUNK_BYTES or len(files[current]) + len(part.chunk) > expected:
                    raise ValueError("private-data response exceeds declared size or chunk limit")
                files[current].extend(part.chunk)
            else:
                raise ValueError("private-data response requires a header before chunks")
        if current is None or len(files[current]) != expected:
            raise ValueError("incomplete private-data response")
        return {path: bytes(data) for path, data in files.items()}
