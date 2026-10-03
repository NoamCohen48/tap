"""Recordings are written by the back end, survive restarts and download as files or a ZIP."""

from __future__ import annotations

import asyncio
import io
import json
import os
import time
import zipfile
from unittest.mock import Mock

import pytest
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from fakes import FakeDaemon, encoded, event, serve, until
from starlette.testclient import TestClient
from tap_e2e.client import Endpoint
from tap_e2e.proto import watch_pb2 as watch

from tap_watcher._gen import watcher_pb2 as pb
from tap_watcher.recordings import Library
from tap_watcher.server import create_app
from tap_watcher.service import Watcher

DAY_MS = 24 * 60 * 60 * 1000


async def record(tmp_path):
    daemon = FakeDaemon()
    header, frames = encoded([index * 100_000 for index in range(9)])
    daemon.video = (header, frames[:4])
    server, endpoint = await serve(daemon)
    watcher = Watcher(Library(tmp_path), endpoint)
    ctx = Mock(spec=RequestContext)
    await watcher.start()
    try:
        await until(lambda: watcher.activity.connected)
        started = await watcher.start_recording(
            pb.StartRecordingRequest(serial="serial"), ctx
        )
        assert started.recording.serial == "serial"
        with pytest.raises(ConnectError, match="already recording"):
            await watcher.start_recording(
                pb.StartRecordingRequest(serial="serial"), ctx
            )
        # No page is open: the recording alone keeps the video coming.
        await until(lambda: daemon.video_open.is_set())
        daemon.push(event(1, "serial", 150_000_000, 180_000_000, failed=True))
        daemon.push(event(2, "other", 150_000_000, 180_000_000))
        await until(
            lambda: watcher.activity.connected and len(watcher.activity._history) == 2
        )
        status = await watcher.status(pb.StatusRequest(), ctx)
        assert [r.serial for r in status.recordings] == ["serial"]
        # The device rotates: a new stream, recorded on as the second part.
        landscape, turned = encoded(
            [500_000, 600_000], width=48, height=32, stream_id="stream-2", first_seq=5
        )
        daemon.video_live.put_nowait(watch.WatchVideoResponse(header=landscape))
        for frame in turned:
            daemon.video_live.put_nowait(watch.WatchVideoResponse(frame=frame))
        feed = watcher.video.feeds["serial"]
        await until(lambda: len(feed.history.segments) == 2)
        await until(lambda: len(feed.history.segments[-1].frames) == len(turned))
        saved = (
            await watcher.stop_recording(pb.StopRecordingRequest(serial="serial"), ctx)
        ).recording
        assert (saved.kind, saved.ended, saved.actions, saved.failures) == (
            pb.RECORDING_KIND_RECORDING,
            "stopped",
            1,
            1,
        )
        assert saved.parts == 2
        assert saved.duration_seconds == pytest.approx(0.4)
        assert not (await watcher.status(pb.StatusRequest(), ctx)).recordings
        with pytest.raises(ConnectError):
            await watcher.stop_recording(pb.StopRecordingRequest(serial="serial"), ctx)
        return saved.id
    finally:
        await watcher.close()
        await server.stop(0)


def test_recording_saves_files_that_download_after_a_restart(tmp_path):
    identifier = asyncio.run(record(tmp_path))
    offline = Watcher(Library(tmp_path), Endpoint("127.0.0.1:9", "unused"))
    with TestClient(
        create_app("new-token", service=offline), base_url="http://127.0.0.1:9000"
    ) as client:
        assert client.get(f"/recordings/{identifier}/video-1.mp4").status_code == 401
        client.get("/login?t=new-token")
        response = client.get(
            f"/recordings/{identifier}/video-1.mp4", headers={"Range": "bytes=0-15"}
        )
        assert response.status_code == 206 and len(response.content) == 16
        assert response.headers["content-type"] == "video/mp4"
        steps = client.get(f"/recordings/{identifier}/steps.json").json()
        assert steps["kind"] == "recording" and steps["serial"] == "serial"
        assert [(p["file"], p["width"]) for p in steps["parts"]] == [
            ("video-1.mp4", 32),
            ("video-2.mp4", 48),
        ]
        assert [a["event"]["seq"] for a in steps["actions"]] == ["1"]
        download = client.get(f"/recordings/{identifier}.zip")
        assert "attachment" in download.headers["content-disposition"]
        with zipfile.ZipFile(io.BytesIO(download.content)) as archive:
            assert archive.namelist() == ["video-1.mp4", "video-2.mp4", "steps.json"]
            assert json.loads(archive.read("steps.json"))["serial"] == "serial"
        assert not list(tmp_path.glob(".zip-*"))
        assert client.get("/recordings/not-a-valid-id/video-1.mp4").status_code == 404
        assert client.get(f"/recordings/{identifier}/video-3.mp4").status_code == 404
        assert client.get(f"/recordings/{identifier}/recording.json").status_code == 404
    assert [r.id for r in Library(tmp_path).list()] == [identifier]


def test_a_full_library_refuses_and_never_deletes(tmp_path, monkeypatch):
    import tap_watcher.recordings as module

    library = Library(tmp_path)
    staging = library.begin()
    (staging / "video-1.mp4").write_bytes(b"x" * 10)
    kept = library.commit(staging, pb.Recording(serial="serial"))
    monkeypatch.setattr(module, "MAX_LIBRARY_BYTES", 1)
    with pytest.raises(module.LibraryFull):
        library.begin()
    assert [r.id for r in library.list()] == [kept.id]
    assert not list(tmp_path.glob(".saving-*"))
    library.delete([kept.id, kept.id, "../escape"])
    assert library.list() == []


def saved(library: Library, created_epoch_ms: int) -> str:
    staging = library.begin()
    (staging / "video-1.mp4").write_bytes(b"x")
    return library.commit(
        staging, pb.Recording(serial="serial", created_epoch_ms=created_epoch_ms)
    ).id


def test_sweep_removes_interrupted_saves_and_only_expired_entries(tmp_path):
    now = int(time.time() * 1000)
    library = Library(tmp_path)
    old, recent = saved(library, now - 3 * DAY_MS), saved(library, now - DAY_MS // 2)
    # A save whose process died: nobody holds its lock.
    interrupted = tmp_path / ".saving-crashed"
    interrupted.mkdir()
    (interrupted / "video-1.mp4").write_bytes(b"half a file")
    abandoned = tmp_path / ".zip-abandoned.zip"
    abandoned.write_bytes(b"x")
    os.utime(abandoned, (time.time() - 7200, time.time() - 7200))
    # Without --keep-days, only the leftovers go.
    assert library.sweep() == []
    assert not list(tmp_path.glob(".*"))
    assert {r.id for r in library.list()} == {old, recent}
    assert Library(tmp_path, keep_days=1).sweep() == [old]
    assert [r.id for r in library.list()] == [recent]


def test_sweep_keeps_a_save_in_progress_in_another_watcher(tmp_path):
    writer, other = Library(tmp_path), Library(tmp_path)
    staging = writer.begin()
    (staging / "video-1.mp4").write_bytes(b"still recording")
    other.sweep()
    writer.sweep()
    assert staging.is_dir()
    record = writer.commit(
        staging, pb.Recording(serial="emulator-5554", created_epoch_ms=1)
    )
    assert [r.id for r in other.list()] == [record.id]
    discarded = writer.begin()
    writer.discard(discarded)
    assert not discarded.exists() and not writer._locks
