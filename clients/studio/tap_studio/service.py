"""``tap.studio.v1.StudioService``: the studio's state and the calls the page makes.

The studio is one ``tap-e2e`` client of the daemon: one observed connection (so the daemon
detaches the device if the studio dies), at most one attached device, and one recording. Calls
on the device go through its ``DeviceWorker``; the rest (listing, connecting) run in a thread so
the server never blocks.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import suppress
from typing import TypeVar, cast

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.request import RequestContext
from google.protobuf.timestamp_pb2 import Timestamp
from tap_e2e import (
    AppDataError,
    Device,
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

from . import __version__, export, library, runs, steps, tools
from ._gen import studio_pb2 as studio
from .recording import FORMAT, RecordingError, dumps, loads, parameter_name, parameter_problems, secret_name
from .screen import DeviceWorker, FrameError

CONNECTION_NAME = "tap-studio"
TAB_HEADER = "x-tap-studio-tab"
UNDO_LIMIT_BYTES = 16 * 1024 * 1024
"""How much undo history is kept; the oldest goes first."""
AUTOSAVE_DELAY_SECONDS = 0.5
"""How long after a change autosave writes, so a burst of edits is one write."""
WATCH_HEARTBEAT_SECONDS = 1.0
"""The server only notices a closed stream when it sends, so a closed tab gives up control this soon,
and a reloaded page is locked out for no longer. One small message a second on loopback costs little."""
T = TypeVar("T")


class Studio:
    """``StudioService``. ``client_factory`` opens the daemon client (tests pass a fake's)."""

    def __init__(self, client_factory: Callable[[], TapClient] = TapClient.create, *, store: library.Library | None = None, **worker_options) -> None:
        self._client_factory = client_factory
        self._store = store or library.Library()
        # The library entry the recording is saved in.
        self._saved_id = ""
        self._saved_name = ""
        self._saved_revision = 0
        self._changes = 0  # of the recording's definition, not its outcomes
        self._saved_changes = 0
        # Bumped whenever the open recording stops being the one the link was made for: a write
        # that finishes after that must not move the link back.
        self._link_generation = 0
        self._autosave = False
        self._autosave_error = ""
        self._autosaving: asyncio.Task | None = None
        self._writing = asyncio.Lock()
        self._worker_options = worker_options
        self._client: TapClient | None = None
        self._connection: TapConnection | None = None
        self._worker: DeviceWorker | None = None
        self._attached: studio.AttachedDevice | None = None
        self._attaching = asyncio.Lock()
        self.recording: studio.Recording | None = None
        self._recording_on = True
        self._next_id = 1
        # Whole recordings, newest last; None is no recording. Definitions only: never secret values.
        self._undo: list[bytes | None] = []
        self._redo: list[bytes | None] = []
        self._replaying = False
        self._tool_running = False
        self._operation: asyncio.Task | None = None
        self._operation_name = ""
        self._replay_step: asyncio.Task | None = None
        self._closing = False
        # Open WatchSession streams by tab, oldest first; the controller is one of them.
        self._watchers: list[str] = []
        self._controller = ""
        self._version = 0
        self._recording_revision = 0
        self._changed = asyncio.Event()
        # Secret values by name, for this process only: never written anywhere.
        self._secrets: dict[str, str] = {}
        self._runs = runs.RunLog()

    # --- session ---------------------------------------------------------------------------------

    async def info(self, request: studio.InfoRequest, ctx: RequestContext) -> studio.InfoResponse:
        return studio.InfoResponse(recorder=f"tap-studio {__version__}", format=FORMAT)

    async def get_session(self, request: studio.GetSessionRequest, ctx: RequestContext) -> studio.GetSessionResponse:
        return studio.GetSessionResponse(session=self._session())

    async def watch_session(
        self, request: studio.WatchSessionRequest, ctx: RequestContext
    ) -> AsyncIterator[studio.WatchSessionResponse]:
        _check_tab(request.tab)
        if self._closing:
            raise ConnectError(Code.FAILED_PRECONDITION, "Studio is shutting down")
        self._watchers.append(request.tab)
        if not self._controller:
            self._controller = request.tab
            self._publish()
        try:
            while not self._closing:
                changed = self._changed  # before the read, so a change during the yield wakes it
                yield studio.WatchSessionResponse(session=self._session())
                with suppress(asyncio.TimeoutError):
                    await asyncio.wait_for(changed.wait(), WATCH_HEARTBEAT_SECONDS)
        finally:
            self._watchers.remove(request.tab)
            if self._controller == request.tab:
                self._controller = self._watchers[-1] if self._watchers else ""
                self._publish()

    async def take_control(self, request: studio.TakeControlRequest, ctx: RequestContext) -> studio.TakeControlResponse:
        _check_tab(request.tab)
        if request.tab not in self._watchers:
            raise ConnectError(Code.FAILED_PRECONDITION, "this tab is not watching the session")
        self._require_idle()
        self._controller = request.tab
        self._publish()
        return studio.TakeControlResponse(session=self._session())

    def _publish(self) -> None:
        """Wakes every WatchSession stream."""
        self._version += 1
        changed, self._changed = self._changed, asyncio.Event()
        changed.set()

    def _touch(self) -> None:
        """The recording changed."""
        self._recording_revision += 1
        self._publish()
        if self._autosave and self._changes != self._saved_changes and self._autosaving is None:
            self._autosaving = asyncio.create_task(self._autosave_soon(), name="tap-studio autosave")

    async def _autosave_soon(self) -> None:
        try:
            await asyncio.sleep(AUTOSAVE_DELAY_SECONDS)
        finally:
            self._autosaving = None
        await self._write_autosave()

    async def _write_autosave(self) -> None:
        """Failure turns autosave off and says so: it must not look on while nothing is saved."""
        if not self._autosave or self._changes == self._saved_changes:
            return
        if self.recording is None or not self.recording.steps:
            return  # an undo back to nothing: the saved entry stays as it was
        try:
            await self._write_library(None, None, autosave=True)
        except library.LibraryError as error:
            self._autosave = False
            self._autosave_error = str(error)
        self._publish()

    async def _write_library(self, name: str | None, entry_id: str | None, *, autosave: bool = False) -> None:
        """One write at a time: autosave and Save must not race each other into a false conflict.
        An autosave reads the entry it writes to once it holds the lock, so it never pairs one
        entry's id with another's revision."""
        async with self._writing:
            if autosave:
                if not self._autosave or not self._saved_id or self._changes == self._saved_changes:
                    return  # unlinked, turned off or saved while it waited
                if self.recording is None or not self.recording.steps:
                    return
                name, entry_id = self._saved_name, self._saved_id
            assert self.recording is not None and name is not None
            recording, changes, generation = studio.Recording(), self._changes, self._link_generation
            recording.CopyFrom(self.recording)
            revision = self._saved_revision if entry_id else None
            entry = await _blocking(lambda: self._store.save(recording, name, entry_id, revision))
            if generation != self._link_generation:
                return  # another recording was opened meanwhile: this one's save is done, unlinked
            self._saved_id, self._saved_name, self._saved_revision = entry.id, entry.name, entry.revision
            self._saved_changes = changes

    def _unlink_library(self) -> None:
        """The recording in progress is no longer what the entry holds: never save over it."""
        self._link_generation += 1
        self._saved_id = self._saved_name = self._autosave_error = ""
        self._saved_revision = 0
        self._autosave = False
        self._saved_changes = self._changes

    def _require_control(self, ctx: RequestContext | None) -> None:
        """While a tab controls Studio, a browser request must come from it. ``ctx`` is None for
        Studio's own calls (the ``--serial`` attach at start)."""
        if ctx is not None and self._controller and ctx.request_headers().get(TAB_HEADER) != self._controller:
            raise ConnectError(Code.FAILED_PRECONDITION, "another tab controls this Studio: take control first")

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
        self._require_control(ctx)
        return await self._operate("attachment", lambda: self._attach(request))

    async def _attach(self, request: studio.AttachRequest) -> studio.AttachResponse:
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
        self._require_control(ctx)
        return await self._operate("release", self._detach)

    async def _detach(self) -> studio.ReleaseResponse:
        async with self._attaching:
            await self._release()
        return studio.ReleaseResponse(session=self._session())

    async def close(self) -> None:
        """Releases the device and closes the daemon connection (server shutdown)."""
        self._closing = True
        self._publish()  # ends the WatchSession streams
        if self._autosaving is not None:
            self._autosaving.cancel()
        # A browser/request disappearing never cancels a command already handed to the worker.
        # Shutdown drains its owner before clearing attachment or recording state.
        for task in (self._operation, self._replay_step):
            if task is not None:
                # Its caller receives the failure; detach still runs. Do not log secret text.
                with suppress(Exception):
                    await asyncio.shield(task)
        await self._write_autosave()  # what the drained operation just recorded
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
        session = studio.Session(
            recording=self._recording_on,
            controller_tab=self._controller,
            operation="replay" if self._replaying else self._operation_name,
            version=self._version,
            recording_revision=self._recording_revision,
            can_undo=bool(self._undo),
            can_redo=bool(self._redo),
            library=studio.LibraryLink(
                id=self._saved_id,
                name=self._saved_name,
                revision=self._saved_revision,
                unsaved=self._changes != self._saved_changes,
                autosave=self._autosave,
                autosave_error=self._autosave_error,
            ),
        )
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
            # The query failed (e.g. NOT_FOUND, AMBIGUOUS or STALE_BEFORE_INPUT).
            # Do not retry implicitly; the next selection reads the node afresh.
            raise ConnectError(Code.FAILED_PRECONDITION, str(error)) from None
        except TapError as error:
            raise _connect_error(error) from None
        response = studio.DescribeElementResponse(
            actions=[f"A11Y_{action.name}" for action in snapshot.actions],
            custom_actions=list(snapshot.custom_actions),
        )
        if snapshot.range is not None:
            r = snapshot.range
            kind = "RANGE_TYPE_UNSPECIFIED" if r.type.name == "UNKNOWN" else f"RANGE_{r.type.name}"
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

    # --- explicit non-recorded tools --------------------------------------------------------------

    async def query_app(self, request: studio.QueryAppRequest, ctx: RequestContext) -> studio.QueryAppResponse:
        """Read app state through the SDK, never implicitly launch or record a query."""
        return await self._tool_call(lambda device: tools.query_app(device, request), ctx)

    async def private_data(self, request: studio.PrivateDataRequest, ctx: RequestContext) -> studio.PrivateDataResponse:
        """Explicit private data access; stop/overwrite/recovery rules remain the SDK's."""
        return await self._tool_call(lambda device: tools.private_data(device, request), ctx)

    async def export_diagnostic(self, request: studio.DiagnosticRequest, ctx: RequestContext) -> studio.DiagnosticResponse:
        """Download bounded diagnostics of Studio's device/connection, never another client's."""
        return await self._tool_call(lambda device: tools.diagnostic(device, request), ctx)

    async def _tool_call(self, function: Callable[[Device], T], ctx: RequestContext | None = None) -> T:
        self._require_control(ctx)
        return await self._operate("tool", lambda: self._run_tool(function))

    async def _run_tool(self, function: Callable[[Device], T]) -> T:
        self._require_idle()
        worker = self._require_device()
        device = worker.device
        assert device is not None
        self._tool_running = True
        try:
            return await worker.call(lambda: function(device), changes_screen=False)
        except tools.ToolInputError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, str(error)) from None
        except tools.ToolLimitError as error:
            raise ConnectError(Code.RESOURCE_EXHAUSTED, str(error)) from None
        except AppDataError as error:
            raise ConnectError(Code.FAILED_PRECONDITION, f"{error.detail}: {error}; do not repeat a mutation blindly") from None
        except TapError as error:
            raise _connect_error(error) from None
        except ValueError as error:
            raise ConnectError(Code.DATA_LOSS, f"SDK data validation failed: {error}; do not repeat a mutation blindly") from None
        finally:
            self._tool_running = False

    # --- steps -----------------------------------------------------------------------------------

    async def perform(self, request: studio.PerformRequest, ctx: RequestContext) -> studio.PerformResponse:
        self._require_control(ctx)
        return await self._operate("action", lambda: self._perform(request))

    async def _perform(self, request: studio.PerformRequest) -> studio.PerformResponse:
        worker = self._require_device()
        device = worker.device
        assert device is not None and self._attached is not None
        self._require_idle()
        secret_value = request.secret_value if request.HasField("secret_value") else None
        try:
            step = steps.prepare(request.step, secret_value, precondition_timeout_ms=request.precondition_timeout_ms if request.HasField("precondition_timeout_ms") else None)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        if request.before_step_id:
            self._index(request.before_step_id)
        runnable = self._resolved(step)
        started = time.monotonic()
        failure: TapError | steps.CheckFailed | None = None
        try:
            await worker.call(lambda: steps.run(device, runnable, secret_value))
        except (TapError, steps.CheckFailed) as error:
            failure = error
        known = [*self._secrets.values(), *([secret_value] if secret_value else [])]
        step.outcome.CopyFrom(steps.outcome(round((time.monotonic() - started) * 1000), failure, device.serial))
        runs.scrub_message(step.outcome, known)
        recorded = self._recording_on and failure is None and not request.skip_recording
        if recorded:
            self._add(step, secret_value, request.before_step_id)
        return studio.PerformResponse(step=step, recorded=recorded, message=runs.scrub(str(failure), known) if failure else "")

    def _snapshot(self) -> bytes | None:
        return self.recording.SerializeToString() if self.recording is not None else None

    def _remember(self) -> None:
        """Call before changing the recording: this state becomes what undo goes back to."""
        self._undo.append(self._snapshot())
        self._redo.clear()
        self._changes += 1
        while sum(len(entry or b"") for entry in self._undo) > UNDO_LIMIT_BYTES and len(self._undo) > 1:
            del self._undo[0]

    def _restore(self, snapshot: bytes | None) -> None:
        current = {step.id: step for step in self.recording.steps} if self.recording is not None else {}
        values_now = {p.name: p.value for p in self.recording.parameters} if self.recording is not None else {}
        restored = None
        if snapshot is not None:
            restored = studio.Recording.FromString(snapshot)
            values_then = {p.name: p.value for p in restored.parameters}
            for step in restored.steps:
                # A result stands only if it is about this step as it is now: an old pass may have
                # used a secret's earlier value, or another value of its parameter.
                now = current.get(step.id)
                step.ClearField("outcome")
                parameter = parameter_name(step)
                same_value = parameter is None or values_now.get(parameter) == values_then.get(parameter)
                if now is not None and now.HasField("outcome") and _same_run(now, step) and same_value:
                    step.outcome.CopyFrom(now.outcome)
        self.recording = restored
        self._changes += 1
        if restored is not None:
            self._sync_secrets()
        else:
            self._secrets.clear()
        self._touch()

    def _step_history(self, source: list[bytes | None], target: list[bytes | None], empty: str) -> None:
        if not source:
            raise ConnectError(Code.FAILED_PRECONDITION, f"nothing to {empty}")
        target.append(self._snapshot())
        self._restore(source.pop())

    def _add(self, step: studio.Step, secret_value: str | None, before_step_id: str) -> None:
        attached = self._attached
        self._remember()
        if self.recording is None or not self.recording.steps:
            assert attached is not None
            # A first step dates the recording; parameters declared before it stay.
            declared = list(self.recording.parameters) if self.recording is not None else []
            self.recording = _new_recording(attached)
            self.recording.parameters.extend(declared)
        step.id = self._new_id()
        name = secret_name(step)
        if name is not None:
            assert secret_value is not None
            if name in self._secrets and self._secrets[name] != secret_value:
                self._invalidate_secret(name)
            self._secrets[name] = secret_value
        if before_step_id:
            self.recording.steps.insert(self._index(before_step_id), step)
        else:
            self.recording.steps.append(step)
        self._sync_secrets()
        self._touch()

    def _resolved(self, step: studio.Step) -> studio.Step:
        """The step with its parameter's value, refused before any input if it has none."""
        declared = {p.name: p.value for p in self.recording.parameters} if self.recording is not None else {}
        try:
            return steps.resolve(step, declared)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None

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

    async def _operate(self, name: str, function: Callable[[], Awaitable[T]]) -> T:
        """The task, not the HTTP request, owns completion and the mutation gate."""
        self._require_idle()

        async def owned():
            try:
                return await function()
            finally:
                self._operation = None
                self._operation_name = ""
                self._publish()

        task = asyncio.create_task(owned(), name=f"tap-studio {name}")
        self._operation, self._operation_name = task, name
        self._publish()
        # Retrieve an orphaned exception without logging possibly sensitive exception text.
        task.add_done_callback(lambda done: None if done.cancelled() else done.exception())
        return await asyncio.shield(task)

    def _require_idle(self) -> None:
        if self._closing:
            raise ConnectError(Code.FAILED_PRECONDITION, "Studio is shutting down")
        if self._replaying:
            raise ConnectError(Code.FAILED_PRECONDITION, "a replay is running: stop it first")
        if self._tool_running:
            raise ConnectError(Code.FAILED_PRECONDITION, "a tool is running: wait for its result")
        if self._operation is not None and self._operation is not asyncio.current_task():
            raise ConnectError(Code.FAILED_PRECONDITION, f"a Studio {self._operation_name} is running: wait for its result")

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

    async def add_step(self, request: studio.AddStepRequest, ctx: RequestContext) -> studio.AddStepResponse:
        self._require_control(ctx)
        self._require_idle()
        if self._attached is None and (self.recording is None or not self.recording.steps):
            raise ConnectError(Code.FAILED_PRECONDITION, "no device attached and no recording to add to")
        secret_value = request.secret_value if request.HasField("secret_value") else None
        try:
            step = steps.prepare(request.step, secret_value)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        if request.before_step_id:
            self._index(request.before_step_id)
        self._resolved(step)
        self._add(step, secret_value, request.before_step_id)
        assert self.recording is not None
        return studio.AddStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def update_step(self, request: studio.UpdateStepRequest, ctx: RequestContext) -> studio.UpdateStepResponse:
        self._require_control(ctx)
        self._require_idle()
        index = self._index(request.step.id)
        assert self.recording is not None
        secret_value = request.secret_value if request.HasField("secret_value") else None
        try:
            step = steps.revise(request.step, secret_value)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        self._resolved(step)
        self._remember()
        current = self.recording.steps[index]
        step.ClearField("outcome")
        name = secret_name(step)
        value_changed = name is not None and secret_value is not None and self._secrets.get(name) != secret_value
        if current.HasField("outcome") and _same_run(current, step) and not value_changed:
            step.outcome.CopyFrom(current.outcome)
        current.CopyFrom(step)
        name = secret_name(step)
        if name is not None and secret_value is not None:
            self._secrets[name] = secret_value
            if value_changed:
                self._invalidate_secret(name)
        self._sync_secrets()
        self._touch()
        return studio.UpdateStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def delete_step(self, request: studio.DeleteStepRequest, ctx: RequestContext) -> studio.DeleteStepResponse:
        self._require_control(ctx)
        self._require_idle()
        index = self._index(request.step_id)
        assert self.recording is not None
        self._remember()
        del self.recording.steps[index]
        self._sync_secrets()
        self._touch()
        return studio.DeleteStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def move_step(self, request: studio.MoveStepRequest, ctx: RequestContext) -> studio.MoveStepResponse:
        self._require_control(ctx)
        self._require_idle()
        index = self._index(request.step_id)
        assert self.recording is not None
        target = self._index(request.before_step_id) if request.before_step_id else len(self.recording.steps)
        self._remember()
        _move(self.recording.steps, index, target)
        self._sync_secrets()
        self._touch()
        return studio.MoveStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def duplicate_step(self, request: studio.DuplicateStepRequest, ctx: RequestContext) -> studio.DuplicateStepResponse:
        self._require_control(ctx)
        self._require_idle()
        index = self._index(request.step_id)
        assert self.recording is not None
        self._remember()
        copy = studio.Step()
        copy.CopyFrom(self.recording.steps[index])
        copy.ClearField("outcome")
        copy.id = self._new_id()
        self.recording.steps.insert(index + 1, copy)
        self._touch()
        return studio.DuplicateStepResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def undo_recording(self, request: studio.UndoRecordingRequest, ctx: RequestContext) -> studio.UndoRecordingResponse:
        self._require_control(ctx)
        self._require_idle()
        self._step_history(self._undo, self._redo, "undo")
        return studio.UndoRecordingResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def redo_recording(self, request: studio.RedoRecordingRequest, ctx: RequestContext) -> studio.RedoRecordingResponse:
        self._require_control(ctx)
        self._require_idle()
        self._step_history(self._redo, self._undo, "redo")
        return studio.RedoRecordingResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def set_parameters(self, request: studio.SetParametersRequest, ctx: RequestContext) -> studio.SetParametersResponse:
        self._require_control(ctx)
        self._require_idle()
        if self.recording is None:
            if self._attached is None:
                raise ConnectError(Code.FAILED_PRECONDITION, "no recording yet: attach a device or open one")
            draft = _new_recording(self._attached)
        else:
            draft = studio.Recording()
            draft.CopyFrom(self.recording)
        before = {p.name: p.value for p in draft.parameters}
        del draft.parameters[:]
        draft.parameters.extend(request.parameters)
        problems = parameter_problems(draft)
        if problems:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(problems))
        self._remember()
        now = {p.name: p.value for p in draft.parameters}
        for step in draft.steps:
            name = parameter_name(step)
            if name is not None and before.get(name) != now[name]:
                step.ClearField("outcome")
        self.recording = draft
        self._touch()
        return studio.SetParametersResponse(recording=self.recording, missing_secrets=self._missing_secrets())

    async def open_recording(self, request: studio.OpenRecordingRequest, ctx: RequestContext) -> studio.OpenRecordingResponse:
        self._require_control(ctx)
        self._require_idle()
        try:
            recording = loads(request.document)
        except RecordingError as error:
            raise ConnectError(Code.INVALID_ARGUMENT, "; ".join(error.problems)) from None
        self._remember()
        self.recording = recording
        self._secrets.clear()
        self._next_id = 1
        self._unlink_library()
        self._touch()
        return studio.OpenRecordingResponse(session=self._session(), recording=recording, missing_secrets=self._missing_secrets())

    # --- library ---------------------------------------------------------------------------------

    async def list_library(self, request: studio.ListLibraryRequest, ctx: RequestContext) -> studio.ListLibraryResponse:
        try:
            entries, unreadable = await _blocking(self._store.list)
        except library.LibraryError as error:
            raise _library_error(error) from None
        return studio.ListLibraryResponse(entries=[_library_entry(e) for e in entries], unreadable=unreadable)

    async def save_to_library(self, request: studio.SaveToLibraryRequest, ctx: RequestContext) -> studio.SaveToLibraryResponse:
        self._require_control(ctx)
        self._require_idle()
        if self.recording is None:
            raise ConnectError(Code.FAILED_PRECONDITION, "nothing to save yet")
        name = request.name if request.HasField("name") else ""
        new = request.as_new or not self._saved_id
        if new and not name:
            raise ConnectError(Code.INVALID_ARGUMENT, "a new library entry needs a name")
        await self._saving(lambda: self._write_library(name or self._saved_name, None if new else self._saved_id))
        self._autosave_error = ""
        self._publish()
        return studio.SaveToLibraryResponse(session=self._session())

    async def _saving(self, write: Callable[[], Awaitable[None]]) -> None:
        async def guarded() -> None:
            try:
                await write()
            except library.LibraryError as error:
                raise _library_error(error) from None

        await self._operate("save", guarded)

    async def open_from_library(self, request: studio.OpenFromLibraryRequest, ctx: RequestContext) -> studio.OpenFromLibraryResponse:
        self._require_control(ctx)

        async def open_entry() -> studio.Recording:
            try:
                entry, recording = await _blocking(lambda: self._store.read(request.id))
            except library.LibraryError as error:
                raise _library_error(error) from None
            # Still inside the gate: nothing else can change the recording between the read and this.
            self.recording = recording
            self._secrets.clear()
            self._next_id = 1
            self._undo.clear()
            self._redo.clear()
            self._unlink_library()
            self._saved_id, self._saved_name, self._saved_revision = entry.id, entry.name, entry.revision
            self._touch()
            return recording

        recording = await self._operate("open", open_entry)
        return studio.OpenFromLibraryResponse(session=self._session(), recording=recording, missing_secrets=self._missing_secrets())

    async def rename_in_library(self, request: studio.RenameInLibraryRequest, ctx: RequestContext) -> studio.RenameInLibraryResponse:
        self._require_control(ctx)
        self._require_idle()
        try:
            entry = await _blocking(lambda: self._store.rename(request.id, request.name, request.revision))
        except library.LibraryError as error:
            raise _library_error(error) from None
        if entry.id == self._saved_id:
            self._saved_name, self._saved_revision = entry.name, entry.revision
        self._publish()
        return studio.RenameInLibraryResponse(session=self._session())

    async def delete_from_library(self, request: studio.DeleteFromLibraryRequest, ctx: RequestContext) -> studio.DeleteFromLibraryResponse:
        self._require_control(ctx)
        self._require_idle()
        try:
            await _blocking(lambda: self._store.delete(request.id, request.revision))
        except library.LibraryError as error:
            raise _library_error(error) from None
        if request.id == self._saved_id:
            self._unlink_library()
            self._saved_changes = self._changes - 1  # saved nowhere now
        self._publish()
        return studio.DeleteFromLibraryResponse(session=self._session())

    async def set_autosave(self, request: studio.SetAutosaveRequest, ctx: RequestContext) -> studio.SetAutosaveResponse:
        self._require_control(ctx)
        if request.enabled and not self._saved_id:
            raise ConnectError(Code.FAILED_PRECONDITION, "save the recording to the library first")
        self._autosave = request.enabled
        self._autosave_error = ""
        self._publish()
        if self._autosave and self._changes != self._saved_changes:
            await self._write_autosave()
        return studio.SetAutosaveResponse(session=self._session())

    def _invalidate_secret(self, name: str) -> None:
        if self.recording is not None:
            for step in self.recording.steps:
                if secret_name(step) == name:
                    step.ClearField("outcome")

    # --- replay ----------------------------------------------------------------------------------

    async def replay(self, request: studio.ReplayRequest, ctx: RequestContext) -> AsyncIterator[studio.ReplayResponse]:
        self._require_control(ctx)
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
                if self._secrets.get(name) != value:
                    self._invalidate_secret(name)
                self._secrets[name] = value
        needed = {name for step in self.recording.steps if step.id in chosen and (name := secret_name(step)) is not None}
        missing = [name for name in self.recording.secrets if name in needed and name not in self._secrets]
        if missing:
            raise ConnectError(Code.FAILED_PRECONDITION, f"values are needed for the secrets {', '.join(missing)}")
        self._replaying = True
        self._publish()
        run = self._runs.begin(device.serial, len(chosen), {p.name: p.value for p in self.recording.parameters})
        run_id = run.result.id
        try:
            for step_id in chosen:
                if self._closing:
                    return
                step = self.recording.steps[self._index(step_id)]
                yield studio.ReplayResponse(step_id=step_id, run_id=run_id)
                if self._closing:
                    return
                name = secret_name(step)
                value = self._secrets[name] if name is not None else None

                runnable = self._resolved(step)
                self._replay_step = asyncio.create_task(self._run_replay_step(worker, device, step, runnable, value, run), name="tap-studio replay step")
                self._replay_step.add_done_callback(lambda done: None if done.cancelled() else done.exception())
                message = await asyncio.shield(self._replay_step)
                yield studio.ReplayResponse(step_id=step_id, outcome=step.outcome, message=message, run_id=run_id)
                if message:
                    return
        finally:
            # A canceled stream stops before the next step, but its accepted step still owns
            # the recording until its outcome is reconciled.
            def settled(_=None):
                run.finish()
                self._replaying = False
                self._replay_step = None
                self._publish()

            if self._replay_step is not None and not self._replay_step.done():
                self._replay_step.add_done_callback(settled)
            else:
                settled()

    async def _run_replay_step(
        self, worker: DeviceWorker, device: Device, step: studio.Step, runnable: studio.Step, value: str | None, run: runs.Run
    ) -> str:
        """Runs an accepted step and keeps its result. Why it failed, or empty when it passed."""
        started = time.monotonic()
        failure: TapError | steps.CheckFailed | None = None
        try:
            await worker.call(lambda: steps.run(device, runnable, value))
        except (TapError, steps.CheckFailed) as error:
            failure = error
        known = list(self._secrets.values())
        step.outcome.CopyFrom(steps.outcome(round((time.monotonic() - started) * 1000), failure, device.serial))
        runs.scrub_message(step.outcome, known)
        message = runs.scrub(str(failure) or type(failure).__name__, known) if failure is not None else ""
        run.add(step.id, step.outcome, message)
        self._touch()
        return message

    async def export_pytest(self, request: studio.ExportPytestRequest, ctx: RequestContext) -> studio.ExportPytestResponse:
        if self.recording is None or not self.recording.steps:
            raise ConnectError(Code.NOT_FOUND, "nothing recorded yet")
        try:
            return studio.ExportPytestResponse(source=export.to_pytest(self.recording, self._saved_name or "recording"))
        except export.ExportError as error:
            raise ConnectError(Code.FAILED_PRECONDITION, str(error)) from None

    async def list_runs(self, request: studio.ListRunsRequest, ctx: RequestContext) -> studio.ListRunsResponse:
        return studio.ListRunsResponse(runs=self._runs.summaries())

    async def get_run(self, request: studio.GetRunRequest, ctx: RequestContext) -> studio.GetRunResponse:
        run = self._runs.get(request.id)
        if run is None:
            raise ConnectError(Code.NOT_FOUND, f"no run {request.id!r}: only the last {runs.MAX_RUNS} are kept")
        return studio.GetRunResponse(run=run, document=runs.dumps(run) + "\n")

    async def set_recording(self, request: studio.SetRecordingRequest, ctx: RequestContext) -> studio.SetRecordingResponse:
        self._require_control(ctx)
        self._require_idle()
        self._recording_on = request.recording
        self._publish()
        return studio.SetRecordingResponse(session=self._session())

    async def new_recording(self, request: studio.NewRecordingRequest, ctx: RequestContext) -> studio.NewRecordingResponse:
        self._require_control(ctx)
        self._require_idle()
        self._remember()
        self.recording = None
        self._secrets.clear()
        self._unlink_library()
        self._touch()
        return studio.NewRecordingResponse(session=self._session())

    async def get_recording(self, request: studio.GetRecordingRequest, ctx: RequestContext) -> studio.GetRecordingResponse:
        # Parameters declared before the first step are a recording too: the page must see them.
        if self.recording is None or not (self.recording.steps or self.recording.parameters):
            raise ConnectError(Code.NOT_FOUND, "nothing recorded yet")
        return studio.GetRecordingResponse(
            recording=self.recording, document=dumps(self.recording) + "\n", missing_secrets=self._missing_secrets()
        )


def _library_entry(entry: library.Entry) -> studio.LibraryEntry:
    updated = Timestamp()
    updated.FromJsonString(entry.updated_at)
    return studio.LibraryEntry(id=entry.id, name=entry.name, revision=entry.revision, updated_at=updated, steps=entry.steps)


def _library_error(error: library.LibraryError) -> ConnectError:
    code = {
        library.EntryNotFound: Code.NOT_FOUND,
        library.RevisionConflict: Code.ABORTED,
        library.NameTaken: Code.ALREADY_EXISTS,
        library.InvalidName: Code.INVALID_ARGUMENT,
        library.LibraryFull: Code.RESOURCE_EXHAUSTED,
    }.get(type(error), Code.FAILED_PRECONDITION)
    return ConnectError(code, str(error))


def _check_tab(tab: str) -> None:
    if not 1 <= len(tab) <= 64:
        raise ConnectError(Code.INVALID_ARGUMENT, "tab is 1 to 64 characters")


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


def _device_state(name: str) -> tap.DeviceState:
    try:
        return cast(tap.DeviceState, tap.DeviceState.Value(f"DEVICE_{name}"))
    except ValueError:
        return tap.DEVICE_STATE_UNSPECIFIED


async def _blocking(function: Callable[[], T]) -> T:
    return await asyncio.to_thread(function)


def _connect_error(error: TapError) -> ConnectError:
    if isinstance(error, (DeviceBusyError, DeviceQuarantinedError)):
        return ConnectError(Code.FAILED_PRECONDITION, str(error))
    if isinstance(error, ServerError):
        code = getattr(Code, error.code, None)
        return ConnectError(code if isinstance(code, Code) else Code.UNKNOWN, error.details)
    return ConnectError(Code.UNAVAILABLE, str(error))
