"""Client-owned tap-run/1 records. No daemon tokens or command payloads are serialized."""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import threading
import time
import uuid
from typing import Any

from ._gen.engine_version import API_COMPATIBILITY, ENGINE_VERSION


class RunRecords:
    """One process/worker's run; events are flushed, metadata is atomically replaced."""

    def __init__(self, root: pathlib.Path, runner: str = "pytest") -> None:
        self.run_id = str(uuid.uuid4())
        self.directory = root / self.run_id
        self.directory.mkdir(parents=True, mode=0o700)
        self._lock = threading.RLock()
        self._origin = time.monotonic_ns()
        self._seq = 0
        self._attempts: dict[str, int] = {}
        self.tests: dict[str, dict[str, Any]] = {}
        self.artifacts: list[dict[str, Any]] = []
        self.errors: list[dict[str, Any]] = []
        self.metadata: dict[str, Any] = {
            "schema": "tap-run/1", "run_id": self.run_id, "runner": runner,
            "engine_version": ENGINE_VERSION, "api_compatibility": API_COMPATIBILITY,
            "started_at_epoch_ms": self._now(), "finished_at_epoch_ms": None,
            "status": "running", "recording_status": "recording", "pid": os.getpid(),
        }
        self.event("run_started")
        self._publish()

    @staticmethod
    def _now() -> int:
        return time.time_ns() // 1_000_000

    def _atomic(self, name: str, value: object) -> None:
        temporary = self.directory / f".{name}.tmp"
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=True, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.directory / name)

    def _publish(self) -> None:
        self._atomic("manifest.json", {
            "schema": "tap-artifacts/1", "run_id": self.run_id,
            "artifacts": self.artifacts, "errors": self.errors,
        })
        self._atomic("run.json", {**self.metadata, "tests": list(self.tests.values())})

    def event(self, kind: str, **fields: Any) -> None:
        with self._lock:
            self._seq += 1
            record = {
                "schema": "tap-events/1", "run_id": self.run_id, "seq": self._seq,
                "at_epoch_ms": self._now(), "elapsed_ns": time.monotonic_ns() - self._origin,
                "type": kind, **fields,
            }
            # A separate append per complete newline-delimited record; no event backlog in RAM.
            with (self.directory / "events.jsonl").open("ab") as stream:
                stream.write((json.dumps(record, ensure_ascii=True, allow_nan=False) + "\n").encode())
                stream.flush()
                os.fsync(stream.fileno())

    def start_test(self, identity: str) -> str:
        with self._lock:
            test_id = hashlib.sha256(identity.encode()).hexdigest()
            attempt = self._attempts.get(test_id, 0) + 1
            self._attempts[test_id] = attempt
            key = f"{test_id}/{attempt}"
            self.tests[key] = {
                "test_id": test_id, "identity": identity, "attempt": attempt,
                "status": "running", "started_at_epoch_ms": self._now(),
                "finished_at_epoch_ms": None,
            }
            self.event("test_started", **self.tests[key])
            self._publish()
            return key

    def finish_test(self, key: str, status: str, error_type: str | None = None) -> None:
        with self._lock:
            test = self.tests[key]
            test.update(status=status, finished_at_epoch_ms=self._now(), error_type=error_type)
            self.event("test_finished", **test)
            self._publish()

    def device(self, key: str, role: str, serial: str, generation: int) -> None:
        self.event("device_attached", test=key, role=role, serial=serial, generation=generation)

    def artifact(
        self, key: str, part: str, data: bytes, extension: str,
        media_type: str, *, role: str | None = None, serial: str | None = None,
        generation: int | None = None,
    ) -> pathlib.Path:
        with self._lock:
            directory = self.directory / "tests" / key
            if role is not None:
                # Hash the pair to avoid path traversal, platform-invalid serials and collisions.
                device_id = hashlib.sha256(json.dumps([role, serial]).encode()).hexdigest()
                directory = directory / device_id / str(generation)
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"{part}.{extension}"
            path.write_bytes(data)
            entry = {
                "test": key, "part": part, "path": path.relative_to(self.directory).as_posix(),
                "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "media_type": media_type, "role": role, "serial": serial, "generation": generation,
            }
            self.artifacts.append(entry)
            self.event("artifact_saved", **entry)
            self._publish()
            return path

    def error(self, key: str, part: str, error: BaseException, **identity: Any) -> None:
        with self._lock:
            entry = {"test": key, "part": part, "error_type": type(error).__name__, **identity}
            self.errors.append(entry)
            self.event("collection_error", **entry)
            self._publish()

    def mark_incomplete(self) -> None:
        with self._lock:
            self.metadata["recording_status"] = "incomplete"

    def finish(self, status: str) -> None:
        with self._lock:
            # A test that never reached teardown is incomplete, not a pass.
            for test in self.tests.values():
                if test["status"] == "running":
                    test["status"] = "incomplete"
            self.metadata.update(status=status, finished_at_epoch_ms=self._now())
            if self.metadata["recording_status"] != "incomplete":
                self.metadata["recording_status"] = "complete"
            self.event("run_finished", status=status)
            self._publish()
