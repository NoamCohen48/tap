"""The one implementation behind ``tap-agent <verb>`` and ``tap-agent mcp``.

Every method is one agent step and returns the short text to show. State lives in the daemon,
not here: a session is a held connection named after it (``TapClient.connect(name, hold=...)``),
so any process — the next CLI call, an MCP server — resumes it by name.
"""

from __future__ import annotations

import contextlib
import datetime
import json
import math
import os
import pathlib
from collections.abc import Callable, Iterator, Sequence
from typing import TypeVar

from tap_e2e import (
    CommandError,
    ConnectionEntry,
    Device,
    DisplayRotation,
    Direction,
    ErrorCode,
    FailureReason,
    Element,
    LocationAccuracy,
    Long,
    MatchMode,
    Notification,
    Orientation,
    PermissionChoice,
    ServerError,
    StandardAction,
    TapClient,
    TapConnection,
    TapError,
    WaitTimeoutError,
)

from . import render
from .targets import Target, UsageError, format_duration, parse_key, parse_target

DEFAULT_SESSION = "agent"
# `export`'s document format; bumped only for a change a reader must know about.
EXPORT_FORMAT = "tap-events/1"
DEFAULT_IDLE = 15 * 60.0
DEFAULT_WAIT = 10.0
SETTLE_STABLE_FOR = 0.5
SETTLE_TIMEOUT = 10.0
PANELS = ("notifications", "quick-settings")
"""The system panels `panel` opens."""
ROTATIONS = ("portrait", "landscape", "natural", "left", "upside-down", "right", "auto")
"""What `rotate` takes: a geometry, an exact rotation, or `auto` (back to the sensor)."""
SCREEN_ACTIONS = ("state", "on", "off", "unlock")
"""What `screen` does: report, wake, sleep, or wake and dismiss a keyguard without a PIN."""
PERMISSION_CHOICES = tuple(choice.name.lower().replace("_", "-") for choice in PermissionChoice)
"""The permission-dialog buttons `permission` can press."""
PINCHES = ("open", "close")
STANDARD_ACTIONS = tuple(action.name.lower().replace("_", "-") for action in StandardAction)
ACCURACIES = tuple(accuracy.name.lower() for accuracy in LocationAccuracy)
EXTRA_TYPES = ("string", "int", "long", "float", "bool")


def parse_extras(items: Sequence[str]) -> dict[str, str | bool | int | float | Long]:
    """Launch extras from ``KEY=VALUE`` (a string) or ``KEY:TYPE=VALUE`` with TYPE one of
    string, int (32-bit), long, float or bool, as ``am start --es/--ei/--el/--ef/--ez`` put them."""
    extras: dict[str, str | bool | int | float | Long] = {}
    for item in items:
        name, eq, value = item.partition("=")
        key, colon, kind = name.partition(":")
        kind = kind.strip().lower() if colon else "string"
        key = key.strip()
        if not eq or not key or kind not in EXTRA_TYPES:
            raise AgentError(f"an extra is KEY=VALUE or KEY:TYPE=VALUE (TYPE: {', '.join(EXTRA_TYPES)}), not {item!r}", EXIT_USAGE)
        if key in extras:
            raise AgentError(f"extra {key!r} is given twice", EXIT_USAGE)
        try:
            if kind == "string":
                extras[key] = value
            elif kind == "bool":
                if value.strip().lower() not in ("true", "false"):
                    raise ValueError
                extras[key] = value.strip().lower() == "true"
            elif kind == "float":
                number = float(value)
                if not math.isfinite(number):
                    raise ValueError
                extras[key] = number
            else:
                whole = int(value.strip())
                if kind == "int" and not -(2**31) <= whole < 2**31:
                    raise ValueError
                extras[key] = Long(whole) if kind == "long" else whole
        except ValueError:
            raise AgentError(f"extra {key!r}: {value!r} is not a {kind}", EXIT_USAGE) from None
    return extras
KEYBOARD_ACTIONS = ("state", "hide")
"""What `keyboard` does: report whether a soft keyboard shows, or hide it."""
CONDITIONS = (
    "animations", "dark-mode", "font-scale", "density", "airplane-mode", "wifi", "mobile-data", "locale",
    "stay-awake", "high-contrast-text", "color-inversion", "bold-text",
)
"""The device conditions `condition` reads or changes (restored on release)."""

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


NOTIFICATION_ACTIONS = ("list", "await", "open", "dismiss")

APP_ACTIONS = (
    "launch", "cold-launch", "foreground", "background", "open-link",
    "stop", "clear", "install", "uninstall", "grant", "revoke", "granted", "running", "locale",
)


def _hint(error: TapError) -> str:
    """The error's message, plus what the agent can do about it when that is clear."""
    message = str(error)
    if isinstance(error, CommandError):
        if error.code is ErrorCode.NOT_FOUND:
            return f"{message}\nnothing matches now; run `snapshot` to see the screen"
        if error.code is ErrorCode.AMBIGUOUS:
            return f"{message}\nseveral nodes match; use a ref from `snapshot` or a narrower selector"
        if error.code is ErrorCode.NOT_INTERACTABLE and error.detail == "OBSCURED":
            return f"{message}\nanother window covers the node; close it (often `key back`) or scroll the node clear"
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
            raise AgentError(f"no session {self.session!r}; start one with `attach <serial>`")
        return TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold)

    def _device(self, serial: str | None) -> Device:
        devices = self._connection().attached_devices()
        if serial:
            for device in devices:
                if device.serial == serial:
                    return device
            raise AgentError(f"{serial} is not attached to session {self.session!r}; `attach {serial}`")
        if not devices:
            raise AgentError(f"session {self.session!r} has no device; `attach <serial>`")
        if len(devices) > 1:
            serials = ", ".join(d.serial for d in devices)
            raise AgentError(f"session {self.session!r} has several devices ({serials}); pass --device", EXIT_USAGE)
        return devices[0]

    @staticmethod
    def _element(device: Device, target: str | Target) -> tuple[Element, str]:
        """The target as an element: a ref's selector on the whole screen (it already names the
        node's package when that is needed), a written selector in ``pkg``'s nodes or else on
        the whole screen."""
        parsed = parse_target(target) if isinstance(target, str) else target
        if parsed.ref:
            return device.screen.element(device.resolve_ref(parsed.ref)), parsed.describe()
        assert parsed.selector is not None
        scope = device.app(parsed.package) if parsed.package else device.screen
        return scope.element(parsed.selector), parsed.describe()

    def _settled(self, device: Device, level: str = render.DEFAULT, full: bool = False, timeout: float = SETTLE_TIMEOUT) -> str:
        """Waits for the focused window's hierarchy to stop changing, then diffs a new snapshot
        against the previous one. A screen that keeps changing is reported, not an error."""
        note = ""
        try:
            device.app(device.info().current_package).await_settled(SETTLE_STABLE_FOR, timeout)
        except WaitTimeoutError:
            note = f"(screen still changing after {format_duration(timeout)})\n"
        return note + render.diff_text(device.screen_snapshot(), level, full)

    def _act(self, device_serial: str | None, target: str, verb: str, action: Callable, settle: bool) -> str:
        def step() -> str:
            device = self._device(device_serial)
            element, described = self._element(device, target)
            action(element)
            text = f"{verb} {described}"
            return f"{text}\n{self._settled(device)}" if settle else text

        return self._run(step)

    # --- sessions -------------------------------------------------------------------------------

    def attach(self, serial: str, idle: float = DEFAULT_IDLE, wait_for_device: float = 0) -> str:
        """Attaches ``serial`` to the session, creating the session (a held connection with an
        ``idle`` timeout) when it does not exist."""

        def step() -> str:
            entry = self._held()
            created = entry is None
            if entry is None:
                connection = self.client.connect(self.session, hold=idle)
            else:
                connection = TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold)
                attached = next((d for d in entry.attached_devices if d.serial == serial), None)
                if attached is not None:
                    return f"{serial} is already attached to session {self.session!r}"
            try:
                connection.attach_device(serial, wait_for_device=wait_for_device)
            except BaseException:
                if created:  # leave no empty session behind
                    with contextlib.suppress(Exception):
                        connection.close()
                raise
            return (
                f"attached {serial} to session {self.session!r}, "
                f"idle timeout {format_duration(connection.hold or idle)}"
            )

        return self._run(step)

    def sessions(self) -> str:
        """Held connections (agent sessions) with their idle time and devices."""

        def step() -> str:
            entries = self.client.connections()
            held = sorted((c for c in entries if c.hold is not None), key=lambda c: c.name)
            lines = []
            for c in held:
                devices = ", ".join(d.serial for d in c.attached_devices) or "no device"
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

    def export(self, out: str | os.PathLike[str] | None = None) -> str:
        """The session's event log as JSON (``EXPORT_FORMAT``): every device call it made, in
        order, with its outcome. Turning it into a test, in whatever language, is the user's job.
        With ``out``, writes the file and says so; otherwise returns the JSON."""

        def step() -> str:
            log = self._connection().event_log()
            document = {
                "format": EXPORT_FORMAT,
                "session": self.session,
                "exported_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "dropped": log.dropped,
                "events": [event.to_dict() for event in log.events],
            }
            text = json.dumps(document, indent=2, ensure_ascii=False)
            if out is None:
                return text
            path = pathlib.Path(out)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text + "\n", encoding="utf-8")
            failed = sum(1 for e in log.events if not e.ok)
            note = f" ({failed} failed)" if failed else ""
            dropped = f"; {log.dropped} older events were dropped" if log.dropped else ""
            return f"wrote {len(log.events)} events{note} to {path}{dropped}"

        return self._run(step)

    # --- actions --------------------------------------------------------------------------------

    def tap(
        self, target: str, device: str | None = None, long: bool = False, settle: bool = False, double: bool = False
    ) -> str:
        if long and double:
            raise AgentError("tap is either long or double, not both", EXIT_USAGE)
        if long:
            return self._act(device, target, "long-tapped", lambda e: e.long_tap(), settle)
        if double:
            return self._act(device, target, "double-tapped", lambda e: e.double_tap(), settle)
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

    def fling(self, target: str, direction: str, device: str | None = None, settle: bool = False) -> str:
        """One fast swipe towards ``direction``'s content edge, as for scroll; content may keep moving."""
        resolved = _direction(direction)
        return self._act(device, target, f"flung {direction}", lambda e: e.fling(resolved), settle)

    def pinch(self, target: str, how: str, percent: int = 80, device: str | None = None, settle: bool = False) -> str:
        """Two fingers moving apart (``open``, zoom in) or together (``close``) across ``percent`` of the node."""
        how = how.strip().lower()
        if how not in PINCHES:
            raise AgentError(f"pinch must be open or close, not {how!r}", EXIT_USAGE)
        if not 1 <= percent <= 100:
            raise AgentError(f"percent must be 1..100, not {percent}", EXIT_USAGE)
        if how == "open":
            return self._act(device, target, f"pinched open {percent}%", lambda e: e.pinch_open(percent), settle)
        return self._act(device, target, f"pinched closed {percent}%", lambda e: e.pinch_close(percent), settle)

    def submit(self, target: str, device: str | None = None, settle: bool = False) -> str:
        """Runs a focused text field's keyboard action (Search, Go, Send, Done, ...) as the
        keyboard's action key does. API 30+."""
        return self._act(device, target, "submitted", lambda e: e.ime_action(), settle)

    def action(
        self, target: str, name: str | None = None, device: str | None = None, custom: bool = False, settle: bool = False
    ) -> str:
        """Without ``name``: lists the accessibility actions the node offers (and its range). With
        one: performs that standard action (expand, collapse, dismiss, scroll-forward, ...), or with
        ``custom`` the custom action labelled ``name``, as a screen reader does (no touch)."""
        if name is None:
            if custom:
                raise AgentError("action --custom needs the action's label", EXIT_USAGE)

            def listing() -> str:
                d = self._device(device)
                element, described = self._element(d, target)
                snapshot = element.snapshot()
                offered = [a.name.lower().replace("_", "-") for a in snapshot.actions] + [repr(c) for c in snapshot.custom_actions]
                text = f"{described} offers: {', '.join(offered) if offered else 'no actions Tap can perform'}"
                if snapshot.range is not None:
                    r = snapshot.range
                    text += f"\nrange {r.min:g}..{r.max:g}, now {r.current:g} ({r.type.name.lower()})"
                return text

            return self._run(listing)
        if custom:
            return self._act(device, target, f"performed {name!r} on", lambda e: e.perform_custom_action(name), settle)
        key = name.strip().upper().replace("-", "_")
        if key not in StandardAction.__members__:
            raise AgentError(f"action takes {', '.join(STANDARD_ACTIONS)} (or --custom LABEL), not {name!r}", EXIT_USAGE)
        standard = StandardAction[key]
        return self._act(device, target, f"performed {name.strip().lower()} on", lambda e: e.perform_action(standard), settle)

    def progress(self, target: str, value: float, device: str | None = None, settle: bool = False) -> str:
        """Sets a slider (SeekBar, Slider, RatingBar) to ``value`` in its own units, exactly."""
        return self._act(device, target, f"set progress {value:g} on", lambda e: e.set_progress(value), settle)

    def drag(self, target: str, destination: str, device: str | None = None, settle: bool = False) -> str:
        """Long-press ``target``, move to the centre of ``destination`` and drop it there."""

        def step() -> str:
            d = self._device(device)
            source, described = self._element(d, target)
            onto, onto_described = self._element(d, destination)
            source.drag_to(onto.selector)
            text = f"dragged {described} onto {onto_described}"
            return f"{text}\n{self._settled(d)}" if settle else text

        return self._run(step)

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

    def panel(self, name: str, device: str | None = None, settle: bool = False) -> str:
        """Opens the notification shade (``notifications``) or ``quick-settings``; ``key back``
        closes it. Its nodes belong to ``com.android.systemui``."""
        panel = name.strip().lower().replace("_", "-")
        if panel not in PANELS:
            raise AgentError(f"panel must be {' or '.join(PANELS)}, not {name!r}", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if panel == "quick-settings":
                d.open_quick_settings()
            else:
                d.open_notifications()
            return f"opened {panel}\n{self._settled(d)}" if settle else f"opened {panel}"

        return self._run(step)

    def rotate(self, how: str, device: str | None = None, settle: bool = False) -> str:
        """Rotates the display (``portrait``/``landscape`` geometry or an exact ``natural``/``left``/
        ``upside-down``/``right``) and keeps it there, or hands it back to the sensor (``auto``).
        Reports where the display is afterwards: the app may pin its own orientation."""
        how = how.strip().lower().replace("_", "-")
        if how not in ROTATIONS:
            raise AgentError(f"rotate takes {', '.join(ROTATIONS)}, not {how!r}", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if how == "auto":
                d.unfreeze_rotation()
            elif how in ("portrait", "landscape"):
                d.set_orientation(Orientation[how.upper()])
            else:
                d.set_display_rotation(DisplayRotation[how.upper().replace("-", "_")])
            info = d.info()
            text = (
                f"rotated {how}: display {info.orientation.name.lower()}, "
                f"{info.display_rotation.name.lower().replace('_', '-')} ({info.display_width}x{info.display_height})"
            )
            return f"{text}\n{self._settled(d)}" if settle else text

        return self._run(step)

    def screen(self, action: str = "state", device: str | None = None, settle: bool = False) -> str:
        """``state`` reports whether the screen is on and the keyguard showing; ``on`` wakes it,
        ``off`` turns it off, ``unlock`` wakes it and dismisses a keyguard without a PIN, pattern
        or password (a secure one is refused, never unlocked)."""
        action = action.strip().lower()
        if action not in SCREEN_ACTIONS:
            raise AgentError(f"screen takes {', '.join(SCREEN_ACTIONS)}, not {action!r}", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if action in ("on", "unlock"):
                d.wake()
            if action == "off":
                d.sleep()
            if action == "unlock":
                d.dismiss_keyguard()
            info = d.info()
            keyguard = "not showing"
            if info.keyguard_locked:
                keyguard = "showing (secure: needs the PIN, pattern or password)" if info.keyguard_secure else "showing"
            text = f"screen {'on' if info.screen_on else 'off'}, keyguard {keyguard}"
            return f"{text}\n{self._settled(d)}" if settle and action != "state" else text

        return self._run(step)

    def keyboard(self, action: str = "state", device: str | None = None, settle: bool = False) -> str:
        """``state`` reports whether a soft keyboard is on screen; ``hide`` hides it (one Back key,
        sent only when a keyboard shows)."""
        action = action.strip().lower()
        if action not in KEYBOARD_ACTIONS:
            raise AgentError(f"keyboard takes {' or '.join(KEYBOARD_ACTIONS)}, not {action!r}", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if action == "hide":
                d.hide_keyboard()
            text = f"keyboard {'shown' if d.keyboard_shown() else 'hidden'}"
            return f"{text}\n{self._settled(d)}" if settle and action == "hide" else text

        return self._run(step)

    def condition(self, name: str | None = None, value: str | None = None, device: str | None = None) -> str:
        """Without ``name``: reports every device condition. With ``name`` (animations on|off,
        dark-mode on|off, font-scale 0.5..2.0, density DPI|reset, airplane-mode / wifi /
        mobile-data on|off, locale TAGS: the device languages as comma-separated BCP-47 tags,
        stay-awake on|off: the screen stays on while plugged in, high-contrast-text /
        color-inversion / bold-text on|off: accessibility display, bold text API 31+) and
        ``value``: changes it until release, which restores what the device had. Reports the value
        read back."""
        if name is not None:
            name = name.strip().lower().replace("_", "-")
            if name not in CONDITIONS:
                raise AgentError(f"condition takes {', '.join(CONDITIONS)}, not {name!r}", EXIT_USAGE)
        change = None if value is None else _condition_change(name or "", value.strip())

        def step() -> str:
            d = self._device(device)
            if change is not None:
                change(d)
            info = d.info()
            shown = {
                "animations": "on" if info.animations_enabled else "off",
                "dark-mode": "on" if info.dark_mode else "off",
                "font-scale": f"{info.font_scale:g}",
                "density": f"{info.density_dpi} dpi",
                "airplane-mode": "on" if info.airplane_mode else "off",
                "wifi": "on" if info.wifi_enabled else "off",
                "mobile-data": "on" if info.mobile_data_enabled else "off",
                "locale": ",".join(info.system_locales) or "unknown",
                "stay-awake": "on" if info.stay_awake else "off",
                "high-contrast-text": _switch(info.high_contrast_text),
                "color-inversion": _switch(info.color_inversion),
                "bold-text": "on" if info.bold_text else "off",
            }
            if name is None:
                return ", ".join(f"{key} {shown[key]}" for key in CONDITIONS)
            return f"{name} {shown[name]}" + (" (restored on release)" if change is not None else "")

        return self._run(step)

    def location(
        self,
        latitude: float,
        longitude: float,
        accuracy: float | None = None,
        device: str | None = None,
        altitude: float | None = None,
    ) -> str:
        """Mocks the device location at ``latitude``, ``longitude`` (``accuracy`` and ``altitude``
        in meters) until release, which ends the mock and restores the device's location setting."""
        if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise AgentError(f"location takes a latitude -90..90 and a longitude -180..180, not {latitude}, {longitude}", EXIT_USAGE)
        if accuracy is not None and not accuracy > 0:
            raise AgentError(f"accuracy must be a positive number of meters, not {accuracy}", EXIT_USAGE)
        if altitude is not None and not math.isfinite(altitude):
            raise AgentError(f"altitude must be a finite number of meters, not {altitude}", EXIT_USAGE)

        def step() -> str:
            self._device(device).set_location(latitude, longitude, accuracy_m=accuracy, altitude_m=altitude)
            shown = (
                f"{latitude:g}, {longitude:g}"
                + (f" ±{accuracy:g} m" if accuracy is not None else "")
                + (f", {altitude:g} m up" if altitude is not None else "")
            )
            return f"location mocked at {shown} (ends on release)"

        return self._run(step)

    def activity(self, device: str | None = None) -> str:
        """The activity on top of the screen (package/class), or that none is resumed (the
        keyguard is showing, or one is starting). Changes nothing."""

        def step() -> str:
            top = self._device(device).foreground_activity()
            return "no activity is resumed" if top is None else f"{top.package_name}/{top.class_name}"

        return self._run(step)

    def push(self, local: str | os.PathLike[str], device_path: str, device: str | None = None) -> str:
        """Copies the local file ``local`` to ``device_path`` on the device until release (its
        directory must exist; a file already there that Tap did not push is refused)."""
        source = pathlib.Path(local)
        if not source.is_file():
            raise AgentError(f"{source} is not a file", EXIT_USAGE)

        def step() -> str:
            self._device(device).push_file(device_path, source)
            return f"pushed {source.stat().st_size} bytes to {device_path} (removed on release)"

        return self._run(step)

    def pull(self, device_path: str, out: str | os.PathLike[str] | None = None, device: str | None = None) -> str:
        """Copies the device file ``device_path`` to ``out`` (default ``.tap/agent/<name>``) and
        returns that path."""

        def step() -> str:
            d = self._device(device)
            name = device_path.rstrip("/").rsplit("/", 1)[-1] or "pulled"
            target = pathlib.Path(out) if out else self.out_dir / f"{d.serial}-{_stamp()}-{name}"
            target.parent.mkdir(parents=True, exist_ok=True)
            d.pull_file(device_path, target)
            return str(target)

        return self._run(step)

    def media(self, local: str | os.PathLike[str], name: str | None = None, device: str | None = None) -> str:
        """Adds the local photo or video ``local`` to the device's gallery (as ``name``, default
        its own name) until release, and returns its device path."""
        source = pathlib.Path(local)
        if not source.is_file():
            raise AgentError(f"{source} is not a file", EXIT_USAGE)

        def step() -> str:
            path = self._device(device).add_media(source, name)
            return f"added {path} to the gallery (removed on release)"

        return self._run(step)

    def clipboard(self, text: str | None = None, device: str | None = None) -> str:
        """Without ``text``: prints the device clipboard. With it: puts it on the clipboard."""

        def step() -> str:
            d = self._device(device)
            if text is None:
                return d.clipboard()
            d.set_clipboard(text)
            return f"clipboard set ({len(text)} characters)"

        return self._run(step)

    def toast(
        self,
        text: str | None = None,
        device: str | None = None,
        contains: bool = False,
        package: str | None = None,
        timeout: float = DEFAULT_WAIT,
    ) -> str:
        """Waits for a toast of any app (only ``package``'s when given), shown in the last 3.5 s
        or arriving within ``timeout``; ``text`` must match it exactly, or be part of it with
        ``contains``."""
        if contains and text is None:
            raise AgentError("toast --contains needs a text", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            mode = MatchMode.CONTAINS if contains else MatchMode.EXACT
            seen = d.await_toast(text, mode, package_name=package, timeout=timeout)
            return f"toast {seen.text!r} from {seen.package_name}"

        return self._run(step)

    def notification(
        self,
        action: str = "list",
        title: str | None = None,
        text: str | None = None,
        device: str | None = None,
        contains: bool = False,
        package: str | None = None,
        button: str | None = None,
        timeout: float = DEFAULT_WAIT,
        settle: bool = False,
    ) -> str:
        """The device's notifications, read as data (the shade stays closed). ``list``: every
        active one, newest first; ``await``: wait until one matches and print it; ``open``: open
        the one that matches as a tap does (or press its ``button``); ``dismiss``: swipe it away.
        ``title`` / ``text`` match exactly, or as parts with ``contains``; ``package`` narrows to
        one app. Notification access is given to the driver for the session."""
        if action not in NOTIFICATION_ACTIONS:
            raise AgentError(f"notification takes {', '.join(NOTIFICATION_ACTIONS)}, not {action!r}", EXIT_USAGE)
        if contains and title is None and text is None:
            raise AgentError("notification --contains needs a title or text", EXIT_USAGE)
        if button is not None and action != "open":
            raise AgentError("--button is for notification open", EXIT_USAGE)
        mode = MatchMode.CONTAINS if contains else MatchMode.EXACT

        def step() -> str:
            d = self._device(device)
            if action == "list":
                shown = d.notifications()
                if package is not None:
                    shown = [n for n in shown if n.package_name == package]
                return "\n".join(_notification_line(n) for n in shown) or "no notifications"
            if action == "await":
                return _notification_line(d.await_notification(title, text, mode, package_name=package, timeout=timeout))
            if action == "open":
                d.open_notification(title, text, mode, package_name=package, action=button)
                done = f"pressed {button!r} on the notification" if button else "opened the notification"
            else:
                d.dismiss_notification(title, text, mode, package_name=package)
                done = "dismissed the notification"
            return f"{done}\n{self._settled(d)}" if settle else done

        return self._run(step)

    def permission(
        self,
        choice: str | None = None,
        device: str | None = None,
        timeout: float = DEFAULT_WAIT,
        settle: bool = False,
        accuracy: str | None = None,
    ) -> str:
        """Without ``choice``: waits for a runtime-permission dialog and lists the buttons it
        offers. With one (``allow``, ``allow-foreground-only``, ``deny``, ...): presses it, after
        picking ``accuracy`` (precise or approximate) on a location dialog that offers it."""
        chosen = None
        if choice is not None:
            name = choice.strip().upper().replace("-", "_")
            if name not in PermissionChoice.__members__:
                raise AgentError(f"permission takes {', '.join(PERMISSION_CHOICES)}, not {choice!r}", EXIT_USAGE)
            chosen = PermissionChoice[name]
        picked = None
        if accuracy is not None:
            if chosen is None:
                raise AgentError("permission --accuracy needs a choice to press", EXIT_USAGE)
            if accuracy.strip().upper() not in LocationAccuracy.__members__:
                raise AgentError(f"accuracy must be {' or '.join(ACCURACIES)}, not {accuracy!r}", EXIT_USAGE)
            picked = LocationAccuracy[accuracy.strip().upper()]

        def step() -> str:
            d = self._device(device)
            if chosen is None:
                prompt = d.await_permission_prompt(timeout)
                offered = ", ".join(c.name.lower().replace("_", "-") for c in prompt.choices)
                text = f"permission dialog ({prompt.package_name}) offers: {offered}"
                if prompt.accuracies:
                    text += f"; accuracy: {', '.join(a.name.lower() for a in prompt.accuracies)}"
                return text
            d.choose_permission(chosen, picked)
            text = f"pressed {choice}" + (f" ({picked.name.lower()})" if picked else "")
            return f"{text}\n{self._settled(d)}" if settle else text

        return self._run(step)

    def wait(self, target: str, device: str | None = None, state: str = "visible", timeout: float = DEFAULT_WAIT) -> str:
        """Waits on the device until the target is visible, gone, or matches exactly one node."""
        if state not in ("visible", "gone", "one"):
            raise AgentError(f"state must be visible, gone or one, not {state!r}", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            element, described = self._element(d, target)
            waiting = element.wait(timeout)
            {"visible": waiting.visible, "gone": waiting.gone, "one": waiting.one}[state]()
            return f"{described} is {'exactly one node' if state == 'one' else state}"

        return self._run(step)

    def app(
        self,
        action: str,
        package: str,
        argument: str | None = None,
        device: str | None = None,
        any_app: bool = False,
        extras: Sequence[str] = (),
    ) -> str:
        """App lifecycle for ``package``: launch [activity], cold-launch
        [activity] (both with ``extras`` on the intent, ``KEY[:TYPE]=VALUE``), foreground, background, open-link URI (``any_app``: any app may handle it),
        stop, clear, install APK, uninstall, grant PERMISSION, revoke PERMISSION, granted PERMISSION, running,
        locale [TAGS] (prints the app's languages; comma-separated BCP-47 tags set them, ``system``
        makes the app follow the system again; API 33+, restored on release)."""
        if action not in APP_ACTIONS:
            raise AgentError(f"unknown app action {action!r} (known: {', '.join(APP_ACTIONS)})", EXIT_USAGE)
        needs = {"install": "APK path", "grant": "permission", "revoke": "permission", "granted": "permission", "open-link": "URI"}
        if action in needs and not argument:
            raise AgentError(f"app {action} needs an argument ({needs[action]})", EXIT_USAGE)
        if extras and action not in ("launch", "cold-launch"):
            raise AgentError(f"extras go with launch and cold-launch, not {action}", EXIT_USAGE)
        intent = parse_extras(extras)

        def step() -> str:
            app = self._device(device).app(package)
            name = app.package_name
            if action == "launch":
                app.launch(argument, extras=intent)
                return f"launched {name}"
            if action == "cold-launch":
                process = app.cold_launch(argument, extras=intent)
                return f"cold-launched {name} (pid {process.pid})"
            if action == "foreground":
                app.foreground()
                return f"brought {name} to the foreground"
            if action == "background":
                app.background()
                return f"sent {name} to the background (pressed home)"
            if action == "open-link":
                activity = app.open_link(argument, any_app=any_app)  # type: ignore[arg-type]
                return f"opened {argument}" + (f" in {activity}" if activity else "")
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
            if action == "revoke":
                app.revoke_permission(argument)  # type: ignore[arg-type]
                return f"revoked {argument} from {name} (Android stops its process)"
            if action == "granted":
                granted = app.is_permission_granted(argument)  # type: ignore[arg-type]
                return f"{argument} is {'granted' if granted else 'not granted'} to {name}"
            if action == "locale":
                if argument is not None:
                    tags = [] if argument.strip().lower() == "system" else [t.strip() for t in argument.split(",") if t.strip()]
                    app.set_locales(tags)
                locales = app.locales()
                return f"{name} languages: {', '.join(locales)}" if locales else f"{name} follows the system language"
            return f"{name} is {'running' if app.is_running() else 'not running'}"

        return self._run(step)


def _condition_change(name: str, value: str) -> Callable[[Device], None]:
    """The device call that sets condition ``name`` to ``value``, checked before any call."""
    if not name:
        raise AgentError("condition: name the condition to change", EXIT_USAGE)
    if name == "locale":
        tags = [tag.strip() for tag in value.split(",") if tag.strip()]
        if not tags:
            raise AgentError("locale takes comma-separated BCP-47 tags, e.g. fr-FR,en", EXIT_USAGE)
        return lambda d: d.set_system_locales(tags)
    value = value.lower()
    if name in ("animations", "dark-mode", "airplane-mode", "wifi", "mobile-data", "stay-awake", "high-contrast-text", "color-inversion", "bold-text"):
        if value not in ("on", "off"):
            raise AgentError(f"{name} takes on or off, not {value!r}", EXIT_USAGE)
        enabled = value == "on"
        switch = {
            "animations": lambda d: d.set_animations(enabled),
            "dark-mode": lambda d: d.set_dark_mode(enabled),
            "airplane-mode": lambda d: d.set_network(airplane_mode=enabled),
            "wifi": lambda d: d.set_network(wifi=enabled),
            "mobile-data": lambda d: d.set_network(mobile_data=enabled),
            "stay-awake": lambda d: d.set_stay_awake(enabled),
            "high-contrast-text": lambda d: d.set_accessibility_display(high_contrast_text=enabled),
            "color-inversion": lambda d: d.set_accessibility_display(color_inversion=enabled),
            "bold-text": lambda d: d.set_accessibility_display(bold_text=enabled),
        }
        return switch[name]
    if name == "font-scale":
        try:
            scale = float(value)
        except ValueError:
            raise AgentError(f"font-scale takes a number (1.0 = default), not {value!r}", EXIT_USAGE) from None
        return lambda d: d.set_font_scale(scale)
    if value == "reset":
        return lambda d: d.set_density(None)
    try:
        dpi = int(value)
    except ValueError:
        raise AgentError(f"density takes a dpi or reset, not {value!r}", EXIT_USAGE) from None
    return lambda d: d.set_density(dpi)


def _notification_line(n: Notification) -> str:
    parts = [n.package_name + ":", repr(n.title) if n.title is not None else "(no title)"]
    if n.text is not None:
        parts.append(f"- {n.text!r}")
    if n.actions:
        parts.append("[" + ", ".join(n.actions) + "]")
    if not n.clearable:
        parts.append("(ongoing)")
    return " ".join(parts)


def _switch(value: bool | None) -> str:
    return "unknown" if value is None else "on" if value else "off"


def _direction(name: str) -> Direction:
    try:
        return Direction[name.strip().upper()]
    except KeyError:
        raise AgentError(f"direction must be up, down, left or right, not {name!r}", EXIT_USAGE) from None


def _stamp() -> str:
    return datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
