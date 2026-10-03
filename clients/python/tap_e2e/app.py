"""App lifecycle, executed by the server (ADB + verified process identity)."""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

import os
from collections.abc import Iterator, Mapping, Sequence
from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .client import mapped_errors
from .models import AppProcess, Long, MatchMode, Notification, StabilitySignal, Toast
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

    def is_permission_granted(self, permission: str) -> bool:
        """Whether ``permission`` is granted to the package now, as ``dumpsys package`` lists it:
        the read-back for ``grant_permission``, ``revoke_permission``, ``clear_data`` and the
        permission dialog."""
        request = pb.IsPermissionGrantedRequest(app=self._target(), permission=permission)
        return self._call(self._apps.IsPermissionGranted, request, None).granted

    def set_locales(self, locales: Sequence[str]) -> None:
        """The app's own languages (Android's per-app language, API 33+), as BCP-47 tags in
        preference order (``"fr-FR"``, ``"en"``); an empty list makes it follow the system again.

        The server checks and canonicalizes the tags (``INVALID_ARGUMENT`` for a malformed one)
        and reads the change back. Like the device conditions, the app's languages before the
        session's first change come back on ``Device.detach()``. Below API 33: ``ServerError``
        (reason ``UNSUPPORTED_API``). Android applies it as a configuration change: the app's
        activities are recreated unless it handles the change itself.
        """
        if isinstance(locales, str):
            raise TypeError("locales is a list of tags, not a string")
        request = pb.SetLocalesRequest(app=self._target(), locales=list(locales))
        self._call(self._apps.SetLocales, request, None)

    def locales(self) -> list[str]:
        """The app's own languages now (canonical BCP-47 tags); empty when it follows the
        system. API 33+."""
        request = pb.GetLocalesRequest(app=self._target())
        return list(self._call(self._apps.GetLocales, request, None).locales)

    def revoke_permission(self, permission: str) -> None:
        """``pm revoke`` a runtime permission, proven by ``dumpsys package``. Android kills the
        app's process when one of its runtime permissions is revoked."""
        request = pb.RevokePermissionRequest(app=self._target(), permission=permission)
        self._call(self._apps.RevokePermission, request, None)

    def launch(
        self,
        activity: str | None = None,
        timeout: float | None = None,
        *,
        extras: Mapping[str, str | bool | int | float | Long] | None = None,
    ) -> None:
        """Starts the activity with ``am start -W`` and returns when Android reports the launch
        complete. Nothing about the UI is assumed: wait for what the test needs
        (``await_visible``, ``await_screen_stable``, an element wait).

        ``extras`` are put on the intent (``am start --es/--ez/--ei/--el/--ef``): ``str``,
        ``bool``, ``int`` (32-bit, ``getIntExtra``), ``float`` (32-bit, ``getFloatExtra``) or
        ``tap_e2e.Long(n)`` for ``getLongExtra``. Anything else raises before the call."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.LaunchRequest(
            app=self._target(), timeout_ms=self._ms(timeout), extras=_proto.intent_extras(extras or {})
        )
        if activity:
            request.activity = activity
        self._call(self._apps.Launch, request, timeout)

    def await_visible(self, timeout: float | None = None) -> None:
        """Waits on the device until this package owns the focused window. Raises
        ``WaitTimeoutError`` only when the device reports ``WAIT_TIMEOUT``; any other failure
        (driver unhealthy, transport lost, ...) is a ``CommandError``."""
        self.device._await_app_visible(self.package_name, timeout)

    def await_toast(
        self, text: str | None = None, mode: MatchMode = MatchMode.EXACT, *, timeout: float | None = None
    ) -> Toast:
        """``Device.await_toast`` for this package's toasts only: on Android 11+ a text toast is
        drawn by SystemUI but still reported under the app that posted it."""
        return self.device.await_toast(text, mode, package_name=self.package_name, timeout=timeout)

    def await_notification(
        self,
        title: str | None = None,
        text: str | None = None,
        mode: MatchMode = MatchMode.EXACT,
        *,
        timeout: float | None = None,
    ) -> Notification:
        """``Device.await_notification`` for this package's notifications only."""
        return self.device.await_notification(title, text, mode, package_name=self.package_name, timeout=timeout)

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

    def foreground(self, timeout: float | None = None) -> None:
        """Bring the app to the front the way the launcher does: its launcher intent with
        ``NEW_TASK | RESET_TASK_IF_NEEDED``, so a running task resumes where it was instead of
        gaining a second copy of the launcher activity, and a stopped app starts. Returns when
        ``am start -W`` reports the launch complete; wait for the screen you need."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.ForegroundRequest(app=self._target(), timeout_ms=self._ms(timeout))
        self._call(self._apps.Foreground, request, timeout)

    def background(self) -> None:
        """Send the app to the background with the Home key, as a user would; the process keeps
        running. Bring it back with ``foreground``."""
        self.device.press_home()

    def open_link(self, uri: str, any_app: bool = False, timeout: float | None = None) -> str | None:
        """Open ``uri`` (a deep link or app link, e.g. ``myapp://orders/42`` or
        ``https://example.com/x``) with an ``ACTION_VIEW`` intent restricted to this package, so
        no chooser or browser can take it; ``any_app=True`` lets Android resolve it like a tap on
        a link elsewhere would. Returns the activity Android reported starting
        (``package/.Activity``) when it reported one. Nothing about the screen is checked: wait
        for what the link should show."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.OpenLinkRequest(app=self._target(), uri=uri, any_app=any_app, timeout_ms=self._ms(timeout))
        response = self._call(self._apps.OpenLink, request, timeout)
        return response.activity if response.HasField("activity") else None

    def cold_launch(
        self,
        activity: str | None = None,
        timeout: float | None = None,
        *,
        extras: Mapping[str, str | bool | int | float | Long] | None = None,
    ) -> AppProcess:
        """Force-stops, launches (with ``extras``, as ``launch``) and returns the verified new
        process identity."""
        timeout = self._or(timeout, self.device.timeouts.lifecycle)
        request = pb.ColdLaunchRequest(
            app=self._target(), timeout_ms=self._ms(timeout), extras=_proto.intent_extras(extras or {})
        )
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
