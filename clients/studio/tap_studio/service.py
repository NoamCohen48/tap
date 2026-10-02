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
    CommandError,
    DeviceBusyError,
    DeviceInfo,
    DisplayRotation,
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
from .recording import FORMAT, RecordingError, dumps, loads, secret_name
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
        self._replaying = False
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
        if not request.serial:
            raise ConnectError(Code.INVALID_ARGUMENT, "serial is required")
        async with self._attaching:
            await self._release()
            connection = await self._live_connection()
            worker = DeviceWorker(request.serial, **self._worker_options)
            try:
                device = await worker.call(lambda: connection.attach_device(request.serial))
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
            count = await worker.call(lambda: device.screen.element(selector).count(), changes_screen=False)
        except TapError as error:
            raise _connect_error(error) from None
        return studio.CountResponse(count=count)

    async def describe_element(
        self, request: studio.DescribeElementRequest, ctx: RequestContext
    ) -> studio.DescribeElementResponse:
        worker = self._require_device()
        if not request.selector.HasField("node"):
            raise ConnectError(Code.INVALID_ARGUMENT, "selector has no node")
        device = worker.device
        assert device is not None
        selector = Selector.from_proto(request.selector)
        try:
            snapshot = await worker.call(lambda: device.screen.element(selector).snapshot(), changes_screen=False)
        except CommandError as error:
            # NOT_FOUND / AMBIGUOUS: the selector does not name one node now.
            raise ConnectError(Code.FAILED_PRECONDITION, str(error)) from None
        except TapError as error:
            raise _connect_error(error) from None
        response = studio.DescribeElementResponse(
            actions=[tap.StandardAction.Value(f"A11Y_{action.name}") for action in snapshot.actions],
            custom_actions=list(snapshot.custom_actions),
        )
        if snapshot.range is not None:
            r = snapshot.range
            kind = tap.RANGE_TYPE_UNSPECIFIED if r.type.name == "UNKNOWN" else tap.RangeType.Value(f"RANGE_{r.type.name}")
            response.range.CopyFrom(tap.Range(type=kind, min=r.min, max=r.max, current=r.current))
        return response

    async def get_device_status(
        self, request: studio.GetDeviceStatusRequest, ctx: RequestContext
    ) -> studio.GetDeviceStatusResponse:
        worker = self._require_device()
        device = worker.device
        assert device is not None
        try:
            info, top = await worker.call(lambda: (device.info(), device.foreground_activity()), changes_screen=False)
        except TapError as error:
            raise _connect_error(error) from None
        response = studio.GetDeviceStatusResponse(info=_device_info(info))
        if top is not None:
            response.foreground_package = top.package_name
            response.foreground_activity = top.class_name
        return response

    async def list_notifications(
        self, request: studio.ListNotificationsRequest, ctx: RequestContext
    ) -> studio.ListNotificationsResponse:
        worker = self._require_device()
        device = worker.device
        assert device is not None
        try:
            shown = await worker.call(device.notifications, changes_screen=False)
        except TapError as error:
            raise _connect_error(error) from None
        return studio.ListNotificationsResponse(
            notifications=[
                tap.DeviceNotification(
                    package_name=n.package_name,
                    title=n.title,
                    text=n.text,
                    actions=list(n.actions),
                    clearable=n.clearable,
                    posted_at_ms=round(n.posted_at.timestamp() * 1000),
                )
                for n in shown
            ]
        )

    # --- steps -----------------------------------------------------------------------------------

    async def perform(self, request: studio.PerformRequest, ctx: RequestContext) -> studio.PerformResponse:
        worker = self._require_device()
        device = worker.device
        assert device is not None and self._attached is not None
        self._require_idle()
        secret_value = request.secret_value if request.HasField("secret_value") else None
        try:
            step = steps.prepare(request.step, secret_value)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        if request.before_step_id:
            self._index(request.before_step_id)
        started = time.monotonic()
        failure: TapError | steps.CheckFailed | None = None
        try:
            await worker.call(lambda: steps.run(device, step, secret_value))
        except (TapError, steps.CheckFailed) as error:
            failure = error
        step.outcome.CopyFrom(steps.outcome(round((time.monotonic() - started) * 1000), failure, device.serial))
        recorded = self._recording_on and failure is None and not request.skip_recording
        if recorded:
            # Edits wait for the replay, not for a running Perform: the step it goes before may be gone.
            before = request.before_step_id if request.before_step_id and self._has_step(request.before_step_id) else ""
            self._add(step, secret_value, before)
        return studio.PerformResponse(step=step, recorded=recorded, message=str(failure) if failure else "")

    def _add(self, step: studio.Step, secret_value: str | None, before_step_id: str) -> None:
        attached = self._attached
        assert attached is not None
        if self.recording is None or not self.recording.steps:
            self.recording = _new_recording(attached)
        step.id = self._new_id()
        name = secret_name(step)
        if name is not None:
            assert secret_value is not None
            self._secrets[name] = secret_value
        if before_step_id:
            self.recording.steps.insert(self._index(before_step_id), step)
        else:
            self.recording.steps.append(step)
        self._sync_secrets()

    def _new_id(self) -> str:
        taken = {step.id for step in self.recording.steps} if self.recording is not None else set()
        while f"s{self._next_id}" in taken:
            self._next_id += 1
        self._next_id += 1
        return f"s{self._next_id - 1}"

    def _has_step(self, step_id: str) -> bool:
        return self.recording is not None and any(step.id == step_id for step in self.recording.steps)

    def _index(self, step_id: str) -> int:
        """The position of a recorded step; NOT_FOUND for an unknown id."""
        if self.recording is not None:
            for index, step in enumerate(self.recording.steps):
                if step.id == step_id:
                    return index
        raise ConnectError(Code.NOT_FOUND, f"no step {step_id!r} in the recording")

    def _require_idle(self) -> None:
        if self._replaying:
            raise ConnectError(Code.FAILED_PRECONDITION, "a replay is running: stop it first")

    def _sync_secrets(self) -> None:
        """``Recording.secrets``: every name the steps use, in step order; values of names no
        step uses any more are forgotten."""
        assert self.recording is not None
        names = list(dict.fromkeys(name for step in self.recording.steps if (name := secret_name(step)) is not None))
        del self.recording.secrets[:]
        self.recording.secrets.extend(names)
        for name in set(self._secrets) - set(names):
            del self._secrets[name]

    def _missing_secrets(self) -> list[str]:
        return [name for name in self.recording.secrets if name not in self._secrets] if self.recording is not None else []

    # --- editing ---------------------------------------------------------------------------------

    async def update_step(self, request: studio.UpdateStepRequest, ctx: RequestContext) -> studio.UpdateStepResponse:
        self._require_idle()
        index = self._index(request.step.id)
        assert self.recording is not None
        secret_value = request.secret_value if request.HasField("secret_value") else None
        try:
            step = steps.revise(request.step, secret_value)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        current = self.recording.steps[index]
        step.ClearField("outcome")
        if current.HasField("outcome") and _same_run(current, step):
            step.outcome.CopyFrom(current.outcome)
        current.CopyFrom(step)
        name = secret_name(step)
        if name is not None and secret_value is not None:
            self._secrets[name] = secret_value
        self._sync_secrets()
        return studio.UpdateStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def delete_step(self, request: studio.DeleteStepRequest, ctx: RequestContext) -> studio.DeleteStepResponse:
        self._require_idle()
        index = self._index(request.step_id)
        assert self.recording is not None
        del self.recording.steps[index]
        self._sync_secrets()
        return studio.DeleteStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def move_step(self, request: studio.MoveStepRequest, ctx: RequestContext) -> studio.MoveStepResponse:
        self._require_idle()
        index = self._index(request.step_id)
        assert self.recording is not None
        target = self._index(request.before_step_id) if request.before_step_id else len(self.recording.steps)
        _move(self.recording.steps, index, target)
        self._sync_secrets()
        return studio.MoveStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def open_recording(self, request: studio.OpenRecordingRequest, ctx: RequestContext) -> studio.OpenRecordingResponse:
        self._require_idle()
        try:
            recording = loads(request.document)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        self.recording = recording
        self._secrets.clear()
        self._next_id = 1
        return studio.OpenRecordingResponse(session=self._session(), recording=recording, missing_secrets=self._missing_secrets())

    # --- replay ----------------------------------------------------------------------------------

    async def replay(self, request: studio.ReplayRequest, ctx: RequestContext) -> AsyncIterator[studio.ReplayResponse]:
        worker = self._require_device()
        device = worker.device
        assert device is not None and self._attached is not None
        self._require_idle()
        if self.recording is None or not self.recording.steps:
            raise ConnectError(Code.FAILED_PRECONDITION, "nothing to replay")
        start = self._index(request.from_step_id) if request.from_step_id else 0
        chosen = [step.id for step in self.recording.steps[start : start + 1 if request.only else None]]
        for name, value in request.secret_values.items():
            if name in self.recording.secrets:
                self._secrets[name] = value
        needed = {name for step in self.recording.steps if step.id in chosen and (name := secret_name(step)) is not None}
        missing = [name for name in self.recording.secrets if name in needed and name not in self._secrets]
        if missing:
            raise ConnectError(Code.FAILED_PRECONDITION, f"values are needed for the secrets {', '.join(missing)}")
        self._replaying = True
        try:
            for step_id in chosen:
                step = self.recording.steps[self._index(step_id)]
                yield studio.ReplayResponse(step_id=step_id)
                name = secret_name(step)
                value = self._secrets[name] if name is not None else None
                started = time.monotonic()
                failure: TapError | steps.CheckFailed | None = None
                try:
                    await worker.call(lambda: steps.run(device, step, value))
                except (TapError, steps.CheckFailed) as error:
                    failure = error
                step.outcome.CopyFrom(steps.outcome(round((time.monotonic() - started) * 1000), failure, device.serial))
                yield studio.ReplayResponse(step_id=step_id, outcome=step.outcome, message=str(failure) if failure else "")
                if failure is not None:
                    return
        finally:
            self._replaying = False

    async def set_recording(self, request: studio.SetRecordingRequest, ctx: RequestContext) -> studio.SetRecordingResponse:
        self._recording_on = request.recording
        return studio.SetRecordingResponse(session=self._session())

    async def new_recording(self, request: studio.NewRecordingRequest, ctx: RequestContext) -> studio.NewRecordingResponse:
        self._require_idle()
        self.recording = None
        self._secrets.clear()
        return studio.NewRecordingResponse(session=self._session())

    async def get_recording(self, request: studio.GetRecordingRequest, ctx: RequestContext) -> studio.GetRecordingResponse:
        if self.recording is None or not self.recording.steps:
            raise ConnectError(Code.NOT_FOUND, "nothing recorded yet")
        return studio.GetRecordingResponse(
            recording=self.recording, document=dumps(self.recording) + "\n", missing_secrets=self._missing_secrets()
        )


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
    )


def _move(steps, index: int, before: int) -> None:
    """Moves ``steps[index]`` to just before position ``before`` (``len(steps)``: the end)."""
    if before in (index, index + 1):
        return
    moved = studio.Step()
    moved.CopyFrom(steps[index])
    del steps[index]
    steps.insert(before - 1 if before > index else before, moved)


def _same_run(old: studio.Step, new: studio.Step) -> bool:
    """Whether two versions of a step run the same commands (they differ at most in the note)."""
    a, b = studio.Step(), studio.Step()
    a.CopyFrom(old)
    b.CopyFrom(new)
    for step in (a, b):
        step.ClearField("note")
        step.ClearField("outcome")
    return a == b


def _device_info(info: DeviceInfo) -> tap.DeviceInfo:
    """The client's ``DeviceInfo`` as the ``tap.v1`` message the page reads."""
    message = tap.DeviceInfo(
        api_level=info.api_level,
        manufacturer=info.manufacturer,
        model=info.model,
        product=info.product,
        display_width=info.display_width,
        display_height=info.display_height,
        display_rotation=list(DisplayRotation).index(info.display_rotation),
        screen_on=info.screen_on,
        keyguard_locked=info.keyguard_locked,
        keyguard_secure=info.keyguard_secure,
        keyboard_shown=info.keyboard_shown,
        auto_rotate=info.auto_rotate,
        animations_enabled=info.animations_enabled,
        dark_mode=info.dark_mode,
        font_scale=info.font_scale,
        density_dpi=info.density_dpi,
        airplane_mode=info.airplane_mode,
        wifi_enabled=info.wifi_enabled,
        mobile_data_enabled=info.mobile_data_enabled,
        system_locales=list(info.system_locales),
        stay_awake=info.stay_awake,
        bold_text=info.bold_text,
    )
    for name in ("current_package", "high_contrast_text", "color_inversion"):
        if getattr(info, name) is not None:
            setattr(message, name, getattr(info, name))
    return message


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
