"""Offline fixtures: an in-process fake Tap server over a real gRPC channel (no daemon, no device)."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import hashlib
import queue
import threading
import time
from collections.abc import Callable, Generator
from concurrent import futures

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb
from tap_e2e._gen import (
    app_data_pb2_grpc,
    app_pb2_grpc,
    client_connection_pb2_grpc,
    device_log_pb2_grpc,
    device_pb2_grpc,
)
from tap_e2e._gen.engine_version import API_COMPATIBILITY

TOKEN = "ab" * 32


def fail(context, code, reason, message: str, serial: str = "") -> None:
    """Aborts like the daemon: the status plus a ``tap-failure-bin`` trailer."""
    failure = pb.Failure(reason=reason, serial=serial)
    context.set_trailing_metadata((("tap-failure-bin", failure.SerializeToString()),))
    context.abort(code, message)


class FakeConnections(client_connection_pb2_grpc.ClientConnectionServiceServicer):
    """Connect/Observe/Disconnect/Info. ``observe_mode``: "hello" (``observing``, then
    ``extra_events`` heartbeats, then park until ``drop`` or ``close_with``), "empty" (end at
    once), "silent" (never emit), "closing" (a ``closing`` first event)."""

    def __init__(self) -> None:
        self.connects = 0
        self.disconnects: list[str] = []
        self.observe_mode = "hello"
        self.extra_events = 0
        self.seen_tokens: list[str | bytes | None] = []
        self.disconnect_error: grpc.StatusCode | None = None
        # What Info reports; empty (not major.minor.patch) skips the client's version check.
        self.daemon_version = ""
        self.api_compatibility = API_COMPATIBILITY
        self._drops: dict[str, threading.Event] = {}
        self._closing: dict[str, str] = {}
        # Live connections: id -> ConnectRequest (hold tells held from observed).
        self.live: dict[str, pb.ConnectRequest] = {}
        self.devices: FakeDevices | None = None
        # Event log per connection id, as the daemon records it (Execute and app changes).
        self.logs: dict[str, list[pb.LoggedEvent]] = {}

    def record(self, cid: str, serial: str, **call) -> None:
        log = self.logs.setdefault(cid, [])
        log.append(
            pb.LoggedEvent(
                seq=len(log) + 1,
                at_epoch_ms=1_790_000_000_000 + len(log),
                duration_ms=5,
                serial=serial,
                attached_device_id=f"attached-{serial}",
                **call,
            )
        )

    def Events(self, request, context):
        events = [e for e in self.logs.get(request.client_connection_id, []) if e.seq > request.after_seq]
        return pb.EventsResponse(events=events)

    def _drop_event(self, cid: str) -> threading.Event:
        return self._drops.setdefault(cid, threading.Event())

    def drop(self, cid: str) -> None:
        self._drop_event(cid).set()

    def close_with(self, cid: str, reason: str) -> None:
        """Sends ``closing(reason)`` on ``cid``'s stream, then completes it normally."""
        self._closing[cid] = reason
        self.drop(cid)

    def Connect(self, request, context):
        if request.HasField("hold") and any(
            c.HasField("hold") and c.name == request.name for c in self.live.values()
        ):
            fail(
                context,
                grpc.StatusCode.FAILED_PRECONDITION,
                pb.FAILURE_REASON_DAEMON_PRECONDITION,
                f"a held connection named {request.name} already exists",
            )
        self.connects += 1
        cid = f"conn-{self.connects}"
        self.live[cid] = request
        self.logs.setdefault(cid, [])
        return pb.ConnectResponse(client_connection_id=cid)

    def ListConnections(self, request, context):
        entries = []
        for cid, connect in self.live.items():
            entry = pb.ConnectionEntry(client_connection_id=cid, name=connect.name, idle_ms=1500)
            if connect.HasField("hold"):
                entry.hold.CopyFrom(connect.hold)
            for attach in self.devices.attached.get(cid, []) if self.devices else []:
                entry.attached_devices.add(
                    attached_device_id=f"attached-{attach.serial}",
                    serial=attach.serial,
                    generation=1,
                )
            entries.append(entry)
        return pb.ListConnectionsResponse(connections=entries)

    def Observe(self, request, context):
        cid = request.client_connection_id
        held = self.live.get(cid)
        if held is not None and held.HasField("hold"):
            fail(
                context,
                grpc.StatusCode.FAILED_PRECONDITION,
                pb.FAILURE_REASON_DAEMON_PRECONDITION,
                "a held connection has no Observe stream",
            )
        if self.observe_mode == "empty":
            return
        if self.observe_mode == "closing":
            yield pb.ObserveResponse(closing=pb.Closing(reason="daemon shutting down"))
            return
        drop = self._drop_event(cid)
        if self.observe_mode == "hello":
            yield pb.ObserveResponse(observing=pb.Observing(client_connection_id=cid))
            for _ in range(self.extra_events):
                yield pb.ObserveResponse(heartbeat=pb.Heartbeat())
        while context.is_active() and not drop.wait(0.02):
            pass
        reason = self._closing.pop(cid, None)
        if reason is not None:
            yield pb.ObserveResponse(closing=pb.Closing(reason=reason))

    def Disconnect(self, request, context):
        self.disconnects.append(request.client_connection_id)
        self.live.pop(request.client_connection_id, None)
        self.drop(request.client_connection_id)
        if self.disconnect_error is not None:
            context.abort(self.disconnect_error, "disconnect boom")
        return pb.DisconnectResponse()

    def Info(self, request, context):
        return pb.InfoResponse(daemon_version=self.daemon_version, api_compatibility=self.api_compatibility)


# A DeviceEvent's `kind` case and the type a subscription asks for.
_EVENT_TYPES = {
    "toast_shown": pb.DEVICE_EVENT_TOAST,
    "window_changed": pb.DEVICE_EVENT_WINDOW_CHANGED,
    "content_changed": pb.DEVICE_EVENT_CONTENT_CHANGED,
    "view_clicked": pb.DEVICE_EVENT_VIEW_CLICKED,
    "view_scrolled": pb.DEVICE_EVENT_VIEW_SCROLLED,
    "text_changed": pb.DEVICE_EVENT_TEXT_CHANGED,
}


class FakeDevices(device_pb2_grpc.DeviceServiceServicer):
    """Attach/Execute/Detach/Screenshot/DriverLog. ``responder(command) -> CommandResult | None``
    scripts Execute; ``owners`` logs (rpc, client_connection_id) for every call on a device."""

    def __init__(self) -> None:
        self.responder: Callable[[pb.Command], pb.CommandResult | None] | None = None
        # Execute is refused like the daemon refuses it: (status code, FailureReason, message).
        self.execute_failure: tuple[grpc.StatusCode, int, str] | None = None
        self.commands: list[pb.Command] = []
        self.steps: list[str | None] = []
        self.detaches: list[str] = []
        self.detach_error: grpc.StatusCode | None = None
        # Serials whose no-wait attach fails as held by another session; (serial, lease ms) log.
        self.busy: set[str] = set()
        self.attaches: list[tuple[str, int]] = []
        self.attach_requests: list[pb.AttachRequest] = []
        self.owners: list[tuple[str, str]] = []
        self.log: FakeConnections | None = None
        self.deny: bool = False
        self.png = b"\x89PNG fake"
        self.device_logs = pb.DeviceLogsResponse(
            logcat="I ActivityManager: Start proc\n", activities="ACTIVITIES", windows="WINDOWS"
        )
        # What ListDevices reports.
        self.listed: list[pb.DeviceEntry] = []
        # Serials Unquarantine was called with, and quarantined serials with their reason.
        self.unquarantined: list[str] = []
        self.quarantined: dict[str, str] = {}
        # The server's death record; ListAppDeaths filters it like the daemon.
        self.deaths: list[pb.AppDeath] = []
        self.death_requests: list[pb.ListAppDeathsRequest] = []
        self.acknowledged: list[int] = []
        self.conditions: list = []
        self.foreground: tuple[str, str] | None = None
        # Device files by path; the headers and chunk sizes the uploads carried.
        self.files: dict[str, bytes] = {}
        self.file_headers: list = []
        self.file_chunks: list[int] = []
        self.capabilities = pb.GetDeviceCapabilitiesResponse()
        self.capability_requests: list[pb.GetDeviceCapabilitiesRequest] = []
        self.corrupt_png = False
        # Accepted attaches per client connection id.
        self.attached: dict[str, list[pb.AttachRequest]] = {}
        self.snapshot = pb.ScreenSnapshotResponse()
        self.snapshot_requests: list[pb.ScreenSnapshotRequest] = []
        # WatchDeviceEvents: what the open streams receive, their requests and how many are open.
        self.event_feeds: list[queue.Queue] = []
        self.event_lock = threading.Lock()
        self.event_requests: list[pb.WatchDeviceEventsRequest] = []
        self.event_cancelled = threading.Event()
        # ref -> selector; any other ref is UNKNOWN_REF.
        self.refs: dict[str, pb.Selector] = {}

    def _own(self, rpc: str, request, context) -> None:
        self.owners.append((rpc, request.client_connection_id))
        if self.deny:
            fail(
                context,
                grpc.StatusCode.PERMISSION_DENIED,
                pb.FAILURE_REASON_NOT_OWNER,
                "not your device",
            )

    def WatchDeviceEvents(self, request, context):
        """``ready``, then whatever ``feed_events`` sends: a list of DeviceEvents is one batch
        (filtered by the request's types and package like the server), ``None`` ends the stream
        as a detach does, and a (code, reason, message) tuple fails it."""
        self._own("WatchDeviceEvents", request, context)
        self.event_requests.append(request)
        if not request.types:
            fail(context, grpc.StatusCode.INVALID_ARGUMENT, pb.FAILURE_REASON_INVALID_ARGUMENT, "types must name at least one event type")
        feed: queue.Queue = queue.Queue()
        with self.event_lock:
            self.event_feeds.append(feed)
        context.add_callback(self.event_cancelled.set)
        try:
            yield pb.WatchDeviceEventsResponse(ready=True)
            while context.is_active():
                try:
                    item = feed.get(timeout=0.05)
                except queue.Empty:
                    continue
                if item is None:
                    return
                if isinstance(item, tuple):
                    fail(context, *item)
                wanted = [
                    e for e in item
                    if _EVENT_TYPES[e.WhichOneof("kind")] in request.types and (not request.HasField("package_name") or e.package_name == request.package_name)
                ]
                if wanted:
                    yield pb.WatchDeviceEventsResponse(batch=pb.DeviceEventBatch(events=wanted))
        finally:
            with self.event_lock:
                self.event_feeds.remove(feed)

    def feed_events(self, item) -> None:
        """Sends ``item`` (see WatchDeviceEvents) to every open event stream."""
        with self.event_lock:
            for feed in self.event_feeds:
                feed.put(item)

    @property
    def event_streams(self) -> int:
        with self.event_lock:
            return len(self.event_feeds)

    def GetDeviceCapabilities(self, request, context):
        self._own("GetDeviceCapabilities", request, context)
        self.capability_requests.append(request)
        return self.capabilities

    def Attach(self, request, context):
        self.attach_requests.append(request)
        self.attaches.append((request.serial, request.lease_timeout_ms))
        if request.serial in self.busy and not request.HasField("lease_timeout_ms"):
            fail(
                context,
                grpc.StatusCode.FAILED_PRECONDITION,
                pb.FAILURE_REASON_DEVICE_BUSY,
                f"device {request.serial} is held by another session",
                serial=request.serial,
            )
        reason = self.quarantined.get(request.serial)
        if reason is not None and not request.inspect_quarantined:
            fail(
                context,
                grpc.StatusCode.FAILED_PRECONDITION,
                pb.FAILURE_REASON_DEVICE_QUARANTINED,
                f"device {request.serial} is quarantined: {reason}",
                serial=request.serial,
            )
        self.attached.setdefault(request.client_connection_id, []).append(request)
        response = pb.AttachResponse(
            attached_device_id=f"attached-{request.serial}",
            serial=request.serial,
            generation=1,
        )
        if reason is not None:
            response.quarantine_reason = reason
        return response

    def Unquarantine(self, request, context):
        self.unquarantined.append(request.serial)
        if request.serial in self.busy:
            fail(
                context,
                grpc.StatusCode.FAILED_PRECONDITION,
                pb.FAILURE_REASON_DEVICE_BUSY,
                f"device {request.serial} is held by another session",
                serial=request.serial,
            )
        reason = self.quarantined.pop(request.serial, None)
        if reason is None:
            return pb.UnquarantineResponse()
        return pb.UnquarantineResponse(
            was_quarantined=True,
            quarantine_reason=reason,
            cleaned=["stopped the driver"],
            remaining=["device settings a session changed were not restored"],
        )

    def Execute(self, request, context):
        self._own("execute", request, context)
        if self.execute_failure is not None:
            fail(context, *self.execute_failure)
        self.commands.append(request.command)
        self.steps.append(dict(context.invocation_metadata()).get("tap-step"))
        result = (pb.CommandResult(toast_history=pb.ToastHistory(stream_id="fake-toast-stream", oldest_sequence=1))
                  if request.command.HasField("read_toast_history") else pb.CommandResult(done=pb.Done()))
        if self.responder is not None:
            result = self.responder(request.command) or result
        if self.log is not None and request.command.WhichOneof("op") not in ("device_info", "dump_hierarchy"):
            error = {"error": result.error} if result.HasField("error") else {}
            serial = request.attached_device_id.removeprefix("attached-")
            step = {"step": self.steps[-1]} if self.steps[-1] else {}
            self.log.record(request.client_connection_id, serial, command=request.command, **error, **step)
        return pb.ExecuteResponse(result=result)

    def Screenshot(self, request, context):
        self._own("screenshot", request, context)
        sent = self.png + b"!" if self.corrupt_png else self.png
        return pb.ScreenshotResponse(png=sent, sha256=hashlib.sha256(self.png).hexdigest())

    def ScreenSnapshot(self, request, context):
        self._own("screen_snapshot", request, context)
        self.snapshot_requests.append(request)
        return self.snapshot

    def ResolveRef(self, request, context):
        self._own("resolve_ref", request, context)
        selector = self.refs.get(request.ref.removeprefix("@"))
        if selector is None:
            fail(context, grpc.StatusCode.NOT_FOUND, pb.FAILURE_REASON_UNKNOWN_REF, f"unknown ref {request.ref}")
        return pb.ResolveRefResponse(selector=selector, snapshot_id=self.snapshot.snapshot_id)

    def DriverLog(self, request, context):
        self._own("driver_log", request, context)
        return pb.DriverLogResponse(lines=["line"])

    def DeviceLogs(self, request, context):
        self._own("device_logs", request, context)
        return self.device_logs

    def ListAppDeaths(self, request, context):
        self._own("list_app_deaths", request, context)
        self.death_requests.append(request)
        # wait_ms: as the server, wait for any app's death after after_sequence, or until the
        # device is gone (here: deny, which then fails the call).
        until = time.monotonic() + request.wait_ms / 1000
        while (
            request.wait_ms and not self.deny
            and not any(d.sequence > request.after_sequence for d in self.deaths) and time.monotonic() < until
        ):
            time.sleep(0.02)
        self._own("list_app_deaths", request, context)
        listed = [] if request.sequence_only else [
            d for d in self.deaths
            if d.sequence > request.after_sequence and (not request.HasField("package_name") or d.package_name == request.package_name)
        ]
        return pb.ListAppDeathsResponse(deaths=listed, last_sequence=max((d.sequence for d in self.deaths), default=0))

    def AcknowledgeAppDeaths(self, request, context):
        self._own("acknowledge_app_deaths", request, context)
        self.acknowledged += request.sequences
        for i, death in enumerate(self.deaths):
            if death.sequence in request.sequences:
                handled = pb.AppDeath()
                handled.CopyFrom(death)
                handled.handled = True
                self.deaths[i] = handled
        return pb.AcknowledgeAppDeathsResponse()

    def SetAnimations(self, request, context):
        self._own("set_animations", request, context)
        self.conditions.append(request)
        return pb.SetAnimationsResponse()

    def SetDarkMode(self, request, context):
        self._own("set_dark_mode", request, context)
        self.conditions.append(request)
        fail(context, grpc.StatusCode.FAILED_PRECONDITION, pb.FAILURE_REASON_UNSUPPORTED_API, "dark mode needs API 29")

    def SetFontScale(self, request, context):
        self._own("set_font_scale", request, context)
        self.conditions.append(request)
        return pb.SetFontScaleResponse()

    def SetDensity(self, request, context):
        self._own("set_density", request, context)
        self.conditions.append(request)
        if request.HasField("dpi") and request.dpi == 999:
            fail(context, grpc.StatusCode.FAILED_PRECONDITION, pb.FAILURE_REASON_DEVICE_SETTING, "density read back 420")
        return pb.SetDensityResponse()

    def SetNetwork(self, request, context):
        self._own("set_network", request, context)
        self.conditions.append(request)
        return pb.SetNetworkResponse()

    def SetSystemLocales(self, request, context):
        self._own("set_system_locales", request, context)
        self.conditions.append(request)
        return pb.SetSystemLocalesResponse()

    def SetLocation(self, request, context):
        self._own("set_location", request, context)
        self.conditions.append(request)
        return pb.SetLocationResponse()

    def SetStayAwake(self, request, context):
        self._own("set_stay_awake", request, context)
        self.conditions.append(request)
        return pb.SetStayAwakeResponse()

    def SetAccessibilityDisplay(self, request, context):
        self._own("set_accessibility_display", request, context)
        self.conditions.append(request)
        return pb.SetAccessibilityDisplayResponse()

    def GetForegroundActivity(self, request, context):
        self._own("foreground_activity", request, context)
        if self.foreground is None:
            return pb.GetForegroundActivityResponse()
        return pb.GetForegroundActivityResponse(package_name=self.foreground[0], activity=self.foreground[1])

    def _receive(self, requests) -> tuple:
        header, data = None, bytearray()
        for part in requests:
            if part.HasField("header"):
                header = part.header
                self.file_headers.append(header)
            else:
                self.file_chunks.append(len(part.chunk))
                data += part.chunk
        return header, bytes(data)

    def PushFile(self, request_iterator, context):
        header, data = self._receive(request_iterator)
        self._own("push_file", header, context)
        if header.device_path in self.files:
            fail(context, grpc.StatusCode.FAILED_PRECONDITION, pb.FAILURE_REASON_DEVICE_FILE, f"{header.device_path} exists")
        self.files[header.device_path] = data
        return pb.PushFileResponse()

    def PullFile(self, request, context):
        self._own("pull_file", request, context)
        self.file_headers.append(request)
        data = self.files.get(request.device_path)
        if data is None:
            fail(context, grpc.StatusCode.FAILED_PRECONDITION, pb.FAILURE_REASON_DEVICE_FILE, "no such file")
        assert data is not None  # fail aborts the RPC.
        yield pb.PullFileResponse(size_bytes=len(data))
        # Two chunks, so the client stitches them.
        half = len(data) // 2
        yield pb.PullFileResponse(chunk=data[:half])
        yield pb.PullFileResponse(chunk=data[half:])

    def AddMedia(self, request_iterator, context):
        header, data = self._receive(request_iterator)
        self._own("add_media", header, context)
        path = f"/sdcard/Pictures/Tap/{header.file_name}"
        self.files[path] = data
        return pb.AddMediaResponse(device_path=path)

    def Detach(self, request, context):
        self._own("detach", request, context)
        self.detaches.append(request.attached_device_id)
        if self.detach_error is not None:
            context.abort(self.detach_error, "detach boom")
        serial = request.attached_device_id.removeprefix("attached-")
        self.attached[request.client_connection_id] = [
            attached for attached in self.attached.get(request.client_connection_id, [])
            if attached.serial != serial
        ]
        return pb.DetachResponse(clean=True)

    def ListDevices(self, request, context):
        return pb.ListDevicesResponse(devices=self.listed)


class FakeApps(app_pb2_grpc.AppServiceServicer):
    """IsInstalled/ForceStop/Launch/ColdLaunch/RevokePermission/Foreground/OpenLink/Install; ``owners`` logs (rpc,
    client_connection_id). OpenLink reports ``<package>/.Link`` unless ``any_app``."""

    def __init__(self) -> None:
        self.owners: list[tuple[str, str]] = []
        self.install_parts: list[pb.InstallRequest] = []
        self.force_stops: list[pb.ForceStopRequest] = []
        self.foregrounds: list[pb.ForegroundRequest] = []
        self.links: list[pb.OpenLinkRequest] = []
        self.launches: list[pb.LaunchRequest] = []
        self.cold_launches: list[pb.ColdLaunchRequest] = []
        self.revokes: list[pb.RevokePermissionRequest] = []
        self.locales: list[str] = []
        self.log: FakeConnections | None = None

    def _record(self, operation: str, app: pb.AppTarget, context, **call) -> None:
        if self.log is not None:
            serial = app.attached_device_id.removeprefix("attached-")
            step = dict(context.invocation_metadata()).get("tap-step")
            self.log.record(app.client_connection_id, serial, app=pb.AppCall(operation=operation, package_name=app.package_name, **call), **({"step": step} if step else {}))

    def IsInstalled(self, request, context):
        self.owners.append(("is_installed", request.app.client_connection_id))
        return pb.IsInstalledResponse(installed=True)

    def ForceStop(self, request, context):
        self.owners.append(("force_stop", request.app.client_connection_id))
        self.force_stops.append(request)
        self._record("force_stop", request.app, context)
        return pb.ForceStopResponse()

    def Launch(self, request, context):
        self.owners.append(("launch", request.app.client_connection_id))
        self.launches.append(request)
        self._record("launch", request.app, context, **({"activity": request.activity} if request.HasField("activity") else {}))
        return pb.LaunchResponse()

    def ColdLaunch(self, request, context):
        self.owners.append(("cold_launch", request.app.client_connection_id))
        self.cold_launches.append(request)
        self._record("cold_launch", request.app, context)
        return pb.ColdLaunchResponse(process=pb.ProcessIdentity(pid=4242, start_token="t"))

    def RevokePermission(self, request, context):
        self.owners.append(("revoke_permission", request.app.client_connection_id))
        self.revokes.append(request)
        self._record("revoke_permission", request.app, context, permission=request.permission)
        return pb.RevokePermissionResponse()

    def IsPermissionGranted(self, request, context):
        self.owners.append(("is_permission_granted", request.app.client_connection_id))
        return pb.IsPermissionGrantedResponse(granted=request.permission == "android.permission.CAMERA")

    def SetLocales(self, request, context):
        self.owners.append(("set_locales", request.app.client_connection_id))
        self.locales = list(request.locales)
        return pb.SetLocalesResponse()

    def GetLocales(self, request, context):
        self.owners.append(("get_locales", request.app.client_connection_id))
        return pb.GetLocalesResponse(locales=self.locales)

    def Foreground(self, request, context):
        self.owners.append(("foreground", request.app.client_connection_id))
        self.foregrounds.append(request)
        self._record("foreground", request.app, context)
        return pb.ForegroundResponse()

    def OpenLink(self, request, context):
        self.owners.append(("open_link", request.app.client_connection_id))
        self.links.append(request)
        self._record("open_link", request.app, context, uri=request.uri, **({"any_app": True} if request.any_app else {}))
        if request.any_app:
            return pb.OpenLinkResponse()
        return pb.OpenLinkResponse(activity=f"{request.app.package_name}/.Link")

    def Install(self, request_iterator, context):
        self.install_parts.extend(request_iterator)
        return pb.InstallResponse()


class FakeAppData(app_data_pb2_grpc.AppDataServiceServicer):
    """Transport fixture, not a second implementation of the host's private-data rules."""

    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.uploads: list[list[pb.AppDataPart]] = []
        self.response_parts: list[pb.AppDataPart] | None = None
        self.requests: list[object] = []

    def GetCapabilities(self, request, context):
        self.requests.append(request)
        return pb.GetCapabilitiesResponse(available=True)

    def ListFiles(self, request, context):
        self.requests.append(request)
        prefix = request.path + "/" if request.path else ""
        return pb.ListFilesResponse(entries=[
            pb.AppDataEntry(name=path[len(prefix):], size_bytes=len(data))
            for path, data in self.files.items()
            if path.startswith(prefix) and "/" not in path[len(prefix):]
        ])

    def _parts(self, app, path):
        data = self.files[path]
        yield pb.AppDataPart(header=pb.AppDataHeader(app=app, path=path, size_bytes=len(data)))
        for start in range(0, len(data), 1 << 20):
            yield pb.AppDataPart(chunk=data[start:start + (1 << 20)])

    def ReadFile(self, request, context):
        self.requests.append(request)
        parts = self.response_parts if self.response_parts is not None else self._parts(request.app, request.path)
        yield from (pb.ReadFileResponse(part=p) for p in parts)

    def ExportDatabase(self, request, context):
        self.requests.append(request)
        if self.response_parts is not None:
            yield from (pb.ExportDatabaseResponse(part=p) for p in self.response_parts)
            return
        for suffix in ("", "-wal", "-journal"):
            path = f"databases/{request.name}{suffix}"
            if path in self.files:
                yield from (pb.ExportDatabaseResponse(part=p) for p in self._parts(request.app, path))

    def WriteFile(self, request_iterator, context):
        self.uploads.append([p.part for p in request_iterator])
        return pb.WriteFileResponse()

    def ImportDatabase(self, request_iterator, context):
        self.uploads.append([p.part for p in request_iterator])
        return pb.ImportDatabaseResponse()

    def DeleteFile(self, request, context):
        self.requests.append(request)
        return pb.DeleteFileResponse()


class FakeDeviceLog(device_log_pb2_grpc.DeviceLogServiceServicer):
    """Serves ``entries`` as they are (the filters are the server's); records every request.
    A follower gets the entries, then whatever ``live`` yields, until it cancels."""

    def __init__(self) -> None:
        self.entries: list[pb.LogEntry] = []
        self.dropped = 0
        self.requests: list[object] = []
        self.live: queue.Queue[pb.LogEntry | None] = queue.Queue()
        self.capture_off = False
        self.cancelled = threading.Event()
        self.recordings: dict[str, pb.LogRecording] = {}
        self.archives: list[pb.RetainedLog] = []
        self.capture_error = ""
        self.clock_id = ""
        self.connections: FakeConnections | None = None

    def _check(self, request, context) -> None:
        self.requests.append(request)
        event_archive = (self.connections is not None and getattr(request, "attached_device_id", "") == request.client_connection_id
            and request.client_connection_id in self.connections.logs)
        if self.capture_off and not event_archive:
            fail(
                context,
                grpc.StatusCode.FAILED_PRECONDITION,
                pb.FAILURE_REASON_DAEMON_PRECONDITION,
                "log capture is off for this attachment",
            )

    def ReadLog(self, request, context):
        self._check(request, context)
        available = self._entries_for(request.attached_device_id, request.client_connection_id)
        if request.WhichOneof("from") == "marker" and not any(
            e.marker == request.marker for e in available if e.WhichOneof("kind") == "marker"
        ):
            fail(
                context,
                grpc.StatusCode.NOT_FOUND,
                pb.FAILURE_REASON_UNKNOWN_LOG_MARKER,
                f"no marker {request.marker}",
            )
        # Only `after_cursor` is honoured; every other start replays all entries.
        entries = [e for e in available if e.cursor > request.after_cursor] if request.WhichOneof("from") == "after_cursor" else available
        if request.HasField("recording"):
            recording = self.recordings[request.recording]
            r = next(r for r in recording.ranges if r.attached_device_id == request.attached_device_id)
            entries = [e for e in entries if e.cursor > r.after_cursor and (not r.HasField("through_cursor") or e.cursor <= r.through_cursor)]
        yield pb.ReadLogResponse(entries=entries[:1], dropped=self.dropped, capture_error=self.capture_error, clock_id=self.clock_id)
        yield pb.ReadLogResponse(entries=entries[1:])
        if not request.follow:
            return
        context.add_callback(self.cancelled.set)
        while context.is_active():
            try:
                entry = self.live.get(timeout=0.05)
            except queue.Empty:
                continue
            if entry is None:  # the device was detached: the stream ends
                return
            yield pb.ReadLogResponse(entries=[entry])

    def MarkLog(self, request, context):
        self._check(request, context)
        self.entries.append(pb.LogEntry(cursor=len(self.entries) + 1, marker=request.name))
        return pb.MarkLogResponse(cursor=len(self.entries))

    def RedactLog(self, request, context):
        self._check(request, context)
        return pb.RedactLogResponse()

    def _archives(self):
        result = []
        for archive in self.archives:
            saved = pb.RetainedLog()
            saved.CopyFrom(archive)
            if self.connections is not None and archive.client_connection_id in self.connections.logs:
                saved.closed = archive.client_connection_id not in self.connections.live
            result.append(saved)
        if self.connections is not None:
            known = {a.client_connection_id for a in result if a.events}
            for cid in set(self.connections.live) | set(self.connections.logs):
                if cid not in known:
                    result.append(pb.RetainedLog(client_connection_id=cid, attached_device_id=cid,
                        events=True, closed=cid not in self.connections.live, last_cursor=len(self.connections.logs.get(cid, []))))
        return result

    @staticmethod
    def _redact_proto(message, secrets, sensitive=False):
        result = type(message)()
        result.CopyFrom(message)
        if message.DESCRIPTOR.name == "Command":
            op = message.WhichOneof("op")
            sensitive = sensitive or (op in ("set_text", "type_text") and getattr(message, op).sensitive)
        sensitive = sensitive or "Clipboard" in message.DESCRIPTOR.name
        for field, value in message.ListFields():
            if message.DESCRIPTOR.name in ("Error", "Failure") and field.name in ("message", "last_state"):
                result.ClearField(field.name)
            elif field.type == field.TYPE_STRING:
                def redact(text):
                    if sensitive:
                        return "<redacted>" if text else text
                    for secret in secrets:
                        if len(secret) >= 4:
                            text = text.replace(secret, "<redacted>")
                    return text
                if field.is_repeated:
                    getattr(result, field.name)[:] = [redact(text) for text in value]
                else:
                    setattr(result, field.name, redact(value))
            elif field.type == field.TYPE_MESSAGE:
                if field.is_repeated:
                    for i, child in enumerate(value):
                        getattr(result, field.name)[i].CopyFrom(FakeDeviceLog._redact_proto(child, secrets, sensitive))
                else:
                    getattr(result, field.name).CopyFrom(FakeDeviceLog._redact_proto(value, secrets, sensitive))
        return result

    def _entries_for(self, archive_id, owner):
        if self.connections is None or archive_id != owner or owner not in (set(self.connections.live) | set(self.connections.logs)):
            return self.entries
        events = self.connections.logs.get(owner, [])
        secrets = []
        for event in events:
            if event.HasField("command"):
                op = event.command.WhichOneof("op")
                if op in ("set_text", "type_text") and getattr(event.command, op).sensitive:
                    secrets.append(getattr(event.command, op).text)
        result = []
        for event in events:
            saved = pb.LoggedEvent()
            saved.CopyFrom(event)
            for name in ("command", "app", "device", "error", "failure"):
                if saved.HasField(name):
                    getattr(saved, name).CopyFrom(self._redact_proto(getattr(saved, name), secrets))
            result.append(pb.LogEntry(cursor=event.seq, host_time_ms=event.at_epoch_ms, event=saved))
        return result

    def StartLogRecording(self, request, context):
        self._check(request, context)
        recording = pb.LogRecording(
            name=request.name,
            client_connection_id=request.client_connection_id,
            started_at_ms=1,
            agent_steps_expected=request.agent_steps_expected,
            ranges=[pb.LogRecordingRange(attached_device_id=a.attached_device_id, serial=a.serial, events=a.events,
                after_cursor=max((e.cursor for e in self._entries_for(a.attached_device_id, request.client_connection_id)), default=0)) for a in self._archives() if a.client_connection_id == request.client_connection_id],
        )
        self.recordings[request.name] = recording
        return pb.StartLogRecordingResponse(recording=recording)

    def StopLogRecording(self, request, context):
        recording = self.recordings[request.name]
        if not recording.HasField("stopped_at_ms"):
            recording.stopped_at_ms = 2
            recording.complete = not bool(self.capture_error)
            recording.capture_error = self.capture_error
            for r in recording.ranges:
                r.through_cursor = max((e.cursor for e in self._entries_for(r.attached_device_id, recording.client_connection_id)), default=0)
        return pb.StopLogRecordingResponse(recording=recording)

    def ListLogRecordings(self, request, context):
        return pb.ListLogRecordingsResponse(recordings=self.recordings.values())

    def DeleteLogRecording(self, request, context):
        del self.recordings[request.name]
        return pb.DeleteLogRecordingResponse()

    def ListRetainedLogs(self, request, context):
        return pb.ListRetainedLogsResponse(logs=[a for a in self._archives() if not request.client_connection_id or a.client_connection_id == request.client_connection_id])


class _TokenCheck(grpc.ServerInterceptor):
    """Rejects calls without ``authorization: Bearer TOKEN`` when ``required`` is set."""

    def __init__(self, fake: FakeConnections) -> None:
        self.fake = fake
        self.required = True

    def intercept_service(self, continuation, handler_call_details):
        metadata = dict(handler_call_details.invocation_metadata or ())
        header = metadata.get("authorization")
        self.fake.seen_tokens.append(header)
        if self.required and header != f"Bearer {TOKEN}":
            def deny(request, context):
                fail(
                    context,
                    grpc.StatusCode.UNAUTHENTICATED,
                    pb.FAILURE_REASON_UNAUTHENTICATED,
                    "missing or wrong daemon token",
                )

            handler = continuation(handler_call_details)
            if handler is not None and handler.unary_stream:
                return grpc.unary_stream_rpc_method_handler(deny)
            if handler is not None and handler.stream_unary:
                return grpc.stream_unary_rpc_method_handler(deny)
            return grpc.unary_unary_rpc_method_handler(deny)
        return continuation(handler_call_details)


class Fake:
    def __init__(self) -> None:
        self.connections = FakeConnections()
        self.devices = FakeDevices()
        self.apps = FakeApps()
        self.app_data = FakeAppData()
        self.device_log = FakeDeviceLog()
        self.device_log.connections = self.connections
        self.connections.devices = self.devices
        self.devices.log = self.connections
        self.apps.log = self.connections
        self.auth = _TokenCheck(self.connections)
        self.server = grpc.server(
            futures.ThreadPoolExecutor(max_workers=16), interceptors=[self.auth]
        )
        client_connection_pb2_grpc.add_ClientConnectionServiceServicer_to_server(
            self.connections, self.server
        )
        device_pb2_grpc.add_DeviceServiceServicer_to_server(self.devices, self.server)
        app_pb2_grpc.add_AppServiceServicer_to_server(self.apps, self.server)
        app_data_pb2_grpc.add_AppDataServiceServicer_to_server(self.app_data, self.server)
        device_log_pb2_grpc.add_DeviceLogServiceServicer_to_server(self.device_log, self.server)
        self.port = self.server.add_insecure_port("127.0.0.1:0")
        self.address = f"127.0.0.1:{self.port}"
        self.server.start()


@pytest.fixture
def fake(monkeypatch: pytest.MonkeyPatch, tmp_path) -> Generator[Fake, None, None]:
    for name in ("TAP_SERVER", "TAP_TOKEN"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("TAP_STATE_DIR", str(tmp_path))
    server = Fake()
    yield server
    server.server.stop(grace=None)
