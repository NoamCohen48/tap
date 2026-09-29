"""``tap.studio.v1.StudioService``: the studio's state and the calls the page makes.

The studio is one ``tap-e2e`` client of the daemon: one observed connection (so the daemon
detaches the device if the studio dies), at most one attached device, and one recording. Calls
on the device go through its ``DeviceWorker``; the rest (listing, connecting) run in a thread so
the server never blocks.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator, Callable

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from google.protobuf.timestamp_pb2 import Timestamp
from tap_e2e import (
    DeviceBusyError,
    DeviceQuarantinedError,
    Selector,
    ServerError,
    TapClient,
    TapConnection,
    TapError,
)
from tap_e2e import proto as tap

from . import __version__, steps
from ._gen import studio_pb2 as studio
from .recording import FORMAT, RecordingError, secret_name
from .screen import DeviceWorker, FrameError

CONNECTION_NAME = "tap-studio"


class Studio:
    """``StudioService``. ``client_factory`` opens the daemon client (tests pass a fake's)."""

    def __init__(self, client_factory: Callable[[], TapClient] = TapClient.create, **worker_options) -> None:
        self._client_factory = client_factory
        self._worker_options = worker_options
        self._client: TapClient | None = None
        self._connection: TapConnection | None = None
        self._worker: DeviceWorker | None = None
        self._attached: studio.AttachedDevice | None = None
        self._attaching = asyncio.Lock()
        self.recording: studio.Recording | None = None
        self._recording_on = True
        self._next_id = 1
        # Secret values by name, for this process only: never written anywhere.
        self._secrets: dict[str, str] = {}

    # --- session ---------------------------------------------------------------------------------

    async def info(self, request: studio.InfoRequest, ctx: RequestContext) -> studio.InfoResponse:
        return studio.InfoResponse(recorder=f"tap-studio {__version__}", format=FORMAT)

    async def get_session(self, request: studio.GetSessionRequest, ctx: RequestContext) -> studio.GetSessionResponse:
        return studio.GetSessionResponse(session=self._session())

    async def list_devices(self, request: studio.ListDevicesRequest, ctx: RequestContext) -> studio.ListDevicesResponse:
        client = await self._daemon()
        entries = await _blocking(client.devices)
        attached = self._attached.serial if self._attached is not None else None
        return studio.ListDevicesResponse(
            devices=[
                studio.DeviceChoice(
                    serial=entry.serial,
                    state=_device_state(entry.state.name),
                    attached=entry.serial == attached,
                    quarantine_reason=entry.quarantine_reason or "",
                )
                for entry in entries
            ]
        )

    async def attach(self, request: studio.AttachRequest, ctx: RequestContext) -> studio.AttachResponse:
        if not request.serial or not request.aut_package:
            raise ConnectError(Code.INVALID_ARGUMENT, "serial and aut_package are required")
        async with self._attaching:
            await self._release()
            connection = await self._live_connection()
            worker = DeviceWorker(request.serial, **self._worker_options)
            try:
                device = await worker.call(lambda: connection.attach_device(request.serial, request.aut_package))
            except TapError as error:
                await worker.close()
                raise _connect_error(error) from None
            worker.device = device
            try:
                info = await worker.call(device.info)
            except TapError as error:
                await worker.close(device.detach)
                raise _connect_error(error) from None
            self._worker = worker
            self._attached = studio.AttachedDevice(
                serial=device.serial,
                aut_package=request.aut_package,
                api_level=info.api_level,
                manufacturer=info.manufacturer,
                model=info.model,
                display_width=info.display_width,
                display_height=info.display_height,
            )
        return studio.AttachResponse(session=self._session())

    async def release(self, request: studio.ReleaseRequest, ctx: RequestContext) -> studio.ReleaseResponse:
        async with self._attaching:
            await self._release()
        return studio.ReleaseResponse(session=self._session())

    async def close(self) -> None:
        """Releases the device and closes the daemon connection (server shutdown)."""
        async with self._attaching:
            await self._release()
            connection, client = self._connection, self._client
            self._connection = self._client = None
        if connection is not None:
            await _blocking(connection.close)
        if client is not None:
            await _blocking(client.close)

    async def _release(self) -> None:
        worker, self._worker, self._attached = self._worker, None, None
        if worker is not None:
            device = worker.device
            try:
                await worker.close(device.detach if device is not None else None)
            except TapError:
                pass  # the daemon detaches it anyway when the connection goes

    async def _daemon(self) -> TapClient:
        if self._client is None:
            try:
                self._client = await _blocking(self._client_factory)
            except TapError as error:
                raise ConnectError(Code.UNAVAILABLE, f"no Tap server: start one with `tap start` ({error})") from None
        return self._client

    async def _live_connection(self) -> TapConnection:
        client = await self._daemon()
        if self._connection is None or not self._connection.usable:
            try:
                self._connection = await _blocking(lambda: client.connect(CONNECTION_NAME))
            except TapError as error:
                raise _connect_error(error) from None
        return self._connection

    def _session(self) -> studio.Session:
        session = studio.Session(recording=self._recording_on)
        if self._attached is not None:
            session.device.CopyFrom(self._attached)
        if self.recording is not None:
            session.steps = len(self.recording.steps)
            session.recording_package = self.recording.aut_package
        return session

    def _require_device(self) -> DeviceWorker:
        if self._worker is None or self._worker.device is None:
            raise ConnectError(Code.FAILED_PRECONDITION, "no device attached")
        return self._worker

    # --- screen ----------------------------------------------------------------------------------

    async def frames(self, request: studio.FramesRequest, ctx: RequestContext) -> AsyncIterator[studio.FramesResponse]:
        worker = self._require_device()
        try:
            async for frame in worker.frames():
                yield frame
        except FrameError as error:
            raise ConnectError(Code.UNAVAILABLE, str(error)) from None

    async def count(self, request: studio.CountRequest, ctx: RequestContext) -> studio.CountResponse:
        worker = self._require_device()
        if not request.selector.HasField("node"):
            raise ConnectError(Code.INVALID_ARGUMENT, "selector has no node")
        device = worker.device
        assert device is not None
        selector = Selector.from_proto(request.selector)
        try:
            count = await worker.call(lambda: device.element(selector).count(), changes_screen=False)
        except TapError as error:
            raise _connect_error(error) from None
        return studio.CountResponse(count=count)

    # --- steps -----------------------------------------------------------------------------------

    async def perform(self, request: studio.PerformRequest, ctx: RequestContext) -> studio.PerformResponse:
        worker = self._require_device()
        device = worker.device
        assert device is not None and self._attached is not None
        secret_value = request.secret_value if request.HasField("secret_value") else None
        try:
            step = steps.prepare(request.step, secret_value)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        package = self._attached.aut_package
        if self._recording_on and self.recording is not None and self.recording.steps and self.recording.aut_package != package:
            raise ConnectError(
                Code.FAILED_PRECONDITION,
                f"the recording is for {self.recording.aut_package}, the device is attached for {package}: "
                "pause recording or start a new one",
            )
        started = time.monotonic()
        failure: TapError | None = None
        try:
            await worker.call(lambda: steps.run(device, step, secret_value))
        except TapError as error:
            failure = error
        step.outcome.CopyFrom(steps.outcome(round((time.monotonic() - started) * 1000), failure, device.serial))
        recorded = self._recording_on and failure is None
        if recorded:
            self._append(step, secret_value)
        return studio.PerformResponse(step=step, recorded=recorded, message=str(failure) if failure else "")

    def _append(self, step: studio.Step, secret_value: str | None) -> None:
        attached = self._attached
        assert attached is not None
        if self.recording is None or not self.recording.steps:
            self.recording = _new_recording(attached)
        step.id = f"s{self._next_id}"
        self._next_id += 1
        name = secret_name(step)
        if name is not None:
            assert secret_value is not None
            self._secrets[name] = secret_value
            if name not in self.recording.secrets:
                self.recording.secrets.append(name)
        self.recording.steps.append(step)

    async def set_recording(self, request: studio.SetRecordingRequest, ctx: RequestContext) -> studio.SetRecordingResponse:
        self._recording_on = request.recording
        return studio.SetRecordingResponse(session=self._session())

    async def new_recording(self, request: studio.NewRecordingRequest, ctx: RequestContext) -> studio.NewRecordingResponse:
        self.recording = None
        self._secrets.clear()
        return studio.NewRecordingResponse(session=self._session())

    async def get_recording(self, request: studio.GetRecordingRequest, ctx: RequestContext) -> studio.GetRecordingResponse:
        if self.recording is None or not self.recording.steps:
            raise ConnectError(Code.NOT_FOUND, "nothing recorded yet")
        return studio.GetRecordingResponse(recording=self.recording)


def _new_recording(attached: studio.AttachedDevice) -> studio.Recording:
    recorded_at = Timestamp()
    recorded_at.GetCurrentTime()
    recorded_at.nanos = 0
    return studio.Recording(
        format=FORMAT,
        recorded_at=recorded_at,
        recorder=f"tap-studio {__version__}",
        device=studio.RecordedDevice(
            serial=attached.serial,
            api_level=attached.api_level,
            manufacturer=attached.manufacturer,
            model=attached.model,
        ),
        aut_package=attached.aut_package,
    )


def _device_state(name: str) -> int:
    try:
        return tap.DeviceState.Value(f"DEVICE_{name}")
    except ValueError:
        return tap.DEVICE_STATE_UNSPECIFIED


async def _blocking(function: Callable[[], object]):
    return await asyncio.to_thread(function)


def _connect_error(error: TapError) -> ConnectError:
    if isinstance(error, (DeviceBusyError, DeviceQuarantinedError)):
        return ConnectError(Code.FAILED_PRECONDITION, str(error))
    if isinstance(error, ServerError):
        code = getattr(Code, error.code, None)
        return ConnectError(code if isinstance(code, Code) else Code.UNKNOWN, error.details)
    return ConnectError(Code.UNAVAILABLE, str(error))
