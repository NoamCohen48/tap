"""Connection to the Tap host service and run ownership.

Discovery order: ``TAP_SERVICE=host:port`` → ``<state dir>/service.json`` written by ``tap serve``
(state dir = ``TAP_STATE_DIR`` or ``~/.tap``) → auto-start ``tap serve`` (binary from
``TAP_BIN`` or ``tap`` on PATH). An auto-started service is left running, like the ADB server;
``tap stop`` shuts it down.
"""
from __future__ import annotations

import contextlib
import json
import os
import pathlib
import shutil
import subprocess
import threading
import time
from dataclasses import dataclass
from typing import Callable, Iterator

import grpc

from ._gen import tap_pb2 as pb
from ._gen import tap_pb2_grpc as rpc
from .errors import AppLifecycleError, DeviceBusyError, ServiceError, TapError, WaitTimeoutError


def state_dir() -> pathlib.Path:
    """``TAP_STATE_DIR`` or ``~/.tap``: where ``tap serve`` writes ``service.json``."""
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
        rpc.RunServiceStub(channel).Info(pb.InfoRequest(), timeout=timeout)
        return True
    except grpc.RpcError:
        return False
    finally:
        channel.close()


def find_binary() -> str | None:
    """``TAP_BIN`` or ``tap`` on PATH."""
    return os.environ.get("TAP_BIN") or shutil.which("tap")


def start_service(binary: str, directory: pathlib.Path, adb: str | None = None, timeout: float = 30.0) -> str:
    """Spawns ``tap serve`` detached and returns its address once it prints TAP_SERVICE_READY."""
    directory.mkdir(parents=True, exist_ok=True)
    args = [binary, "serve", "--state-dir", str(directory)]
    if adb:
        args += ["--adb", adb]
    log = open(directory / "service.log", "ab")
    process = subprocess.Popen(
        args, stdout=subprocess.PIPE, stderr=log, stdin=subprocess.DEVNULL,
        start_new_session=True, text=True,
    )
    deadline = time.monotonic() + timeout
    port = None
    assert process.stdout is not None
    while time.monotonic() < deadline:
        line = process.stdout.readline()
        if not line:
            break
        log.write(line.encode())
        log.flush()
        if line.startswith("TAP_SERVICE_READY"):
            port = int(line.split("port=")[1].strip())
            break
    if port is None:
        process.kill()
        raise TapError(f"tap serve did not become ready within {timeout}s (see {directory / 'service.log'})")

    # Keep draining stdout so the service never blocks on a full pipe.
    def drain() -> None:
        for chunk in process.stdout:
            log.write(chunk.encode())
            log.flush()

    threading.Thread(target=drain, name="tap-service-stdout", daemon=True).start()
    return f"127.0.0.1:{port}"


def resolve_address(autostart: bool = True, binary: str | None = None, adb: str | None = None) -> str:
    """Address of a service to use: ``TAP_SERVICE``, a live ``service.json``, else auto-start."""
    explicit = os.environ.get("TAP_SERVICE")
    if explicit:
        return explicit
    directory = state_dir()
    descriptor = _read_descriptor(directory)
    if descriptor:
        address = f"127.0.0.1:{descriptor['port']}"
        if _alive(address):
            return address
    if not autostart:
        raise TapError(f"no running tap service (no live descriptor in {directory}); start one with `tap serve`")
    binary = binary or find_binary()
    if binary is None:
        raise TapError("no running tap service and no `tap` binary found (set TAP_BIN or add it to PATH)")
    return start_service(binary, directory, adb)


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


@dataclass(frozen=True)
class DeviceFacts:
    """What the pool knows about a device: serial, API level, manufacturer, model, emulator."""

    serial: str
    api_level: int
    manufacturer: str
    model: str
    emulator: bool

    @classmethod
    def of(cls, facts: pb.DeviceFacts) -> "DeviceFacts":
        return cls(facts.serial, facts.api_level, facts.manufacturer, facts.model, facts.emulator)


class Service:
    """One gRPC channel to a host service. Cheap to create; share one per process."""

    def __init__(self, address: str | None = None, **resolve_options):
        self.address = address or resolve_address(**resolve_options)
        self.channel = grpc.insecure_channel(
            self.address,
            options=[("grpc.max_receive_message_length", 64 * 1024 * 1024)],
        )
        self.runs = rpc.RunServiceStub(self.channel)
        self.pool = rpc.PoolServiceStub(self.channel)
        self.sessions = rpc.SessionServiceStub(self.channel)
        self.apps = rpc.AppServiceStub(self.channel)

    def info(self) -> pb.InfoResponse:
        """Service version, protocol version, ADB executable, state dir, bundled driver."""
        with mapped_errors():
            return self.runs.Info(pb.InfoRequest(), timeout=10)

    def inventory(self) -> list[pb.PoolDevice]:
        """Every device in the pool with its state (``FREE``, ``LEASED``, ``QUARANTINED``, ``OFFLINE``)."""
        with mapped_errors():
            return list(self.pool.Inventory(pb.InventoryRequest(), timeout=30).devices)

    def open_run(self, name: str) -> "Run":
        """Open and attach a run named ``name``; use as a context manager."""
        with mapped_errors():
            run_id = self.runs.Open(pb.OpenRunRequest(name=name), timeout=10).run_id
        return Run(self, run_id)

    def close(self) -> None:
        self.channel.close()


class Run:
    """Ownership scope for sessions. ``attach`` starts the liveness stream: if this process dies,
    the service closes every session of the run, which frees its devices."""

    def __init__(self, service: Service, run_id: str):
        self.service = service
        self.id = run_id
        self._stream = None
        self._events: list[str] = []
        self.on_event: Callable[[str], None] | None = None

    def attach(self) -> None:
        stream = self.service.runs.Attach(pb.AttachRequest(run_id=self.id))
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

        threading.Thread(target=pump, name=f"tap-run-{self.id[:8]}", daemon=True).start()

    def available_serials(self) -> list[str]:
        """Serials a test can use, from the service inventory: online and not quarantined, free
        ones first, then ones another session holds (``open_device`` then waits for them when
        ``wait_for_device`` is set). Exclusive use is enforced by the session itself, so there is
        nothing to acquire beforehand."""
        devices = [d for d in self.service.inventory() if d.state in (pb.DEVICE_FREE, pb.DEVICE_LEASED)]
        devices.sort(key=lambda d: d.state != pb.DEVICE_FREE)
        return [d.facts.serial for d in devices]

    def open_device(self, serial: str, aut_package: str, **options) -> "Device":
        """Open a driver session on ``serial`` for ``aut_package``; ``options`` are ``Device.open`` keywords."""
        from .device import Device  # circular import at module load
        return Device.open(self, serial, aut_package, **options)

    def close(self) -> pb.CloseRunResponse:
        # Close explicitly before dropping the liveness stream, so the service records a client
        # request rather than a detach.
        try:
            with mapped_errors():
                return self.service.runs.Close(pb.CloseRunRequest(run_id=self.id), timeout=60)
        finally:
            if self._stream is not None:
                self._stream.cancel()
                self._stream = None

    @property
    def events(self) -> list[str]:
        return list(self._events)

    def __enter__(self) -> "Run":
        self.attach()
        return self

    def __exit__(self, *exc) -> None:
        with contextlib.suppress(TapError):
            self.close()
