"""A device under one live driver session, proxied by the host server."""
# pyright: reportAttributeAccessIssue=false, reportArgumentType=false, reportCallIssue=false

from __future__ import annotations

import concurrent.futures
import hashlib
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .app import App
from .errors import CommandError, TapError, WaitTimeoutError
from .models import (
    AttachedDeviceEntry,
    Capture,
    DeviceInfo,
    DriverLog,
    Hierarchy,
    Recording,
    ScreenSnapshot,
    Screenshot,
    StabilitySignal,
)
from .selectors import Selector
from .screen import Screen
from .client import TapConnection, mapped_errors

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
    thread, and use one thread per Device for concurrency across devices. Nothing here caches
    UI state: ``App.element`` / ``Screen.element`` return a lazy selector that every action
    resolves again, and mutations fail with AMBIGUOUS/NOT_FOUND before any input when the
    selector does not match exactly one node."""

    def __init__(
        self,
        owner_connection: TapConnection,
        response: pb.AttachResponse,
        timeouts: Timeouts,
    ):
        self.owner_connection = owner_connection
        self.client = owner_connection.client
        self.attached_device_id = response.attached_device_id
        self.serial: str = response.serial
        """The device's ADB serial, e.g. ``emulator-5554``."""
        self.generation: int = response.generation
        """The server's session generation for this device; it changes when the driver is rebuilt."""
        self.screen: Screen = Screen(self)
        """Whatever is visible, without an implicit package predicate."""
        self.timeouts: Timeouts = timeouts
        """Default timeouts for actions, waits and app lifecycle calls on this device."""
        self._detached = False

    @classmethod
    def _attach_device(
        cls,
        owner_connection: TapConnection,
        serial: str,
        timeouts: Timeouts | None = None,
        skip_driver_install: bool = False,
        wait_for_device: float = 0,
    ) -> Device:
        """Attach ``serial`` (used by ``TapConnection.attach_device``).
        The attachment holds the device's per-serial lock until ``detach``; if another device session holds
        it, attachment raises ``DeviceBusyError`` — at once, or after ``wait_for_device`` seconds.
        The driver is always the daemon's (bundled, or ``tap serve --driver-apk X
        --driver-test-apk Y``); ``skip_driver_install`` only skips reinstalling it."""
        timeouts = timeouts or Timeouts()
        request = pb.AttachRequest(
            client_connection_id=owner_connection.id,
            serial=serial,
            default_timeout_ms=int(timeouts.action * 1000),
        )
        if wait_for_device > 0:  # absent = fail at once when another session holds it
            request.lease_timeout_ms = int(wait_for_device * 1000)
        if skip_driver_install:
            request.skip_driver_install = True
        with mapped_errors(serial):
            response = owner_connection.client.device_stub.Attach(
                request, timeout=180 + wait_for_device
            )
        return cls(owner_connection, response, timeouts)

    @classmethod
    def _resume(
        cls, owner_connection: TapConnection, entry: AttachedDeviceEntry, timeouts: Timeouts
    ) -> Device:
        """A device ``owner_connection`` already has attached (``TapConnection.attached_devices``)."""
        response = pb.AttachResponse(
            attached_device_id=entry.attached_device_id,
            serial=entry.serial,
            generation=entry.generation,
        )
        return cls(owner_connection, response, timeouts)

    # --- protocol commands (private: the public API is the typed methods) ------------------------

    def _execute(self, timeout: float | None = None, **op) -> pb.CommandResult:
        """Runs one protocol command and returns the result as data (the outcome may be ``error``).
        ``op`` is exactly one ``Command`` case: ``_execute(tap=pb.Tap(selector=...))``."""
        ((name, message),) = op.items()
        self._ensure_usable(f"execute {name}")
        command = pb.Command(timeout_ms=int(_or(timeout, self.timeouts.action) * 1000))
        getattr(command, name).CopyFrom(message)
        with mapped_errors(self.serial):
            return self.client.device_stub.Execute(
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

    def _execute_or_raise(
        self, timeout: float | None = None, selector: Selector | None = None, **op
    ) -> pb.CommandResult:
        """``_execute`` that raises ``CommandError`` (naming ``selector``) instead of returning an error outcome."""
        result = self._execute(timeout, **op)
        if result.HasField("error"):
            (name,) = op
            raise CommandError._from_result(
                result, name, self.serial, selector.render() if selector else None
            )
        return result

    # --- selector contexts --------------------------------------------------------------------

    def app(self, package_name: str) -> App:
        """Bind lifecycle and selectors to ``package_name``."""
        return App(self, package_name)

    def info(self) -> DeviceInfo:
        """API level, model, display size and the package owning the focused window."""
        return _proto.device_info(self._execute_or_raise(device_info=pb.DeviceInfoQuery()).device_info)

    def press_back(self) -> None:
        """Send ``KEYCODE_BACK``."""
        self.press_key(KEYCODE_BACK)

    def press_home(self) -> None:
        """Send ``KEYCODE_HOME``."""
        self.press_key(KEYCODE_HOME)

    def press_key(self, key_code: int) -> None:
        """Injects one Android key code (a mutation: never replayed on transport loss)."""
        self._execute_or_raise(press_key=pb.PressKey(key_code=key_code))

    def open_notifications(self) -> None:
        """Open the notification shade (the system's accessibility action).

        Only whether the system accepted it is reported: wait for what the test needs in the
        shade, and ``press_back`` closes it.
        """
        self._execute_or_raise(open_system_panel=pb.OpenSystemPanel(panel=pb.SYSTEM_PANEL_NOTIFICATIONS))

    def open_quick_settings(self) -> None:
        """Open the quick settings panel; otherwise as ``open_notifications``."""
        self._execute_or_raise(open_system_panel=pb.OpenSystemPanel(panel=pb.SYSTEM_PANEL_QUICK_SETTINGS))

    def type_text(self, value: str, timeout: float | None = None) -> None:
        """Type ``value`` as real key events into whatever has input focus now.

        No target and no click (``Element.type_text`` taps first). Unsupported characters are
        rejected before any input with ``INVALID_REQUEST``/``UNSUPPORTED_CHARACTERS``; otherwise
        it reports whether every key event was accepted. Where the characters landed is for the
        test to assert.
        """
        self._execute_or_raise(timeout, type_text=pb.TypeText(text=value))

    def screenshot(self, timeout: float | None = None) -> Screenshot:
        """A PNG ``Screenshot`` of the screen, with its size. The server verifies the bytes
        against the driver's checksum and the client checks the returned sha256 again. Keep it
        with ``Screenshot.save(path)`` or use ``Screenshot.bytes`` directly."""
        self._ensure_usable("screenshot")
        request = pb.ScreenshotRequest(
            client_connection_id=self.owner_connection.id,
            attached_device_id=self.attached_device_id,
            timeout_ms=int(_or(timeout, self.timeouts.lifecycle) * 1000),
        )
        with mapped_errors(self.serial):
            response = self.client.device_stub.Screenshot(
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
        return Screenshot._png(png)

    def start_recording(
        self, *, video: bool = True, audio_source: str | None = None, max_seconds: int | None = None
    ) -> None:
        """Begin recording this device with scrcpy on the server host.

        The server runs ``tap start --scrcpy PATH`` (default ``scrcpy`` on its ``PATH``).
        Defaults to silent H.264 MP4 video. Set ``audio_source`` to ``output`` (Android 11+,
        mutes device playback), ``playback`` (Android 13+, retains local audio but apps may
        opt out) or ``mic``. With both tracks the result is Matroska; ``video=False`` records
        Opus audio only. Video is limited to 30 seconds/16 MiB, audio only to 60 seconds/3 MiB,
        and ``max_seconds`` ends the capture early. One recording per attached device; detach
        discards an unfinished one. Capture begins shortly after this returns, and a capture
        the device refuses is reported by ``stop_recording``.
        """
        self._ensure_usable("start_recording")
        limit = 30 if video else 60
        if (not video and audio_source is None) or (audio_source is not None and audio_source not in ("output", "playback", "mic")):
            raise ValueError("select video or an audio_source of output/playback/mic")
        duration = limit if max_seconds is None else max_seconds
        if not 1 <= duration <= limit:
            raise ValueError(f"max_seconds must be 1..{limit}")
        with mapped_errors(self.serial):
            self.client.device_stub.StartRecording(
                pb.StartRecordingRequest(
                    client_connection_id=self.owner_connection.id,
                    attached_device_id=self.attached_device_id,
                    video=video,
                    audio_source=audio_source or "",
                    max_seconds=duration,
                ), timeout=30,
            )

    def stop_recording(self) -> Recording:
        """Stop and return a checksummed, bounded MP4, Matroska or Opus artifact."""
        self._ensure_usable("stop_recording")
        with mapped_errors(self.serial):
            response = self.client.device_stub.StopRecording(
                pb.StopRecordingRequest(
                    client_connection_id=self.owner_connection.id,
                    attached_device_id=self.attached_device_id,
                ), timeout=30,
            )
        if hashlib.sha256(response.data).hexdigest() != response.sha256:
            raise TapError(f"recording of {self.serial} failed its checksum")
        return Recording(response.data, response.format)

    def dump_hierarchy(self, timeout: float | None = None) -> Hierarchy:
        """The diagnostic accessibility ``Hierarchy``. Never used by selectors; keep it out of
        assertions."""
        return Hierarchy(
            self._execute_or_raise(
                _or(timeout, self.timeouts.lifecycle), dump_hierarchy=pb.DumpHierarchy()
            ).text
        )

    def screen_snapshot(self, timeout: float | None = None, *, selector_candidates: bool = False) -> ScreenSnapshot:
        """The visible screen as ref-addressed ``ScreenNode``s, each with a selector the daemon
        found to match only that node — for exploring an app, not for tests (it is built from
        the diagnostic hierarchy dump). Refs stay stable across snapshots of this attached
        device, and each node says whether it was added since the previous snapshot; nodes
        that are gone are in ``removed``. Act on a node through its selector
        (``device.screen.element(node.selector)``) or ``resolve_ref``: the device still requires
        exactly one match at action time. With ``selector_candidates`` each node also lists every
        selector that matched only it (``ScreenNode.candidates``), for an inspector that lets a
        person choose.

        Experimental: not covered by the compatibility promise; it may change in any release."""
        self._ensure_usable("screen_snapshot")
        request = pb.ScreenSnapshotRequest(
            client_connection_id=self.owner_connection.id,
            attached_device_id=self.attached_device_id,
            timeout_ms=int(_or(timeout, self.timeouts.lifecycle) * 1000),
            selector_candidates=selector_candidates,
        )
        with mapped_errors(self.serial):
            return _proto.screen_snapshot(
                self.client.device_stub.ScreenSnapshot(
                    request, timeout=request.timeout_ms / 1000 + RPC_DEADLINE_SLACK
                )
            )

    def resolve_ref(self, ref: str) -> Selector:
        """The selector behind a ``ScreenNode.ref`` (``e7`` or ``@e7``) of any snapshot of this
        attached device. Raises ``ServerError`` with reason ``UNKNOWN_REF`` for a ref the daemon
        never issued or whose node has gone, and ``REF_NOT_ADDRESSABLE`` for a node without a
        selector.

        Experimental: not covered by the compatibility promise; it may change in any release."""
        self._ensure_usable("resolve_ref")
        with mapped_errors(self.serial):
            response = self.client.device_stub.ResolveRef(
                pb.ResolveRefRequest(
                    client_connection_id=self.owner_connection.id,
                    attached_device_id=self.attached_device_id,
                    ref=ref,
                ),
                timeout=30,
            )
        return Selector(response.selector)

    def driver_log(self) -> DriverLog:
        """The driver instrumentation's recent output."""
        self._ensure_usable("driver_log")
        with mapped_errors(self.serial):
            return DriverLog(
                self.client.device_stub.DriverLog(
                    pb.DriverLogRequest(
                        client_connection_id=self.owner_connection.id,
                        attached_device_id=self.attached_device_id,
                    ),
                    timeout=30,
                ).lines
            )

    def capture(self, timeout: float = 30.0) -> Capture:
        """Takes a screenshot, the hierarchy, the device info and the driver log at once, each
        within ``timeout`` seconds, and returns them as one ``Capture``. Never raises for the
        device: a part that fails or runs out of time is None in the result, with its cause in
        ``Capture.failures``. Use it wherever a test wants evidence (after a step, in an
        ``except``); the pytest plugin calls it for every failed test."""
        parts: dict[str, Callable[[], object]] = {
            Capture.SCREENSHOT: lambda: self.screenshot(timeout),
            Capture.HIERARCHY: lambda: self.dump_hierarchy(timeout),
            Capture.DEVICE_INFO: self.info,
            Capture.DRIVER_LOG: self.driver_log,
        }
        produced: dict[str, object] = {}
        failures: dict[str, BaseException] = {}
        # A straggler is abandoned, not joined: its own gRPC deadline ends it later.
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=len(parts), thread_name_prefix="tap-capture")
        try:
            futures = {name: pool.submit(produce) for name, produce in parts.items()}
            concurrent.futures.wait(futures.values(), timeout=timeout)
            for name, future in futures.items():
                if not future.done():
                    failures[name] = TimeoutError(f"{name} of {self.serial} not produced within {timeout}s")
                elif (error := future.exception()) is not None:
                    failures[name] = error
                else:
                    produced[name] = future.result()
        finally:
            pool.shutdown(wait=False, cancel_futures=True)
        return Capture(
            self.serial,
            produced.get(Capture.SCREENSHOT),  # type: ignore[arg-type]
            produced.get(Capture.HIERARCHY),  # type: ignore[arg-type]
            produced.get(Capture.DEVICE_INFO),  # type: ignore[arg-type]
            produced.get(Capture.DRIVER_LOG),  # type: ignore[arg-type]
            failures,
        )

    def _await_app_visible(self, package_name: str, timeout: float | None) -> None:
        """``App.await_visible``."""
        timeout = self.timeouts.wait if timeout is None else timeout
        result = self._execute(
            timeout, wait_app_visible=pb.WaitAppVisible(package_name=package_name)
        )
        if result.HasField("error"):
            if result.error.code != pb.ERR_WAIT_TIMEOUT:
                raise CommandError._from_result(result, "wait_app_visible", self.serial, None)
            try:
                last = f"currentPackage={self.info().current_package}"
            except Exception:  # noqa: BLE001 - diagnostics only
                last = None
            raise WaitTimeoutError._from_result(
                result, f"package {package_name} to be in the foreground", self.serial, last
            )

    def _await_screen_stable(
        self,
        package_name: str,
        stable_for: float,
        timeout: float | None,
        signal: StabilitySignal,
    ) -> None:
        """``App.await_screen_stable`` and its single-signal shorthands."""
        timeout = self.timeouts.wait if timeout is None else timeout
        result = self._execute(
            timeout,
            wait_screen_stable=pb.WaitScreenStable(
                package_name=package_name,
                stable_for_ms=int(stable_for * 1000),
                signal=_proto.stability_signal(signal),
            ),
        )
        if result.HasField("error"):
            if result.error.code != pb.ERR_WAIT_TIMEOUT:
                raise CommandError._from_result(result, "wait_screen_stable", self.serial, None)
            what = {StabilitySignal.TREE: "hierarchy", StabilitySignal.PIXELS: "pixels"}.get(
                signal, "screen"
            )
            raise WaitTimeoutError._from_result(
                result, f"the {package_name} {what} to stay unchanged for {stable_for:g}s", self.serial
            )

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

    @property
    def detached(self) -> bool:
        """True once ``detach()`` ran; every later call on this device is rejected."""
        return self._detached

    def detach(self) -> str | None:
        """Detaches the device. Returns the quarantine detail when the device could not be left
        clean (the server keeps it out of circulation), else None. Idempotent once it
        succeeded; a failed detach may be retried."""
        if self._detached:
            return None
        with mapped_errors(self.serial):
            response = self.client.device_stub.Detach(
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
