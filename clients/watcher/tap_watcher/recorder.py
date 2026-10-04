"""Recordings and clips, written by the back end so neither depends on a page staying open."""

from __future__ import annotations

import asyncio
import logging
import time

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from tap_e2e.proto import event_log_pb2 as event_log
from tap_e2e.proto import watch_pb2 as watch

from ._gen import watcher_pb2 as pb
from .activity import ActivityHub
from .media import Parts, steps, write_steps
from .recordings import MAX_LIBRARY_BYTES, Library, LibraryFull
from .video import Feed, Sink, VideoHub

MAX_RECORDING_SECONDS = 30 * 60
MAX_RECORDING_BYTES = 1024 * 1024 * 1024
_log = logging.getLogger(__name__)

Events = list[tuple[str, event_log.LoggedEvent]]


class Recorder(Sink):
    """One serial's recording: from its latest retained key frame until [end]. A new stream
    (rotation, encoder or daemon restart) continues it in a new part."""

    def __init__(
        self, serial: str, feed: Feed, library: Library, activity: ActivityHub
    ) -> None:
        self.serial = serial
        self.started_epoch_ms = int(time.time() * 1000)
        self.ended: str | None = None
        self.done: asyncio.Task[pb.Recording] | None = None
        self._feed = feed
        self._library = library
        self._activity = activity
        self._budget = min(
            MAX_RECORDING_BYTES, MAX_LIBRARY_BYTES - library.used_bytes()
        )
        if self._budget <= 0:
            raise LibraryFull()
        self._staging = library.begin()
        self._parts = Parts(self._staging)
        self._events: dict[tuple[str, int], tuple[str, event_log.LoggedEvent]] = {}
        # Wall time, so a still screen (no frames) or a daemon outage still ends it.
        self._limit = asyncio.get_running_loop().call_later(
            MAX_RECORDING_SECONDS, self.end, "30-minute limit"
        )
        # Start from the latest key frame already retained, so the recording opens on a picture.
        for header, frame in feed.history.latest_gop():
            self.add(header, frame)

    def status(self) -> pb.ActiveRecording:
        return pb.ActiveRecording(
            serial=self.serial,
            started_epoch_ms=self.started_epoch_ms,
            bytes=self._parts.bytes,
            seconds=self._parts.seconds,
        )

    def add(self, header: watch.VideoHeader, frame: watch.VideoFrame) -> None:
        if self.ended is not None:
            return
        if self._parts.empty and not frame.key_frame:
            return
        try:
            self._parts.add(header, frame)
        except (ValueError, OSError) as error:
            self.end(f"writing failed: {error}")
            return
        if self._parts.bytes > self._budget:
            self.end(
                "size limit" if self._budget == MAX_RECORDING_BYTES else "library full"
            )

    def note(self, owner: str, event: event_log.LoggedEvent) -> None:
        """A call on this serial while recording (kept even if the activity history evicts it)."""
        if self.ended is None and event.serial == self.serial:
            self._events[(owner, event.seq)] = (owner, event)

    def end(self, reason: str) -> None:
        if self.ended is not None:
            return
        self.ended = reason
        self._limit.cancel()
        self._feed.detach(self)
        self.done = asyncio.get_running_loop().create_task(self._finish())
        self.done.add_done_callback(_log_failure)

    async def _finish(self) -> pb.Recording:
        parts = self._parts
        if parts.empty:
            self._library.discard(self._staging)
            raise ConnectError(
                Code.FAILED_PRECONDITION, "no video was received while recording"
            )
        events = _overlapping(self._activity, self.serial, parts, self._events)
        record = _record(
            pb.RECORDING_KIND_RECORDING,
            self.serial,
            parts,
            events,
            self._activity,
            self.ended or "stopped",
        )
        record.created_epoch_ms = self.started_epoch_ms

        def save() -> pb.Recording:
            try:
                parts.close()
                write_steps(
                    self._staging / "steps.json",
                    steps(
                        kind="recording",
                        serial=self.serial,
                        parts=parts.parts,
                        events=events,
                        names=self._activity.names,
                        ended=record.ended,
                    ),
                )
                return self._library.commit(self._staging, record)
            except BaseException:
                self._library.discard(self._staging)
                raise

        return await asyncio.to_thread(save)


class Recorders:
    def __init__(
        self, library: Library, activity: ActivityHub, video: VideoHub
    ) -> None:
        self.library = library
        self._activity = activity
        self._video = video
        self.active: dict[str, Recorder] = {}
        activity.listen(self._on_activity)

    def _on_activity(self, activity: watch.Activity) -> None:
        if activity.HasField("event"):
            recorder = self.active.get(activity.event.serial)
            if recorder is not None:
                recorder.note(activity.client_connection_id, activity.event)

    def start(self, serial: str) -> Recorder:
        current = self.active.get(serial)
        if current is not None and current.ended is None:
            raise ConnectError(Code.ALREADY_EXISTS, f"{serial} is already recording")
        feed = self._video.feed(serial)
        try:
            recorder = Recorder(serial, feed, self.library, self._activity)
        except LibraryFull as error:
            raise ConnectError(Code.RESOURCE_EXHAUSTED, str(error)) from error
        self.active[serial] = recorder
        feed.attach(recorder)
        return recorder

    async def stop(self, serial: str) -> pb.Recording:
        recorder = self.active.get(serial)
        if recorder is None:
            raise ConnectError(Code.NOT_FOUND, f"{serial} is not recording")
        recorder.end("stopped")
        try:
            assert recorder.done is not None
            return await recorder.done
        finally:
            if self.active.get(serial) is recorder:
                del self.active[serial]

    def statuses(self) -> list[pb.ActiveRecording]:
        """Recordings still running. One that a limit ended is saved in the background and
        leaves this list."""
        for serial, recorder in list(self.active.items()):
            if recorder.ended is not None:
                del self.active[serial]
        return [recorder.status() for recorder in self.active.values()]

    async def save_clip(self, request: pb.SaveClipRequest) -> pb.Recording:
        feed = self._video.feeds.get(request.serial)
        if feed is None or not feed.history.segments:
            raise ConnectError(
                Code.FAILED_PRECONDITION, f"no video is retained for {request.serial}"
            )
        frames = feed.history.span(
            request.from_stream_id,
            request.from_seq,
            request.to_stream_id,
            request.to_seq,
        )
        try:
            staging = self.library.begin()
        except LibraryFull as error:
            raise ConnectError(Code.RESOURCE_EXHAUSTED, str(error)) from error

        def save() -> pb.Recording:
            try:
                parts = Parts(staging)
                for header, frame in frames:
                    parts.add(header, frame)
                parts.close()
                events = _overlapping(self._activity, request.serial, parts, {})
                record = _record(
                    pb.RECORDING_KIND_CLIP,
                    request.serial,
                    parts,
                    events,
                    self._activity,
                    "",
                )
                write_steps(
                    staging / "steps.json",
                    steps(
                        kind="clip",
                        serial=request.serial,
                        parts=parts.parts,
                        events=events,
                        names=self._activity.names,
                        ended="",
                    ),
                )
                return self.library.commit(staging, record)
            except BaseException:
                self.library.discard(staging)
                raise

        return await asyncio.to_thread(save)

    async def close(self) -> None:
        """Saves every running recording (the watcher is stopping)."""
        for serial, recorder in list(self.active.items()):
            recorder.end("watcher stopped")
            assert recorder.done is not None
            try:
                await recorder.done
            except Exception as error:  # noqa: BLE001 - reported, the others still save
                _log.warning("recording of %s was not saved: %s", serial, error)
        self.active.clear()


def _overlapping(
    activity: ActivityHub,
    serial: str,
    parts: Parts,
    noted: dict[tuple[str, int], tuple[str, event_log.LoggedEvent]],
) -> Events:
    """Calls that overlap the written frames: those noted live plus any still in the history.
    Each part's clock is searched over that part's frames."""
    found = dict(noted)
    clocks = set()
    for part in parts.parts:
        clocks.add(part.header.clock_id)
        for owner, event in activity.events(
            serial,
            part.header.clock_id,
            part.frames[0].received_monotonic_ns,
            part.frames[-1].received_monotonic_ns,
        ):
            found[(owner, event.seq)] = (owner, event)
    events = [
        (owner, event)
        for owner, event in found.values()
        if event.clock_id in clocks and event.HasField("started_monotonic_ns")
    ]
    return sorted(
        events, key=lambda item: (item[1].at_epoch_ms, item[1].started_monotonic_ns)
    )


def _record(
    kind: pb.RecordingKind,
    serial: str,
    parts: Parts,
    events: Events,
    activity: ActivityHub,
    ended: str,
) -> pb.Recording:
    return pb.Recording(
        kind=kind,
        serial=serial,
        created_epoch_ms=int(time.time() * 1000),
        duration_seconds=parts.seconds,
        parts=len(parts.parts),
        connection_names=sorted(
            {activity.names.get(owner, "") for owner, _ in events} - {""}
        ),
        actions=len(events),
        failures=sum(
            1
            for _, event in events
            if event.HasField("error") or event.HasField("failure")
        ),
        ended=ended,
    )


def _log_failure(task: asyncio.Task[pb.Recording]) -> None:
    if not task.cancelled() and task.exception() is not None:
        _log.warning("a recording was not saved: %s", task.exception())
