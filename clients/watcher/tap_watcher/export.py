"""Bounded, lossless H.264 → MP4 muxing with original packet timestamps plus JSON."""

from __future__ import annotations

import base64
import io
import json
import zipfile
from fractions import Fraction
from typing import Any

import av

MAX_BODY = 48 * 1024 * 1024
MAX_VIDEO = 32 * 1024 * 1024


def export_clip(document: dict[str, Any]) -> bytes:
    """Return a ZIP. Caller authenticates, bounds request size and limits concurrency.

    Cuts begin at the supplied preceding key frame, not an invented exact action
    boundary. No decoder/encoder runs, and no device or owner API is called.
    """
    header = document["header"]
    width, height = int(header["width"]), int(header["height"])
    if not 0 < width <= 8192 or not 0 < height <= 8192:
        raise ValueError("invalid video dimensions")
    configuration = base64.b64decode(header["configuration"], validate=True)
    rows = document["frames"]
    if not rows or len(rows) > 3600 or not rows[0]["keyFrame"]:
        raise ValueError("clip must start at a key frame; at most 3600 frames")
    packets: list[tuple[bytes, int, bool]] = []
    total = len(configuration)
    origin = int(rows[0]["ptsUs"])
    last_pts = -1
    for row in rows:
        data = base64.b64decode(row["data"], validate=True)
        total += len(data)
        pts = int(row["ptsUs"]) - origin
        if not data or len(data) > 1024 * 1024 or total > MAX_VIDEO:
            raise ValueError("clip exceeds encoded byte limits")
        if pts <= last_pts or pts > 120_000_000:
            raise ValueError("timestamps must increase; clip limit is 120 seconds")
        packets.append((data, pts, bool(row["keyFrame"])))
        last_pts = pts
    movie = io.BytesIO()
    with av.open(movie, "w", format="mp4") as container:
        stream = container.add_stream("h264", rate=15)
        stream.width, stream.height = width, height
        stream.time_base = Fraction(1, 1_000_000)
        stream.codec_context.extradata = configuration
        for index, (data, pts, key) in enumerate(packets):
            packet = av.Packet(configuration + data if key else data)
            packet.stream = stream
            packet.time_base = Fraction(1, 1_000_000)
            packet.pts = packet.dts = pts
            packet.is_keyframe = key
            packet.duration = (
                packets[index + 1][1] - pts if index + 1 < len(packets) else 66_667
            )
            container.mux(packet)
    # Remove binary video payloads; retain original packet/host timestamps and offsets.
    metadata = {key: value for key, value in document.items() if key != "frames"}
    metadata["format"] = "tap-video-clip/1"
    metadata["correlation"] = (
        "approximate host receipt; not device before/after evidence"
    )
    metadata["mediaOriginPtsUs"] = str(origin)
    metadata["frames"] = [
        {
            **{key: value for key, value in row.items() if key != "data"},
            "clipOffsetUs": str(int(row["ptsUs"]) - origin),
        }
        for row in rows
    ]
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as output:
        output.writestr("video.mp4", movie.getvalue())
        output.writestr("steps.json", json.dumps(metadata, indent=2))
    return archive.getvalue()
