"""The attached device's thread and its live screen (decisions 3 and 4, ``screen-streaming.md``).

``tap-e2e`` blocks and wants one thread per device, so every call on the attached device runs on
the ``DeviceWorker``'s single thread, in order. The page's calls (Perform, Count) are *urgent*:
while one is waiting, the frame loop takes nothing new, so a frame never queues ahead of a tap
(at most the one call already running finishes first). A frame is the daemon's screen snapshot
and the screenshot taken right after it; a snapshot taken while a user call came in is dropped,
because the picture after it would show another screen.

The loop runs only while a page reads frames. It takes the next frame at once while the screen
changes (a node added, removed or moved; a picture-only change such as a blinking cursor does
not count), backs off to ``slow`` seconds while it
does not, and starts over at once after a user call that may change the screen (Perform). A
read-only call (Count) does not wake it: the inspector counts as the user browses, and waking
the loop would put each next count behind a screenshot.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from concurrent.futures import ThreadPoolExecutor
from typing import TypeVar

from google.protobuf.timestamp_pb2 import Timestamp
from tap_e2e import Bounds, Device, NodeChange, ScreenNode, ScreenSnapshot, Screenshot, SelectorKind, TapError
from tap_e2e import proto as tap

from ._gen import studio_pb2 as studio

T = TypeVar("T")

_CHANGES = {
    NodeChange.NONE: tap.NODE_CHANGE_UNSPECIFIED,
    NodeChange.ADDED: tap.NODE_ADDED,
    NodeChange.UNCHANGED: tap.NODE_UNCHANGED,
    NodeChange.REMOVED: tap.NODE_REMOVED,
}


class FrameError(Exception):
    """The frame loop stopped because a device call failed; a new reader starts it again."""


class DeviceWorker:
    """One attached device: its thread, the order of its calls, and its frames."""

    def __init__(self, serial: str, *, slow: float = 2.0, backoff_start: float = 0.25):
        self.serial = serial
        self.device: Device | None = None
        self._slow = slow
        self._backoff_start = backoff_start
        self._executor = ThreadPoolExecutor(1, thread_name_prefix=f"tap-studio-{serial}")
        self._urgent = 0
        self._calm = asyncio.Event()  # no urgent call is waiting or running
        self._calm.set()
        self._kick = asyncio.Event()  # the urgent calls finished and one may have changed the screen
        self._touched = False  # an urgent call that may change the screen ran since the last kick
        self._published = asyncio.Condition()
        self._frame: studio.FramesResponse | None = None
        self._sequence = 0
        self._readers = 0
        self._loop: asyncio.Task[None] | None = None
        self._error: str | None = None
        self._closed = False

    async def call(self, function: Callable[[], T], *, urgent: bool = True, changes_screen: bool = True) -> T:
        """Runs ``function`` on the device's thread. Urgent calls hold the frame loop off; one that
        ``changes_screen`` also restarts it at full rate when the urgent calls are done."""
        if urgent:
            self._urgent += 1
            self._touched = self._touched or changes_screen
            self._calm.clear()
        try:
            return await asyncio.get_running_loop().run_in_executor(self._executor, function)
        finally:
            if urgent:
                self._urgent -= 1
                if self._urgent == 0:
                    self._calm.set()
                    if self._touched:
                        self._touched = False
                        self._kick.set()

    async def frames(self) -> AsyncIterator[studio.FramesResponse]:
        """The newest frame, then each new one; a reader that falls behind skips frames. Ends
        when the worker closes; raises ``FrameError`` when the loop stopped on a failure."""
        self._readers += 1
        if self._loop is None or self._loop.done():
            self._error = None
            self._loop = asyncio.create_task(self._run(), name=f"tap-studio frames {self.serial}")
        seen = self._sequence - 1 if self._frame is not None else self._sequence
        try:
            while True:
                async with self._published:
                    await self._published.wait_for(lambda: self._closed or self._error is not None or self._sequence > seen)
                if self._sequence > seen and self._frame is not None:
                    seen = self._sequence
                    yield self._frame
                elif self._error is not None:
                    raise FrameError(self._error)
                else:
                    return
        finally:
            self._readers -= 1

    async def close(self, last: Callable[[], object] | None = None) -> None:
        """Ends the frame streams, then runs ``last`` (the detach) after every queued call."""
        self._closed = True
        async with self._published:
            self._published.notify_all()
        if self._loop is not None:
            self._loop.cancel()
            try:
                await self._loop
            except (asyncio.CancelledError, Exception):  # noqa: S110 - the loop's own failure is reported to readers
                pass
        try:
            if last is not None:
                await self.call(last, urgent=False)
        finally:
            self._executor.shutdown(wait=False)

    async def _run(self) -> None:
        device = self.device
        assert device is not None, "frames before attach"
        delay = 0.0
        previous: dict[str, Bounds] | None = None
        while self._readers and not self._closed:
            await self._calm.wait()
            self._kick.clear()
            taken_at = Timestamp()
            taken_at.GetCurrentTime()
            try:
                snapshot = await self.call(lambda: device.screen_snapshot(selector_candidates=True), urgent=False)
                if self._urgent:
                    continue  # the picture would be taken after the user's call: not this screen
                shot = await self.call(device.screenshot, urgent=False)
            except TapError as error:
                self._error = f"taking a frame of {self.serial} failed: {error}"
                async with self._published:
                    self._published.notify_all()
                return
            layout = {node.ref: node.bounds for node in snapshot.nodes}
            moving = previous is not None and (changed(snapshot) or layout != previous)
            previous = layout
            async with self._published:
                self._sequence += 1
                self._frame = frame(self._sequence, taken_at, snapshot, shot, moving)
                self._published.notify_all()
            delay = 0.0 if moving else min(max(delay * 2, self._backoff_start), self._slow)
            if delay:
                try:
                    await asyncio.wait_for(self._kick.wait(), delay)
                except asyncio.TimeoutError:
                    pass
                if self._kick.is_set():
                    delay = 0.0


def changed(snapshot: ScreenSnapshot) -> bool:
    """Nodes appeared or went since the device's previous snapshot.

    With moved bounds (compared by ref in ``_run``), this is what makes a frame ``moving``: the
    overlay could be out of place. The picture is not compared: a blinking text cursor, the
    status bar clock or a spinner change it without moving any element, and would keep the frame
    loop at full rate and the overlay provisional for as long as they run."""
    return bool(snapshot.removed) or any(node.change is NodeChange.ADDED for node in snapshot.nodes)


def frame(
    sequence: int, taken_at: Timestamp, snapshot: ScreenSnapshot, shot: Screenshot, moving: bool
) -> studio.FramesResponse:
    return studio.FramesResponse(
        sequence=sequence,
        taken_at=taken_at,
        png=shot.bytes,
        width=shot.width,
        height=shot.height,
        rotation=snapshot.rotation,
        snapshot_id=snapshot.snapshot_id,
        nodes=[screen_node(node) for node in snapshot.nodes],
        moving=moving,
    )


def screen_node(node: ScreenNode) -> tap.ScreenNode:
    """``tap-e2e``'s ``ScreenNode`` back as the ``tap.v1`` message the page reads."""
    message = tap.ScreenNode(
        ref=node.ref,
        depth=node.depth,
        window_package=node.window_package,
        bounds=tap.Bounds(left=node.bounds.left, top=node.bounds.top, right=node.bounds.right, bottom=node.bounds.bottom),
        flags=sorted(tap.NodeFlag.Value(f"FLAG_{flag.name}") for flag in node.flags),
        password=node.password,
        interactive=node.interactive,
        by_index=node.by_index,
        change=_CHANGES[node.change],
    )
    for field in ("class_name", "resource_name", "text", "content_description", "hint"):
        value = getattr(node, field)
        if value is not None:
            setattr(message, field, value)
    if node.selector is not None:
        message.selector.CopyFrom(node.selector.to_proto())
    for candidate in node.candidates:
        kind = tap.SELECTOR_KIND_UNSPECIFIED if candidate.kind is SelectorKind.UNKNOWN else tap.SelectorKind.Value(f"SELECTOR_KIND_{candidate.kind.name}")
        message.candidates.add(selector=candidate.selector.to_proto(), kind=kind)
    return message
