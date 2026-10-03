"""Per-device video: one daemon `WatchVideo` per serial, shared by every page and recording.

A feed runs while a page watches the serial or a recording is active, and lingers briefly
after the last page leaves so a reload keeps its history. It retains whole GOPs within
[HISTORY_SECONDS] / [HISTORY_BYTES], so a page can scrub back and a clip can be cut from what
happened before anyone pressed a button. A new stream (rotation, encoder or daemon restart)
starts a new segment; earlier segments stay until they age out.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections import deque
from collections.abc import AsyncGenerator, Callable
from dataclasses import dataclass, field

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from tap_e2e.proto import watch_pb2 as watch

from .daemon import Daemon

HISTORY_SECONDS = 120
HISTORY_BYTES = 32 * 1024 * 1024
# A page that falls this far behind is dropped; it reconnects and gets the history again.
READER_UPDATES = 256
READER_BYTES = 16 * 1024 * 1024
LINGER_S = 15.0
_END = object()


@dataclass(eq=False)
class Segment:
    """A run of one stream's frames, starting at a key frame. A rotation or encoder restart
    (a new header) starts the next segment; the previous one stays until it ages out."""

    header: watch.VideoHeader
    frames: deque[watch.VideoFrame] = field(default_factory=deque)


class History:
    """Segments of whole GOPs, oldest evicted first, within one time and byte budget for all of
    them. A sequence gap drops frames until the next key frame, so what is retained always
    decodes."""

    def __init__(
        self, max_seconds: float = HISTORY_SECONDS, max_bytes: int = HISTORY_BYTES
    ) -> None:
        self.max_ns = int(max_seconds * 1e9)
        self.max_bytes = max_bytes
        self.segments: deque[Segment] = deque()
        self.bytes = 0
        self._pending: watch.VideoHeader | None = None
        # Frames of an older stream that a reconnect replays are already retained.
        self._skipping = False
        self._awaiting_key = False
        # Per clock, what to add to its monotonic times to place them on one timeline (a daemon
        # restart brings a new clock; segments on different clocks are ordered by wall time).
        self._offsets: dict[str, int] = {}

    @property
    def pending(self) -> watch.VideoHeader | None:
        """A new stream's header whose first key frame has not arrived yet."""
        return self._pending

    def set_header(self, header: watch.VideoHeader) -> bool:
        """True when this is a new stream (the next key frame starts a segment)."""
        if any(_same(segment.header, header) for segment in self.segments):
            self._skipping = not _same(self.segments[-1].header, header)
            self._pending = None
            return False
        self._skipping = False
        if self._pending is not None and _same(self._pending, header):
            return False
        self._pending = header
        return True

    def append(self, frame: watch.VideoFrame) -> watch.VideoHeader | None:
        """The header of the stream [frame] was kept in, or None for a frame that cannot be kept
        (a repeat, a delta frame after a gap, or a frame of an older stream)."""
        if self._skipping:
            return None
        if self._pending is not None:
            if not frame.key_frame:
                return None
            self._start(self._pending, frame)
            self._pending = None
        elif not self.segments:
            return None
        else:
            segment = self.segments[-1]
            last = segment.frames[-1]
            if frame.seq <= last.seq:
                return None
            if frame.seq != last.seq + 1:
                self._awaiting_key = True
            if self._awaiting_key and not frame.key_frame:
                return None
            if frame.pts_us <= last.pts_us:
                # Time went backwards inside one stream: keep it apart so each segment muxes.
                if not frame.key_frame:
                    self._awaiting_key = True
                    return None
                self._start(segment.header, frame)
            else:
                segment.frames.append(frame)
        self._awaiting_key = False
        self.bytes += len(frame.data)
        self._evict(frame)
        return self.segments[-1].header

    def _start(self, header: watch.VideoHeader, frame: watch.VideoFrame) -> None:
        if header.clock_id not in self._offsets:
            offset = 0
            if self.segments:
                previous = self.segments[-1]
                newest = previous.frames[-1]
                gap_ns = max(
                    1, (frame.received_epoch_ms - newest.received_epoch_ms) * 1_000_000
                )
                offset = (
                    self._timeline(previous.header, newest)
                    + gap_ns
                    - frame.received_monotonic_ns
                )
            self._offsets[header.clock_id] = offset
        self.segments.append(Segment(header, deque([frame])))

    def _timeline(self, header: watch.VideoHeader, frame: watch.VideoFrame) -> int:
        return frame.received_monotonic_ns + self._offsets[header.clock_id]

    def _evict(self, newest: watch.VideoFrame) -> None:
        newest_ns = self._timeline(self.segments[-1].header, newest)

        def over() -> bool:
            oldest = self.segments[0]
            return (
                self.bytes > self.max_bytes
                or newest_ns - self._timeline(oldest.header, oldest.frames[0])
                > self.max_ns
            )

        # Drop the oldest GOP while over the limit, keeping at least the newest one.
        while over():
            oldest = self.segments[0]
            frames = oldest.frames
            next_key = next(
                (i for i, f in enumerate(frames) if i > 0 and f.key_frame), None
            )
            if next_key is None:
                if len(self.segments) == 1:
                    return
                self.segments.popleft()
                self.bytes -= sum(len(f.data) for f in frames)
                clocks = {segment.header.clock_id for segment in self.segments}
                self._offsets = {
                    clock: offset
                    for clock, offset in self._offsets.items()
                    if clock in clocks
                }
                continue
            for _ in range(next_key):
                self.bytes -= len(frames.popleft().data)

    def frames(self) -> list[tuple[watch.VideoHeader, watch.VideoFrame]]:
        """Every retained frame, oldest first, with its stream's header."""
        return [
            (segment.header, frame)
            for segment in self.segments
            for frame in segment.frames
        ]

    def latest_gop(self) -> list[tuple[watch.VideoHeader, watch.VideoFrame]]:
        """From the newest key frame on (where a recording starts)."""
        if not self.segments:
            return []
        segment = self.segments[-1]
        frames = list(segment.frames)
        start = max(i for i, frame in enumerate(frames) if frame.key_frame)
        return [(segment.header, frame) for frame in frames[start:]]

    def span(
        self, from_stream: str, from_seq: int, to_stream: str, to_seq: int
    ) -> list[tuple[watch.VideoHeader, watch.VideoFrame]]:
        """Retained frames from (from_stream, from_seq) to (to_stream, to_seq), possibly across
        streams, starting at the key frame at or before the first."""
        frames = self.frames()

        def find(stream: str, seq: int) -> int | None:
            return next(
                (
                    i
                    for i in range(len(frames) - 1, -1, -1)
                    if frames[i][0].stream_id == stream and frames[i][1].seq <= seq
                ),
                None,
            )

        first, last = find(from_stream, from_seq), find(to_stream, to_seq)
        if first is None or frames[first][1].seq != from_seq:
            raise ConnectError(
                Code.FAILED_PRECONDITION, "the start of that clip is no longer retained"
            )
        if last is None or last < first:
            raise ConnectError(Code.INVALID_ARGUMENT, "the clip is empty")
        while not frames[first][1].key_frame:
            first -= 1
        return frames[first : last + 1]


def _same(a: watch.VideoHeader, b: watch.VideoHeader) -> bool:
    return a.stream_id == b.stream_id and a.configuration == b.configuration


@dataclass(eq=False)
class _Reader:
    queue: asyncio.Queue[object] = field(default_factory=asyncio.Queue)
    bytes: int = 0


class Feed:
    """One serial's upstream, history, readers and sinks (active recordings)."""

    def __init__(
        self, daemon: Daemon, serial: str, on_idle: Callable[[Feed], None]
    ) -> None:
        self.serial = serial
        self.history = History()
        self.error = ""
        self._daemon = daemon
        self._readers: set[_Reader] = set()
        self._sinks: set[Sink] = set()
        self._on_idle = on_idle
        self._task: asyncio.Task[None] | None = None
        self._linger: asyncio.TimerHandle | None = None

    @property
    def in_use(self) -> bool:
        return bool(self._readers or self._sinks)

    def _ensure_running(self) -> None:
        if self._linger is not None:
            self._linger.cancel()
            self._linger = None
        if self._task is None or self._task.done():
            self._task = asyncio.get_running_loop().create_task(self._run())

    def _release(self) -> None:
        if not self.in_use and self._linger is None:
            self._linger = asyncio.get_running_loop().call_later(LINGER_S, self._idle)

    def _idle(self) -> None:
        self._linger = None
        if not self.in_use:
            self._on_idle(self)

    async def _run(self, retry_s: float = 1.0) -> None:
        delay = retry_s
        while self.in_use:
            try:
                async for response in self._daemon.watch_video(self.serial):
                    self.error = ""
                    delay = retry_s
                    self._receive(response)
                self.error = "the video stream ended"
            except ConnectError as error:
                self.error = error.message
                if error.code in (
                    Code.INVALID_ARGUMENT,
                    Code.FAILED_PRECONDITION,
                    Code.UNIMPLEMENTED,
                ):
                    # Not something a retry fixes (no scrcpy server, unknown serial): tell the
                    # pages and wait for one to ask again.
                    self._end_readers(error)
                    for sink in list(self._sinks):
                        sink.end(error.message)
                    return
            await asyncio.sleep(delay)
            delay = min(10.0, delay * 2)

    def _receive(self, response: watch.WatchVideoResponse) -> None:
        if response.HasField("header"):
            self.history.set_header(response.header)
            self._fan_out(response, 0)
        elif response.HasField("frame"):
            header = self.history.append(response.frame)
            if header is None:
                return
            for sink in list(self._sinks):
                sink.add(header, response.frame)
            self._fan_out(response, len(response.frame.data))

    def _fan_out(self, response: watch.WatchVideoResponse, size: int) -> None:
        for reader in list(self._readers):
            if (
                reader.queue.qsize() >= READER_UPDATES
                or reader.bytes + size > READER_BYTES
            ):
                self._readers.discard(reader)
                reader.queue.put_nowait(
                    ConnectError(Code.RESOURCE_EXHAUSTED, "video reader fell behind")
                )
                continue
            reader.bytes += size
            reader.queue.put_nowait(response)

    def _end_readers(self, error: Exception | None) -> None:
        for reader in self._readers:
            reader.queue.put_nowait(error if error is not None else _END)
        self._readers.clear()

    async def read(self) -> AsyncGenerator[watch.WatchVideoResponse, None]:
        """Each retained segment (its header, then its frames), then live updates, until the
        reader is dropped."""
        reader = _Reader()
        backlog: list[watch.WatchVideoResponse] = []
        for segment in self.history.segments:
            backlog.append(watch.WatchVideoResponse(header=segment.header))
            backlog.extend(watch.WatchVideoResponse(frame=f) for f in segment.frames)
        if self.history.pending is not None:
            backlog.append(watch.WatchVideoResponse(header=self.history.pending))
        self._readers.add(reader)
        self._ensure_running()
        try:
            for response in backlog:
                yield response
            while True:
                item = await reader.queue.get()
                if item is _END:
                    return
                if isinstance(item, Exception):
                    raise item
                assert isinstance(item, watch.WatchVideoResponse)
                if item.HasField("frame"):
                    reader.bytes -= len(item.frame.data)
                yield item
        finally:
            self._readers.discard(reader)
            self._release()

    def attach(self, sink: Sink) -> None:
        self._sinks.add(sink)
        self._ensure_running()

    def detach(self, sink: Sink) -> None:
        self._sinks.discard(sink)
        self._release()

    async def close(self) -> None:
        self._end_readers(None)
        if self._linger is not None:
            self._linger.cancel()
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task


class Sink:
    """Receives a feed's frames, each with its stream's header, until [end]; recordings
    implement it."""

    def add(self, header: watch.VideoHeader, frame: watch.VideoFrame) -> None:
        raise NotImplementedError

    def end(self, reason: str) -> None:
        raise NotImplementedError


class VideoHub:
    def __init__(self, daemon: Daemon) -> None:
        self._daemon = daemon
        self.feeds: dict[str, Feed] = {}

    def feed(self, serial: str) -> Feed:
        if serial not in self.feeds:
            self.feeds[serial] = Feed(self._daemon, serial, self._forget)
        return self.feeds[serial]

    def _forget(self, feed: Feed) -> None:
        if self.feeds.get(feed.serial) is feed:
            del self.feeds[feed.serial]
            asyncio.get_running_loop().create_task(feed.close())

    async def close(self) -> None:
        for feed in list(self.feeds.values()):
            await feed.close()
        self.feeds.clear()
