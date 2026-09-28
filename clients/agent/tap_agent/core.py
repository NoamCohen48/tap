"""The one implementation behind ``tap-agent <verb>`` and ``tap-agent mcp``.

Every method is one agent step and returns the short text to show. State lives in the daemon,
not here: a session is a held connection named after it (``TapClient.connect(name, hold=...)``),
so any process — the next CLI call, an MCP server — resumes it by name.
"""

from __future__ import annotations

import contextlib
import datetime
import os
import pathlib
from collections.abc import Callable, Iterator
from typing import TypeVar

from tap_e2e import (
    CommandError,
    ConnectionEntry,
    Device,
    Direction,
    ErrorCode,
    FailureReason,
    Selector,
    ServerError,
    StabilitySignal,
    TapClient,
    TapConnection,
    TapError,
    WaitTimeoutError,
)

from . import render
from .targets import Target, UsageError, format_duration, parse_key, parse_target

DEFAULT_SESSION = "agent"
DEFAULT_IDLE = 15 * 60.0
DEFAULT_WAIT = 10.0
SETTLE_STABLE_FOR = 0.5
SETTLE_TIMEOUT = 10.0

# Exit codes shared by the CLI and reported in MCP error results.
EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NO_DAEMON = 3

T = TypeVar("T")


class AgentError(Exception):
    """A step that failed, with the text to show and the CLI exit code."""

    def __init__(self, message: str, exit_code: int = EXIT_FAILED):
        super().__init__(message)
        self.exit_code = exit_code


APP_ACTIONS = ("launch", "cold-launch", "stop", "clear", "install", "uninstall", "grant", "running")


def _hint(error: TapError) -> str:
    """The error's message, plus what the agent can do about it when that is clear."""
    message = str(error)
    if isinstance(error, CommandError):
        if error.code is ErrorCode.NOT_FOUND:
            return f"{message}\nnothing matches now; run `snapshot` to see the screen"
        if error.code is ErrorCode.AMBIGUOUS:
            return f"{message}\nseveral nodes match; use a ref from `snapshot` or a narrower selector"
    if isinstance(error, ServerError):
        if error.reason is FailureReason.UNKNOWN_REF:
            return f"{message}\nthat ref is not on the screen any more; run `snapshot` for current refs"
        if error.reason is FailureReason.REF_NOT_ADDRESSABLE:
            return f"{message}\nthis node has no selector; target a nearby node or write a selector"
    return message


class Agent:
    """Agent steps against the daemon, for one session name.

    ``client`` is used as given (tests pass one); otherwise one is created on first use from
    ``TAP_SERVER``/``daemon.json`` and closed by :meth:`close`. ``out_dir`` is where screenshots
    and captures go (default ``.tap/agent`` under the working directory, or ``TAP_AGENT_DIR``).
    """

    def __init__(
        self,
        session: str | None = None,
        *,
        client: TapClient | None = None,
        out_dir: str | os.PathLike[str] | None = None,
    ):
        self.session = session or os.environ.get("TAP_AGENT_SESSION") or DEFAULT_SESSION
        self._client = client
        self._owns_client = client is None
        self.out_dir = pathlib.Path(out_dir or os.environ.get("TAP_AGENT_DIR") or ".tap/agent")

    # --- plumbing -------------------------------------------------------------------------------

    @property
    def client(self) -> TapClient:
        if self._client is None:
            try:
                self._client = TapClient.create()
            except TapError as error:
                raise AgentError(f"{error}", EXIT_NO_DAEMON) from error
        return self._client

    def close(self) -> None:
        if self._client is not None and self._owns_client:
            self._client.close()
            self._client = None

    @contextlib.contextmanager
    def _errors(self) -> Iterator[None]:
        try:
            yield
        except AgentError:
            raise
        except UsageError as error:
            raise AgentError(str(error), EXIT_USAGE) from error
        except ServerError as error:
            if error.code in ("UNAVAILABLE", "DEADLINE_EXCEEDED") and error.reason is FailureReason.UNSPECIFIED:
                raise AgentError(
                    f"tap daemon unreachable ({error.code}); is it running? `tap start`",
                    EXIT_NO_DAEMON,
                ) from error
            raise AgentError(_hint(error)) from error
        except TapError as error:
            raise AgentError(_hint(error)) from error
        except OSError as error:
            raise AgentError(str(error)) from error

    def _run(self, step: Callable[[], T]) -> T:
        with self._errors():
            return step()

    def _held(self) -> ConnectionEntry | None:
        return next(
            (c for c in self.client.connections() if c.hold is not None and c.name == self.session),
            None,
        )

    def _connection(self) -> TapConnection:
        entry = self._held()
        if entry is None:
            raise AgentError(f"no session {self.session!r}; start one with `attach <serial> <package>`")
        return TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold)

    def _device(self, serial: str | None) -> Device:
        devices = self._connection().attached_devices()
        if serial:
            for device in devices:
                if device.serial == serial:
                    return device
            raise AgentError(f"{serial} is not attached to session {self.session!r}; `attach {serial} <package>`")
        if not devices:
            raise AgentError(f"session {self.session!r} has no device; `attach <serial> <package>`")
        if len(devices) > 1:
            serials = ", ".join(d.serial for d in devices)
            raise AgentError(f"session {self.session!r} has several devices ({serials}); pass --device", EXIT_USAGE)
        return devices[0]

    @staticmethod
    def _selector(device: Device, target: str | Target) -> tuple[Selector, str]:
        parsed = parse_target(target) if isinstance(target, str) else target
        if parsed.ref:
            return device.resolve_ref(parsed.ref), parsed.describe()
        assert parsed.selector is not None
        return parsed.selector, parsed.describe()

    def _settled(self, device: Device, level: str = render.DEFAULT, full: bool = False, timeout: float = SETTLE_TIMEOUT) -> str:
        """Waits for the focused window's hierarchy to stop changing, then diffs a new snapshot
        against the previous one. A screen that keeps changing is reported, not an error."""
        note = ""
        try:
            package = device.info().current_package
            device.await_screen_stable(SETTLE_STABLE_FOR, timeout, package_name=package, signal=StabilitySignal.TREE)
        except WaitTimeoutError:
            note = f"(screen still changing after {format_duration(timeout)})\n"
        return note + render.diff_text(device.screen_snapshot(), level, full)

    def _act(self, device_serial: str | None, target: str, verb: str, action: Callable, settle: bool) -> str:
        def step() -> str:
            device = self._device(device_serial)
            selector, described = self._selector(device, target)
            action(device.element(selector))
            text = f"{verb} {described}"
            return f"{text}\n{self._settled(device)}" if settle else text

        return self._run(step)

    # --- sessions -------------------------------------------------------------------------------

    def attach(
        self,
        serial: str,
        package: str,
        idle: float = DEFAULT_IDLE,
        launch: str | None = None,
        wait_for_device: float = 0,
    ) -> str:
        """Attaches ``serial`` for ``package`` to the session, creating the session (a held
        connection with an ``idle`` timeout) when it does not exist. ``launch``: None, "launch"
        or "cold" (force-stop first)."""
        if launch not in (None, "launch", "cold"):
            raise AgentError(f"launch must be launch or cold, not {launch!r}", EXIT_USAGE)

        def step() -> str:
            entry = self._held()
            created = entry is None
            if entry is None:
                connection = self.client.connect(self.session, hold=idle)
            else:
                connection = TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold)
                attached = next((d for d in entry.attached_devices if d.serial == serial), None)
                if attached is not None:
                    if attached.aut_package != package:
                        raise AgentError(
                            f"{serial} is attached to session {self.session!r} for {attached.aut_package}; "
                            "`release` first to change the app"
                        )
                    return f"{serial} is already attached to session {self.session!r} ({package})"
            try:
                device = connection.attach_device(serial, package, wait_for_device=wait_for_device)
            except BaseException:
                if created:  # leave no empty session behind
                    with contextlib.suppress(Exception):
                        connection.close()
                raise
            lines = [
                f"attached {serial} ({package}) to session {self.session!r}, "
                f"idle timeout {format_duration(connection.hold or idle)}"
            ]
            if launch == "launch":
                device.app().launch()
                lines.append(f"launched {package}")
            elif launch == "cold":
                device.app().cold_launch()
                lines.append(f"cold-launched {package}")
            return "\n".join(lines)

        return self._run(step)

    def sessions(self) -> str:
        """Held connections (agent sessions) with their idle time and devices."""

        def step() -> str:
            entries = self.client.connections()
            held = sorted((c for c in entries if c.hold is not None), key=lambda c: c.name)
            lines = []
            for c in held:
                devices = ", ".join(f"{d.serial} ({d.aut_package})" for d in c.attached_devices) or "no device"
                lines.append(
                    f"{c.name}  idle {format_duration(round(c.idle))} of {format_duration(c.hold or 0)}  {devices}"
                )
            others = len(entries) - len(held)
            if others:
                lines.append(f"({others} other connection{'s' if others > 1 else ''}, e.g. test runs)")
            return "\n".join(lines) or "no sessions"

        return self._run(step)

    def release(self) -> str:
        """Ends the session: every device is detached and the name is free again."""

        def step() -> str:
            entry = self._held()
            if entry is None:
                return f"no session {self.session!r}"
            TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold).close()
            serials = ", ".join(d.serial for d in entry.attached_devices)
            return f"released session {self.session!r}" + (f" (detached {serials})" if serials else "")

        return self._run(step)

    def devices(self) -> str:
        """Every device ADB lists, its state, and the session holding it."""

        def step() -> str:
            names = {c.id: c.name for c in self.client.connections()}
            lines = []
            for d in self.client.devices():
                line = f"{d.serial}  {d.state.name}"
                if d.client_connection_id:
                    line += f"  held by {names.get(d.client_connection_id, d.client_connection_id)!r}"
                if d.quarantine_reason:
                    line += f"  ({d.quarantine_reason})"
                lines.append(line)
            return "\n".join(lines) or "no devices (adb devices lists none)"

        return self._run(step)

    # --- the screen -----------------------------------------------------------------------------

    def snapshot(self, device: str | None = None, level: str = render.DEFAULT) -> str:
        """The visible screen, one node per line with its ref."""
        return self._run(lambda: render.snapshot_text(self._device(device).screen_snapshot(), level))

    def settle(self, device: str | None = None, full: bool = False, timeout: float = SETTLE_TIMEOUT) -> str:
        """Waits for the screen to stop changing, then shows what changed since the last snapshot."""
        return self._run(lambda: self._settled(self._device(device), full=full, timeout=timeout))

    def screenshot(self, device: str | None = None, out: str | os.PathLike[str] | None = None) -> pathlib.Path:
        """Saves a PNG and returns its path."""

        def step() -> pathlib.Path:
            d = self._device(device)
            path = pathlib.Path(out) if out else self.out_dir / f"{d.serial}-{_stamp()}.png"
            return d.screenshot().save(path)

        return self._run(step)

    def capture(self, device: str | None = None, out: str | os.PathLike[str] | None = None) -> str:
        """Screenshot, hierarchy, device info and driver log into a directory."""

        def step() -> str:
            d = self._device(device)
            directory = pathlib.Path(out) if out else self.out_dir / f"capture-{d.serial}-{_stamp()}"
            captured = d.capture()
            lines = [str(p) for p in captured.save_to(directory)]
            lines += [f"{name} failed: {error}" for name, error in captured.failures.items()]
            return "\n".join(lines)

        return self._run(step)

    # --- actions --------------------------------------------------------------------------------

    def tap(self, target: str, device: str | None = None, long: bool = False, settle: bool = False) -> str:
        if long:
            return self._act(device, target, "long-tapped", lambda e: e.long_tap(), settle)
        return self._act(device, target, "tapped", lambda e: e.tap(), settle)

    def fill(self, target: str, text: str, device: str | None = None, settle: bool = False) -> str:
        """Replaces the node's text (accessibility SET_TEXT, no key events)."""
        return self._act(device, target, "filled", lambda e: e.set_text(text), settle)

    def clear(self, target: str, device: str | None = None, settle: bool = False) -> str:
        return self._act(device, target, "cleared", lambda e: e.clear_text(), settle)

    def scroll(self, target: str, direction: str, device: str | None = None, settle: bool = False) -> str:
        """One scroll gesture towards ``direction``'s content edge (``down`` reveals content below)."""
        resolved = _direction(direction)
        return self._act(device, target, f"scrolled {direction}", lambda e: e.scroll(resolved), settle)

    def swipe(self, target: str, direction: str, device: str | None = None, settle: bool = False) -> str:
        """One swipe across the node, the finger moving towards ``direction``."""
        resolved = _direction(direction)
        return self._act(device, target, f"swiped {direction}", lambda e: e.swipe(resolved), settle)

    def type(self, text: str, device: str | None = None, settle: bool = False) -> str:
        """Types real key events into whatever has input focus."""

        def step() -> str:
            d = self._device(device)
            d.type_text(text)
            return f"typed {len(text)} characters\n{self._settled(d)}" if settle else f"typed {len(text)} characters"

        return self._run(step)

    def key(self, name: str, device: str | None = None, settle: bool = False) -> str:
        """Presses a key: back, home, enter, tab, delete, ... or an Android key code."""

        def step() -> str:
            code = parse_key(name)
            d = self._device(device)
            d.press_key(code)
            return f"pressed {name}\n{self._settled(d)}" if settle else f"pressed {name}"

        return self._run(step)

    def wait(self, target: str, device: str | None = None, state: str = "visible", timeout: float = DEFAULT_WAIT) -> str:
        """Waits on the device until the target is visible, gone, or matches exactly one node."""
        if state not in ("visible", "gone", "one"):
            raise AgentError(f"state must be visible, gone or one, not {state!r}", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            selector, described = self._selector(d, target)
            waiting = d.wait(selector, timeout)
            {"visible": waiting.visible, "gone": waiting.gone, "one": waiting.one}[state]()
            return f"{described} is {'exactly one node' if state == 'one' else state}"

        return self._run(step)

    def app(
        self,
        action: str,
        argument: str | None = None,
        device: str | None = None,
        package: str | None = None,
    ) -> str:
        """App lifecycle for the session's app (or ``package``): launch [activity], cold-launch
        [activity], stop, clear, install APK, uninstall, grant PERMISSION, running."""
        if action not in APP_ACTIONS:
            raise AgentError(f"unknown app action {action!r} (known: {', '.join(APP_ACTIONS)})", EXIT_USAGE)
        if action in ("install", "grant") and not argument:
            raise AgentError(f"app {action} needs an argument ({'APK path' if action == 'install' else 'permission'})", EXIT_USAGE)

        def step() -> str:
            app = self._device(device).app(package)
            name = app.package_name
            if action == "launch":
                app.launch(argument)
                return f"launched {name}"
            if action == "cold-launch":
                process = app.cold_launch(argument)
                return f"cold-launched {name} (pid {process.pid})"
            if action == "stop":
                app.force_stop()
                return f"stopped {name}"
            if action == "clear":
                app.clear_data()
                return f"cleared data of {name}"
            if action == "install":
                app.install(argument)  # type: ignore[arg-type]
                return f"installed {argument}"
            if action == "uninstall":
                app.uninstall()
                return f"uninstalled {name}"
            if action == "grant":
                app.grant_permission(argument)  # type: ignore[arg-type]
                return f"granted {argument} to {name}"
            return f"{name} is {'running' if app.is_running() else 'not running'}"

        return self._run(step)


def _direction(name: str) -> Direction:
    try:
        return Direction[name.strip().upper()]
    except KeyError:
        raise AgentError(f"direction must be up, down, left or right, not {name!r}", EXIT_USAGE) from None


def _stamp() -> str:
    return datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
