"""Retained history is whole GOPs, gap-free, and shared by every reader of a serial."""

from __future__ import annotations

import asyncio

import pytest
from connectrpc.errors import ConnectError
from fakes import FakeDaemon, encoded, serve, until
from tap_e2e.proto import watch_pb2 as watch

from tap_watcher.daemon import Daemon
from tap_watcher.video import History, VideoHub


def frame(
    seq: int, seconds: float, key: bool = False, size: int = 1
) -> watch.VideoFrame:
    return watch.VideoFrame(
        seq=seq,
        pts_us=int(seconds * 1e6),
        received_monotonic_ns=int(seconds * 1e9),
        key_frame=key,
        data=b"x" * size,
    )


def seqs(history: History) -> list[tuple[str, int]]:
    return [(header.stream_id, f.seq) for header, f in history.frames()]


def test_history_evicts_whole_gops_and_waits_for_a_key_frame_after_a_gap():
    history = History(max_seconds=10, max_bytes=1000)
    assert history.set_header(watch.VideoHeader(stream_id="s"))
    assert not history.append(frame(1, 0))  # nothing decodes before a key frame
    for seq, at, key in [(1, 0, True), (2, 1, False), (3, 5, True), (4, 11, False)]:
        assert history.append(frame(seq, at, key))
    assert seqs(history) == [("s", 3), ("s", 4)]
    assert not history.append(frame(4, 11))  # a repeat
    assert not history.append(frame(6, 12))  # a gap: delta frames wait for a key frame
    assert history.append(frame(7, 13, True))
    assert seqs(history) == [("s", 3), ("s", 4), ("s", 7)]
    assert not history.set_header(watch.VideoHeader(stream_id="s"))


def test_a_new_stream_starts_a_segment_and_keeps_the_previous_one():
    history = History(max_seconds=10, max_bytes=1000)
    history.set_header(watch.VideoHeader(stream_id="portrait", clock_id="c"))
    for seq in range(1, 4):
        history.append(frame(seq, seq, key=seq == 1))
    assert history.set_header(watch.VideoHeader(stream_id="landscape", clock_id="c"))
    assert history.pending is not None
    assert not history.append(frame(4, 4))  # the new stream opens on a key frame
    kept = history.append(frame(5, 5, True))
    assert kept is not None and kept.stream_id == "landscape"
    assert [len(s.frames) for s in history.segments] == [3, 1]
    # A reconnect replays the older stream: already retained, so skipped.
    assert not history.set_header(watch.VideoHeader(stream_id="portrait", clock_id="c"))
    assert not history.append(frame(3, 3))
    assert not history.set_header(
        watch.VideoHeader(stream_id="landscape", clock_id="c")
    )
    assert history.append(frame(6, 6))
    # Age evicts the old segment whole, once the newest is past the window.
    history.append(frame(7, 12, True))
    assert seqs(history) == [("landscape", 5), ("landscape", 6), ("landscape", 7)]


def test_a_new_clock_is_placed_after_the_old_one_by_wall_time():
    history = History(max_seconds=10, max_bytes=1000)
    history.set_header(watch.VideoHeader(stream_id="a", clock_id="old"))
    old = frame(1, 100, True)
    old.received_epoch_ms = 1_000_000
    history.append(old)
    # The restarted daemon's monotonic clock reads lower, but 4 s of wall time passed.
    history.set_header(watch.VideoHeader(stream_id="b", clock_id="new"))
    new = frame(1, 1, True)
    new.received_epoch_ms = 1_004_000
    history.append(new)
    assert seqs(history) == [("a", 1), ("b", 1)]
    late = frame(2, 8, True)  # 11 s after the old frame on the joint timeline
    history.append(late)
    assert seqs(history) == [("b", 1), ("b", 2)]


def test_span_crosses_streams_and_starts_at_the_key_frame_before_it():
    history = History()
    history.set_header(watch.VideoHeader(stream_id="s"))
    for seq in range(1, 7):
        history.append(frame(seq, seq / 10, key=seq in (1, 4)))
    history.set_header(watch.VideoHeader(stream_id="t"))
    for seq in range(7, 9):
        history.append(frame(seq, seq / 10, key=seq == 7))
    assert [f.seq for _, f in history.span("s", 5, "s", 6)] == [4, 5, 6]
    spanned = history.span("s", 5, "t", 8)
    assert [(h.stream_id, f.seq) for h, f in spanned] == [
        ("s", 4),
        ("s", 5),
        ("s", 6),
        ("t", 7),
        ("t", 8),
    ]
    with pytest.raises(ConnectError):
        history.span("other", 1, "s", 2)
    with pytest.raises(ConnectError):
        history.span("t", 8, "s", 2)


async def readers_share_one_upstream():
    daemon = FakeDaemon()
    daemon.video = encoded([0, 100_000, 200_000, 300_000])
    server, endpoint = await serve(daemon)
    hub = VideoHub(Daemon(endpoint))
    try:
        first = hub.feed("serial").read()
        updates = [await anext(first) for _ in range(5)]
        assert updates[0].HasField("header")
        assert [u.frame.seq for u in updates[1:]] == [1, 2, 3, 4]
        # A second page gets the retained history at once, from the same upstream.
        second = hub.feed("serial").read()
        replay = [await anext(second) for _ in range(5)]
        assert [u.frame.seq for u in replay[1:]] == [1, 2, 3, 4]
        assert daemon.video_calls == 1
        await first.aclose()
        await second.aclose()
    finally:
        await hub.close()
        await server.stop(0)


def test_readers_share_one_upstream():
    asyncio.run(readers_share_one_upstream())


async def unconfigured_video_ends_readers():
    daemon = FakeDaemon()
    server, endpoint = await serve(daemon)
    hub = VideoHub(Daemon(endpoint))
    try:
        with pytest.raises(ConnectError, match="scrcpy"):
            await anext(hub.feed("serial").read())
        await until(lambda: hub.feeds["serial"].error != "")
    finally:
        await hub.close()
        await server.stop(0)


def test_unconfigured_video_ends_readers_without_retrying():
    asyncio.run(unconfigured_video_ends_readers())
