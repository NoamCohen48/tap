"""The frame loop: paired frames, moving vs settled, user calls first, readers, close."""

from __future__ import annotations

import asyncio
import dataclasses
import threading
import time

import pytest

from tap_e2e import NodeChange, ScreenSnapshot, Screenshot, TapError, res
from tap_e2e import proto as tap
from tap_e2e._proto import screen_node as model_node
from tap_studio.screen import DeviceWorker, FrameError, screen_node

pytestmark = pytest.mark.anyio


def png(width: int, marker: int = 0) -> bytes:
    """Enough of a PNG for Screenshot to read its size, plus a marker to make pictures differ."""
    return b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + width.to_bytes(4, "big") + (2 * width).to_bytes(4, "big") + bytes([marker])


class ScriptedDevice:
    """Duck-types the Device calls the loop makes; logs every call in order."""

    serial = "emulator-5554"

    def __init__(self) -> None:
        self.calls: list[str] = []
        self.picture = png(100)
        self.change = NodeChange.UNCHANGED
        self.top = 0
        self.fail: TapError | None = None
        self.snapshots = 0
        self.lock = threading.Lock()

    def screen_snapshot(self, selector_candidates: bool = False) -> ScreenSnapshot:
        assert selector_candidates
        self._log("snapshot")
        if self.fail is not None:
            raise self.fail
        self.snapshots += 1
        node = model_node(tap.ScreenNode(ref="e1", window_package="com.example", change=tap.NODE_UNCHANGED))
        bounds = dataclasses.replace(node.bounds, top=self.top, bottom=self.top + 50)
        node = dataclasses.replace(node, change=self.change, bounds=bounds)
        return ScreenSnapshot(self.snapshots, (node,), (), 0)

    def screenshot(self) -> Screenshot:
        self._log("screenshot")
        return Screenshot._png(self.picture)

    def _log(self, name: str) -> None:
        time.sleep(0.005)
        with self.lock:
            self.calls.append(name)


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
async def worker():
    worker = DeviceWorker("emulator-5554", slow=0.05, backoff_start=0.01)
    worker.device = ScriptedDevice()  # type: ignore[assignment]
    yield worker
    await worker.close()


async def take(stream, count: int) -> list:
    frames = []
    async for frame in stream:
        frames.append(frame)
        if len(frames) == count:
            break
    return frames


async def test_frames_pair_a_snapshot_with_the_picture_taken_after_it(worker):
    first, second = await asyncio.wait_for(take(worker.frames(), 2), 5)
    assert (first.sequence, second.sequence) == (1, 2)
    assert (first.width, first.height, first.png) == (100, 200, worker.device.picture)
    assert [n.ref for n in first.nodes] == ["e1"] and first.snapshot_id == 1
    assert worker.device.calls[:4] == ["snapshot", "screenshot", "snapshot", "screenshot"]
    assert not first.moving and not second.moving  # nothing changed


async def test_new_or_moved_nodes_mark_the_frame_moving(worker):
    stream = worker.frames()
    await asyncio.wait_for(take(stream, 1), 5)
    worker.device.change = NodeChange.ADDED
    assert (await asyncio.wait_for(anext(stream), 5)).moving
    worker.device.change = NodeChange.UNCHANGED
    assert not (await asyncio.wait_for(anext(stream), 5)).moving  # settled again
    worker.device.top = 10  # scrolled: the same node, elsewhere
    assert (await asyncio.wait_for(anext(stream), 5)).moving
    assert not (await asyncio.wait_for(anext(stream), 5)).moving
    await stream.aclose()


async def test_a_picture_only_change_is_not_moving(worker):
    """A blinking cursor or the clock changes the picture, not where the elements are."""
    stream = worker.frames()
    await asyncio.wait_for(take(stream, 1), 5)
    worker.device.picture = png(100, 1)
    frame = await asyncio.wait_for(anext(stream), 5)
    assert frame.png == worker.device.picture and not frame.moving
    await stream.aclose()


async def test_a_user_call_never_waits_behind_more_than_the_call_running(worker):
    stream = worker.frames()
    await asyncio.wait_for(take(stream, 1), 5)
    device = worker.device
    for i in range(20):
        await worker.call(lambda i=i: device._log(f"user{i}"))
        await asyncio.sleep(0.003)
    await stream.aclose()
    calls = list(device.calls)
    for i in range(20):
        at = calls.index(f"user{i}")
        # A snapshot is never followed by its picture across a user call.
        assert not (calls[at - 1] == "snapshot" and at + 1 < len(calls) and calls[at + 1] == "screenshot"), calls


async def test_only_a_call_that_may_change_the_screen_wakes_a_settled_loop():
    worker = DeviceWorker("emulator-5554", slow=5, backoff_start=5)
    worker.device = ScriptedDevice()  # type: ignore[assignment]
    frames: list = []

    async def read() -> None:
        async for frame in worker.frames():
            frames.append(frame)

    reader = asyncio.create_task(read())
    try:
        while not frames:
            await asyncio.sleep(0.01)
        await asyncio.sleep(0.1)  # into the 5 s back-off
        device = worker.device
        await worker.call(lambda: device._log("count"), changes_screen=False)
        await asyncio.sleep(0.3)
        assert len(frames) == 1  # still backing off
        await worker.call(lambda: device._log("tap"))
        await asyncio.sleep(0.3)
        assert [f.sequence for f in frames] == [1, 2]  # at once
    finally:
        reader.cancel()
        await worker.close()


async def test_the_loop_runs_only_while_someone_reads(worker):
    stream = worker.frames()
    await asyncio.wait_for(take(stream, 2), 5)
    await stream.aclose()
    await asyncio.sleep(0.1)
    count = len(worker.device.calls)
    await asyncio.sleep(0.2)
    assert len(worker.device.calls) == count


async def test_close_ends_the_streams_and_runs_the_last_call_after_the_queue(worker):
    stream = worker.frames()
    await asyncio.wait_for(take(stream, 1), 5)
    await worker.close(lambda: worker.device._log("detach"))

    async def rest() -> list:
        return [frame async for frame in stream]

    assert len(await asyncio.wait_for(rest(), 5)) <= 1  # at most a frame published before close, then the end
    assert worker.device.calls[-1] == "detach"


async def test_a_failing_device_call_ends_the_stream_with_a_frame_error(worker):
    worker.device.fail = TapError("device gone")
    with pytest.raises(FrameError, match="device gone"):
        await asyncio.wait_for(take(worker.frames(), 1), 5)
    worker.device.fail = None
    assert len(await asyncio.wait_for(take(worker.frames(), 1), 5)) == 1  # a new reader starts again


def test_nodes_go_back_to_the_message_unchanged():
    message = tap.ScreenNode(
        ref="e3",
        depth=2,
        window_package="com.example",
        class_name="android.widget.Button",
        resource_name="com.example:id/login",
        text="Log in",
        bounds=tap.Bounds(left=1, top=2, right=3, bottom=4),
        flags=[tap.FLAG_ENABLED, tap.FLAG_CLICKABLE],
        interactive=True,
        selector=res("login").to_proto(),
        change=tap.NODE_ADDED,
        candidates=[
            tap.SelectorCandidate(selector=res("login").to_proto(), kind=tap.SELECTOR_KIND_PLAIN),
            tap.SelectorCandidate(selector=res("login").at(0).to_proto(), kind=tap.SELECTOR_KIND_BY_INDEX),
        ],
    )
    assert screen_node(model_node(message)) == message
    bare = tap.ScreenNode(ref="e4", window_package="android", bounds=tap.Bounds(right=9), password=True, by_index=True)
    assert screen_node(model_node(bare)) == bare
