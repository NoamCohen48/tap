"""The page's service only reads: the daemon sees Info, ListDevices, Watch and WatchVideo."""

from __future__ import annotations

import asyncio
from unittest.mock import Mock

import pytest
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from fakes import FakeDaemon, encoded, event, serve, until
from tap_e2e.proto import device_pb2 as device
from tap_e2e.proto import watch_pb2 as watch

from tap_watcher._gen import watcher_pb2 as pb
from tap_watcher.recordings import Library
from tap_watcher.service import Watcher

READS = {"Info", "ListDevices", "Watch", "WatchVideo"}


async def exercise(tmp_path):
    daemon = FakeDaemon()
    daemon.video = encoded([0, 100_000, 200_000, 300_000])
    daemon.push(event(1, "serial", 50_000_000, 60_000_000))
    server, endpoint = await serve(daemon)
    watcher = Watcher(Library(tmp_path), endpoint)
    ctx = Mock(spec=RequestContext)
    await watcher.start()
    try:
        await until(lambda: watcher.activity.connected)
        status = await watcher.status(pb.StatusRequest(), ctx)
        assert status.daemon_connected and status.history.max_seconds == 120
        inventory = await watcher.list_devices(device.ListDevicesRequest(), ctx)
        assert inventory.devices[0].serial == "serial"

        stream = watcher.watch(watch.WatchRequest(), ctx)
        first = await anext(stream)
        assert [a.event.seq for a in first.activities] == [1]
        await stream.aclose()
        with pytest.raises(ConnectError):
            await anext(watcher.watch(watch.WatchRequest(after_seq=-1), ctx))

        video = watcher.watch_video(watch.WatchVideoRequest(serial="serial"), ctx)
        updates = [await anext(video) for _ in range(5)]
        await video.aclose()
        clip = (
            await watcher.save_clip(
                pb.SaveClipRequest(
                    serial="serial",
                    from_stream_id="stream-1",
                    from_seq=2,
                    to_stream_id="stream-1",
                    to_seq=4,
                ),
                ctx,
            )
        ).recording
        assert clip.kind == pb.RECORDING_KIND_CLIP
        assert clip.actions == 1  # the event at 50-60 ms is inside the clip's frames
        listed = await watcher.list_recordings(pb.ListRecordingsRequest(), ctx)
        assert [r.id for r in listed.recordings] == [clip.id]
        assert listed.used_bytes >= clip.bytes > 0 and listed.max_bytes == 2 << 30
        await watcher.delete_recordings(pb.DeleteRecordingsRequest(ids=[clip.id]), ctx)
        assert not (
            await watcher.list_recordings(pb.ListRecordingsRequest(), ctx)
        ).recordings
        assert updates[0].header.stream_id == "stream-1"

        assert {name for name, _ in daemon.calls} <= READS
        assert all(
            ("authorization", "Bearer secret") in metadata
            for _, metadata in daemon.calls
        )
    finally:
        await watcher.close()
        await server.stop(0)


def test_service_reads_only_and_saves_clips_from_retained_video(tmp_path):
    asyncio.run(exercise(tmp_path))


async def screens(tmp_path):
    daemon = FakeDaemon()
    daemon.video = encoded([n * 100_000 for n in range(6)], keyint=3)
    server, endpoint = await serve(daemon)
    watcher = Watcher(Library(tmp_path), endpoint)
    ctx = Mock(spec=RequestContext)
    await watcher.start()
    try:
        # Warm one serial's history so the wall has something older to skip.
        page = watcher.watch_video(watch.WatchVideoRequest(serial="a"), ctx)
        assert [(await anext(page)).frame.seq for _ in range(7)][1:] == [1, 2, 3, 4, 5, 6]

        stream = watcher.watch_screens(pb.WatchScreensRequest(serials=["a", "b", "a"]), ctx)
        got: dict[str, list[pb.ScreenUpdate]] = {"a": [], "b": []}
        while len(got["a"]) < 4 or len(got["b"]) < 7:
            update = await asyncio.wait_for(anext(stream), 5)
            got[update.serial].append(update)
        # Each serial opens with its header; "a" skips its history to the newest key frame,
        # "b" (no history yet) gets what the daemon sends from its first key frame.
        assert got["a"][0].HasField("header") and got["b"][0].HasField("header")
        assert [u.frame.seq for u in got["a"][1:4]] == [4, 5, 6]
        assert [u.frame.seq for u in got["b"][1:7]] == [1, 2, 3, 4, 5, 6]
        assert set(got) == {"a", "b"}
        await stream.aclose()
        await page.aclose()

        for bad in ([], [""], [str(n) for n in range(33)]):
            with pytest.raises(ConnectError):
                await anext(watcher.watch_screens(pb.WatchScreensRequest(serials=bad), ctx))
    finally:
        await watcher.close()
        await server.stop(0)


def test_watch_screens_tags_each_serial_from_its_latest_key_frame(tmp_path):
    asyncio.run(screens(tmp_path))


async def screen_errors(tmp_path):
    daemon = FakeDaemon()  # no video: the daemon refuses WatchVideo
    server, endpoint = await serve(daemon)
    watcher = Watcher(Library(tmp_path), endpoint)
    ctx = Mock(spec=RequestContext)
    await watcher.start()
    try:
        stream = watcher.watch_screens(pb.WatchScreensRequest(serials=["a"]), ctx)
        update = await asyncio.wait_for(anext(stream), 5)
        assert update.serial == "a" and "scrcpy" in update.error
        await stream.aclose()
    finally:
        await watcher.close()
        await server.stop(0)


def test_watch_screens_reports_a_serial_without_video(tmp_path):
    asyncio.run(screen_errors(tmp_path))
