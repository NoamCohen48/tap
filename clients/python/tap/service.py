"""The Tap host service: discovery, the gRPC channel and this process's ``Connection``.

Discovery order: ``TAP_SERVICE=host:port`` → ``<state dir>/service.json`` written by a running
service (state dir = ``TAP_STATE_DIR`` or ``~/.tap``). The client never starts a service:
``tap start`` (or :func:`start_service`) does, and like the ADB server it stays up until
``tap stop`` / :func:`stop_service`.
"""
from __future__ import annotations

import contextlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import threading
from dataclasses import dataclass
from typing import Callable, Iterator

import grpc

from ._gen import tap_pb2 as pb
from ._gen import tap_pb2_grpc as rpc
from .errors import AppLifecycleError, DeviceBusyError, ServiceError, TapError, WaitTimeoutError


def state_dir() -> pathlib.Path:
    """``TAP_STATE_DIR`` or ``~/.tap``: where the service writes ``service.json``."""
    return pathlib.Path(os.environ.get("TAP_STATE_DIR") or pathlib.Path.home() / ".tap")


def _read_descriptor(directory: pathlib.Path) -> dict | None:
    path = directory / "service.json"
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def _alive(address: str, timeout: float = 2.0) -> bool:
    channel = grpc.insecure_channel(address)
    try:
        rpc.ConnectionServiceStub(channel).Info(pb.InfoRequest(), timeout=timeout)
        return True
    except grpc.RpcError:
        return False
    finally:
        channel.close()


def find_binary() -> str | None:
    """The ``tap`` executable: ``TAP_BIN`` or ``tap`` on PATH."""
    return os.environ.get("TAP_BIN") or shutil.which("tap")


def running_service(directory: pathlib.Path | None = None) -> str | None:
    """Address of the service ``service.json`` points at, if it answers ``Info``."""
    descriptor = _read_descriptor(directory or state_dir())
    if not descriptor:
        return None
    address = f"127.0.0.1:{descriptor['port']}"
    return address if _alive(address) else None


def resolve_address() -> str:
    """Address of a service to use: ``TAP_SERVICE``, else a live ``service.json``; never starts one."""
    explicit = os.environ.get("TAP_SERVICE")
    if explicit:
        return explicit
    directory = state_dir()
    address = running_service(directory)
    if address is None:
        raise TapError(f"no running tap service (no live descriptor in {directory}); run `tap start`")
    return address


@dataclass(frozen=True)
class StartResult:
    """``address`` of the service; ``started`` is False when it was already running."""
    address: str
    started: bool


def _tap(binary: str | None, args: list[str], timeout: float) -> str:
    executable = binary or find_binary()
    if executable is None:
        raise TapError("no `tap` executable found (set TAP_BIN or add it to PATH)")
    try:
        result = subprocess.run(
            [executable, *args], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, timeout=timeout,
        )
    except subprocess.TimeoutExpired as error:
        raise TapError(f"`tap {args[0]}` did not finish within {timeout}s: {error.output}") from None
    if result.returncode != 0:
        raise TapError(f"`tap {args[0]}` failed (exit {result.returncode}): {result.stdout.strip()}")
    return result.stdout


def start_service(
    binary: str | None = None,
    directory: pathlib.Path | None = None,
    adb: str | None = None,
    timeout: float = 45.0,
) -> StartResult:
    """``tap start``: starts a service in the background unless one is already running in the
    state dir. The executable picks the port, detaches the process and waits for ``Info`` to
    answer; nothing is parsed from the service itself."""
    args = ["start", "--state-dir", str(directory or state_dir())]
    if adb:
        args += ["--adb", adb]
    output = _tap(binary, args, timeout)
    match = re.search(r"^(started|running) (\S+)", output, re.MULTILINE)
    if not match:
        raise TapError(f"unexpected `tap start` output: {output}")
    return StartResult(match.group(2), started=match.group(1) == "started")


def stop_service(binary: str | None = None, directory: pathlib.Path | None = None, timeout: float = 30.0) -> None:
    """``tap stop``: stops the service recorded in the state dir; a no-op when none is running."""
    _tap(binary, ["stop", "--state-dir", str(directory or state_dir())], timeout)


# The service's wording for a held per-serial lock (``DeviceBusyException`` in ``:host:core``).
_DEVICE_BUSY_MARKER = "is in use by another session"


def _map_rpc_error(error: grpc.RpcError, serial: str | None = None) -> TapError:
    code = error.code()
    details = error.details() or ""
    if code == grpc.StatusCode.DEADLINE_EXCEEDED and details.startswith("Timed out"):
        return WaitTimeoutError(details, serial or "?", 0)
    if _DEVICE_BUSY_MARKER in details:
        return DeviceBusyError(details)
    if code == grpc.StatusCode.FAILED_PRECONDITION:
        return AppLifecycleError(details)
    return ServiceError(code.name, details)


@contextlib.contextmanager
def mapped_errors(serial: str | None = None) -> Iterator[None]:
    try:
        yield
    except grpc.RpcError as error:
        raise _map_rpc_error(error, serial) from None


class Service:
    """One gRPC channel to a host service. Cheap to create; share one per process."""

    def __init__(self, address: str | None = None):
        self.address = address or resolve_address()
        self.channel = grpc.insecure_channel(
            self.address,
            options=[("grpc.max_receive_message_length", 64 * 1024 * 1024)],
        )
        self.connections = rpc.ConnectionServiceStub(self.channel)
        self.devices_stub = rpc.DeviceServiceStub(self.channel)
        self.sessions = rpc.SessionServiceStub(self.channel)
        self.apps = rpc.AppServiceStub(self.channel)

    def info(self) -> pb.InfoResponse:
        """Service version, protocol version, ADB executable, state dir, bundled driver."""
        with mapped_errors():
            return self.connections.Info(pb.InfoRequest(), timeout=10)

    def devices(self) -> list[pb.DeviceEntry]:
        """Every device ADB lists, with its state (``FREE``, ``LEASED``, ``QUARANTINED``, ``OFFLINE``)."""
        with mapped_errors():
            return list(self.devices_stub.ListDevices(pb.ListDevicesRequest(), timeout=30).devices)

    def connect(self, name: str) -> "Connection":
        """Open a ``Connection`` named ``name``; as a context manager it attaches on enter and
        closes on exit."""
        with mapped_errors():
            connection_id = self.connections.Open(pb.OpenConnectionRequest(name=name), timeout=10).connection_id
        return Connection(self, connection_id)

    def close(self) -> None:
        self.channel.close()


class Connection:
    """This process's identity at the service: every ``Device`` it opens belongs to it and is
    closed with it. ``attach`` starts the liveness stream: if this process dies, the service
    closes every session of the connection, which frees its devices."""

    def __init__(self, service: Service, connection_id: str):
        self.service = service
        self.id = connection_id
        self._stream = None
        self._events: list[str] = []
        self.on_event: Callable[[str], None] | None = None

    def attach(self) -> None:
        stream = self.service.connections.Attach(pb.AttachRequest(connection_id=self.id))
        first = next(stream)  # server acknowledges before we return
        self._events.append(first.message)
        self._stream = stream

        def pump() -> None:
            try:
                for event in stream:
                    self._events.append(event.message)
                    if self.on_event:
                        self.on_event(event.message)
            except grpc.RpcError:
                pass  # cancelled by close(), or the service went away

        threading.Thread(target=pump, name=f"tap-connection-{self.id[:8]}", daemon=True).start()

    def available_serials(self) -> list[str]:
        """Serials a test can use, from ``Service.devices``: online and not quarantined, free
        ones first, then ones another session holds (``open_device`` then waits for them when
        ``wait_for_device`` is set). Exclusive use is enforced by the session itself, so there is
        nothing to acquire beforehand."""
        devices = [d for d in self.service.devices() if d.state in (pb.DEVICE_FREE, pb.DEVICE_LEASED)]
        devices.sort(key=lambda d: d.state != pb.DEVICE_FREE)
        return [d.serial for d in devices]

    def open_device(self, serial: str, aut_package: str, **options) -> "Device":
        """Open a driver session on ``serial`` for ``aut_package``; ``options`` are ``Device.open`` keywords."""
        from .device import Device  # circular import at module load
        return Device.open(self, serial, aut_package, **options)

    def close(self) -> pb.CloseConnectionResponse:
        # Close explicitly before dropping the liveness stream, so the service records a client
        # request rather than a detach.
        try:
            with mapped_errors():
                return self.service.connections.Close(pb.CloseConnectionRequest(connection_id=self.id), timeout=60)
        finally:
            if self._stream is not None:
                self._stream.cancel()
                self._stream = None

    @property
    def events(self) -> list[str]:
        return list(self._events)

    def __enter__(self) -> "Connection":
        self.attach()
        return self

    def __exit__(self, *exc) -> None:
        with contextlib.suppress(TapError):
            self.close()
