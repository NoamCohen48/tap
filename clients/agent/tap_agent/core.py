"""The one implementation behind ``tap-agent <verb>`` and ``tap-agent mcp``.

Every method returns the short text to show. Device/session state lives in the daemon:
a session is a held connection named after it (``TapClient.connect(name, hold=...)``),
so any process — the next CLI call, an MCP server — resumes it by name. Each session's step
history (``records``) is client-owned durable intent under the private agent directory.
"""

from __future__ import annotations

import contextlib
import datetime
import json
import math
import os
import pathlib
import queue
import time
import uuid
from collections.abc import Callable, Iterator, Sequence
from dataclasses import replace
from typing import Literal, TypeVar

from tap_e2e import (  # pyright: ignore[reportMissingImports]
    AppDeath,
    CommandError,
    ConnectionEntry,
    DatabaseBundle,
    Device,
    Direction,
    DisplayRotation,
    Element,
    ErrorCode,
    FailureReason,
    LocationAccuracy,
    Long,
    MatchMode,
    Notification,
    Orientation,
    OutsideTouch,
    PermissionChoice,
    ServerError,
    StabilitySignal,
    StandardAction,
    TapClient,
    TapConnection,
    TapError,
    WaitTimeoutError,
)

from . import deaths as deaths_output
from . import events as device_events
from . import render
from .output import json_text, json_value, save_private
from .records import RecordError, RecordStore
from .step_capture import CURRENT, instrument, sending
from .targets import (
    Target,
    UsageError,
    format_duration,
    parse_condition,
    parse_int32,
    parse_key,
    parse_target,
)
from .toast_inbox import ToastInbox

DEFAULT_SESSION = "agent"
DEFAULT_IDLE = 15 * 60.0
DEFAULT_WAIT = 10.0
# Seconds of each server-side wait of `deaths --wait`; it asks again until its own timeout.
DEATH_WAIT_CHUNK = 600.0
MAX_COMMAND_TIMEOUT = 120.0
WAIT_STATES = ("visible", "gone", "one", "enabled", "disabled", "checked", "unchecked", "focused", "text", "text-contains", "count")
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
# The step ran, but a watched app crashed or stopped responding unexpectedly (its output says which).
EXIT_APP_DIED = 4

T = TypeVar("T")


class AgentError(Exception):
    """A step that failed, with the text to show and the CLI exit code."""

    def __init__(self, message: str, exit_code: int = EXIT_FAILED):
        super().__init__(message)
        self.exit_code = exit_code


class AppDiedStep(AgentError):
    """A step that ran, but reported an unexpected watched app death: the message is the step's
    whole output, deaths included; exit code ``EXIT_APP_DIED``."""

    def __init__(self, output: str):
        super().__init__(output, EXIT_APP_DIED)


def command_timeout(value: float | None) -> float | None:
    """Validate the existing driver budget before resolving a target or sending any input."""
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AgentError("timeout must be a number of seconds", EXIT_USAGE)
    try:
        number = float(value)
    except OverflowError:
        number = math.inf
    if not math.isfinite(number) or not 0.001 <= number <= MAX_COMMAND_TIMEOUT:
        raise AgentError("timeout must be at least 1ms and at most 120 seconds", EXIT_USAGE)
    return number


def _integer(value: int, name: str, low: int, high: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise AgentError(f"{name} must be an integer in {low}..{high}", EXIT_USAGE)
    return value


NOTIFICATION_ACTIONS = ("list", "await", "open", "dismiss")


APP_DATA_ACTIONS = ("capabilities", "list", "read", "write", "delete", "export-db", "import-db")

APP_ACTIONS = (
    "launch", "cold-launch", "foreground", "background", "open-link",
    "stop", "clear", "install", "uninstall", "grant", "revoke", "granted", "running", "locale", "installed", "process", "visible",
)


def _hint(error: TapError) -> str:
    """The error's message, plus what the agent can do about it when that is clear."""
    message = str(error)
    if isinstance(error, CommandError):
        if error.code is ErrorCode.NOT_FOUND:
            return f"{message}\nnothing matches now; run `snapshot` to see the screen"
        if error.code is ErrorCode.STALE_BEFORE_INPUT:
            return f"{message}\nthe node was re-rendered before any input; nothing changed, so run the same command again"
        if error.code is ErrorCode.AMBIGUOUS:
            return f"{message}\nseveral nodes match; use a ref from `snapshot` or a narrower selector"
        if error.code in (ErrorCode.AUT_CRASHED, ErrorCode.AUT_ANR):
            return f"{message}\nthe app died, so nothing on its screen is there any more; launch it again"
        if error.code is ErrorCode.NOT_INTERACTABLE and error.detail == "OBSCURED":
            return f"{message}\nanother window covers the node; close it (often `key back`) or scroll the node clear"
        if error.code is ErrorCode.NOT_INTERACTABLE and error.detail == "NO_OUTSIDE_POINT":
            return f"{message}\nthe target is not in a dialog, menu, drawer or sheet with room around it; target a node inside the panel"
        if error.code is ErrorCode.NOT_INTERACTABLE and error.detail == "TOUCH_REACHES_BELOW":
            return f"{message}\nthe panel lets touches through to the screen under it; add --allow-reach-below to touch there anyway"
    if isinstance(error, ServerError):
        if error.reason is FailureReason.UNKNOWN_REF:
            return f"{message}\nthat ref is not on the screen any more; run `snapshot` for current refs"
        if error.reason is FailureReason.REF_NOT_ADDRESSABLE:
            return f"{message}\nthis node has no selector; target a nearby node or write a selector"
    return message


DRIVER_PACKAGES = frozenset({"io.github.noamcohen48.tap.driver", "io.github.noamcohen48.tap.driver.test"})


@instrument
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
        self.records = RecordStore(self.out_dir / "records")
        # The recorded step the running user step finished as, for the deaths noticed after it.
        self._recorded: tuple[str, int] | None = None

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
        except RecordError as error:
            raise AgentError(str(error)) from error
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

    # --- app deaths -----------------------------------------------------------------------------

    def reporting_deaths(self, step: Callable[[], str], *, expect: bool = False, expect_timeout: float | None = None) -> str:
        """Runs one user step, then appends the session's watched app deaths not reported yet
        (``!`` lines with the top frames and a trace file) and takes them (``AppDeath.handled``),
        so they explain no later failure. A step that ran but reported such a death raises
        ``AppDiedStep`` (exit code 4) with its output: a script or an agent must not go on as if
        the app were alive. With ``expect``, the step must make a watched app die, during it or
        within ``expect_timeout`` seconds after it (default the wait timeout): that death is
        printed with ``✓``, a command failing because of it does not fail the step, and no death
        fails it. The CLI and the MCP server wrap every step that uses a session in this."""
        self._recorded = None
        failure: AgentError | None = None
        result = ""
        try:
            result = step()
        except AgentError as error:
            failure = error
        carried = _carried_death(failure)
        if expect and failure is not None and carried is None:
            raise failure
        try:
            report, unexpected = self._death_report(expect, carried, failed=failure is not None and not expect,
                                                    timeout=DEFAULT_WAIT if expect_timeout is None else expect_timeout)
        except AgentError:
            raise
        except Exception:
            report, unexpected = "", False  # the report never hides the step's own outcome
        if failure is not None and not expect:
            raise AgentError(f"{failure}\n{report}" if report else str(failure), failure.exit_code) from failure
        output = f"{result}\n{report}" if report and result else report or result
        if unexpected:
            raise AppDiedStep(output)
        return output

    def _death_report(self, expect: bool, carried: AppDeath | None, *, failed: bool, timeout: float) -> tuple[str, bool]:
        """The death blocks after a step and whether any death was unexpected. ``failed``: the
        step's error already names ``carried``, so its block leaves the summary out."""
        entry = self._held()
        if entry is None:
            if expect:
                raise AgentError(f"no session {self.session!r}; start one with `attach <serial>`")
            return "", False
        devices = TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold).attached_devices()
        cursors = deaths_output.Cursors(self.out_dir, self.session)
        seen = cursors.load()
        deadline = time.monotonic() + timeout
        fresh: list[tuple[Device, AppDeath]] = []
        if carried is not None:
            owner = next((d for d in devices if d.serial == carried.serial), None)
            if owner is not None and carried.sequence > seen.get(owner.attached_device_id, 0):
                fresh.append((owner, carried))
        while True:
            for device in devices:
                # Waits up to 300 ms after the step's input first: a crash it caused is logged shortly after.
                taken = device.take_unhandled_deaths(seen.get(device.attached_device_id, 0))
                known = {(d.serial, d.sequence) for _, d in fresh}
                fresh += [(device, d) for d in taken if (d.serial, d.sequence) not in known]
            if not expect or fresh or time.monotonic() >= deadline:
                break
            time.sleep(0.2)
        for device, death in fresh:
            seen[device.attached_device_id] = max(seen.get(device.attached_device_id, 0), death.sequence)
        cursors.save({key: value for key, value in seen.items() if key in {d.attached_device_id for d in devices}})
        fresh.sort(key=lambda pair: pair[1].sequence)
        expected = None
        if expect:
            expected = carried.sequence if carried is not None else (fresh[0][1].sequence if fresh else None)
            if expected is None:
                raise AgentError(f"expected a watched app to crash or stop responding within {format_duration(timeout)}; none did")
        several = len(devices) > 1
        blocks = []
        entries = []
        for device, death in fresh:
            trace = deaths_output.trace_file(self.out_dir, device.serial, death)
            named = failed and carried is not None and death.sequence == carried.sequence
            blocks.append(deaths_output.block(death, expected=death.sequence == expected, serial=device.serial if several else None, trace=trace, summary=not named))
            entries.append(deaths_output.record_entry(death, device.serial, death.sequence == expected))
        if entries and self._recorded is not None:
            with contextlib.suppress(RecordError):
                self.records.deaths(*self._recorded, entries)
        return "\n".join(blocks), any(death.sequence != expected for _, death in fresh)

    def deaths(self, device: str | None = None, *, package: str | None = None, all_apps: bool = False, trace: int | None = None, as_json: bool = False) -> str:
        """The crashes and ANRs since the device was attached, oldest first: the session's
        watched apps, or every app with ``all_apps``. ``trace`` prints one death's full stack
        trace instead. Read from the server's record; changes nothing."""

        def step() -> str:
            selected = self._device(device)
            listed = selected.app_deaths(package_name=package)
            if trace is not None:
                found = next((d for d in listed if d.sequence == trace), None)
                if found is None:
                    raise AgentError(f"no death #{trace} on {selected.serial}; `deaths` lists them")
                return f"{found.summary}\n\n{found.stack_trace or '(no stack trace: an ANR, or the crash buffer lost it)'}"
            shown = [d for d in listed if all_apps or d.watched]
            if as_json:
                return json.dumps([deaths_output.as_json(d, selected.serial) for d in shown], indent=2, ensure_ascii=False)
            if not shown:
                return f"no {'' if all_apps else 'watched '}app died on {selected.serial} since it was attached"
            return "\n".join(deaths_output.listing(d, None) for d in shown)

        return self._run(step)

    def wait_for_death(self, device: str | None = None, *, package: str | None = None, all_apps: bool = False, timeout: float | None = None) -> str:
        """Blocks until a watched app (any app with ``all_apps``; only ``package`` when given) dies
        on the device, the session ends, or ``timeout`` seconds pass (None: until the session
        ends). A death raises ``AppDiedStep`` (exit code 4) naming it; the other two return a line.
        Only notifies: the death stays unhandled, so the next step still reports and takes it. The
        wait runs on the server and keeps no idle session alive; meant to run in the background."""
        selected = self._run(lambda: self._device(device))
        serial = selected.serial
        mark = self._run(selected.death_sequence)
        deadline = None if timeout is None else time.monotonic() + timeout
        which = "" if all_apps else "watched "
        while True:
            left = DEATH_WAIT_CHUNK if deadline is None else deadline - time.monotonic()
            if left <= 0:
                return f"no {which}app died on {serial} within {format_duration(timeout or 0)}"
            try:
                listed = selected.await_app_deaths(mark, min(left, DEATH_WAIT_CHUNK), package_name=package)
            except TapError:
                if not self._attached(serial):
                    return f"session {self.session!r} ended; no {which}app died on {serial}"
                raise AgentError(f"could not wait for app deaths on {serial}") from None
            shown = [d for d in listed if all_apps or d.watched]
            if shown:
                lines = [deaths_output.listing(d, None) for d in shown]
                lines.append(f"  `tap-agent deaths --trace {shown[0].sequence}` for its stack trace; the next step reports it")
                raise AppDiedStep("\n".join(lines))
            if listed:
                mark = max(d.sequence for d in listed)

    def _attached(self, serial: str) -> bool:
        """Whether the session still holds ``serial``; False when it cannot be told."""
        try:
            return any(d.serial == serial for d in self._connection().attached_devices())
        except Exception:
            return False

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
                    return self._selected_device(device)
            raise AgentError(f"{serial} is not attached to session {self.session!r}; `attach {serial}`")
        if not devices:
            raise AgentError(f"session {self.session!r} has no device; `attach <serial>`")
        if len(devices) > 1:
            serials = ", ".join(d.serial for d in devices)
            raise AgentError(f"session {self.session!r} has several devices ({serials}); pass --device", EXIT_USAGE)
        return self._selected_device(devices[0])

    @staticmethod
    def _selected_device(device: Device) -> Device:
        capture = CURRENT.get()
        if capture is not None:
            capture.device(device.serial, device.owner_connection.id, device.attached_device_id)
        return device

    @staticmethod
    def _element(device: Device, target: str | Target, *, slot: str = "target") -> tuple[Element, str]:
        """The target as an element: a ref's selector on the whole screen (it already names the
        node's package when that is needed), a written selector in ``pkg``'s nodes or else on
        the whole screen."""
        parsed = parse_target(target) if isinstance(target, str) else target
        if parsed.ref:
            element = device.screen.element(device.resolve_ref(parsed.ref))
        else:
            assert parsed.selector is not None
            scope = device.app(parsed.package) if parsed.package else device.screen
            element = scope.element(parsed.selector)
        capture = CURRENT.get()
        if capture is not None:
            capture.target(slot, element.selector)
        return element, parsed.describe()

    def _settled(self, device: Device, level: str = render.DEFAULT, full: bool = False, timeout: float = SETTLE_TIMEOUT, *, package: str | None = None, stable_for: float = SETTLE_STABLE_FOR, signal: StabilitySignal = StabilitySignal.TREE) -> str:
        """Waits for the focused window's hierarchy to stop changing, then diffs a new snapshot
        against the previous one. A screen that keeps changing is reported, not an error."""
        note = ""
        package = package or device.info().current_package
        if package is None:
            raise AgentError("no focused app to settle; use snapshot to inspect the screen", EXIT_FAILED)
        try:
            device.app(package).await_screen_stable(stable_for, timeout, signal)
        except WaitTimeoutError:
            note = f"(screen still changing after {format_duration(timeout)})\n"
        output = note + render.diff_text(device.screen_snapshot(), level, full)
        return self._with_toasts(device, output)

    def _act(self, device_serial: str | None, target: str, verb: str, action: Callable, settle: bool) -> str:
        def step() -> str:
            device = self._device(device_serial)
            element, described = self._element(device, target)
            with sending():
                action(element)
            text = f"{verb} {described}"
            return f"{text}\n{self._settled(device)}" if settle else text

        return self._run(step)

    def inspect(self, target: str, device: str | None = None, timeout: float | None = None) -> str:
        """The exact selector's current SDK element snapshot as JSON; ambiguity remains an error."""
        return self._query(target, device, "snapshot", timeout)

    def exists(self, target: str, device: str | None = None, timeout: float | None = None) -> str:
        """Whether at least one node matches now; this is a read, not a wait."""
        return self._query(target, device, "exists", timeout)

    def count(self, target: str, device: str | None = None, timeout: float | None = None) -> str:
        """The number of matches now, using the SDK's count semantics."""
        return self._query(target, device, "count", timeout)

    def elements(
        self, target: str, device: str | None = None, timeout: float | None = None, *, texts: bool = False,
    ) -> str:
        """All matching SDK snapshots as a JSON array; ``texts`` projects raw text instead.

        One fresh bulk device read, no diagnostic dump, scroll, wait or persistent handles.
        Accepts written selectors (including pkg and relations), not refs or first/index.
        Results follow accessibility order, not necessarily visual order. Only currently
        exposed nodes are read, sequentially rather than as an atomic UI transaction. Empty
        matches return []; staleness or overflow (1000 matches / 1 MiB) fails the whole read.
        Texts preserve duplicates, empty strings and null; hints match SDK text semantics.
        """
        timeout = command_timeout(timeout)

        def step() -> str:
            parsed = parse_target(target)
            if parsed.ref or parsed.selector is None:
                raise UsageError("elements needs a written selector, not a snapshot ref")
            if parsed.selector.to_proto().WhichOneof("pick") in ("first", "at"):
                raise UsageError("elements does not accept first/index; use inspect for one node")
            element, _ = self._element(self._device(device), parsed)
            collection = element.device.screen.elements(element.selector)
            values = collection.texts(timeout) if texts else collection.snapshots(timeout)
            return json_text(values)

        return self._run(step)

    def _query(self, target: str, device: str | None, operation: str, timeout: float | None) -> str:
        timeout = command_timeout(timeout)

        def step() -> str:
            element, _ = self._element(self._device(device), target)
            return json_text(getattr(element, operation)(timeout=timeout))

        return self._run(step)

    # --- sessions -------------------------------------------------------------------------------

    def attach(
        self,
        serial: str,
        idle: float = DEFAULT_IDLE,
        wait_for_device: float = 0,
        inspect_quarantined: bool = False,
        driver_start: Literal["auto", "cold"] = "auto",
    ) -> str:
        """Attaches ``serial`` to the session, creating the session (a held connection with an
        ``idle`` timeout) when it does not exist. ``inspect_quarantined`` attaches a quarantined
        device to inspect and fix it; it stays quarantined after detach until ``unquarantine``.
        ``driver_start="cold"`` forces a fresh driver, independently of APK installation. Release
        an existing attachment first; cold mode never silently replaces an active session."""

        def step() -> str:
            if driver_start not in ("auto", "cold"):
                raise AgentError("driver_start must be auto or cold", EXIT_USAGE)
            entry = self._held()
            created = entry is None
            if entry is None:
                connection = self.client.connect(self.session, hold=idle)
            else:
                connection = TapConnection(self.client, entry.id, name=entry.name, hold=entry.hold)
                attached = next((d for d in entry.attached_devices if d.serial == serial), None)
                if attached is not None:
                    if driver_start == "cold":
                        raise AgentError("Release or detach the existing attachment before requesting a cold driver", EXIT_USAGE)
                    return f"{serial} is already attached to session {self.session!r}"
            try:
                device = connection.attach_device(
                    serial, wait_for_device=wait_for_device, inspect_quarantined=inspect_quarantined,
                    driver_start=driver_start,
                )
            except BaseException:
                if created:  # leave no empty session behind
                    with contextlib.suppress(Exception):
                        connection.close()
                raise
            text = (
                f"attached {serial} to session {self.session!r}, "
                f"idle timeout {format_duration(connection.hold or idle)}"
            )
            if device.quarantine_reason is not None:
                text += (
                    f"\n{serial} is quarantined ({device.quarantine_reason}): inspect and fix it; "
                    f"it stays quarantined after detach until `unquarantine {serial}`"
                )
            return text

        return self._run(step)

    def unquarantine(self, serial: str) -> str:
        """Puts a quarantined device back in service once it was checked: the server retries the
        cleanup, then lifts the quarantine and says what is still off."""

        def step() -> str:
            result = self.client.unquarantine(serial)
            if not result.was_quarantined:
                return f"{serial} is not quarantined; nothing to do"
            lines = [f"{serial} is back in service (it was quarantined: {result.quarantine_reason})"]
            lines += [f"  - {line}" for line in result.cleaned]
            lines += [f"  - still off: {line}" for line in result.remaining]
            return "\n".join(lines)

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
        """Release the session and finalize protected capture, without exporting files."""
        from .history import release
        return release(self)

    def detach(self, device: str | None = None) -> str:
        """Detach only the selected device; retain the held session and its other devices."""
        def step() -> str:
            selected = self._device(device)
            selected.detach()
            return f"detached {selected.serial} from session {self.session!r}"
        return self._run(step)

    def server_info(self) -> str:
        """Daemon version, compatibility and configuration as SDK JSON; no daemon startup."""
        return self._run(lambda: json_text(self.client.info()))

    def device_info(self, device: str | None = None) -> str:
        """Complete current SDK device information as JSON."""
        return self._run(lambda: json_text(self._device(device).info()))

    def capabilities(self, device: str | None = None, *, as_json: bool = False) -> str:
        """Read SDK operation eligibility without input or implicit setup; not guaranteed success."""
        def step() -> str:
            capabilities = self._device(device).capabilities()
            if as_json:
                return json_text(capabilities)
            return "\n".join(
                f"{entry.id.name if entry.id.name != 'UNKNOWN' else f'UNKNOWN_{entry.raw_id}'}  "
                f"{entry.status.name}  {entry.detail}"
                for entry in capabilities.entries
            )
        return self._run(step)

    def devices(self) -> str:
        """Every device ADB lists, its state, and the session holding it."""

        def step() -> str:
            names = {c.id: c.name for c in self.client.connections()}
            lines = []
            for d in self.client.devices():
                line = f"{d.serial}  {d.state.name}"
                if d.properties is not None:
                    p = d.properties
                    line += f"  API {p.api_level}  {p.manufacturer} {p.model}  {'emulator' if p.emulator else 'physical'}"
                if d.client_connection_id:
                    line += f"  held by {names.get(d.client_connection_id, d.client_connection_id)!r}"
                if d.quarantine_reason:
                    line += f"  ({d.quarantine_reason})"
                if d.under_inspection:
                    line += "  under inspection"
                elif d.retry_on_attach:
                    line += "  retried on attach"
                lines.append(line)
            return "\n".join(lines) or "no devices (adb devices lists none)"

        return self._run(step)

    # --- the screen -----------------------------------------------------------------------------

    def snapshot(self, device: str | None = None, level: str = render.DEFAULT, *, candidates: bool = False,
                 package: str | None = None, window: int | None = None,
                 hide_keyboard: bool = False, hide_system_chrome: bool = False) -> str:
        """Contextual ref-addressed outline, optionally filtered by window-root package or
        one-based window ordinal. Hiding keyboard/system windows uses Android window types;
        unknown types stay visible. Filters never change refs or selectors. ``context`` is
        descriptive, not a target predicate. Candidates returns unfiltered structured SDK data."""
        filters = render.SnapshotFilter(package, window, hide_keyboard, hide_system_chrome)
        if window is not None and (isinstance(window, bool) or not isinstance(window, int) or window < 1):
            raise AgentError("window must be a positive one-based ordinal", EXIT_USAGE)
        if candidates and (level != render.DEFAULT or filters != render.SnapshotFilter()):
            raise AgentError("candidates returns complete structured data; use the default view without filters", EXIT_USAGE)
        def step() -> str:
            selected = self._device(device)
            snapshot = selected.screen_snapshot(selector_candidates=candidates)
            output = json_text(snapshot) if candidates else render.snapshot_text(snapshot, level, filters=filters)
            return self._with_toasts(selected, output, structured=json_value(snapshot) if candidates else None)
        return self._run(step)

    def _with_toasts(self, device: Device, output: str, *, structured: dict | None = None) -> str:
        """Attach unread historical evidence, never replace a successful screen read with an inbox error."""
        def render_page(page) -> str:
            if structured is not None:
                return json_text({**structured, "toast_events": page})
            if not page.events and not page.gap:
                return output
            return output + "\n\nToast events since the previous inbox read (history, not current UI):\n" + _toast_history_text(page)
        try:
            return ToastInbox(self.out_dir).read(device, render_page)
        except (TapError, OSError, ValueError) as error:
            warning = f"toast inbox unavailable (cursor not advanced): {error}"
            if structured is not None:
                return json_text({**structured, "toast_events_error": warning})
            return output + "\n! " + warning

    def settle(self, device: str | None = None, full: bool = False, timeout: float = SETTLE_TIMEOUT, *, package: str | None = None, stable_for: float = SETTLE_STABLE_FOR, signal: str = "tree") -> str:
        """Wait for tree/pixel/both stability, then report a diff or an explicit changing-screen note."""
        command_timeout(timeout)
        if signal.upper() not in StabilitySignal.__members__:
            raise AgentError("signal must be tree, pixels or all", EXIT_USAGE)
        if isinstance(stable_for, bool) or not isinstance(stable_for, (int, float)) or not math.isfinite(stable_for) or not 0.001 <= stable_for <= min(30, timeout):
            raise AgentError("stable_for must be 1ms..30s and no greater than timeout", EXIT_USAGE)
        return self._run(lambda: self._settled(self._device(device), full=full, timeout=timeout, package=package, stable_for=stable_for, signal=StabilitySignal[signal.upper()]))

    def screenshot(self, device: str | None = None, out: str | os.PathLike[str] | None = None) -> pathlib.Path:
        """Saves a PNG and returns its path."""

        def step() -> pathlib.Path:
            d = self._device(device)
            path = pathlib.Path(out) if out else self.out_dir / f"{d.serial}-{_stamp()}.png"
            return d.screenshot().save(path)

        return self._run(step)

    def capture(self, device: str | None = None, out: str | os.PathLike[str] | None = None) -> str:
        """Collect a current-state bundle and index its saved files in retained history."""

        def step() -> str:
            d = self._device(device)
            directory = pathlib.Path(out) if out else self.out_dir / f"capture-{d.serial}-{_stamp()}"
            directory.mkdir(mode=0o700, parents=True)
            started = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")
            captured = d.capture()
            stem = captured.serial.replace("/", "_").replace("\\", "_")
            saved = [str(save_private(directory / f"{stem}.{name}.{artifact.extension}", artifact.bytes))
                for name, artifact in captured.artifacts.items()]
            lines = saved + [f"{name} failed: {error}" for name, error in captured.failures.items()]
            try:
                record = self.records.ensure(self.session, d.owner_connection.id)
                with self.records.lease(record["id"]):
                    self.records.artifact(record, serial=d.serial, attached_device_id=d.attached_device_id,
                        started_at=started, directory=str(directory.absolute()), parts=[pathlib.Path(p).name for p in saved],
                        failures={name: type(error).__name__ for name, error in captured.failures.items()})
            except RecordError:
                lines.append("WARNING: capture files were saved, but their history reference could not be indexed; do not repeat capture to recover them")
            return "\n".join(lines)

        return self._run(step)

    def record(self, action: str, name: str | None = None, *, long: bool = False) -> str:
        """Start, stop, list or clean a named recording of this session's history."""
        from .history import record
        return record(self, action, name, long=long)

    def history(self, action: str = "read", value: str | None = None, *, start: str | None = None, until: str | None = None,
                last: str | None = None, limit: int = 200, logs: bool = False, as_json: bool = False, details: bool = False,
                devices: Sequence[str] = (), packages: Sequence[str] = (), priority: str | None = None, tags: Sequence[str] = (),
                grep: str | None = None, actions: bool = True, driver_lines: bool = False, since_marker: str | None = None,
                until_marker: str | None = None, step: str | int | None = None, last_action: bool = False,
                recording: str | None = None, after: str | None = None) -> str:
        """What happened: the server's calls, crashes, ANRs and gaps (``logs`` or any log filter
        adds device logs), merged with this agent's steps and capture bundles, oldest first.

        One start: ``start`` (ISO time), ``last`` (a duration such as ``30s``), ``since_marker``,
        ``step`` (a cursor or id), ``last_action`` or ``after`` (the position a previous page
        printed); without one, the newest ``limit`` entries. ``until`` / ``until_marker`` end it.
        ``recording`` reads a named recording's range. ``mark``/``redact`` with ``value`` act on
        this session's held devices instead."""
        from .history import history
        return history(self, action, value, start=start, until=until, last=last, limit=limit, logs=logs, as_json=as_json, details=details,
            devices=devices, packages=packages, priority=priority, tags=tags, grep=grep, actions=actions, driver_lines=driver_lines,
            since_marker=since_marker, until_marker=until_marker, step=step, last_action=last_action, recording=recording, after=after)

    def _end_ended_histories(self) -> bool:
        """Stops each live step history whose held connection the server no longer has (an idle
        timeout, a daemon restart), so it is never shown or exported as live. False when the
        server could not be asked; a stopped history needs no server."""
        live = [row for row in self.records.list() if row["state"] == "recording"]
        if not live:
            return True
        try:
            current = {entry.id for entry in self.client.connections()}
        except (AgentError, TapError, OSError):
            return False
        for row in live:
            if row["connection_id"] not in current:
                with contextlib.suppress(RecordError):
                    self.records.stop(row["name"], row["session"], reason="connection ended")
        return True

    def recording(self, action: str, device: str | None = None, *, video: bool = True, audio_source: str | None = None, max_seconds: int | None = None, out: str | None = None) -> str:
        """Explicit bounded recording. Save verified stop bytes privately; never replay start/stop."""
        if action not in ("start", "stop"):
            raise AgentError("recording action must be start or stop", EXIT_USAGE)
        if not isinstance(video, bool) or audio_source not in (None, "output", "playback", "mic"):
            raise AgentError("choose video and/or audio output, playback or mic", EXIT_USAGE)
        if action == "start":
            if out is not None or (not video and audio_source is None):
                raise AgentError("start needs a track; --out is only for stop", EXIT_USAGE)
            if max_seconds is not None:
                _integer(max_seconds, "max_seconds", 1, 30 if video else 60)
        elif not video or audio_source is not None or max_seconds is not None:
            raise AgentError("track/duration options are only for start", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if action == "start":
                try:
                    d.start_recording(video=video, audio_source=audio_source, max_seconds=max_seconds)
                except TapError as error:
                    raise AgentError(f"recording start failed: {_hint(error)}; do not repeat start automatically") from error
                note = "; output audio mutes the device speakers" if audio_source == "output" else "; microphone capture enabled" if audio_source == "mic" else ""
                return f"recording started{note}"
            target = pathlib.Path(out) if out is not None else None
            if target is not None and os.path.lexists(target):
                raise AgentError("local output already exists; choose a new path", EXIT_USAGE)
            # Establish a private canonical destination before stopping. A later custom-copy
            # error must not make the caller repeat the device stop to recover the artifact.
            directory = self.out_dir / f"recording-{d.serial}-{uuid.uuid4().hex}"
            directory.mkdir(mode=0o700, parents=True)
            try:
                recording = d.stop_recording()
            except (TapError, ValueError) as error:
                detail = _hint(error) if isinstance(error, TapError) else str(error)
                raise AgentError(f"recording stop failed: {detail}; do not repeat stop automatically") from error
            canonical = directory / f"recording.{recording.extension}"
            try:
                save_private(canonical, recording.bytes)
            except OSError as error:
                raise AgentError(f"recording stopped but local artifact save failed: {error}; do not repeat stop") from error
            if target is not None:
                try:
                    save_private(target, recording.bytes)
                except OSError as error:
                    raise AgentError(f"recording stopped; artifact is saved at {canonical}; custom output failed: {error}") from error
                return f"saved recording to {target} (private canonical copy: {canonical})"
            return f"saved recording to {canonical}"
        return self._run(step)

    def scroll_until(self, target: str, destination: str, direction: str = "down", device: str | None = None, *, max_scrolls: int = 20, percent: int = 80, timeout: float | None = None) -> str:
        """Use the bounded SDK container/descendant loop, never transport-level mutation replay."""
        resolved = _direction(direction)
        _integer(max_scrolls, "max_scrolls", 1, 1000)
        _integer(percent, "percent", 1, 100)
        timeout = command_timeout(timeout)
        def step() -> str:
            d = self._device(device)
            container, described = self._element(d, target)
            wanted, destination_description = self._element(d, destination, slot="destination")
            with sending(compound=True):
                container.scroll_until(wanted.selector, resolved, max_scrolls, percent, timeout)
            return f"found {destination_description} inside {described}"
        return self._run(step)

    # --- actions --------------------------------------------------------------------------------

    def tap(
        self, target: str, device: str | None = None, long: bool = False, settle: bool = False, double: bool = False,
        *, count: int = 1, interval: float = 0.1, duration: float | None = None, timeout: float | None = None,
        outside_panel: bool = False, allow_reach_below: bool = False,
    ) -> str:
        """Tap once or ``count`` times, double-tap, hold for ``duration`` seconds, or tap outside
        the panel (dialog, menu, drawer, sheet) the target is in.
        ``interval`` is the release-to-press gap in seconds for counted taps.
        """
        if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 100:
            raise AgentError("count must be an integer in 1..100", EXIT_USAGE)
        if isinstance(interval, bool) or not math.isfinite(interval) or not 0.04 <= interval <= 1:
            raise AgentError("interval must be in 0.04..1 seconds", EXIT_USAGE)
        if count * 50 + (count - 1) * round(interval * 1000) > 30_000:
            raise AgentError("the tap sequence must not exceed 30 seconds", EXIT_USAGE)
        timeout = command_timeout(timeout)
        if duration is not None:
            if isinstance(duration, bool) or not math.isfinite(duration) or not 0.001 <= duration <= 30:
                raise AgentError("duration must be in 0.001..30 seconds", EXIT_USAGE)
            long = True
        if long and double:
            raise AgentError("tap is either long or double, not both", EXIT_USAGE)
        if allow_reach_below and not outside_panel:
            raise AgentError("allow_reach_below needs outside_panel", EXIT_USAGE)
        if outside_panel:
            if long or double or count != 1 or interval != 0.1:
                raise AgentError("outside_panel is a single tap: it cannot be combined with long, double, duration, count or interval", EXIT_USAGE)
            touched: list[OutsideTouch] = []
            text = self._act(device, target, "tapped outside the panel of", lambda e: touched.append(e.tap_outside_panel(timeout, allow_reach_below=allow_reach_below)), settle)
            where = f" at ({touched[0].x}, {touched[0].y}): {touched[0].panel}" if touched else ""
            first, _, rest = text.partition("\n")
            return f"{first}{where}" + (f"\n{rest}" if rest else "")
        if (long or double) and (count != 1 or interval != 0.1):
            raise AgentError("count/interval cannot be combined with long, duration or double", EXIT_USAGE)
        if long:
            return self._act(device, target, "long-tapped", lambda e: e.long_tap(timeout, duration=duration), settle)
        if double:
            return self._act(device, target, "double-tapped", lambda e: e.double_tap(timeout), settle)
        return self._act(device, target, "tapped" if count == 1 else f"tapped {count} times", lambda e: e.tap(timeout, count=count, interval=interval), settle)

    def fill(
        self, target: str, text: str, device: str | None = None, settle: bool = False, sensitive: bool = False, *, timeout: float | None = None
    ) -> str:
        """Replaces the node's text (accessibility SET_TEXT, no key events). ``sensitive``: the
        daemon logs the call without the text, so ``export`` never holds it."""
        timeout = command_timeout(timeout)
        return self._act(device, target, "filled", lambda e: e.set_text(text, timeout, sensitive=sensitive), settle)

    def clear(self, target: str, device: str | None = None, settle: bool = False, *, timeout: float | None = None) -> str:
        timeout = command_timeout(timeout)
        return self._act(device, target, "cleared", lambda e: e.clear_text(timeout), settle)

    def scroll(self, target: str, direction: str, device: str | None = None, settle: bool = False, *, percent: int = 80, timeout: float | None = None) -> str:
        """One bounded SDK scroll; this does not promise that content moved."""
        resolved = _direction(direction)
        _integer(percent, "percent", 1, 100)
        timeout = command_timeout(timeout)
        return self._act(device, target, f"scrolled {direction}", lambda e: e.scroll(resolved, percent, timeout), settle)

    def swipe(self, target: str, direction: str, device: str | None = None, settle: bool = False, *, percent: int = 80, timeout: float | None = None) -> str:
        """One bounded SDK swipe, the finger moving towards direction."""
        resolved = _direction(direction)
        _integer(percent, "percent", 1, 100)
        timeout = command_timeout(timeout)
        return self._act(device, target, f"swiped {direction}", lambda e: e.swipe(resolved, percent, timeout), settle)

    def fling(self, target: str, direction: str, device: str | None = None, settle: bool = False, *, timeout: float | None = None) -> str:
        """One fast swipe towards ``direction``'s content edge, as for scroll; content may keep moving."""
        resolved = _direction(direction)
        timeout = command_timeout(timeout)
        return self._act(device, target, f"flung {direction}", lambda e: e.fling(resolved, timeout), settle)

    def pinch(self, target: str, how: str, percent: int = 80, device: str | None = None, settle: bool = False, *, timeout: float | None = None) -> str:
        """Two fingers moving apart (``open``, zoom in) or together (``close``) across ``percent`` of the node."""
        how = how.strip().lower()
        if how not in PINCHES:
            raise AgentError(f"pinch must be open or close, not {how!r}", EXIT_USAGE)
        _integer(percent, "percent", 1, 100)
        timeout = command_timeout(timeout)
        if how == "open":
            return self._act(device, target, f"pinched open {percent}%", lambda e: e.pinch_open(percent, timeout), settle)
        return self._act(device, target, f"pinched closed {percent}%", lambda e: e.pinch_close(percent, timeout), settle)

    def submit(self, target: str, device: str | None = None, settle: bool = False, *, timeout: float | None = None) -> str:
        """Runs a focused text field's keyboard action (Search, Go, Send, Done, ...) as the
        keyboard's action key does. API 30+."""
        timeout = command_timeout(timeout)
        return self._act(device, target, "submitted", lambda e: e.ime_action(timeout), settle)

    def action(
        self, target: str, name: str | None = None, device: str | None = None, custom: bool = False, settle: bool = False, *, timeout: float | None = None
    ) -> str:
        """Without ``name``: lists the accessibility actions the node offers (and its range). With
        one: performs that standard action (expand, collapse, dismiss, scroll-forward, ...), or with
        ``custom`` the custom action labelled ``name``, as a screen reader does (no touch)."""
        timeout = command_timeout(timeout)
        if name is None:
            if custom:
                raise AgentError("action --custom needs the action's label", EXIT_USAGE)

            def listing() -> str:
                d = self._device(device)
                element, described = self._element(d, target)
                snapshot = element.snapshot(timeout)
                offered = [a.name.lower().replace("_", "-") for a in snapshot.actions] + [repr(c) for c in snapshot.custom_actions]
                text = f"{described} offers: {', '.join(offered) if offered else 'no actions Tap can perform'}"
                if snapshot.range is not None:
                    r = snapshot.range
                    text += f"\nrange {r.min:g}..{r.max:g}, now {r.current:g} ({r.type.name.lower()})"
                return text

            return self._run(listing)
        if custom:
            return self._act(device, target, f"performed {name!r} on", lambda e: e.perform_custom_action(name, timeout), settle)
        key = name.strip().upper().replace("-", "_")
        if key not in StandardAction.__members__:
            raise AgentError(f"action takes {', '.join(STANDARD_ACTIONS)} (or --custom LABEL), not {name!r}", EXIT_USAGE)
        standard = StandardAction[key]
        return self._act(device, target, f"performed {name.strip().lower()} on", lambda e: e.perform_action(standard, timeout), settle)

    def progress(self, target: str, value: float, device: str | None = None, settle: bool = False, *, timeout: float | None = None) -> str:
        """Sets a slider (SeekBar, Slider, RatingBar) to ``value`` in its own units, exactly."""
        timeout = command_timeout(timeout)
        return self._act(device, target, f"set progress {value:g} on", lambda e: e.set_progress(value, timeout), settle)

    def drag(self, target: str, destination: str, device: str | None = None, settle: bool = False, *, timeout: float | None = None) -> str:
        """Long-press target, move to destination and drop once; never replay after uncertainty."""
        timeout = command_timeout(timeout)

        def step() -> str:
            d = self._device(device)
            source, described = self._element(d, target)
            onto, onto_described = self._element(d, destination, slot="destination")
            with sending():
                source.drag_to(onto.selector, timeout)
            text = f"dragged {described} onto {onto_described}"
            return f"{text}\n{self._settled(d)}" if settle else text

        return self._run(step)

    def type(self, text: str, device: str | None = None, settle: bool = False, sensitive: bool = False, *, target: str | None = None, await_focus: bool = True, timeout: float | None = None, focus_timeout: float | None = None) -> str:
        """SDK tap/focus-wait/type: timeout is per input; focus_timeout bounds its separate wait."""
        timeout = command_timeout(timeout)
        focus_timeout = command_timeout(focus_timeout)
        if focus_timeout is not None and (target is None or not await_focus):
            raise AgentError("focus_timeout requires a target with await_focus", EXIT_USAGE)
        if not await_focus and target is None:
            raise AgentError("await_focus is only configurable with a target", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if target is None:
                with sending():
                    d.type_text(text, timeout, sensitive=sensitive)
            else:
                if focus_timeout is not None:
                    d.timeouts = replace(d.timeouts, wait=focus_timeout)
                element, _ = self._element(d, target)
                with sending(compound=True):  # tap, focus wait, then type
                    element.type_text(text, await_focus, timeout, sensitive=sensitive)
            return f"typed {len(text)} characters\n{self._settled(d)}" if settle else f"typed {len(text)} characters"

        return self._run(step)

    def key(self, name: str, device: str | None = None, settle: bool = False) -> str:
        """Presses a key: back, home, enter, tab, delete, ... or an Android key code."""

        def step() -> str:
            code = parse_key(name)
            d = self._device(device)
            with sending():
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
            with sending():
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
            with sending():
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
                with sending():
                    d.wake()
            if action == "off":
                with sending():
                    d.sleep()
            if action == "unlock":
                with sending():
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
                with sending():
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
                with sending():
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
            d = self._device(device)
            with sending():
                d.set_location(latitude, longitude, accuracy_m=accuracy, altitude_m=altitude)
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
            d = self._device(device)
            with sending():
                d.push_file(device_path, source)
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
            d = self._device(device)
            with sending():
                path = d.add_media(source, name)
            return f"added {path} to the gallery (removed on release)"

        return self._run(step)

    def clipboard(self, text: str | None = None, device: str | None = None) -> str:
        """Without ``text``: prints the device clipboard. With it: puts it on the clipboard."""

        def step() -> str:
            d = self._device(device)
            if text is None:
                return d.clipboard()
            with sending():
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
        *, history: bool = False, after_seq: int = 0, stream: str = "", as_json: bool = False,
    ) -> str:
        """Waits for a toast of any app (only ``package``'s when given), shown in the last 3.5 s
        or arriving within ``timeout``; ``text`` must match it exactly, or be part of it with
        ``contains``. With ``history``, reads the retained observations immediately instead;
        ``after_seq``/``stream`` form a non-consuming cursor. History is not current UI state."""
        command_timeout(timeout)
        if contains and text is None:
            raise AgentError("toast --contains needs a text", EXIT_USAGE)
        _integer(after_seq, "after_seq", 0, 2**63 - 1)
        if not isinstance(stream, str) or len(stream) > 128:
            raise AgentError("stream must be a string of at most 128 characters", EXIT_USAGE)
        if not history and (after_seq or stream or as_json):
            raise AgentError("after_seq, stream and json need toast history", EXIT_USAGE)

        def step() -> str:
            d = self._device(device)
            if history:
                page = d.toast_history(after_seq, stream_id=stream)
                kept = tuple(e for e in page.events if (package is None or e.toast.package_name == package)
                             and (text is None or (text in e.toast.text if contains else text == e.toast.text)))
                page = replace(page, events=kept)
                return json_text(page) if as_json else _toast_history_text(page)
            mode = MatchMode.CONTAINS if contains else MatchMode.EXACT
            seen = d.await_toast(text, mode, package_name=package, timeout=timeout)
            return f"toast {seen.text!r} from {seen.package_name}"

        return self._run(step)

    # --- device events ----------------------------------------------------------------------------

    def observing_events(
        self,
        step: Callable[[], str],
        *,
        device: str | None = None,
        events: Sequence[str] = (),
        expect: Sequence[str] = (),
        timeout: float | None = None,
    ) -> str:
        """Runs one step while listening for device events, then appends an ``events:`` block
        with what was observed: the kinds in ``events`` (names or comma lists: ``toast,window``)
        and those the ``expect`` specs name (``KIND`` or ``KIND:TEXT``, TEXT a case-insensitive
        part of the event). Listening starts before the step, without guaranteeing complete
        capture or causality, and goes on ``GRACE_SECONDS`` after it, or with ``expect`` until every expectation was
        met or ``timeout`` seconds (default the wait timeout) passed: then the step fails, its
        output saying what did happen. A step that fails is reported as it is. The CLI and the
        MCP server wrap supported input steps in this when either option is given."""
        if not events and not expect:
            return step()
        wait = command_timeout(DEFAULT_WAIT if timeout is None else timeout)
        assert wait is not None

        def start() -> tuple[device_events.Observation, list[device_events.Expectation]]:
            classes = device_events.kinds(events)
            expectations = [device_events.Expectation.parse(spec) for spec in expect]
            classes += [x.kind for x in expectations if x.kind not in classes]
            return device_events.Observation(self._device(device), classes), expectations

        observation, expectations = self._run(start)
        try:
            result = step()
            if expectations:
                unmet = observation.wait(expectations, wait)
            else:
                unmet = []
                time.sleep(device_events.GRACE_SECONDS)
        finally:
            observation.close()
        report = device_events.block(observation.snapshot(), expectations, observation.lost())
        output = f"{result}\n{report}" if result else report
        if unmet:
            missing = ", ".join(str(x) for x in unmet)
            raise AgentError(f"{output}\nexpected event did not happen within {format_duration(wait)}: {missing}")
        return output

    def wait_event(
        self,
        kind: str,
        text: str | None = None,
        device: str | None = None,
        package: str | None = None,
        timeout: float = DEFAULT_WAIT,
    ) -> str:
        """Waits up to ``timeout`` for the first event of ``kind`` (``toast``, ``window``,
        ``content``, ``click``, ``scroll``, ``text``) from now on, whose subject contains
        ``text`` (ignoring case) when given, from ``package`` only when given, and prints it.
        Only events after the call count: for what an input causes, use ``--expect`` on it."""
        command_timeout(timeout)

        def step() -> str:
            expectation = device_events.Expectation(device_events.kind(kind), text or None)
            event = self._device(device).events.wait_for(expectation.kind, where=expectation.matches, package=package, timeout=timeout)
            return device_events.line(event)

        return self._run(step)

    def watch(
        self,
        kinds: Sequence[str],
        device: str | None = None,
        package: str | None = None,
        duration: float | None = None,
        as_json: bool = False,
    ) -> Iterator[str]:
        """Yields one line per device event of ``kinds`` as it happens (a JSON object per line
        with ``as_json``), for ``duration`` seconds or, without one, until the device is detached
        or the caller stops iterating. Read-only: other steps run meanwhile, from this session
        or another process. A last line says why it ended when that was not ``duration``."""
        if duration is not None and duration <= 0:
            raise AgentError("watch duration must be positive", EXIT_USAGE)
        arrived: queue.Queue[device_events.DeviceEvent] = queue.Queue()

        def start() -> list:
            classes = device_events.kinds(kinds)
            if not classes:
                raise UsageError(f"name at least one event kind: {', '.join(device_events.KIND_NAMES)}")
            d = self._device(device)
            registrations = []
            try:
                for cls in classes:
                    registrations.append(d.events.register(cls, arrived.put, package=package))
            except BaseException:
                for registration in registrations:
                    registration.close()
                raise
            return registrations

        registrations = self._run(start)
        deadline = None if duration is None else time.monotonic() + duration
        try:
            while True:
                remaining = None if deadline is None else deadline - time.monotonic()
                if remaining is not None and remaining <= 0:
                    return
                try:
                    event = arrived.get(timeout=0.2 if remaining is None else min(remaining, 0.2))
                except queue.Empty:
                    ended = [r for r in registrations if not r.active]
                    if ended:
                        errors = [str(r.error) for r in ended if r.error is not None]
                        yield f"(watch ended: {errors[0]})" if errors else "(watch ended: the device was detached)"
                        return
                    continue
                yield json.dumps(device_events.json_event(event), ensure_ascii=False) if as_json else device_events.line(event)
        finally:
            for registration in registrations:
                registration.close()

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
        command_timeout(timeout)
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
                shown = d.notifications(timeout=timeout)
                if package is not None:
                    shown = [n for n in shown if n.package_name == package]
                return "\n".join(_notification_line(n) for n in shown) or "no notifications"
            if action == "await":
                return _notification_line(d.await_notification(title, text, mode, package_name=package, timeout=timeout))
            if action == "open":
                with sending():
                    d.open_notification(title, text, mode, package_name=package, action=button, timeout=timeout)
                done = f"pressed {button!r} on the notification" if button else "opened the notification"
            else:
                with sending():
                    d.dismiss_notification(title, text, mode, package_name=package, timeout=timeout)
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
        command_timeout(timeout)
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
            with sending():
                d.choose_permission(chosen, picked, timeout=timeout)
            text = f"pressed {choice}" + (f" ({picked.name.lower()})" if picked else "")
            return f"{text}\n{self._settled(d)}" if settle else text

        return self._run(step)

    def wait(
        self, target: str, device: str | None = None, state: str = "visible", timeout: float = DEFAULT_WAIT, *,
        value: str | int | None = None, until: str | None = None,
    ) -> str:
        """Wait for visibility, uniqueness, state, text or count using SDK waits (no input). With
        ``until`` (a condition in the target grammar, such as ``enabled=true,text=Pay``), wait until
        the one node the target picks satisfies it: ``ElementWait.until``, polled on the device."""
        command_timeout(timeout)
        if until is not None:
            if state != "visible" or value is not None:
                raise AgentError("wait --until takes no --state or --value", EXIT_USAGE)
            try:
                condition = parse_condition(until)
            except UsageError as error:
                raise AgentError(f"--until: {error}", EXIT_USAGE) from error

            def satisfied() -> str:
                element, described = self._element(self._device(device), target)
                element.wait(timeout).until(condition)
                return f"{described} satisfies {until}"

            return self._run(satisfied)
        if state not in WAIT_STATES:
            raise AgentError(f"state must be one of {', '.join(WAIT_STATES)}", EXIT_USAGE)
        if state in ("text", "text-contains") and not isinstance(value, str):
            raise AgentError(f"wait {state} needs a string value", EXIT_USAGE)
        count_value: int | None = None
        if state == "count":
            try:
                count_value = parse_int32(value, "wait count value")
            except UsageError as error:
                raise AgentError(str(error), EXIT_USAGE) from error
        elif state not in ("text", "text-contains") and value is not None:
            raise AgentError(f"wait {state} does not take a value", EXIT_USAGE)

        def step() -> str:
            element, described = self._element(self._device(device), target)
            waiting = element.wait(timeout)
            if state == "count":
                assert count_value is not None
                waiting.count(count_value)
            elif state == "text":
                waiting.text_equals(str(value))
            elif state == "text-contains":
                waiting.text_contains(str(value))
            else:
                getattr(waiting, state)()
            label = 'exactly one node' if state == 'one' else state
            return f"{described} is {label}" + (f" {value!r}" if value is not None else "")

        return self._run(step)

    def app_data(
        self, action: str, package: str, path: str = "", local: str | None = None,
        device: str | None = None, *, overwrite: bool = False,
    ) -> str:
        """Debuggable-app private data: capabilities, list [PATH], read PATH LOCAL,
        write PATH LOCAL, delete PATH, export-db NAME DIRECTORY, import-db NAME DIRECTORY.
        Paths are relative private paths; database names are basenames. Except capabilities,
        requires a stopped app: stop/relaunch explicitly. Never prints contents. Downloads
        create owner-only files and refuse existing outputs; exports require a new directory.
        Overwrite is explicit for remote write/import only. Bundles keep WAL/journal, not SHM.
        After interrupted import, repair/clear data before launch; never retry blindly."""
        if action not in APP_DATA_ACTIONS:
            raise AgentError("unknown app-data action", EXIT_USAGE)
        transfers = ("read", "write", "export-db", "import-db")
        if action != "capabilities" and action != "list" and not path:
            raise AgentError(f"app-data {action} needs a private path or database name", EXIT_USAGE)
        if (action in transfers) != (local is not None):
            raise AgentError(f"app-data {action}: local path {'required' if action in transfers else 'not accepted'}", EXIT_USAGE)
        if action == "capabilities" and path:
            raise AgentError("app-data capabilities takes no path", EXIT_USAGE)
        if overwrite and action not in ("write", "import-db"):
            raise AgentError("--overwrite is only for remote write/import-db", EXIT_USAGE)
        if action in ("export-db", "import-db") and (
            pathlib.PurePosixPath(path).name != path or path in (".", "..") or "\\" in path
            or path.endswith(("-wal", "-journal", "-shm"))
        ):
            raise AgentError("database name must be a basename, not a path or sidecar", EXIT_USAGE)

        def load(source: pathlib.Path, limit: int = 64 * 1024 * 1024) -> bytes:
            with source.open("rb") as stream:
                data = stream.read(limit + 1)
            if len(data) > limit:
                raise AgentError("private-data transfer exceeds 64 MiB", EXIT_USAGE)
            return data

        def save(destination: pathlib.Path, data: bytes) -> None:
            destination.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)

        def step() -> str:
            data = self._device(device).app(package).data
            if action == "capabilities":
                access = data.capabilities()
                return "private data available (run-as)" if access.available else f"private data unavailable: {access.reason}"
            if action == "list":
                return "\n".join(f"{'link' if e.is_symlink else 'dir' if e.is_directory else 'file'} {e.size_bytes} {json.dumps(e.name, ensure_ascii=False)}" for e in data.list(path)) or "(empty)"
            if action == "delete":
                with sending():
                    data.delete(path)
                return "deleted private file"
            target = pathlib.Path(local or "")
            if action == "read":
                if target.exists():
                    raise AgentError("local output already exists; choose a new path", EXIT_USAGE)
                save(target, data.read(path))
                return f"saved private file to {target}"
            if action == "write":
                payload = load(target)
                with sending():
                    data.write(path, payload, overwrite=overwrite)
                return "wrote private file"
            if action == "export-db":
                if target.exists():
                    raise AgentError("database export needs a new local directory", EXIT_USAGE)
                bundle = data.export_database(path)
                target.mkdir(mode=0o700, parents=True)
                for suffix, contents in (("", bundle.database), ("-wal", bundle.wal), ("-journal", bundle.journal)):
                    if contents is not None:
                        save(target / (path + suffix), contents)
                return f"saved database bundle to {target} (keep database and sidecars together)"
            base = load(target / path)
            remaining = 64 * 1024 * 1024 - len(base)
            sidecars: list[bytes | None] = []
            for suffix in ("-wal", "-journal"):
                source = target / (path + suffix)
                contents = load(source, remaining) if source.exists() else None
                if contents is not None:
                    remaining -= len(contents)
                sidecars.append(contents)
            with sending():
                data.import_database(DatabaseBundle(path, base, *sidecars), overwrite=overwrite)
            return "imported database bundle; app remains stopped"

        return self._run(step)

    def app(
        self,
        action: str,
        package: str,
        argument: str | None = None,
        device: str | None = None,
        any_app: bool = False,
        extras: Sequence[str] = (),
        *, timeout: float | None = None,
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
        timeout = command_timeout(timeout)
        timed = {"launch", "cold-launch", "foreground", "open-link", "stop", "clear", "install", "process", "visible"}
        if timeout is not None and action not in timed:
            raise AgentError(f"app {action} uses the SDK's fixed/default budget, not --timeout", EXIT_USAGE)
        if any_app and action != "open-link":
            raise AgentError("--any-app is only for open-link", EXIT_USAGE)
        if action in ("installed", "process", "visible") and argument is not None:
            raise AgentError(f"app {action} takes no argument", EXIT_USAGE)
        intent = parse_extras(extras)

        def step() -> str:
            app = self._device(device).app(package)
            name = app.package_name
            if action == "installed":
                return json_text(app.is_installed())
            if action == "process":
                return json_text(app.process(timeout))
            if action == "visible":
                app.await_visible(timeout)
                return f"{name} is visible"
            if action == "launch":
                with sending():
                    app.launch(argument, timeout, extras=intent)
                return f"launched {name}"
            if action == "cold-launch":
                with sending():
                    process = app.cold_launch(argument, timeout, extras=intent)
                return f"cold-launched {name} (pid {process.pid})"
            if action == "foreground":
                with sending():
                    app.foreground(timeout)
                return f"brought {name} to the foreground"
            if action == "background":
                with sending():
                    app.background()
                return f"sent {name} to the background (pressed home)"
            if action == "open-link":
                assert argument is not None  # Validated before admitting the step.
                with sending():
                    activity = app.open_link(argument, any_app=any_app, timeout=timeout)
                return f"opened {argument}" + (f" in {activity}" if activity else "")
            if action == "stop":
                with sending():
                    app.force_stop(timeout)
                return f"stopped {name}"
            if action == "clear":
                with sending():
                    app.clear_data(timeout)
                return f"cleared data of {name}"
            if action == "install":
                assert argument is not None
                with sending():
                    app.install(argument, timeout)
                return f"installed {argument}"
            if action == "uninstall":
                with sending():
                    app.uninstall()
                return f"uninstalled {name}"
            if action == "grant":
                assert argument is not None
                with sending():
                    app.grant_permission(argument)
                return f"granted {argument} to {name}"
            if action == "revoke":
                assert argument is not None
                with sending():
                    app.revoke_permission(argument)
                return f"revoked {argument} from {name} (Android stops its process)"
            if action == "granted":
                assert argument is not None
                granted = app.is_permission_granted(argument)
                return f"{argument} is {'granted' if granted else 'not granted'} to {name}"
            if action == "locale":
                if argument is not None:
                    tags = [] if argument.strip().lower() == "system" else [t.strip() for t in argument.split(",") if t.strip()]
                    with sending():
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


def _carried_death(error: BaseException | None) -> AppDeath | None:
    """The app death a step's failure carried (a ``CommandError`` with ``app_death``), if any."""
    for _ in range(8):
        if error is None:
            return None
        if isinstance(error, CommandError):
            return error.app_death
        error = error.__cause__
    return None


def _toast_history_text(page) -> str:
    lines = []
    if page.reset:
        lines.append("! toast stream reset; earlier observations are unavailable")
    elif page.gap:
        lines.append(f"! toast history gap; first retained sequence is {page.oldest_sequence}")
    for event in page.events:
        note = " [text truncated]" if event.text_truncated else ""
        lines.append(f"#{event.sequence} {event.at_epoch_ms} toast {event.toast.text!r} from {event.toast.package_name!r}{note}")
    if not page.events:
        lines.append("No retained toasts matched.")
    lines.append(f"toast cursor: --after-seq {page.next_sequence} --stream {page.stream_id}")
    return "\n".join(lines)


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
