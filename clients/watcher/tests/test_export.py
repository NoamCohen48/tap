"""Synthetic H.264 only: no private device footage or external encoders required."""

from __future__ import annotations

import base64
import io
import json
import re
import zipfile
from fractions import Fraction

import av
import pytest
from av.video.codeccontext import VideoCodecContext
from tap_watcher.export import export_clip


def document():
    codec = av.CodecContext.create("libx264", "w")
    assert isinstance(codec, VideoCodecContext)
    codec.width, codec.height = 32, 48
    codec.pix_fmt = "yuv420p"
    codec.time_base = Fraction(1, 1_000_000)
    codec.framerate = Fraction(15)
    codec.options = {
        "preset": "ultrafast",
        "tune": "zerolatency",
        "x264-params": "keyint=3:bframes=0",
    }
    packets = []
    for pts in (1_000_000, 1_070_000, 1_400_000, 1_500_000):
        frame = av.VideoFrame(32, 48, "yuv420p")
        for index, plane in enumerate(frame.planes):
            plane.update(bytes([80 if index == 0 else 128]) * plane.buffer_size)
        frame.pts, frame.time_base = pts, Fraction(1, 1_000_000)
        packets.extend(codec.encode(frame))
    packets.extend(codec.encode(None))
    configuration = b"".join(
        b"\x00\x00\x00\x01" + nal
        for nal in re.split(b"\x00\x00(?:\x00)?\x01", bytes(packets[0]))
        if nal and nal[0] & 31 in (7, 8)
    )
    return {
        "header": {
            "width": 32,
            "height": 48,
            "configuration": base64.b64encode(configuration).decode(),
        },
        "actions": [{"original": "retained"}],
        "frames": [
            {
                "ptsUs": str(packet.pts),
                "keyFrame": packet.is_keyframe,
                "data": base64.b64encode(bytes(packet)).decode(),
                "receivedMonotonicNs": str(10_000_000 + index * 1000),
            }
            for index, packet in enumerate(packets)
        ],
    }


def test_export_preserves_vfr_offsets_and_original_timestamps():
    source = document()
    archive = export_clip(source)
    with zipfile.ZipFile(io.BytesIO(archive)) as output:
        metadata = json.loads(output.read("steps.json"))
        assert metadata["frames"][0]["ptsUs"] == "1000000"
        assert [row["clipOffsetUs"] for row in metadata["frames"]] == [
            "0",
            "70000",
            "400000",
            "500000",
        ]
        assert all("data" not in row for row in metadata["frames"])
        assert metadata["actions"] == source["actions"]
        with av.open(io.BytesIO(output.read("video.mp4")), mode="r") as movie:
            times = []
            for frame in movie.decode(video=0):
                assert frame.pts is not None and frame.time_base is not None
                times.append(round(float(frame.pts * frame.time_base), 6))
        assert times == [0.0, 0.07, 0.4, 0.5]


@pytest.mark.parametrize(
    "mutation", ["not_key", "backwards", "too_long", "invalid_size"]
)
def test_export_rejects_invalid_windows(mutation):
    source = document()
    if mutation == "not_key":
        source["frames"][0]["keyFrame"] = False
    if mutation == "backwards":
        source["frames"][1]["ptsUs"] = source["frames"][0]["ptsUs"]
    if mutation == "too_long":
        source["frames"][-1]["ptsUs"] = "122000001"
    if mutation == "invalid_size":
        source["header"]["width"] = 0
    with pytest.raises(ValueError):
        export_clip(source)
