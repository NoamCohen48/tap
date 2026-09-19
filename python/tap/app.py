"""App lifecycle, executed by the service (ADB + verified process identity)."""
from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from ._gen import tap_pb2 as pb
from .service import mapped_errors

if TYPE_CHECKING:
    from .device import Device


@dataclass(frozen=True)
class ProcessIdentity:
    pid: int
    start_token: str


class App:
    def __init__(self, device: "Device", package_name: str):
        self.device = device
        self.package_name = package_name
        self._apps = device.service.apps

    def _request(self, timeout: float | None) -> pb.AppRequest:
        return pb.AppRequest(
            session_id=self.device.session_id, package_name=self.package_name,
            timeout_ms=int(timeout * 1000) if timeout else 0,
        )

    def _call(self, method, request, timeout: float | None):
        with mapped_errors(self.device.serial):
            return method(request, timeout=(timeout or self.device.timeouts.lifecycle) + 60)

    def is_installed(self) -> bool:
        return self._call(self._apps.IsInstalled, self._request(None), None).value

    def install(self, apk_path: str, timeout: float | None = None) -> None:
        timeout = timeout or self.device.timeouts.lifecycle
        self._call(self._apps.Install, pb.AppInstallRequest(app=self._request(timeout), apk_path=str(apk_path)), timeout + 120)

    def uninstall(self) -> None:
        self._call(self._apps.Uninstall, self._request(None), 120)

    def force_stop(self, timeout: float | None = None) -> None:
        self._call(self._apps.ForceStop, self._request(timeout or self.device.timeouts.action), timeout)

    def clear_data(self, timeout: float | None = None) -> None:
        self._call(self._apps.ClearData, self._request(timeout or self.device.timeouts.action), timeout)

    def grant_permission(self, permission: str) -> None:
        self._call(self._apps.GrantPermission, pb.AppGrantRequest(app=self._request(None), permission=permission), None)

    def launch(self, activity: str | None = None, timeout: float | None = None) -> None:
        """Starts the activity and waits until the package owns the focused window."""
        request = pb.AppLaunchRequest(app=self._request(timeout or self.device.timeouts.lifecycle))
        if activity:
            request.activity = activity
        self._call(self._apps.Launch, request, timeout)

    def cold_launch(self, activity: str | None = None, timeout: float | None = None) -> ProcessIdentity:
        """Force-stops, launches and returns the verified new process identity."""
        request = pb.AppLaunchRequest(app=self._request(timeout or self.device.timeouts.lifecycle))
        if activity:
            request.activity = activity
        identity = self._call(self._apps.ColdLaunch, request, timeout)
        return ProcessIdentity(identity.pid, identity.start_token)

    def process(self, timeout: float | None = None) -> ProcessIdentity:
        identity = self._call(self._apps.Process, self._request(timeout or self.device.timeouts.action), timeout)
        return ProcessIdentity(identity.pid, identity.start_token)

    def is_running(self) -> bool:
        return self._call(self._apps.IsRunning, self._request(None), None).value

    def await_idle(self, timeout: float | None = None, stable_for: float = 0.2) -> None:
        """Waits until the app's own sync contract reports idle for ``stable_for`` seconds."""
        timeout = self.device.timeouts.wait if timeout is None else timeout
        self._call(
            self._apps.AwaitIdle,
            pb.AppAwaitIdleRequest(app=self._request(timeout), stable_for_ms=int(stable_for * 1000)),
            timeout,
        )

    def __repr__(self) -> str:
        return f"App({self.package_name} on {self.device.serial})"
