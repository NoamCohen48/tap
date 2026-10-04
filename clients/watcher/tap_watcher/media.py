"""H.264 → MP4 muxing with the original packet timestamps, and the `steps.json` beside it.

No decoder or encoder runs: packets are copied as they arrived, so a variable frame rate
stays variable and nothing is re-encoded. One MP4 holds one stream, so a recording that spans
a rotation (or an encoder or daemon restart) is several parts, `video-1.mp4`, `video-2.mp4`…,
played back to back.
"""

from __future__ import annotations

import json
import pathlib
from fractions import Fraction
from typing import Any

import av
from google.protobuf import json_format
from tap_e2e.proto import event_log_pb2 as event_log
from tap_e2e.proto import watch_pb2 as watch

STEPS_FORMAT = "tap-watch-steps/2"
CORRELATION = (
    "Approximate: frame times are host receipt of the encoded packet, action times are host "
    "boundaries of the RPC. Neither is device input or capture time."
)
# The duration given to the last frame, which has no successor to measure against.
_LAST_FRAME_US = 66_667


class Mp4Writer:
    """Writes one stream's frames to [path] as they come. The first frame must be a key frame."""

    def __init__(self, path: pathlib.Path, header: watch.VideoHeader) -> None:
        if not 0 < header.width <= 8192 or not 0 < header.height <= 8192:
            raise ValueError("invalid video dimensions")
        self.header = header
        # The written frames without their data.
        self.frames: list[watch.VideoFrame] = []
        self.bytes = 0
        self._container = av.open(str(path), "w", format="mp4")
        self._stream = self._container.add_stream("h264", rate=30)
        self._stream.width, self._stream.height = header.width, header.height
        self._stream.time_base = Fraction(1, 1_000_000)
        self._stream.codec_context.extradata = header.configuration
        self._pending: tuple[bytes, int, bool] | None = None

    @property
    def seconds(self) -> float:
        return (
            (self.frames[-1].pts_us - self.frames[0].pts_us) / 1e6
            if self.frames
            else 0.0
        )

    @property
    def duration_us(self) -> int:
        """The file's length: up to the end of its last frame."""
        return int(self.seconds * 1e6) + _LAST_FRAME_US if self.frames else 0

    def add(self, frame: watch.VideoFrame) -> None:
        if not self.frames and not frame.key_frame:
            raise ValueError("a clip must start at a key frame")
        if self.frames and frame.pts_us <= self.frames[-1].pts_us:
            raise ValueError("frame timestamps must increase")
        pts = frame.pts_us - (self.frames[0].pts_us if self.frames else frame.pts_us)
        self._mux_pending(next_pts=pts)
        # Each key frame carries SPS/PPS, so any key frame in the file decodes on its own.
        data = self.header.configuration + frame.data if frame.key_frame else frame.data
        self._pending = (data, pts, frame.key_frame)
        # Timestamps only: the data is in the file, and a long recording must not hold it twice.
        self.frames.append(
            watch.VideoFrame(
                seq=frame.seq,
                pts_us=frame.pts_us,
                key_frame=frame.key_frame,
                received_monotonic_ns=frame.received_monotonic_ns,
                received_epoch_ms=frame.received_epoch_ms,
            )
        )
        self.bytes += len(frame.data)

    def _mux_pending(self, next_pts: int | None) -> None:
        if self._pending is None:
            return
        data, pts, key = self._pending
        packet = av.Packet(data)
        packet.stream = self._stream
        packet.time_base = Fraction(1, 1_000_000)
        packet.pts = packet.dts = pts
        packet.is_keyframe = key
        packet.duration = next_pts - pts if next_pts is not None else _LAST_FRAME_US
        self._container.mux(packet)
        self._pending = None

    def close(self) -> None:
        """Writes the last frame and the index; the file is complete after this."""
        try:
            self._mux_pending(next_pts=None)
        finally:
            self._container.close()


def part_name(index: int) -> str:
    """The file of part [index] (from 0)."""
    return f"video-{index + 1}.mp4"


class Parts:
    """A recording's video under [directory], one [Mp4Writer] per stream: a frame with a new
    header (or a timestamp that does not increase) closes the current part and starts the
    next, which must begin at a key frame."""

    def __init__(self, directory: pathlib.Path) -> None:
        self.directory = directory
        self.parts: list[Mp4Writer] = []

    @property
    def bytes(self) -> int:
        return sum(part.bytes for part in self.parts)

    @property
    def seconds(self) -> float:
        return sum(part.seconds for part in self.parts)

    @property
    def empty(self) -> bool:
        return not self.parts

    def add(self, header: watch.VideoHeader, frame: watch.VideoFrame) -> None:
        current = self.parts[-1] if self.parts else None
        if (
            current is None
            or current.header.stream_id != header.stream_id
            or current.header.configuration != header.configuration
            or frame.pts_us <= current.frames[-1].pts_us
        ):
            if not frame.key_frame:
                raise ValueError("a part must start at a key frame")
            if current is not None:
                current.close()
            current = Mp4Writer(self.directory / part_name(len(self.parts)), header)
            self.parts.append(current)
        current.add(frame)

    def close(self) -> None:
        """Completes the last part (the earlier ones closed when the next began)."""
        if self.parts:
            self.parts[-1].close()


def steps(
    *,
    kind: str,
    serial: str,
    parts: list[Mp4Writer],
    events: list[tuple[str, event_log.LoggedEvent]],
    names: dict[str, str],
    ended: str,
) -> dict[str, Any]:
    """The `steps.json` document: each part's stream and frame timestamps, and the actions that
    overlap the video, each placed in a part and on the whole recording's timeline (the parts
    back to back)."""
    starts, at = [], 0
    for part in parts:
        starts.append(at)
        at += part.duration_us
    # Per clock, the first and last frame received on it (actions on other clocks are not here).
    bounds: dict[str, tuple[int, int]] = {}
    for part in parts:
        first, last = (
            part.frames[0].received_monotonic_ns,
            part.frames[-1].received_monotonic_ns,
        )
        low, high = bounds.get(part.header.clock_id, (first, last))
        bounds[part.header.clock_id] = (min(low, first), max(high, last))
    actions = []
    for owner, event in events:
        if event.clock_id not in bounds:
            continue
        started = event.started_monotonic_ns
        finished = (
            event.finished_monotonic_ns
            if event.HasField("finished_monotonic_ns")
            else started
        )
        index = _part_at(parts, event.clock_id, started)
        part = parts[index]
        origin = part.frames[0]
        offset = part.frames[_frame_at(part.frames, started)].pts_us - origin.pts_us
        low, high = bounds[event.clock_id]
        actions.append(
            {
                "connectionId": owner,
                "connection": names.get(owner, ""),
                "event": json_format.MessageToDict(event),
                "part": index,
                "hostOffsetUs": str((started - origin.received_monotonic_ns) // 1000),
                "videoOffsetUs": str(offset),
                "offsetUs": str(starts[index] + offset),
                # It began before the first frame or ended after the last.
                "partial": started < low or finished > high,
            }
        )
    return {
        "format": STEPS_FORMAT,
        "kind": kind,
        "serial": serial,
        "ended": ended,
        "correlation": CORRELATION,
        "parts": [
            {
                "file": part_name(index),
                "streamId": part.header.stream_id,
                "clockId": part.header.clock_id,
                "width": part.header.width,
                "height": part.header.height,
                "startUs": str(starts[index]),
                "durationUs": str(part.duration_us),
                "mediaOriginPtsUs": str(part.frames[0].pts_us),
                "frames": [
                    {
                        "seq": str(frame.seq),
                        "ptsUs": str(frame.pts_us),
                        "offsetUs": str(frame.pts_us - part.frames[0].pts_us),
                        "keyFrame": frame.key_frame,
                        "receivedMonotonicNs": str(frame.received_monotonic_ns),
                        "receivedEpochMs": str(frame.received_epoch_ms),
                    }
                    for frame in part.frames
                ],
            }
            for index, part in enumerate(parts)
        ],
        "actions": actions,
    }


def _part_at(parts: list[Mp4Writer], clock_id: str, ns: int) -> int:
    """The last part on [clock_id] whose first frame came at or before [ns] (the first part on
    that clock when none did): an action between two parts belongs to the earlier one."""
    on_clock = [i for i, part in enumerate(parts) if part.header.clock_id == clock_id]
    started = [i for i in on_clock if parts[i].frames[0].received_monotonic_ns <= ns]
    return started[-1] if started else on_clock[0]


def write_steps(path: pathlib.Path, document: dict[str, Any]) -> None:
    path.write_text(json.dumps(document, indent=2))


def _frame_at(frames: list[watch.VideoFrame], ns: int) -> int:
    """The last frame received at or before [ns] (the first frame when none was)."""
    low, high = 0, len(frames) - 1
    while low < high:
        middle = (low + high + 1) // 2
        if frames[middle].received_monotonic_ns <= ns:
            low = middle
        else:
            high = middle - 1
    return low
