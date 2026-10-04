"""Muxing keeps the original packet timestamps; steps place actions on the clip's timeline."""

from __future__ import annotations

import io

import av
import pytest
from fakes import encoded, event

from tap_watcher.media import Mp4Writer, Parts, steps


def test_mp4_keeps_variable_frame_offsets(tmp_path):
    header, frames = encoded([1_000_000, 1_070_000, 1_400_000, 1_500_000])
    writer = Mp4Writer(tmp_path / "video.mp4", header)
    for frame in frames:
        writer.add(frame)
    writer.close()
    assert writer.seconds == pytest.approx(0.5)
    assert all(not frame.data for frame in writer.frames)
    with av.open(io.BytesIO((tmp_path / "video.mp4").read_bytes()), mode="r") as movie:
        times = []
        for decoded in movie.decode(video=0):
            assert decoded.pts is not None and decoded.time_base is not None
            times.append(round(float(decoded.pts * decoded.time_base), 6))
    assert times == [0.0, 0.07, 0.4, 0.5]


def test_writer_refuses_a_delta_start_and_timestamps_that_do_not_increase(tmp_path):
    header, frames = encoded([0, 100_000, 200_000])
    writer = Mp4Writer(tmp_path / "a.mp4", header)
    with pytest.raises(ValueError, match="key frame"):
        writer.add(frames[1])
    writer.add(frames[0])
    with pytest.raises(ValueError, match="increase"):
        writer.add(frames[0])
    writer.close()
    header.width = 0
    with pytest.raises(ValueError, match="dimensions"):
        Mp4Writer(tmp_path / "b.mp4", header)


def decoded_sizes(path) -> list[tuple[int, int]]:
    with av.open(str(path), mode="r") as movie:
        return [(f.width, f.height) for f in movie.decode(video=0)]


def test_a_new_stream_starts_the_next_part(tmp_path):
    portrait = encoded([0, 100_000, 200_000], width=32, height=48)
    landscape = encoded(
        [0, 100_000], width=48, height=32, stream_id="stream-2", first_seq=4
    )
    parts = Parts(tmp_path)
    for header, frames in (portrait, landscape):
        for frame in frames:
            parts.add(header, frame)
    with pytest.raises(ValueError, match="key frame"):
        parts.add(portrait[0], portrait[1][1])  # back to a stream, but on a delta frame
    parts.close()
    assert [p.name for p in sorted(tmp_path.iterdir())] == [
        "video-1.mp4",
        "video-2.mp4",
    ]
    assert decoded_sizes(tmp_path / "video-1.mp4") == [(32, 48)] * 3
    assert decoded_sizes(tmp_path / "video-2.mp4") == [(48, 32)] * 2
    assert parts.seconds == pytest.approx(0.3)


def test_steps_place_actions_and_mark_partial_ones(tmp_path):
    header, frames = encoded([0, 100_000, 200_000, 300_000])
    parts = Parts(tmp_path)
    for frame in frames:
        parts.add(header, frame)
    parts.close()
    inside = event(1, "serial", 120_000_000, 150_000_000).event
    straddling = event(2, "serial", 250_000_000, 900_000_000).event
    document = steps(
        kind="clip",
        serial="serial",
        parts=parts.parts,
        events=[("c1", inside), ("c1", straddling)],
        names={"c1": "JUnit fixture-tests"},
        ended="",
    )
    assert document["format"] == "tap-watch-steps/2"
    (part,) = document["parts"]
    assert part["file"] == "video-1.mp4" and part["startUs"] == "0"
    assert [row["offsetUs"] for row in part["frames"]] == [
        "0",
        "100000",
        "200000",
        "300000",
    ]
    assert all("data" not in row for row in part["frames"])
    first, second = document["actions"]
    assert first["connection"] == "JUnit fixture-tests"
    assert (first["hostOffsetUs"], first["videoOffsetUs"], first["partial"]) == (
        "120000",
        "100000",
        False,
    )
    assert (second["videoOffsetUs"], second["partial"]) == ("200000", True)


def test_steps_place_actions_in_their_part_on_the_joint_timeline(tmp_path):
    # Portrait until 0.2 s, then landscape from 0.5 s on the same host clock.
    portrait = encoded([0, 100_000, 200_000])
    landscape = encoded(
        [500_000, 600_000], width=48, height=32, stream_id="stream-2", first_seq=4
    )
    parts = Parts(tmp_path)
    for header, frames in (portrait, landscape):
        for frame in frames:
            parts.add(header, frame)
    parts.close()
    rotate = event(1, "serial", 150_000_000, 520_000_000).event
    after = event(2, "serial", 610_000_000, 620_000_000).event
    document = steps(
        kind="recording",
        serial="serial",
        parts=parts.parts,
        events=[("c1", rotate), ("c1", after)],
        names={},
        ended="stopped",
    )
    first_part, second_part = document["parts"]
    assert (first_part["width"], second_part["width"]) == (32, 48)
    # The first part lasts 0.2 s plus its last frame's 1/15 s.
    assert second_part["startUs"] == str(200_000 + 66_667)
    spanning, later = document["actions"]
    # Rotating starts in the first part and ends in the second: not outside the video.
    assert (spanning["part"], spanning["videoOffsetUs"], spanning["partial"]) == (
        0,
        "100000",
        False,
    )
    assert (later["part"], later["videoOffsetUs"], later["offsetUs"]) == (
        1,
        "100000",
        str(266_667 + 100_000),
    )
