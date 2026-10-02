"""Offline fixtures: an in-process fake Tap server over a real gRPC channel (no daemon, no device)."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import hashlib
import threading
from collections.abc import Callable, Generator
from concurrent import futures

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb
from tap_e2e._gen import app_pb2_grpc, client_connection_pb2_grpc, device_pb2_grpc

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
        self.seen_tokens: list[str | None] = []
        self.disconnect_error: grpc.StatusCode | None = None
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
        return pb.InfoResponse()


class FakeDevices(device_pb2_grpc.DeviceServiceServicer):
    """Attach/Execute/Detach/Screenshot/DriverLog. ``responder(command) -> CommandResult | None``
    scripts Execute; ``owners`` logs (rpc, client_connection_id) for every call on a device."""

    def __init__(self) -> None:
        self.responder: Callable[[pb.Command], pb.CommandResult | None] | None = None
        self.commands: list[pb.Command] = []
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
        self.conditions: list = []
        self.foreground: tuple[str, str] | None = None
        # Device files by path; the headers and chunk sizes the uploads carried.
        self.files: dict[str, bytes] = {}
        self.file_headers: list = []
        self.file_chunks: list[int] = []
        self.corrupt_png = False
        # Accepted attaches per client connection id.
        self.attached: dict[str, list[pb.AttachRequest]] = {}
        self.snapshot = pb.ScreenSnapshotResponse()
        self.snapshot_requests: list[pb.ScreenSnapshotRequest] = []
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
        self.attached.setdefault(request.client_connection_id, []).append(request)
        return pb.AttachResponse(
            attached_device_id=f"attached-{request.serial}",
            serial=request.serial,
            generation=1,
        )

    def Execute(self, request, context):
        self._own("execute", request, context)
        self.commands.append(request.command)
        result = pb.CommandResult(done=pb.Done())
        if self.responder is not None:
            result = self.responder(request.command) or result
        if self.log is not None and request.command.WhichOneof("op") not in ("device_info", "dump_hierarchy"):
            error = {"error": result.error} if result.HasField("error") else {}
            serial = request.attached_device_id.removeprefix("attached-")
            self.log.record(request.client_connection_id, serial, command=request.command, **error)
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
        return pb.DetachResponse(clean=True)

    def ListDevices(self, request, context):
        return pb.ListDevicesResponse()


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

    def _record(self, operation: str, app: pb.AppTarget, **call) -> None:
        if self.log is not None:
            serial = app.attached_device_id.removeprefix("attached-")
            self.log.record(app.client_connection_id, serial, app=pb.AppCall(operation=operation, package_name=app.package_name, **call))

    def IsInstalled(self, request, context):
        self.owners.append(("is_installed", request.app.client_connection_id))
        return pb.IsInstalledResponse(installed=True)

    def ForceStop(self, request, context):
        self.owners.append(("force_stop", request.app.client_connection_id))
        self.force_stops.append(request)
        self._record("force_stop", request.app)
        return pb.ForceStopResponse()

    def Launch(self, request, context):
        self.owners.append(("launch", request.app.client_connection_id))
        self.launches.append(request)
        self._record("launch", request.app, **({"activity": request.activity} if request.HasField("activity") else {}))
        return pb.LaunchResponse()

    def ColdLaunch(self, request, context):
        self.owners.append(("cold_launch", request.app.client_connection_id))
        self.cold_launches.append(request)
        self._record("cold_launch", request.app)
        return pb.ColdLaunchResponse(process=pb.ProcessIdentity(pid=4242, start_token="t"))

    def RevokePermission(self, request, context):
        self.owners.append(("revoke_permission", request.app.client_connection_id))
        self.revokes.append(request)
        self._record("revoke_permission", request.app, permission=request.permission)
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
        self._record("foreground", request.app)
        return pb.ForegroundResponse()

    def OpenLink(self, request, context):
        self.owners.append(("open_link", request.app.client_connection_id))
        self.links.append(request)
        self._record("open_link", request.app, uri=request.uri, **({"any_app": True} if request.any_app else {}))
        if request.any_app:
            return pb.OpenLinkResponse()
        return pb.OpenLinkResponse(activity=f"{request.app.package_name}/.Link")

    def Install(self, request_iterator, context):
        self.install_parts.extend(request_iterator)
        return pb.InstallResponse()


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
