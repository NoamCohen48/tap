"""Shared CLI/MCP acknowledgement cursors. Toast text stays in the driver's bounded buffer."""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
import pathlib
import tempfile
from collections.abc import Callable
from typing import TypeVar

from tap_e2e import Device, ToastHistory  # pyright: ignore[reportMissingImports]

T = TypeVar("T")


class ToastInbox:
    """Serialize reads/acknowledgements across processes for each unique attachment.

    A failed read/render/write leaves the cursor unchanged for a later read. A crash
    after acknowledgement but before stdout reaches its caller can still lose that delivery;
    explicit toast history remains non-consuming and can recover retained observations.
    """

    def __init__(self, root: pathlib.Path):
        self.root = root / "toast-inbox"

    def read(self, device: Device, render: Callable[[ToastHistory], T]) -> T:
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        name = hashlib.sha256(device.attached_device_id.encode()).hexdigest()
        path = self.root / f"{name}.json"
        fd = os.open(self.root / f"{name}.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "r+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if (not isinstance(data, dict) or not isinstance(data.get("stream"), str)
                    or len(data["stream"]) > 128 or type(data.get("sequence")) is not int
                    or not 0 <= data["sequence"] <= 2**63 - 1):
                    raise ValueError("Invalid toast inbox cursor")
            except FileNotFoundError:
                data = {"stream": "", "sequence": 0}
            page = device.toast_history(data["sequence"], stream_id=data["stream"])
            result = render(page)
            temporary_fd, temporary = tempfile.mkstemp(prefix=f".{name}.", dir=self.root)
            try:
                with os.fdopen(temporary_fd, "w", encoding="utf-8") as output:
                    json.dump({"stream": page.stream_id, "sequence": page.next_sequence}, output)
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
            return result
