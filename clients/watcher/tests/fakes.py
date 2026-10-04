"""A scripted Tap daemon (only the read RPCs the watcher may call) and synthetic H.264."""

from __future__ import annotations

import asyncio
import re
from fractions import Fraction

import av
import grpc
from av.video.codeccontext import VideoCodecContext
from tap_e2e.client import Endpoint
from tap_e2e.proto import client_connection_pb2 as connection
from tap_e2e.proto import client_connection_pb2_grpc, device_pb2_grpc, watch_pb2_grpc
from tap_e2e.proto import command_pb2 as command
from tap_e2e.proto import device_pb2 as device
from tap_e2e.proto import event_log_pb2 as event_log
from tap_e2e.proto import watch_pb2 as watch

Stream = tuple[watch.VideoHeader, list[watch.VideoFrame]]


def encoded(
    pts_us: list[int],
    width: int = 32,
    height: int = 48,
    keyint: int = 3,
    stream_id: str = "stream-1",
    first_seq: int = 1,
) -> Stream:
    """A real H.264 stream of flat frames at [pts_us], as the daemon would send it."""
    codec = av.CodecContext.create("libx264", "w")
    assert isinstance(codec, VideoCodecContext)
    codec.width, codec.height = width, height
    codec.pix_fmt = "yuv420p"
    codec.time_base = Fraction(1, 1_000_000)
    codec.framerate = Fraction(15)
    codec.options = {
        "preset": "ultrafast",
        "tune": "zerolatency",
        "x264-params": f"keyint={keyint}:bframes=0",
    }
    packets = []
    for pts in pts_us:
        frame = av.VideoFrame(width, height, "yuv420p")
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
    header = watch.VideoHeader(
        stream_id=stream_id,
        clock_id="clock",
        width=width,
        height=height,
        configuration=configuration,
    )
    frames = [
        watch.VideoFrame(
            seq=first_seq + index,
            pts_us=packet.pts,
            key_frame=packet.is_keyframe,
            data=bytes(packet),
            received_monotonic_ns=packet.pts * 1000,
            received_epoch_ms=1_700_000_000_000 + packet.pts // 1000,
        )
        for index, packet in enumerate(packets)
    ]
    return header, frames


def event(
    seq: int, serial: str, started_ns: int, finished_ns: int, failed: bool = False
) -> watch.Activity:
    """A command connection "c1" ran on [serial], with host boundaries on the fake clock."""
    logged = event_log.LoggedEvent(
        seq=seq,
        serial=serial,
        at_epoch_ms=1_700_000_000_000,
        duration_ms=(finished_ns - started_ns) // 1_000_000,
        started_monotonic_ns=started_ns,
        finished_monotonic_ns=finished_ns,
        clock_id="clock",
        command=command.Command(),
    )
    if failed:
        logged.error.code = command.ERR_NOT_FOUND
        logged.error.message = "no match"
    return watch.Activity(client_connection_id="c1", event=logged)


class FakeDaemon(
    client_connection_pb2_grpc.ClientConnectionServiceServicer,
    device_pb2_grpc.DeviceServiceServicer,
    watch_pb2_grpc.WatchServiceServicer,
):
    """Records every call (with its metadata). `Watch` replays [snapshot] + [activities] after
    the requested seq, then whatever [push] adds; `WatchVideo` sends [video] (one stream, or
    several in a row as a rotation would)."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple]] = []
        self.pid = 100
        self.snapshot: list[connection.ConnectionEntry] = []
        self.activities: list[watch.Activity] = []
        self.live: asyncio.Queue[watch.Activity | None] = asyncio.Queue()
        self.video: Stream | list[Stream] | None = None
        self.video_open = asyncio.Event()
        # Sent after [video], while the stream is held open.
        self.video_live: asyncio.Queue[watch.WatchVideoResponse] = asyncio.Queue()
        self.video_calls = 0
        self.hold_video = True

    def _call(self, name: str, context: grpc.aio.ServicerContext) -> None:
        self.calls.append((name, tuple(context.invocation_metadata() or ())))

    async def Info(self, request, context):
        self._call("Info", context)
        return connection.InfoResponse(pid=self.pid, daemon_version="test")

    async def ListDevices(self, request, context):
        self._call("ListDevices", context)
        return device.ListDevicesResponse(
            devices=[device.DeviceEntry(serial="serial", state=device.DEVICE_FREE)]
        )

    def push(self, activity: watch.Activity) -> None:
        activity.seq = len(self.activities) + 1
        self.activities.append(activity)
        self.live.put_nowait(activity)

    async def Watch(self, request, context):
        self._call("Watch", context)
        # Activity pushed before this call is in the backlog, not in `live`.
        while not self.live.empty():
            self.live.get_nowait()
        yield watch.WatchResponse(
            connections=self.snapshot,
            activities=[a for a in self.activities if a.seq > request.after_seq],
        )
        while (activity := await self.live.get()) is not None:
            yield watch.WatchResponse(activities=[activity])

    async def WatchVideo(self, request, context):
        self._call("WatchVideo", context)
        self.video_calls += 1
        if self.video is None:
            await context.abort(
                grpc.StatusCode.FAILED_PRECONDITION, "No scrcpy server configured"
            )
            return
        for header, frames in (
            self.video if isinstance(self.video, list) else [self.video]
        ):
            yield watch.WatchVideoResponse(header=header)
            for frame in frames:
                yield watch.WatchVideoResponse(frame=frame)
        self.video_open.set()
        while self.hold_video:
            yield await self.video_live.get()


async def serve(daemon: FakeDaemon) -> tuple[grpc.aio.Server, Endpoint]:
    server = grpc.aio.server()
    client_connection_pb2_grpc.add_ClientConnectionServiceServicer_to_server(
        daemon, server
    )
    device_pb2_grpc.add_DeviceServiceServicer_to_server(daemon, server)
    watch_pb2_grpc.add_WatchServiceServicer_to_server(daemon, server)
    port = server.add_insecure_port("127.0.0.1:0")
    await server.start()
    return server, Endpoint(f"127.0.0.1:{port}", "secret")


async def until(condition, timeout: float = 5.0) -> None:
    async def wait() -> None:
        while not condition():
            await asyncio.sleep(0.01)

    await asyncio.wait_for(wait(), timeout)
