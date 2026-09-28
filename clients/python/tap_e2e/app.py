"""App lifecycle, executed by the server (ADB + verified process identity)."""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

import os
from collections.abc import Iterator
from dataclasses import dataclass
from typing import TYPE_CHECKING

from . import _gen as pb
from .client import mapped_errors

# Size of each streamed ``InstallRequest.chunk``.
INSTALL_CHUNK_BYTES = 1 << 20

# Same deadline slack as Device commands (see device.RPC_DEADLINE_SLACK).
RPC_DEADLINE_SLACK = 60.0

if TYPE_CHECKING:
    from .device import Device


@dataclass(frozen=True)
class ProcessIdentity:
    """PID plus a start token that changes on every process start."""

    pid: int
    start_token: str


class App:
    """Lifecycle of one package, executed by the server over ADB and verified against a
    postcondition; failures are ``AppLifecycleError``. Obtain with ``Device.app()``.
    Timeouts left as None use the device's client-side defaults (``Timeouts``) where the call
    has one, else the server's default.
    """

    def __init__(self, device: Device, package_name: str):
        self.device = device
        self.package_name = package_name
        self._apps = device.client.apps

    def _target(self) -> pb.AppTarget:
        return pb.AppTarget(
            client_connection_id=self.device.owner_connection.id,
            attached_device_id=self.device.attached_device_id,
            package_name=self.package_name,
        )

    @staticmethod
    def _ms(seconds: float) -> int:
        return int(seconds * 1000)

    @staticmethod
    def _or(value: float | None, default: float) -> float:
        return default if value is None else value

    def _call(self, method, request, timeout: float | None):
        self.device._ensure_usable(f"app {self.package_name}")
        if timeout is None:
            timeout = self.device.timeouts.lifecycle
        with mapped_errors(self.device.serial):
            return method(request, timeout=timeout + RPC_DEADLINE_SLACK)

    def is_installed(self) -> bool:
        """Whether the package is installed."""
        request = pb.IsInstalledRequest(app=self._target())
        return self._call(self._apps.IsInstalled, request, None).installed

    def install(self, apk_path: str | os.PathLike[str], timeout: float | None = None) -> None:
        """``adb install -r -t`` of the APK at ``apk_path``, verified. ``apk_path`` is a file on
        *this* machine: its bytes are streamed to the server in 1 MiB chunks, so the server may
        run elsewhere."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        size = os.path.getsize(apk_path)  # raises for a missing file before any RPC
        self._call(self._apps.Install, self._install_parts(apk_path, size, timeout), timeout + 120)

    def _install_parts(
        self, apk_path: str | os.PathLike[str], size: int, timeout: float
    ) -> Iterator[pb.InstallRequest]:
        yield pb.InstallRequest(
            header=pb.InstallHeader(
                app=self._target(), timeout_ms=self._ms(timeout), size_bytes=size
            )
        )
        with open(apk_path, "rb") as apk:
            while chunk := apk.read(INSTALL_CHUNK_BYTES):
                yield pb.InstallRequest(chunk=chunk)

    def uninstall(self) -> None:
        """``pm uninstall``, verified."""
        self._call(self._apps.Uninstall, pb.UninstallRequest(app=self._target()), 120)

    def force_stop(self, timeout: float | None = None) -> None:
        """``am force-stop`` plus proof that no process of the package remains."""
        timeout = self._or(timeout, self.device.timeouts.action)
        request = pb.ForceStopRequest(app=self._target(), timeout_ms=self._ms(timeout))
        self._call(self._apps.ForceStop, request, timeout)

    def clear_data(self, timeout: float | None = None) -> None:
        """``pm clear``: data, cache and runtime permissions are gone; the app is left stopped."""
        timeout = self._or(timeout, self.device.timeouts.action)
        request = pb.ClearDataRequest(app=self._target(), timeout_ms=self._ms(timeout))
        self._call(self._apps.ClearData, request, timeout)

    def grant_permission(self, permission: str) -> None:
        """``pm grant`` a runtime permission, e.g. ``android.permission.CAMERA``."""
        request = pb.GrantPermissionRequest(app=self._target(), permission=permission)
        self._call(self._apps.GrantPermission, request, None)

    def launch(self, activity: str | None = None, timeout: float | None = None) -> None:
        """Starts the activity with ``am start -W`` and returns when Android reports the launch
        complete. Nothing about the UI is assumed: wait for what the test needs
        (``Device.await_app_visible``, ``Device.await_screen_stable``, an element wait)."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.LaunchRequest(app=self._target(), timeout_ms=self._ms(timeout))
        if activity:
            request.activity = activity
        self._call(self._apps.Launch, request, timeout)

    def cold_launch(
        self, activity: str | None = None, timeout: float | None = None
    ) -> ProcessIdentity:
        """Force-stops, launches and returns the verified new process identity."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.ColdLaunchRequest(app=self._target(), timeout_ms=self._ms(timeout))
        if activity:
            request.activity = activity
        identity = self._call(self._apps.ColdLaunch, request, timeout).process
        return ProcessIdentity(identity.pid, identity.start_token)

    def process(self, timeout: float | None = None) -> ProcessIdentity:
        """The single current process identity; waits briefly for it to exist."""
        timeout = self._or(timeout, self.device.timeouts.action)
        request = pb.ProcessRequest(app=self._target(), timeout_ms=self._ms(timeout))
        identity = self._call(self._apps.Process, request, timeout).process
        return ProcessIdentity(identity.pid, identity.start_token)

    def is_running(self) -> bool:
        """Whether any process of the package is alive."""
        request = pb.IsRunningRequest(app=self._target())
        return self._call(self._apps.IsRunning, request, None).running

    def await_idle(self, timeout: float | None = None, stable_for: float = 0.2) -> None:
        """Waits until the app's own sync contract reports idle for ``stable_for`` seconds."""
        timeout = self._or(timeout, self.device.timeouts.wait)
        request = pb.AwaitIdleRequest(
            app=self._target(),
            timeout_ms=self._ms(timeout),
            stable_for_ms=self._ms(stable_for),
        )
        self._call(self._apps.AwaitIdle, request, timeout)

    def __repr__(self) -> str:
        return f"App({self.package_name} on {self.device.serial})"
