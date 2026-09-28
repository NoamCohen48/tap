"""A device under one live driver session, proxied by the host server."""
# pyright: reportAttributeAccessIssue=false, reportArgumentType=false, reportCallIssue=false

from __future__ import annotations

import hashlib
import os
import pathlib
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING

from . import _gen as pb
from .app import App
from .element import Element, ElementWait
from .errors import CommandError, TapError, WaitTimeoutError
from .selectors import Selector
from .server import ClientConnection, mapped_errors

if TYPE_CHECKING:
    # typing.Self is 3.11+; the annotation is never evaluated at runtime (PEP 563).
    from typing_extensions import Self

KEYCODE_HOME = 3
KEYCODE_BACK = 4


@dataclass(frozen=True)
class Timeouts:
    """Per-device defaults in seconds. Every call also accepts an explicit timeout."""

    action: float = 10.0
    wait: float = 10.0
    lifecycle: float = 30.0
    poll_interval: float = 0.1


# Extra seconds a device RPC's gRPC deadline allows past the command's own timeout, so that a
# command timeout arrives as a device result rather than a client-side DEADLINE_EXCEEDED.
RPC_DEADLINE_SLACK = 60.0


def _or(value: float | None, default: float) -> float:
    """``value`` unless it is None (0 is a real timeout, not "use the default")."""
    return default if value is None else value


class Device:
    """All calls block. The client does not serialize calls on one device: issue them from one
    thread, and use one thread per Device for concurrency across devices. Nothing here caches UI state: ``element`` returns a lazy
    selector that every action resolves again, and mutations fail with AMBIGUOUS/NOT_FOUND
    before any input when the selector does not match exactly one node."""

    def __init__(
        self,
        owner_connection: ClientConnection,
        response: pb.AttachResponse,
        aut_package: str,
        timeouts: Timeouts,
    ):
        self.owner_connection = owner_connection
        self.server = owner_connection.server
        self.attached_device_id = response.attached_device_id
        self.serial = response.serial
        self.generation = response.generation
        self.aut_package = aut_package
        self.timeouts = timeouts
        self._detached = False

    @classmethod
    def _attach_device(
        cls,
        owner_connection: ClientConnection,
        serial: str,
        aut_package: str,
        timeouts: Timeouts | None = None,
        skip_driver_install: bool = False,
        sync_authority: str | None = None,
        wait_for_device: float = 0,
    ) -> Device:
        """Attach ``serial`` for ``aut_package`` (used by ``ClientConnection.attach_device``).
        The attachment holds the device's per-serial lock until ``detach``; if another device session holds
        it, attachment raises ``DeviceBusyError`` — at once, or after ``wait_for_device`` seconds.
        The driver is always the daemon's (bundled, or ``tap serve --driver-apk X
        --driver-test-apk Y``); ``skip_driver_install`` only skips reinstalling it."""
        timeouts = timeouts or Timeouts()
        request = pb.AttachRequest(
            client_connection_id=owner_connection.id,
            serial=serial,
            aut_package=aut_package,
            default_timeout_ms=int(timeouts.action * 1000),
        )
        if wait_for_device > 0:  # absent = fail at once when another session holds it
            request.lease_timeout_ms = int(wait_for_device * 1000)
        if skip_driver_install:
            request.skip_driver_install = True
        if sync_authority:
            request.sync_authority = sync_authority
        with mapped_errors(serial):
            response = owner_connection.server.device_stub.Attach(
                request, timeout=180 + wait_for_device
            )
        return cls(owner_connection, response, aut_package, timeouts)

    # --- raw protocol escape hatch --------------------------------------------------------------

    def execute(self, timeout: float | None = None, **op) -> pb.CommandResult:
        """Runs one protocol command and returns the result as data (the outcome may be ``error``).
        ``op`` is exactly one ``Command`` case: ``execute(tap=pb.Tap(selector=...))``."""
        ((name, message),) = op.items()
        self._ensure_usable(f"execute {name}")
        command = pb.Command(timeout_ms=int(_or(timeout, self.timeouts.action) * 1000))
        getattr(command, name).CopyFrom(message)
        with mapped_errors(self.serial):
            return self.server.device_stub.Execute(
                pb.ExecuteRequest(
                    client_connection_id=self.owner_connection.id,
                    attached_device_id=self.attached_device_id,
                    command=command,
                ),
                timeout=command.timeout_ms / 1000 + RPC_DEADLINE_SLACK,
            ).result

    def _ensure_usable(self, operation: str) -> None:
        """Raises ``TapError`` when detached or when the owning connection is closed/broken."""
        if self._detached:
            raise TapError(f"Device({self.serial}) is detached; {operation} rejected")
        self.owner_connection.ensure_usable(f"{operation} on {self.serial}")

    def execute_or_raise(
        self, timeout: float | None = None, selector: Selector | None = None, **op
    ) -> pb.CommandResult:
        """``execute`` that raises ``CommandError`` (naming ``selector``) instead of returning an error outcome."""
        result = self.execute(timeout, **op)
        if result.HasField("error"):
            (name,) = op
            raise CommandError(
                result, name, self.serial, selector.render() if selector else None
            )
        return result

    # --- elements and waits ---------------------------------------------------------------------

    def element(self, selector: Selector) -> Element:
        """A lazy ``Element`` for ``selector``."""
        return Element(self, selector)

    def wait(self, selector: Selector, timeout: float | None = None) -> ElementWait:
        """An ``ElementWait`` on ``selector`` (default timeout ``timeouts.wait``)."""
        return ElementWait(
            self, selector, self.timeouts.wait if timeout is None else timeout
        )

    def app(self, package_name: str | None = None) -> App:
        """The ``App`` for ``package_name`` (default: the app under test)."""
        return App(self, package_name or self.aut_package)

    def info(self) -> pb.DeviceInfo:
        """Serial, API level, model and display size."""
        return self.execute_or_raise(device_info=pb.DeviceInfoQuery()).device_info

    def press_back(self) -> None:
        """Send ``KEYCODE_BACK``."""
        self.press_key(KEYCODE_BACK)

    def press_home(self) -> None:
        """Send ``KEYCODE_HOME``."""
        self.press_key(KEYCODE_HOME)

    def press_key(self, key_code: int) -> None:
        """Injects one Android key code (a mutation: never replayed on transport loss)."""
        self.execute_or_raise(press_key=pb.PressKey(key_code=key_code))

    def type_text(self, value: str, timeout: float | None = None) -> None:
        """Type ``value`` as real key events into whatever has input focus now.

        No target and no click (``Element.type_text`` taps first). Unsupported characters are
        rejected before any input with ``INVALID_REQUEST``/``UNSUPPORTED_CHARACTERS``; otherwise
        it reports whether every key event was accepted. Where the characters landed is for the
        test to assert.
        """
        self.execute_or_raise(timeout, type_text=pb.TypeText(text=value))

    def screenshot(
        self,
        timeout: float | None = None,
        write_to: str | os.PathLike[str] | None = None,
    ) -> bytes:
        """PNG bytes of the screen. The server verifies them against the driver's checksum and
        the client checks the returned sha256 again. With ``write_to`` the bytes are also
        written to that file on this machine (parent directories are created)."""
        self._ensure_usable("screenshot")
        request = pb.ScreenshotRequest(
            client_connection_id=self.owner_connection.id,
            attached_device_id=self.attached_device_id,
            timeout_ms=int(_or(timeout, self.timeouts.lifecycle) * 1000),
        )
        with mapped_errors(self.serial):
            response = self.server.device_stub.Screenshot(
                request, timeout=request.timeout_ms / 1000 + RPC_DEADLINE_SLACK
            )
        png = response.png
        if response.sha256:
            actual = hashlib.sha256(png).hexdigest()
            if actual != response.sha256.lower():
                raise TapError(
                    f"screenshot of {self.serial} failed its checksum: sha256 {actual}, "
                    f"server said {response.sha256}"
                )
        if write_to is not None:
            path = pathlib.Path(write_to)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(png)
        return png

    def dump_hierarchy(self, timeout: float | None = None) -> str:
        """Diagnostic accessibility XML. Never used by selectors; keep it out of assertions."""
        return self.execute_or_raise(
            _or(timeout, self.timeouts.lifecycle), dump_hierarchy=pb.DumpHierarchy()
        ).text

    def driver_log(self) -> list[str]:
        """The attached device's recent driver log lines."""
        self._ensure_usable("driver_log")
        with mapped_errors(self.serial):
            return list(
                self.server.device_stub.DriverLog(
                    pb.DriverLogRequest(
                        client_connection_id=self.owner_connection.id,
                        attached_device_id=self.attached_device_id,
                    ),
                    timeout=30,
                ).lines
            )

    def await_app_visible(
        self, package_name: str | None = None, timeout: float | None = None
    ) -> None:
        """Waits on the device until ``package_name`` owns the focused window. Raises
        ``WaitTimeoutError`` only when the device reports ``WAIT_TIMEOUT``; any other failure
        (driver unhealthy, transport lost, ...) is a ``CommandError``."""
        package_name = package_name or self.aut_package
        timeout = self.timeouts.wait if timeout is None else timeout
        result = self.execute(
            timeout, wait_app_visible=pb.WaitAppVisible(package_name=package_name)
        )
        if result.HasField("error"):
            if result.error.code != pb.ERR_WAIT_TIMEOUT:
                raise CommandError(result, "wait_app_visible", self.serial, None)
            try:
                last = f"currentPackage={self.info().current_package}"
            except Exception:  # noqa: BLE001 - diagnostics only
                last = None
            raise WaitTimeoutError(
                f"package {package_name} to be in the foreground",
                self.serial,
                result.duration_ms,
                0,
                last,
            )

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
        ``SCREEN_CHANGING``. ``await_app_settled`` / ``await_animation_end`` are the shorthands.
        Only a device ``WAIT_TIMEOUT`` becomes ``WaitTimeoutError``; other failures are
        ``CommandError``."""
        package_name = package_name or self.aut_package
        timeout = self.timeouts.wait if timeout is None else timeout
        result = self.execute(
            timeout,
            wait_screen_stable=pb.WaitScreenStable(
                package_name=package_name,
                stable_for_ms=int(stable_for * 1000),
                signal=signal,
            ),
        )
        if result.HasField("error"):
            if result.error.code != pb.ERR_WAIT_TIMEOUT:
                raise CommandError(result, "wait_screen_stable", self.serial, None)
            what = {pb.STABILITY_TREE: "hierarchy", pb.STABILITY_PIXELS: "pixels"}.get(
                signal, "screen"
            )
            raise WaitTimeoutError(
                f"the {package_name} {what} to stay unchanged for {stable_for:g}s",
                self.serial,
                result.duration_ms,
                0,
                result.error.detail or None,
            )

    def await_app_settled(
        self,
        stable_for: float = 0.5,
        timeout: float | None = None,
        package_name: str | None = None,
    ) -> None:
        """Maestro's ``waitForAppToSettle``, on request only: the accessibility hierarchy has
        not changed for ``stable_for`` seconds. Cheap (no screenshots); misses pure drawing."""
        self.await_screen_stable(stable_for, timeout, package_name, pb.STABILITY_TREE)

    def await_animation_end(
        self,
        stable_for: float = 0.5,
        timeout: float | None = None,
        package_name: str | None = None,
    ) -> None:
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
        poll_interval = (
            self.timeouts.poll_interval if poll_interval is None else poll_interval
        )
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
                raise WaitTimeoutError(
                    description,
                    self.serial,
                    int((time.monotonic() - started) * 1000),
                    polls,
                    last,
                )
            time.sleep(poll_interval)

    # --- lifecycle --------------------------------------------------------------------------------

    def detach(self) -> str | None:
        """Detaches the device. Returns the quarantine detail when the device could not be left
        clean (the server keeps it out of circulation), else None. Idempotent once it
        succeeded; a failed detach may be retried."""
        if self._detached:
            return None
        with mapped_errors(self.serial):
            response = self.server.device_stub.Detach(
                pb.DetachRequest(
                    client_connection_id=self.owner_connection.id,
                    attached_device_id=self.attached_device_id,
                ),
                timeout=120,
            )
        self._detached = True
        return None if response.clean else response.detail

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc) -> None:
        self.detach()

    def __repr__(self) -> str:
        return f"Device({self.serial}, generation={self.generation})"
