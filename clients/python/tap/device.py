"""A device under one live driver session, proxied by the host service."""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

from ._gen import tap_pb2 as pb
from .app import App
from .element import Element, ElementWait
from .errors import CommandError, WaitTimeoutError
from .selectors import Selector
from .service import Connection, mapped_errors

KEYCODE_HOME = 3
KEYCODE_BACK = 4


@dataclass(frozen=True)
class Timeouts:
    """Per-device defaults in seconds. Every call also accepts an explicit timeout."""

    action: float = 10.0
    wait: float = 10.0
    lifecycle: float = 30.0
    poll_interval: float = 0.1


class Device:
    """All calls block and are serialized per device; use one Device per thread for
    concurrency across devices. Nothing here caches UI state: ``element`` returns a lazy
    selector that every action resolves again, and mutations fail with AMBIGUOUS/NOT_FOUND
    before any input when the selector does not match exactly one node."""

    def __init__(self, connection: Connection, response: pb.OpenSessionResponse, aut_package: str, timeouts: Timeouts):
        self.connection = connection
        self.service = connection.service
        self.session_id = response.session_id
        self.serial = response.serial
        self.generation = response.generation
        self.aut_package = aut_package
        self.timeouts = timeouts
        self._closed = False

    @classmethod
    def open(
        cls,
        connection: Connection,
        serial: str,
        aut_package: str,
        timeouts: Timeouts | None = None,
        driver_apk: str | None = None,
        driver_test_apk: str | None = None,
        skip_driver_install: bool = False,
        sync_authority: str | None = None,
        allowed_system_packages: list[str] | None = None,
        wait_for_device: float = 0,
    ) -> "Device":
        """Open a driver session on ``serial`` for ``aut_package`` (used by ``Connection.open_device``).
        The session holds the device's per-serial lock until ``close``; if another session holds
        it the open raises ``DeviceBusyError`` — at once, or after ``wait_for_device`` seconds."""
        timeouts = timeouts or Timeouts()
        request = pb.OpenSessionRequest(
            connection_id=connection.id, serial=serial, aut_package=aut_package,
            default_timeout_ms=int(timeouts.action * 1000),
            lease_timeout_ms=int(wait_for_device * 1000),
            allowed_system_packages=allowed_system_packages or [],
        )
        if driver_apk:
            request.driver_apk = driver_apk
        if driver_test_apk:
            request.driver_test_apk = driver_test_apk
        if skip_driver_install:
            request.skip_driver_install = True
        if sync_authority:
            request.sync_authority = sync_authority
        with mapped_errors(serial):
            response = connection.service.sessions.Open(request, timeout=180 + wait_for_device)
        return cls(connection, response, aut_package, timeouts)

    # --- raw protocol escape hatch --------------------------------------------------------------

    def execute(self, operation: int, selector: Selector | None = None, timeout: float | None = None, **fields) -> pb.CommandResult:
        """Runs one protocol operation and returns the result as data (ok may be False).
        ``fields`` are Command fields (input_text, direction, key_code, container_selector...)."""
        command = pb.Command(operation=operation, timeout_ms=int((timeout or self.timeouts.action) * 1000))
        if selector is not None:
            command.selector.CopyFrom(selector.proto)
        for name, value in fields.items():
            if value is None:
                continue
            if isinstance(value, Selector):
                getattr(command, name).CopyFrom(value.proto)
            else:
                setattr(command, name, value)
        with mapped_errors(self.serial):
            return self.service.sessions.Execute(
                pb.ExecuteRequest(session_id=self.session_id, command=command),
                timeout=command.timeout_ms / 1000 + 60,
            )

    def execute_or_raise(self, operation: int, selector: Selector | None = None, timeout: float | None = None, **fields) -> pb.CommandResult:
        """Send one raw command; raises ``CommandError`` on ``ok == False``."""
        result = self.execute(operation, selector, timeout, **fields)
        if not result.ok:
            raise CommandError(result, pb.Operation.Name(operation)[len("OP_"):], self.serial, selector.render() if selector else None)
        return result

    # --- elements and waits ---------------------------------------------------------------------

    def element(self, selector: Selector) -> Element:
        """A lazy ``Element`` for ``selector``."""
        return Element(self, selector)

    def wait(self, selector: Selector, timeout: float | None = None) -> ElementWait:
        """An ``ElementWait`` on ``selector`` (default timeout ``timeouts.wait``)."""
        return ElementWait(self, selector, self.timeouts.wait if timeout is None else timeout)

    def app(self, package_name: str | None = None) -> App:
        """The ``App`` for ``package_name`` (default: the app under test)."""
        return App(self, package_name or self.aut_package)

    def info(self) -> pb.DeviceInfo:
        """Serial, API level, model and display size."""
        return self.execute_or_raise(pb.OP_DEVICE_INFO).device_info

    def press_back(self) -> None:
        """Send ``KEYCODE_BACK``."""
        self.press_key(KEYCODE_BACK)

    def press_home(self) -> None:
        """Send ``KEYCODE_HOME``."""
        self.press_key(KEYCODE_HOME)

    def press_key(self, key_code: int) -> None:
        """Injects one Android key code (a mutation: never replayed on transport loss)."""
        self.execute_or_raise(pb.OP_PRESS_KEY, key_code=key_code)

    def screenshot(self, timeout: float | None = None, write_to: str | None = None) -> bytes:
        """PNG bytes, verified against the driver's checksum. With ``write_to`` the service
        writes the file and the returned bytes are empty."""
        request = pb.ScreenshotRequest(session_id=self.session_id, timeout_ms=int((timeout or self.timeouts.lifecycle) * 1000))
        if write_to:
            request.write_to = write_to
        with mapped_errors(self.serial):
            return self.service.sessions.Screenshot(request, timeout=request.timeout_ms / 1000 + 60).png

    def dump_hierarchy(self, timeout: float | None = None) -> str:
        """Diagnostic accessibility XML. Never used by selectors; keep it out of assertions."""
        return self.execute_or_raise(pb.OP_DUMP_HIERARCHY, timeout=timeout or self.timeouts.lifecycle).text

    def driver_log(self) -> list[str]:
        """The driver's log lines for this session."""
        with mapped_errors(self.serial):
            return list(self.service.sessions.DriverLog(pb.DriverLogRequest(session_id=self.session_id), timeout=30).lines)

    def await_app_visible(self, package_name: str | None = None, timeout: float | None = None) -> None:
        """Waits on the device until ``package_name`` owns the focused window."""
        package_name = package_name or self.aut_package
        timeout = self.timeouts.wait if timeout is None else timeout
        result = self.execute(pb.OP_WAIT_APP_VISIBLE, timeout=timeout, package_name=package_name)
        if not result.ok:
            try:
                last = f"currentPackage={self.info().current_package}"
            except Exception:  # noqa: BLE001 - diagnostics only
                last = None
            raise WaitTimeoutError(f"package {package_name} to be in the foreground", self.serial, result.duration_ms, 0, last)

    def await_screen_stable(
        self,
        stable_for: float = 0.5,
        timeout: float | None = None,
        package_name: str | None = None,
        signal: int = pb.STABILITY_ALL,
    ) -> None:
        """Waits on the device until the AUT's focused window has stopped changing for
        ``stable_for`` seconds according to ``signal``: the accessibility tree
        (``STABILITY_TREE``), the window pixels (``STABILITY_PIXELS``, 0.5 % tolerance) or both
        (default). Call it explicitly after an action that starts an animation or transition;
        nothing waits for this implicitly. A screen that keeps changing times out with detail
        ``SCREEN_CHANGING``. ``await_app_settled`` / ``await_animation_end`` are the shorthands."""
        package_name = package_name or self.aut_package
        timeout = self.timeouts.wait if timeout is None else timeout
        result = self.execute(
            pb.OP_WAIT_SCREEN_STABLE,
            timeout=timeout,
            package_name=package_name,
            stable_for_ms=int(stable_for * 1000),
            stable_signal=signal,
        )
        if not result.ok:
            what = {pb.STABILITY_TREE: "hierarchy", pb.STABILITY_PIXELS: "pixels"}.get(signal, "screen")
            raise WaitTimeoutError(
                f"the {package_name} {what} to stay unchanged for {stable_for:g}s", self.serial, result.duration_ms, 0, result.detail or None
            )

    def await_app_settled(self, stable_for: float = 0.5, timeout: float | None = None, package_name: str | None = None) -> None:
        """Maestro's ``waitForAppToSettle``, on request only: the accessibility hierarchy has
        not changed for ``stable_for`` seconds. Cheap (no screenshots); misses pure drawing."""
        self.await_screen_stable(stable_for, timeout, package_name, pb.STABILITY_TREE)

    def await_animation_end(self, stable_for: float = 0.5, timeout: float | None = None, package_name: str | None = None) -> None:
        """Maestro's ``waitForAnimationToEnd``, on request only: the window pixels have not
        changed (beyond 0.5 %) for ``stable_for`` seconds. One screenshot per 100 ms."""
        self.await_screen_stable(stable_for, timeout, package_name, pb.STABILITY_PIXELS)

    def await_until(
        self,
        description: str,
        condition: Callable[[], bool],
        timeout: float | None = None,
        poll_interval: float | None = None,
        observe: Callable[[], str | None] | None = None,
    ) -> None:
        """Host-side polling for conditions the driver cannot evaluate in one command
        (cross-device, backend state). Prefer ``wait`` for UI conditions: it polls on the device."""
        timeout = self.timeouts.wait if timeout is None else timeout
        poll_interval = self.timeouts.poll_interval if poll_interval is None else poll_interval
        started = time.monotonic()
        deadline = started + timeout
        polls = 0
        while True:
            polls += 1
            if condition():
                return
            if time.monotonic() >= deadline:
                last = None
                if observe:
                    try:
                        last = observe()
                    except Exception:  # noqa: BLE001 - diagnostics only
                        last = None
                raise WaitTimeoutError(description, self.serial, int((time.monotonic() - started) * 1000), polls, last)
            time.sleep(poll_interval)

    # --- lifecycle --------------------------------------------------------------------------------

    def close(self) -> str | None:
        """Closes the session. Returns the quarantine detail when the device could not be left
        clean (the service keeps it out of circulation), else None."""
        if self._closed:
            return None
        self._closed = True
        with mapped_errors(self.serial):
            response = self.service.sessions.Close(pb.CloseSessionRequest(session_id=self.session_id), timeout=120)
        return None if response.clean else response.detail

    def __enter__(self) -> "Device":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"Device({self.serial}, generation={self.generation})"
