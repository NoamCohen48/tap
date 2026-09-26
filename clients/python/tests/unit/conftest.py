"""Offline fixtures: an in-process fake Tap server over a real gRPC channel (no daemon, no device)."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import hashlib
import threading
from collections.abc import Callable, Generator
from concurrent import futures

import grpc
import pytest  # type: ignore[import-not-found]

from tap import _gen as pb
from tap._gen import app_pb2_grpc, client_connection_pb2_grpc, device_pb2_grpc

TOKEN = "ab" * 32


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

    def _drop_event(self, cid: str) -> threading.Event:
        return self._drops.setdefault(cid, threading.Event())

    def drop(self, cid: str) -> None:
        self._drop_event(cid).set()

    def close_with(self, cid: str, reason: str) -> None:
        """Sends ``closing(reason)`` on ``cid``'s stream, then completes it normally."""
        self._closing[cid] = reason
        self.drop(cid)

    def Connect(self, request, context):
        self.connects += 1
        return pb.ConnectResponse(client_connection_id=f"conn-{self.connects}")

    def Observe(self, request, context):
        if self.observe_mode == "empty":
            return
        cid = request.client_connection_id
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
        self.deny: bool = False
        self.png = b"\x89PNG fake"
        self.corrupt_png = False

    def _own(self, rpc: str, request, context) -> None:
        self.owners.append((rpc, request.client_connection_id))
        if self.deny:
            context.abort(grpc.StatusCode.PERMISSION_DENIED, "not your device")

    def Attach(self, request, context):
        self.attach_requests.append(request)
        self.attaches.append((request.serial, request.lease_timeout_ms))
        if request.serial in self.busy and not request.HasField("lease_timeout_ms"):
            context.abort(
                grpc.StatusCode.UNAVAILABLE,
                f"device {request.serial} is in use by another session",
            )
        return pb.AttachResponse(
            attached_device_id=f"attached-{request.serial}",
            serial=request.serial,
            generation=1,
        )

    def Execute(self, request, context):
        self._own("execute", request, context)
        self.commands.append(request.command)
        if self.responder is not None:
            result = self.responder(request.command)
            if result is not None:
                return pb.ExecuteResponse(result=result)
        return pb.ExecuteResponse(result=pb.CommandResult(done=pb.Done()))

    def Screenshot(self, request, context):
        self._own("screenshot", request, context)
        sent = self.png + b"!" if self.corrupt_png else self.png
        return pb.ScreenshotResponse(png=sent, sha256=hashlib.sha256(self.png).hexdigest())

    def DriverLog(self, request, context):
        self._own("driver_log", request, context)
        return pb.DriverLogResponse(lines=["line"])

    def Detach(self, request, context):
        self._own("detach", request, context)
        self.detaches.append(request.attached_device_id)
        if self.detach_error is not None:
            context.abort(self.detach_error, "detach boom")
        return pb.DetachResponse(clean=True)

    def ListDevices(self, request, context):
        return pb.ListDevicesResponse()


class FakeApps(app_pb2_grpc.AppServiceServicer):
    """IsInstalled/ForceStop/Install; ``owners`` logs (rpc, client_connection_id)."""

    def __init__(self) -> None:
        self.owners: list[tuple[str, str]] = []
        self.install_parts: list[pb.InstallRequest] = []
        self.force_stops: list[pb.ForceStopRequest] = []

    def IsInstalled(self, request, context):
        self.owners.append(("is_installed", request.app.client_connection_id))
        return pb.IsInstalledResponse(installed=True)

    def ForceStop(self, request, context):
        self.owners.append(("force_stop", request.app.client_connection_id))
        self.force_stops.append(request)
        return pb.ForceStopResponse()

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
                context.abort(grpc.StatusCode.UNAUTHENTICATED, "missing or invalid bearer token")

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
