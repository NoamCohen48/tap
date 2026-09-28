"""Talking to the Tap host server: discovery, the ``TapClient`` channel and this process's
``TapConnection``.

Discovery order: ``TAP_SERVER=host:port`` → ``<state dir>/daemon.json`` written by a running
daemon (state dir = ``TAP_STATE_DIR`` or ``~/.tap``). Every RPC carries the daemon's bearer
token (``authorization: Bearer <token>``): read from ``daemon.json``, or from ``TAP_TOKEN`` when
the address is explicit. The client never starts the daemon: ``tap start`` (or
:func:`start_daemon`) does, and like the ADB server it stays up until ``tap stop`` /
:func:`stop_daemon`.
"""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

import collections
import contextlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import TYPE_CHECKING, NamedTuple

import grpc

from . import _gen as pb
from ._gen import (
    app_pb2_grpc,
    client_connection_pb2_grpc,
    device_pb2_grpc,
)
from .errors import (
    AppLifecycleError,
    DeviceBusyError,
    DeviceQuarantinedError,
    ServerError,
    TapError,
    WaitTimeoutError,
)

if TYPE_CHECKING:
    # typing.Self is 3.11+; the annotation is never evaluated at runtime (PEP 563).
    from typing_extensions import Self

    from .device import Device


def state_dir() -> pathlib.Path:
    """``TAP_STATE_DIR`` or ``~/.tap``: where the server writes ``daemon.json``."""
    return pathlib.Path(os.environ.get("TAP_STATE_DIR") or pathlib.Path.home() / ".tap")


@dataclass(frozen=True)
class Endpoint:
    """Where a server listens and the bearer token its RPCs need (None: send no token)."""

    address: str
    token: str | None = None


def _read_descriptor(directory: pathlib.Path) -> dict | None:
    path = directory / "daemon.json"
    try:
        descriptor = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    if not isinstance(descriptor, dict) or type(descriptor.get("port")) is not int:
        return None
    return descriptor


class _CallDetails(NamedTuple):
    method: str
    timeout: float | None
    metadata: list[tuple[str, str]] | None
    credentials: grpc.CallCredentials | None
    wait_for_ready: bool | None
    compression: grpc.Compression | None


class _BearerToken(
    grpc.UnaryUnaryClientInterceptor,
    grpc.UnaryStreamClientInterceptor,
    grpc.StreamUnaryClientInterceptor,
):
    """Adds ``authorization: Bearer <token>`` to every unary, server-streaming (Observe) and
    client-streaming (Install) call."""

    def __init__(self, token: str):
        self._header = ("authorization", f"Bearer {token}")

    def _details(self, details: grpc.ClientCallDetails) -> _CallDetails:
        metadata = [*(details.metadata or []), self._header]
        return _CallDetails(
            details.method,
            details.timeout,
            metadata,
            details.credentials,
            getattr(details, "wait_for_ready", None),
            getattr(details, "compression", None),
        )

    def intercept_unary_unary(self, continuation, client_call_details, request):
        return continuation(self._details(client_call_details), request)

    def intercept_unary_stream(self, continuation, client_call_details, request):
        return continuation(self._details(client_call_details), request)

    def intercept_stream_unary(self, continuation, client_call_details, request_iterator):
        return continuation(self._details(client_call_details), request_iterator)


def _channel(endpoint: Endpoint, options: list | None = None) -> grpc.Channel:
    channel = grpc.insecure_channel(endpoint.address, options=options or [])
    if endpoint.token:
        channel = grpc.intercept_channel(channel, _BearerToken(endpoint.token))
    return channel


def _alive(endpoint: Endpoint, timeout: float = 2.0) -> bool:
    channel = _channel(endpoint)
    try:
        client_connection_pb2_grpc.ClientConnectionServiceStub(channel).Info(
            pb.InfoRequest(), timeout=timeout
        )
        return True
    except grpc.RpcError:
        return False
    finally:
        channel.close()


def find_binary() -> str | None:
    """The ``tap`` executable: ``TAP_BIN`` or ``tap`` on PATH."""
    return os.environ.get("TAP_BIN") or shutil.which("tap")


def running_endpoint(directory: pathlib.Path | None = None) -> Endpoint | None:
    """Address and token of the server ``daemon.json`` points at, if it answers ``Info``."""
    descriptor = _read_descriptor(directory or state_dir())
    if not descriptor:
        return None
    token = descriptor.get("token")
    endpoint = Endpoint(
        f"127.0.0.1:{descriptor['port']}", token if isinstance(token, str) and token else None
    )
    return endpoint if _alive(endpoint) else None


def running_server(directory: pathlib.Path | None = None) -> str | None:
    """Address of the server ``daemon.json`` points at, if it answers ``Info``."""
    endpoint = running_endpoint(directory)
    return endpoint.address if endpoint else None


def resolve_endpoint(address: str | None = None, token: str | None = None) -> Endpoint:
    """The server to use and its token. An explicit ``address`` (or ``TAP_SERVER``) takes its
    token from ``token`` or ``TAP_TOKEN``; otherwise both come from a live ``daemon.json``.
    Never starts a server."""
    explicit = address or os.environ.get("TAP_SERVER")
    if explicit:
        return Endpoint(explicit, token or os.environ.get("TAP_TOKEN") or None)
    directory = state_dir()
    endpoint = running_endpoint(directory)
    if endpoint is None:
        raise TapError(
            f"no running tap server (no live descriptor in {directory}); run `tap start`"
        )
    return endpoint


def resolve_address() -> str:
    """Address of a server to use: ``TAP_SERVER``, else a live ``daemon.json``; never starts one."""
    return resolve_endpoint().address


@dataclass(frozen=True)
class DaemonStartResult:
    """``address`` of the server; ``started`` is False when it was already running."""

    address: str
    started: bool


def _tap(binary: str | None, args: list[str], timeout: float) -> str:
    executable = binary or find_binary()
    if executable is None:
        raise TapError("no `tap` executable found (set TAP_BIN or add it to PATH)")
    try:
        result = subprocess.run(
            [executable, *args],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise TapError(
            f"`tap {args[0]}` did not finish within {timeout}s: {error.output}"
        ) from error
    if result.returncode != 0:
        raise TapError(
            f"`tap {args[0]}` failed (exit {result.returncode}): {result.stdout.strip()}"
        )
    return result.stdout


def start_daemon(
    binary: str | None = None,
    directory: pathlib.Path | None = None,
    adb: str | None = None,
    timeout: float = 45.0,
) -> DaemonStartResult:
    """``tap start``: starts the daemon in the background unless one is already running in the
    state dir. The executable picks the port, detaches the process and waits for ``Info`` to
    answer; nothing is parsed from the daemon process itself."""
    args = ["start", "--state-dir", str(directory or state_dir())]
    if adb:
        args += ["--adb", adb]
    output = _tap(binary, args, timeout)
    match = re.search(r"^(started|running) (\S+)", output, re.MULTILINE)
    if not match:
        raise TapError(f"unexpected `tap start` output: {output}")
    return DaemonStartResult(match.group(2), started=match.group(1) == "started")


def stop_daemon(
    binary: str | None = None,
    directory: pathlib.Path | None = None,
    timeout: float = 30.0,
) -> None:
    """``tap stop``: stops the daemon recorded in the state dir; a no-op when none is running."""
    _tap(binary, ["stop", "--state-dir", str(directory or state_dir())], timeout)


# The binary trailer carrying a serialized ``tap.v1.Failure`` on every non-OK daemon status.
FAILURE_TRAILER = "tap-failure-bin"


def _failure(error: grpc.RpcError) -> pb.Failure:
    """The ``tap-failure-bin`` trailer of ``error``; an empty ``Failure`` (reason UNSPECIFIED)
    when the status did not come from the daemon (a proxy, a transport failure, a cancel)."""
    trailers = error.trailing_metadata() if hasattr(error, "trailing_metadata") else None
    for key, value in trailers or ():
        if key == FAILURE_TRAILER:
            return pb.Failure.FromString(value)
    return pb.Failure()


def _map_rpc_error(error: grpc.RpcError, serial: str | None = None) -> TapError:
    """Switches on the daemon's failure reason; the status message is carried for humans only."""
    code = error.code()
    details = error.details() or ""
    failure = _failure(error)
    reason = failure.reason
    failed_serial = failure.serial or serial or "?"
    if reason == pb.FAILURE_REASON_UNAUTHENTICATED:
        return ServerError(
            code.name,
            "wrong or missing daemon token (read from daemon.json in the state dir, or "
            f"TAP_TOKEN with an explicit TAP_SERVER): {details}",
            reason,
        )
    if reason == pb.FAILURE_REASON_NOT_OWNER:
        return ServerError(
            code.name,
            f"device {failed_serial} is attached by another client connection; only the "
            f"connection that attached it may use or detach it: {details}",
            reason,
        )
    if reason == pb.FAILURE_REASON_HOST_WAIT_TIMEOUT:
        return WaitTimeoutError(details, failed_serial, failure.waited_ms)
    if reason == pb.FAILURE_REASON_DEVICE_BUSY:
        return DeviceBusyError(details)
    if reason == pb.FAILURE_REASON_DEVICE_QUARANTINED:
        return DeviceQuarantinedError(failed_serial, details)
    if reason == pb.FAILURE_REASON_APP_LIFECYCLE:
        return AppLifecycleError(details)
    return ServerError(code.name, details, reason)


@contextlib.contextmanager
def mapped_errors(serial: str | None = None) -> Iterator[None]:
    """Maps a ``grpc.RpcError`` raised inside the block to a ``TapError``, chained to it."""
    try:
        yield
    except grpc.RpcError as error:
        raise _map_rpc_error(error, serial) from error


# How long ``connect`` waits for the server to acknowledge the liveness stream.
FIRST_EVENT_TIMEOUT = 30.0
# Liveness-stream messages kept for diagnostics (older ones are dropped).
MAX_EVENTS = 200


class TapClient:
    """One gRPC channel to a host server. Cheap to create; share one per process.

    :meth:`create` is the usual entry point: it resolves the endpoint (see
    :func:`resolve_endpoint`) and, when discovering from ``daemon.json``, checks that the
    server answers ``Info``. The constructor takes an already-resolved :class:`Endpoint` and
    does no I/O. Use it as a context manager, or call :meth:`close` when done.
    """

    def __init__(self, endpoint: Endpoint):
        self.endpoint = endpoint
        self.address = endpoint.address
        self.channel = _channel(
            endpoint, options=[("grpc.max_receive_message_length", 64 * 1024 * 1024)]
        )
        self.client_connections = client_connection_pb2_grpc.ClientConnectionServiceStub(
            self.channel
        )
        self.device_stub = device_pb2_grpc.DeviceServiceStub(self.channel)
        self.apps = app_pb2_grpc.AppServiceStub(self.channel)

    @classmethod
    def create(cls, address: str | None = None, token: str | None = None) -> TapClient:
        """A client for ``address`` (token: ``token`` or ``TAP_TOKEN``), or for ``TAP_SERVER``,
        or for the live server ``daemon.json`` records. Raises ``TapError`` telling you to run
        ``tap start`` when none is running. Never starts a server."""
        return cls(resolve_endpoint(address, token))

    def info(self) -> pb.InfoResponse:
        """Daemon version and pid, protocol version, ADB executable, state dir, and whether the
        daemon carries a driver to install (``driver_available``)."""
        with mapped_errors():
            return self.client_connections.Info(pb.InfoRequest(), timeout=10)

    def devices(self) -> list[pb.DeviceEntry]:
        """Every device ADB lists, with its state (``FREE``, ``LEASED``, ``QUARANTINED``,
        ``OFFLINE``, ``UNAUTHORIZED``); only ``FREE`` and ``LEASED`` devices can be attached."""
        with mapped_errors():
            return list(
                self.device_stub.ListDevices(
                    pb.ListDevicesRequest(), timeout=30
                ).devices
            )

    def connect(
        self, name: str, first_event_timeout: float = FIRST_EVENT_TIMEOUT
    ) -> TapConnection:
        """Open a ``TapConnection`` named ``name`` and start its liveness stream.

        The stream is established before this returns (the server's first event,
        ``observing``, is awaited for up to ``first_event_timeout`` seconds, else ``TapError``
        and the new id is disconnected), so every later ``attach_device`` belongs to a live
        connection: if this process dies, the server detaches every device the connection owns.
        A later ``closing`` event (the daemon reaped the connection or is shutting down) makes
        it unusable with the daemon's reason. Use the result as a context manager, or call
        ``close`` when done.
        """
        with mapped_errors():
            client_connection_id = self.client_connections.Connect(
                pb.ConnectRequest(name=name), timeout=10
            ).client_connection_id
        connection = TapConnection(self, client_connection_id)
        connection._observe(first_event_timeout)
        return connection

    def close(self) -> None:
        """Closes the channel. Close every ``TapConnection`` first."""
        self.channel.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc) -> None:
        self.close()


class TapConnection:
    """This process's identity at the server, returned by ``TapClient.connect`` with its
    liveness stream already running on a background thread. If this process dies, the server
    detaches every device owned by the connection. If the stream ends unexpectedly (server
    restart, network loss) or the daemon sends ``closing``, the connection becomes unusable:
    ``attach_device`` and every call on its devices raise ``TapError`` (``broken`` holds the
    cause)."""

    def __init__(self, client: TapClient, client_connection_id: str):
        self.client = client
        self.id = client_connection_id
        self._stream = None
        self._events: collections.deque[str] = collections.deque(maxlen=MAX_EVENTS)
        self._closing = False
        self._closed = False
        self._broken: TapError | None = None
        self.on_event: Callable[[str], None] | None = None

    def _observe(self, first_event_timeout: float) -> None:
        stream = self.client.client_connections.Observe(
            pb.ObserveRequest(client_connection_id=self.id)
        )
        self._stream = stream
        first = threading.Event()

        def pump() -> None:
            ended: TapError | None = None
            try:
                for event in stream:
                    kind = event.WhichOneof("event")
                    if kind == "heartbeat":
                        continue  # idle traffic; not recorded
                    if kind == "closing":
                        described = f"closing: {event.closing.reason}"
                        ended = TapError(
                            f"daemon closed client connection {self.id}: {event.closing.reason}"
                        )
                    else:
                        described = kind or "unknown"
                    self._events.append(described)
                    callback = self.on_event
                    if callback is not None:
                        with contextlib.suppress(Exception):
                            callback(described)
                    if ended is not None:
                        break
                    first.set()
                if ended is None:
                    ended = TapError(f"connection {self.id} liveness stream ended unexpectedly")
            except grpc.RpcError as error:
                ended = TapError(f"connection {self.id} liveness stream failed: {error}")
                ended.__cause__ = error
            if not self._closing:
                self._broken = ended
            first.set()

        threading.Thread(
            target=pump, name=f"tap-connection-{self.id[:8]}", daemon=True
        ).start()
        if first.wait(first_event_timeout) and self._broken is None and self._events:
            return
        failure = self._broken or TapError(
            f"connection {self.id}: no liveness event within {first_event_timeout:g}s"
        )
        # Release the id the server just opened; the setup failure stays primary.
        with contextlib.suppress(Exception):
            self.close()
        raise failure

    @property
    def broken(self) -> TapError | None:
        """Why the liveness stream ended unexpectedly, or None while it is healthy."""
        return self._broken

    @property
    def usable(self) -> bool:
        """True while the connection can attach devices: not closed and its stream is alive."""
        return not self._closed and not self._closing and self._broken is None

    def ensure_usable(self, operation: str) -> None:
        """Raises ``TapError`` when the connection is closed or its liveness stream ended."""
        if self._closed or self._closing:
            raise TapError(f"connection {self.id} is closed; {operation} rejected")
        broken = self._broken
        if broken is not None:
            raise TapError(f"{broken}; {operation} rejected") from broken

    def available_serials(self) -> list[str]:
        """Serials a test can use, from ``TapClient.devices``: online and not quarantined, free
        ones first, then ones another session holds (``attach_device`` then waits for them when
        ``wait_for_device`` is set). Exclusive use is enforced by the session itself, so there is
        nothing to acquire beforehand."""
        devices = [
            d
            for d in self.client.devices()
            if d.state in (pb.DEVICE_FREE, pb.DEVICE_LEASED)
        ]
        devices.sort(key=lambda d: d.state != pb.DEVICE_FREE)
        return [d.serial for d in devices]

    def attach_device(self, serial: str, aut_package: str, **options) -> Device:
        """Attach ``serial`` for ``aut_package``; additional options configure the attachment.
        Raises ``TapError`` without an RPC once the connection is closed or broken."""
        from .device import Device  # circular import at module load

        self.ensure_usable("attach_device")
        return Device._attach_device(self, serial, aut_package, **options)

    def close(self) -> pb.DisconnectResponse | None:
        """Disconnects (the server detaches this connection's devices), then drops the liveness
        stream. Idempotent: later calls return None."""
        if self._closed:
            return None
        self._closing = True
        # Close explicitly before dropping the liveness stream, so the server records a client
        # request rather than a dropped stream.
        try:
            with mapped_errors():
                return self.client.client_connections.Disconnect(
                    pb.DisconnectRequest(client_connection_id=self.id),
                    timeout=60,
                )
        finally:
            self._closed = True
            if self._stream is not None:
                self._stream.cancel()
                self._stream = None

    @property
    def events(self) -> list[str]:
        """The most recent liveness-stream events (at most ``MAX_EVENTS``), oldest first:
        ``observing`` and ``closing: <reason>``; heartbeats are not recorded."""
        return list(self._events)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc) -> None:
        # Never suppresses: a close failure propagates (chained to any in-flight exception).
        self.close()
