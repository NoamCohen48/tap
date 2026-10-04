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
