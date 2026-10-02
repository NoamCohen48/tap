"""Durable local recording library. Caller authenticates and serializes writes."""

from __future__ import annotations

import io
import json
import os
import pathlib
import shutil
import tempfile
import time
import uuid
import zipfile
from typing import Any

MAX_LIBRARY_BYTES = 2 * 1024 * 1024 * 1024


def default_directory() -> pathlib.Path:
    """TAP_WATCHER_RECORDINGS, otherwise ~/.tap/recordings/watcher (not browser downloads)."""
    return pathlib.Path(
        os.environ.get("TAP_WATCHER_RECORDINGS")
        or pathlib.Path.home() / ".tap/recordings/watcher"
    ).expanduser()


class Recordings:
    """Save atomically; never remove older recordings silently when the quota is reached."""

    def __init__(self, directory: pathlib.Path) -> None:
        self.directory = directory.absolute()

    def list(self) -> list[dict[str, Any]]:
        results = []
        if not self.directory.exists():
            return results
        for file in self.directory.glob("*/recording.json"):
            try:
                record = json.loads(file.read_text())
                if (
                    isinstance(record, dict)
                    and record.get("id") == file.parent.name
                    and isinstance(record.get("createdEpochMs"), int)
                    and isinstance(record.get("durationSeconds"), (int, float))
                    and isinstance(record.get("serial"), str)
                ):
                    results.append(record)
            except (OSError, ValueError):
                continue
        return sorted(
            results, key=lambda record: record["createdEpochMs"], reverse=True
        )

    def save(self, archive: bytes, document: dict[str, Any]) -> dict[str, Any]:
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        used = sum(
            file.stat().st_size for file in self.directory.glob("*/*") if file.is_file()
        )
        if used + len(archive) * 2 > MAX_LIBRARY_BYTES:
            raise ValueError(
                "Recording library reached 2 GiB; move/delete saved recordings before saving more"
            )
        identifier = uuid.uuid4().hex
        destination = self.directory / identifier
        temporary = pathlib.Path(
            tempfile.mkdtemp(prefix=".saving-", dir=self.directory)
        )
        try:
            with zipfile.ZipFile(io.BytesIO(archive)) as clips:
                for name in ("video.mp4", "steps.json"):
                    (temporary / name).write_bytes(clips.read(name))
            (temporary / "clip.zip").write_bytes(archive)
            rows = document["frames"]
            record = {
                "id": identifier,
                "serial": document.get("serial", ""),
                "createdEpochMs": int(time.time() * 1000),
                "durationSeconds": (int(rows[-1]["ptsUs"]) - int(rows[0]["ptsUs"]))
                / 1_000_000,
                "bytes": len(archive),
                "path": str(destination),
            }
            (temporary / "recording.json").write_text(json.dumps(record))
            if (
                used + sum(file.stat().st_size for file in temporary.iterdir())
                > MAX_LIBRARY_BYTES
            ):
                raise ValueError(
                    "Recording library reached 2 GiB; move/delete saved recordings before saving more"
                )
            temporary.rename(destination)
            return record
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)

    def file(self, identifier: str, name: str) -> pathlib.Path:
        if len(identifier) != 32 or any(
            char not in "0123456789abcdef" for char in identifier
        ):
            raise ValueError("invalid recording id")
        if name not in {"video.mp4", "steps.json", "clip.zip"}:
            raise ValueError("invalid recording file")
        return self.directory / identifier / name
