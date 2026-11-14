"""Device logs: one daemon `WatchLog` stream per attached device, kept per serial.

The back end follows the log of every attachment from the moment it learns of it (the activity
stream) until the device is detached, so the log of a test that has ended is still here to read
and to save with a recording. Entries are numbered per serial with the back end's own sequence:
a serial outlives its attachments, and each attachment starts its cursors again at 1.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from tap_e2e.proto import watch_pb2 as watch

from ._gen import watcher_pb2 as pb
from .activity import ActivityHub, ReaderOverflow
from .daemon import Daemon

# Entries kept per serial; older ones leave (the daemon keeps 16 MiB per attachment itself).
HISTORY = 50_000
READER_QUEUE = 20_000
BATCH = 1_000
_OVERFLOW = object()
_CLOSED = object()


@dataclass
class Subscription:
    backlog: list[pb.LogRecord]
    dropped: int
    unavailable: str
    queue: asyncio.Queue[object]


@dataclass
class _Device:
    records: deque[pb.LogRecord] = field(default_factory=deque)
    seq: int = 0
    readers: set[asyncio.Queue[object]] = field(default_factory=set)
    # Why the current attachment has no log (its capture is off); empty otherwise.
    unavailable: str = ""


class LogHub:
    def __init__(
        self,
        daemon: Daemon,
        activity: ActivityHub,
        history: int = HISTORY,
        reader_queue: int = READER_QUEUE,
    ) -> None:
        self._daemon = daemon
        self._activity = activity
        self._capacity = history
        self._reader_queue = reader_queue
        self._devices: dict[str, _Device] = {}
        self._following: dict[str, asyncio.Task[None]] = {}
        self._listeners: list[Callable[[str, pb.LogRecord], None]] = []
        activity.listen(self._on_activity)
        activity.on_resync(self._on_resync)

    def listen(self, listener: Callable[[str, pb.LogRecord], None]) -> None:
        """Calls [listener] with every new entry and its serial, in order (the recordings)."""
        self._listeners.append(listener)

    def _on_activity(self, activity: watch.Activity) -> None:
        if activity.HasField("device_attached"):
            attached = activity.device_attached
            self._follow(attached.attached_device_id, attached.serial)

    def _on_resync(self) -> None:
        """Attachments made before the back end was following the daemon."""
        for entry in self._activity.connections.values():
            for device in entry.attached_devices:
                self._follow(device.attached_device_id, device.serial)

    def _follow(self, attached_id: str, serial: str) -> None:
        current = self._following.get(attached_id)
        if current is not None and not current.done():
            return
        self._device(serial).unavailable = ""
        self._following[attached_id] = asyncio.get_running_loop().create_task(
            self._run(attached_id, serial)
        )

    def _device(self, serial: str) -> _Device:
        return self._devices.setdefault(serial, _Device())

    def _attached(self, attached_id: str) -> bool:
        return any(
            device.attached_device_id == attached_id
            for entry in self._activity.connections.values()
            for device in entry.attached_devices
        )

    async def _run(self, attached_id: str, serial: str, retry_s: float = 1.0) -> None:
        """Follows one attachment until it is detached, resuming after the last cursor."""
        cursor, clock_id = 0, ""
        try:
            while True:
                try:
                    async for response in self._daemon.watch_log(attached_id, cursor):
                        clock_id = response.clock_id or clock_id
                        for entry in response.entries:
                            cursor = entry.cursor
                            self._append(
                                serial, pb.LogRecord(clock_id=clock_id, entry=entry)
                            )
                    return  # The daemon ended the stream: the device was detached.
                except ConnectError as error:
                    if error.code == Code.FAILED_PRECONDITION:
                        self._device(serial).unavailable = error.message
                        return
                    if error.code in (Code.NOT_FOUND, Code.INVALID_ARGUMENT):
                        return
                    if error.code == Code.UNIMPLEMENTED:
                        self._device(
                            serial
                        ).unavailable = "This Tap daemon does not serve device logs"
                        return
                    if error.code != Code.RESOURCE_EXHAUSTED:
                        # The daemon is unreachable; the activity stream decides whether the
                        # attachment survived.
                        await asyncio.sleep(retry_s)
                        if not self._attached(attached_id):
                            return
        finally:
            if self._following.get(attached_id) is asyncio.current_task():
                del self._following[attached_id]

    def _append(self, serial: str, record: pb.LogRecord) -> None:
        device = self._device(serial)
        device.seq += 1
        record.seq = device.seq
        if len(device.records) == self._capacity:
            device.records.popleft()
        device.records.append(record)
        for reader in list(device.readers):
            if reader.qsize() >= self._reader_queue:
                device.readers.discard(reader)
                reader.put_nowait(_OVERFLOW)
            else:
                reader.put_nowait(record)
        for listener in self._listeners:
            listener(serial, record)

    def subscribe(self, serial: str, after_seq: int) -> Subscription:
        """The retained entries after [after_seq] and a queue of what follows, taken together."""
        device = self._device(serial)
        queue: asyncio.Queue[object] = asyncio.Queue()
        device.readers.add(queue)
        oldest = device.records[0].seq if device.records else device.seq + 1
        return Subscription(
            backlog=[r for r in device.records if r.seq > after_seq],
            dropped=max(0, oldest - 1 - after_seq),
            unavailable=device.unavailable,
            queue=queue,
        )

    def unsubscribe(self, serial: str, subscription: Subscription) -> None:
        self._device(serial).readers.discard(subscription.queue)

    async def next_batch(self, subscription: Subscription) -> list[pb.LogRecord] | None:
        """What is queued for [subscription], waiting for at least one; None once closed."""
        batch: list[pb.LogRecord] = []
        item = await subscription.queue.get()
        while True:
            if item is _OVERFLOW:
                raise ReaderOverflow()
            if item is _CLOSED:
                return batch or None
            assert isinstance(item, pb.LogRecord)
            batch.append(item)
            if len(batch) == BATCH:
                return batch
            try:
                item = subscription.queue.get_nowait()
            except asyncio.QueueEmpty:
                return batch

    def records(
        self, serial: str, clock_id: str, from_ns: int, to_ns: int
    ) -> list[pb.LogRecord]:
        """Retained entries of [serial] the daemon got in [from_ns, to_ns] on [clock_id]."""
        device = self._devices.get(serial)
        if device is None:
            return []
        return [
            record
            for record in device.records
            if record.clock_id == clock_id
            and from_ns <= record.entry.host_monotonic_ns <= to_ns
        ]

    async def close(self) -> None:
        for device in self._devices.values():
            for reader in device.readers:
                reader.put_nowait(_CLOSED)
            device.readers.clear()
        tasks = list(self._following.values())
        for task in tasks:
            task.cancel()
        for task in tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
