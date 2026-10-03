"""The back end's activity history: one daemon `Watch` stream, relayed to every page.

The history outlives a page and a daemon restart. Activities are renumbered with the back end's
own sequence, so a page resumes with its last seq whatever happened to the daemon meanwhile.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections import deque
from collections.abc import Callable, Iterable
from dataclasses import dataclass

from connectrpc.errors import ConnectError
from tap_e2e.proto import client_connection_pb2 as connection
from tap_e2e.proto import event_log_pb2 as event_log
from tap_e2e.proto import watch_pb2 as watch

from .daemon import Daemon

HISTORY = 20_000
READER_QUEUE = 1_024
_OVERFLOW = object()
_CLOSED = object()


class ReaderOverflow(Exception):
    """A page fell behind; it resubscribes after the last seq it got."""


@dataclass
class Subscription:
    connections: list[connection.ConnectionEntry]
    backlog: list[watch.Activity]
    dropped: int
    queue: asyncio.Queue[object]


def apply(
    connections: dict[str, connection.ConnectionEntry], activity: watch.Activity
) -> None:
    """Folds one activity into the live connections. Idempotent, so a snapshot followed by a
    backlog that overlaps it converges to the same state."""
    owner = activity.client_connection_id
    kind = activity.WhichOneof("kind")
    if kind == "connection_opened":
        entry = connections.setdefault(
            owner, connection.ConnectionEntry(client_connection_id=owner)
        )
        entry.name = activity.connection_opened.name
        if activity.connection_opened.held:
            entry.hold.SetInParent()
    elif kind == "connection_closed":
        connections.pop(owner, None)
    elif kind == "device_attached" and owner in connections:
        attached = activity.device_attached
        devices = connections[owner].attached_devices
        if all(d.attached_device_id != attached.attached_device_id for d in devices):
            devices.add(
                attached_device_id=attached.attached_device_id, serial=attached.serial
            )
    elif kind == "device_detached" and owner in connections:
        devices = connections[owner].attached_devices
        kept = [
            d
            for d in devices
            if d.attached_device_id != activity.device_detached.attached_device_id
        ]
        del devices[:]
        devices.extend(kept)


class ActivityHub:
    """Follows the daemon from [run]; [subscribe] gives a page a gap-free view of it."""

    def __init__(
        self, daemon: Daemon, history: int = HISTORY, reader_queue: int = READER_QUEUE
    ) -> None:
        self._daemon = daemon
        self._history: deque[watch.Activity] = deque()
        self._capacity = history
        self._reader_queue = reader_queue
        self._readers: set[asyncio.Queue[object]] = set()
        self._seq = 0
        self._dropped = 0
        self._daemon_seq = 0
        self._daemon_pid: int | None = None
        self.connections: dict[str, connection.ConnectionEntry] = {}
        # Every name seen, for activity whose connection has since closed.
        self.names: dict[str, str] = {}
        self.connected = False
        self.error = "connecting to the Tap daemon"
        self._listeners: list[Callable[[watch.Activity], None]] = []

    def listen(self, listener: Callable[[watch.Activity], None]) -> None:
        """Calls [listener] with every new activity, in order (the recordings use it)."""
        self._listeners.append(listener)

    async def run(self, retry_s: float = 1.0) -> None:
        """Follows the daemon until cancelled, reconnecting with backoff."""
        delay = retry_s
        while True:
            try:
                await self._follow()
                self.error = "the daemon ended the activity stream"
            except ConnectError as error:
                self.error = error.message
            # A stream that got going earns a quick retry; repeated failures back off.
            delay = retry_s if self.connected else min(10.0, delay * 2)
            self.connected = False
            await self._daemon.reset()
            await asyncio.sleep(delay)

    async def _follow(self) -> None:
        info = await self._daemon.info()
        restarted = self._daemon_pid is not None and info.pid != self._daemon_pid
        if self._daemon_pid != info.pid:
            self._daemon_seq = 0
        self._daemon_pid = info.pid
        first = True
        async for response in self._daemon.watch(self._daemon_seq):
            if first:
                first = False
                self.connected = True
                self.error = ""
                self._resync(response.connections, response.activities, restarted)
            for activity in response.activities:
                self._daemon_seq = activity.seq
                self._append(activity)

    def _resync(
        self,
        snapshot: Iterable[connection.ConnectionEntry],
        backlog: Iterable[watch.Activity],
        restarted: bool,
    ) -> None:
        """Closes what ended while the back end was not following, then adopts the snapshot."""
        current = {entry.client_connection_id: entry for entry in snapshot}
        closed_in_backlog = {
            a.client_connection_id for a in backlog if a.HasField("connection_closed")
        }
        reason = (
            "daemon restarted"
            if restarted
            else "closed while the watcher was disconnected"
        )
        for owner in list(self.connections):
            if owner not in current and owner not in closed_in_backlog:
                self._append(
                    watch.Activity(
                        client_connection_id=owner,
                        connection_closed=watch.ConnectionClosed(reason=reason),
                    )
                )
        self.connections = current
        for entry in current.values():
            self.names[entry.client_connection_id] = entry.name

    def _append(self, upstream: watch.Activity) -> None:
        activity = watch.Activity()
        activity.CopyFrom(upstream)
        self._seq += 1
        activity.seq = self._seq
        if not activity.at_epoch_ms:
            activity.at_epoch_ms = _now_ms()
        if len(self._history) == self._capacity:
            self._history.popleft()
            self._dropped += 1
        self._history.append(activity)
        apply(self.connections, activity)
        if activity.HasField("connection_opened"):
            self.names[activity.client_connection_id] = activity.connection_opened.name
        for reader in list(self._readers):
            if reader.qsize() >= self._reader_queue:
                self._readers.discard(reader)
                reader.put_nowait(_OVERFLOW)
            else:
                reader.put_nowait(activity)
        for listener in self._listeners:
            listener(activity)

    def subscribe(self, after_seq: int) -> Subscription:
        """The live connections, the history after [after_seq] and a queue of what follows,
        taken together (no await in between, so nothing is missed or repeated)."""
        queue: asyncio.Queue[object] = asyncio.Queue()
        self._readers.add(queue)
        return Subscription(
            connections=sorted(self.connections.values(), key=lambda entry: entry.name),
            backlog=[a for a in self._history if a.seq > after_seq],
            dropped=self._dropped,
            queue=queue,
        )

    def unsubscribe(self, subscription: Subscription) -> None:
        self._readers.discard(subscription.queue)

    def close(self) -> None:
        for reader in self._readers:
            reader.put_nowait(_CLOSED)
        self._readers.clear()

    async def next_batch(
        self, subscription: Subscription
    ) -> list[watch.Activity] | None:
        """What is queued for [subscription], waiting for at least one; None once closed."""
        batch: list[watch.Activity] = []
        item = await subscription.queue.get()
        while True:
            if item is _OVERFLOW:
                raise ReaderOverflow()
            if item is _CLOSED:
                return batch or None
            assert isinstance(item, watch.Activity)
            batch.append(item)
            try:
                item = subscription.queue.get_nowait()
            except asyncio.QueueEmpty:
                return batch

    def events(
        self, serial: str, clock_id: str, from_ns: int, to_ns: int
    ) -> list[tuple[str, event_log.LoggedEvent]]:
        """Retained calls on [serial] that overlap [from_ns, to_ns] on clock [clock_id], with
        the id of the connection that made each."""
        found = []
        for activity in self._history:
            if not activity.HasField("event"):
                continue
            event = activity.event
            if (
                event.serial != serial
                or event.clock_id != clock_id
                or not event.HasField("started_monotonic_ns")
            ):
                continue
            finished = (
                event.finished_monotonic_ns
                if event.HasField("finished_monotonic_ns")
                else event.started_monotonic_ns
            )
            if finished >= from_ns and event.started_monotonic_ns <= to_ns:
                found.append((activity.client_connection_id, event))
        return found


def _now_ms() -> int:
    return int(time.time() * 1000)


async def cancel(task: asyncio.Task[None] | None) -> None:
    if task is None:
        return
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task
