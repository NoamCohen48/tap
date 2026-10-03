"""The page's Connect service (`tap.watcher.v1.WatcherService`).

Every method reads from the back end's own state (activity history, video feeds, library) or
makes a read-only daemon call; none attaches, executes, observes or renews a connection.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import AsyncGenerator

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from tap_e2e.client import Endpoint
from tap_e2e.proto import device_pb2 as device
from tap_e2e.proto import watch_pb2 as watch

from ._gen import watcher_pb2 as pb
from .activity import ActivityHub, ReaderOverflow
from .daemon import Daemon
from .recorder import Recorders
from .recordings import MAX_LIBRARY_BYTES, Library
from .video import HISTORY_BYTES, HISTORY_SECONDS, VideoHub

# How often entries past `--keep-days` are deleted while the watcher runs.
SWEEP_S = 60 * 60
_log = logging.getLogger(__name__)


class Watcher:
    def __init__(self, library: Library, endpoint: Endpoint | None = None) -> None:
        self.daemon = Daemon(endpoint)
        self.activity = ActivityHub(self.daemon)
        self.video = VideoHub(self.daemon)
        self.recorders = Recorders(library, self.activity, self.video)
        self._following: asyncio.Task[None] | None = None
        self._sweeping: asyncio.Task[None] | None = None

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        self._following = loop.create_task(self.activity.run())
        self._sweeping = loop.create_task(self._sweep())

    async def _sweep(self) -> None:
        library = self.recorders.library
        while True:
            try:
                expired = await asyncio.to_thread(library.sweep)
                if expired:
                    _log.info(
                        "deleted %d recordings older than %d days",
                        len(expired),
                        library.keep_days,
                    )
            except OSError as error:
                _log.warning("cleaning the recording library failed: %s", error)
            if library.keep_days <= 0:
                return
            await asyncio.sleep(SWEEP_S)

    async def close(self) -> None:
        """Saves running recordings, ends the streams and closes the channel. The daemon sees
        only its read streams end."""
        await self.recorders.close()
        self.activity.close()
        await self.video.close()
        for task in (self._following, self._sweeping):
            if task is not None:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
        await self.daemon.close()

    async def status(
        self, request: pb.StatusRequest, ctx: RequestContext
    ) -> pb.StatusResponse:
        return pb.StatusResponse(
            daemon_connected=self.activity.connected,
            daemon_error="" if self.activity.connected else self.activity.error,
            recordings=self.recorders.statuses(),
            history=pb.VideoHistory(
                max_seconds=HISTORY_SECONDS, max_bytes=HISTORY_BYTES
            ),
        )

    async def list_devices(
        self, request: device.ListDevicesRequest, ctx: RequestContext
    ) -> device.ListDevicesResponse:
        return await self.daemon.list_devices()

    async def watch(
        self, request: watch.WatchRequest, ctx: RequestContext
    ) -> AsyncGenerator[watch.WatchResponse, None]:
        if request.after_seq < 0:
            raise ConnectError(Code.INVALID_ARGUMENT, "after_seq must be >= 0")
        subscription = self.activity.subscribe(request.after_seq)
        try:
            yield watch.WatchResponse(
                connections=subscription.connections,
                activities=subscription.backlog,
                dropped=subscription.dropped,
            )
            while (batch := await self.activity.next_batch(subscription)) is not None:
                yield watch.WatchResponse(activities=batch)
        except ReaderOverflow as error:
            raise ConnectError(
                Code.RESOURCE_EXHAUSTED,
                "activity reader fell behind; resume after the last seq",
            ) from error
        finally:
            self.activity.unsubscribe(subscription)

    async def watch_video(
        self, request: watch.WatchVideoRequest, ctx: RequestContext
    ) -> AsyncGenerator[watch.WatchVideoResponse, None]:
        if not request.serial:
            raise ConnectError(Code.INVALID_ARGUMENT, "serial is required")
        async for response in self.video.feed(request.serial).read():
            yield response

    async def start_recording(
        self, request: pb.StartRecordingRequest, ctx: RequestContext
    ) -> pb.StartRecordingResponse:
        if not request.serial:
            raise ConnectError(Code.INVALID_ARGUMENT, "serial is required")
        return pb.StartRecordingResponse(
            recording=self.recorders.start(request.serial).status()
        )

    async def stop_recording(
        self, request: pb.StopRecordingRequest, ctx: RequestContext
    ) -> pb.StopRecordingResponse:
        return pb.StopRecordingResponse(
            recording=await self.recorders.stop(request.serial)
        )

    async def save_clip(
        self, request: pb.SaveClipRequest, ctx: RequestContext
    ) -> pb.SaveClipResponse:
        return pb.SaveClipResponse(recording=await self.recorders.save_clip(request))

    async def list_recordings(
        self, request: pb.ListRecordingsRequest, ctx: RequestContext
    ) -> pb.ListRecordingsResponse:
        library = self.recorders.library
        return pb.ListRecordingsResponse(
            recordings=await asyncio.to_thread(library.list),
            directory=str(library.directory),
            used_bytes=await asyncio.to_thread(library.used_bytes),
            max_bytes=MAX_LIBRARY_BYTES,
            keep_days=library.keep_days,
        )

    async def delete_recordings(
        self, request: pb.DeleteRecordingsRequest, ctx: RequestContext
    ) -> pb.DeleteRecordingsResponse:
        await asyncio.to_thread(self.recorders.library.delete, list(request.ids))
        return pb.DeleteRecordingsResponse()
