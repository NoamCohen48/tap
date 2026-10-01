"""App lifecycle, executed by the server (ADB + verified process identity)."""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

import os
from collections.abc import Iterator
from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .client import mapped_errors
from .models import AppProcess, StabilitySignal
from .element import Element, ElementWait
from .selectors import Selector

# Size of each streamed ``InstallRequest.chunk``.
INSTALL_CHUNK_BYTES = 1 << 20

# Same deadline slack as Device commands (see device.RPC_DEADLINE_SLACK).
RPC_DEADLINE_SLACK = 60.0

if TYPE_CHECKING:
    from .device import Device


class App:
    """One app on a device: its elements, its lifecycle and the waits on it. Obtain with
    ``device.app(package_name)``, which performs no I/O.

    ``element`` and ``wait`` add the package as one more selector predicate, so only this app's
    nodes match (in any of its windows); ``device.screen`` matches anywhere. Lifecycle calls are
    executed by the server over ADB and verified against a postcondition; failures are
    ``AppLifecycleError``.

    Example::

        app = device.app("com.example.shop")
        app.cold_launch()
        app.element(res("search")).set_text("socks")
        app.wait(text("3 results")).visible()

    Timeouts left as None use the device's client-side defaults (``Timeouts``) where the call
    has one, else the server's default.
    """

    def __init__(self, device: Device, package_name: str):
        self.device: Device = device
        """The device the app runs on."""
        self.package_name: str = package_name
        """The app's package name."""
        self._apps = device.client.apps

    def element(self, selector: Selector) -> Element:
        """A lazy element: ``selector`` restricted to this package's nodes. Nothing is looked
        up until an action or query runs."""
        return Element(self.device, selector._in_package(self.package_name))

    def wait(self, selector: Selector, timeout: float | None = None) -> ElementWait:
        """A wait on ``selector`` restricted to this package's nodes; ``timeout`` (seconds)
        defaults to ``device.timeouts.wait``."""
        return ElementWait(
            self.device,
            selector._in_package(self.package_name),
            self.device.timeouts.wait if timeout is None else timeout,
        )

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
        """``am force-stop`` plus proof that no process and no activity of the package remain."""
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
        (``await_visible``, ``await_screen_stable``, an element wait)."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.LaunchRequest(app=self._target(), timeout_ms=self._ms(timeout))
        if activity:
            request.activity = activity
        self._call(self._apps.Launch, request, timeout)

    def await_visible(self, timeout: float | None = None) -> None:
        """Waits on the device until this package owns the focused window. Raises
        ``WaitTimeoutError`` only when the device reports ``WAIT_TIMEOUT``; any other failure
        (driver unhealthy, transport lost, ...) is a ``CommandError``."""
        self.device._await_app_visible(self.package_name, timeout)

    def await_screen_stable(
        self,
        stable_for: float = 0.5,
        timeout: float | None = None,
        signal: StabilitySignal = StabilitySignal.ALL,
    ) -> None:
        """Waits on the device until this package's focused window has stopped changing for
        ``stable_for`` seconds according to ``signal``: the accessibility tree
        (``StabilitySignal.TREE``), the window pixels (``StabilitySignal.PIXELS``, 0.5 %
        tolerance) or both (default). Call it explicitly after an action that starts an animation
        or transition; nothing waits for this implicitly. A screen that keeps changing times out
        with detail ``SCREEN_CHANGING``. ``await_settled`` / ``await_animation_end`` are the
        shorthands. Only a device ``WAIT_TIMEOUT`` becomes ``WaitTimeoutError``; other failures
        are ``CommandError``."""
        self.device._await_screen_stable(self.package_name, stable_for, timeout, signal)

    def await_settled(self, stable_for: float = 0.5, timeout: float | None = None) -> None:
        """Maestro's ``waitForAppToSettle``, on request only: this package's accessibility
        hierarchy has not changed for ``stable_for`` seconds. Cheap (no screenshots); misses pure
        drawing."""
        self.await_screen_stable(stable_for, timeout, StabilitySignal.TREE)

    def await_animation_end(self, stable_for: float = 0.5, timeout: float | None = None) -> None:
        """Maestro's ``waitForAnimationToEnd``, on request only: this package's window pixels
        have not changed (beyond 0.5 %) for ``stable_for`` seconds. One screenshot per 100 ms."""
        self.await_screen_stable(stable_for, timeout, StabilitySignal.PIXELS)

    def cold_launch(
        self, activity: str | None = None, timeout: float | None = None
    ) -> AppProcess:
        """Force-stops, launches and returns the verified new process identity."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.ColdLaunchRequest(app=self._target(), timeout_ms=self._ms(timeout))
        if activity:
            request.activity = activity
        return _proto.app_process(self._call(self._apps.ColdLaunch, request, timeout).process)

    def process(self, timeout: float | None = None) -> AppProcess:
        """The single current process identity; waits briefly for it to exist."""
        timeout = self._or(timeout, self.device.timeouts.action)
        request = pb.ProcessRequest(app=self._target(), timeout_ms=self._ms(timeout))
        return _proto.app_process(self._call(self._apps.Process, request, timeout).process)

    def is_running(self) -> bool:
        """Whether any process of the package is alive."""
        request = pb.IsRunningRequest(app=self._target())
        return self._call(self._apps.IsRunning, request, None).running

    def await_idle(self, timeout: float | None = None, stable_for: float = 0.2) -> None:
        """Waits until the app's own sync contract reports idle for ``stable_for`` seconds.

        Experimental: synchronization is still being designed; this may change in any release."""
        timeout = self._or(timeout, self.device.timeouts.wait)
        request = pb.AwaitIdleRequest(
            app=self._target(),
            timeout_ms=self._ms(timeout),
            stable_for_ms=self._ms(stable_for),
        )
        self._call(self._apps.AwaitIdle, request, timeout)

    def __repr__(self) -> str:
        return f"App({self.package_name} on {self.device.serial})"
