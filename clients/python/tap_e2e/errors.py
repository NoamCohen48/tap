"""Exception hierarchy. Driver failures arrive as data (CommandResult.error) and become
CommandError; server-level failures arrive as gRPC status codes and are mapped in client.py."""

from __future__ import annotations

from collections.abc import Sequence
from typing import TYPE_CHECKING

from . import _proto
from .models import AppDeath, ConditionPart, ElementSnapshot, ErrorCode, FailureReason, SelectorNarrowing, WaitReason

if TYPE_CHECKING:
    from . import _gen as pb


class TapError(Exception):
    """Base class for everything this package raises deliberately."""


class ServerError(TapError):
    """The host server rejected or failed a call (unknown connection or device, bad argument,
    driver start failure, ...). ``code`` is the gRPC status code name; ``reason`` is the server's
    structured ``FailureReason``, ``FailureReason.UNSPECIFIED`` when the failure did not come
    from the daemon."""

    def __init__(
        self, code: str, details: str, reason: FailureReason = FailureReason.UNSPECIFIED
    ):
        super().__init__(f"{code}: {details}")
        self.code = code
        self.details = details
        self.reason = reason


class CommandError(TapError):
    """A driver command's outcome was ``error``. ``code`` is an ErrorCode; ``detail`` refines it
    (e.g. NOT_FOUND/END_REACHED); TRANSPORT_LOST/INDETERMINATE carry the transmission state. A
    ``NOT_FOUND`` from a selector also carries ``narrowing``: which part of the selector ruled the
    nodes out, which the message shows on the lines after the first."""

    def __init__(
        self,
        code: ErrorCode,
        detail: str | None,
        driver_message: str | None,
        operation: str,
        serial: str,
        selector: str | None,
        request_id: int,
        generation: int,
        duration_ms: int,
        narrowing: SelectorNarrowing | None = None,
        app_death: AppDeath | None = None,
    ):
        self.code = code
        self.detail = detail
        self.driver_message = driver_message
        self.operation = operation
        self.serial = serial
        self.selector = selector
        self.request_id = request_id
        self.generation = generation
        self.duration_ms = duration_ms
        self.narrowing = narrowing
        """Why the selector found no node, read on the device after a ``NOT_FOUND``; None
        otherwise."""
        self.app_death = app_death
        """For ``AUT_CRASHED`` / ``AUT_ANR``: the app's death that replaced the code; None
        otherwise."""
        where = f" {selector}" if selector else ""
        detail = f"/{self.detail}" if self.detail else ""
        message = f": {self.driver_message}" if self.driver_message else ""
        super().__init__(
            f"{self.code.name}{detail} during {operation}{where} on {serial} "
            f"(request {self.request_id}, generation {self.generation}, {self.duration_ms} ms){message}"
            + _explained(narrowing)
        )


    @classmethod
    def _from_result(
        cls,
        result: pb.CommandResult,
        operation: str,
        serial: str,
        selector: str | None,
        selectors: Sequence[pb.Selector] = (),
    ) -> CommandError:
        """From a driver error result; ``selectors`` are the command's own, which its
        ``narrowing`` refers to."""
        error = result.error
        return cls(
            _proto.error_code(error.code),
            error.detail if error.HasField("detail") else None,
            error.message if error.HasField("message") else None,
            operation,
            serial,
            selector,
            result.request_id,
            result.session_generation,
            result.duration_ms,
            _proto.selector_narrowing(error, selectors),
            _proto.app_death(error.app_death) if error.HasField("app_death") else None,
        )


class AppDied(TapError):
    """A watched app crashed or stopped responding during a ``guard()`` block, or during a test
    (the pytest plugin's end-of-test check). ``deaths`` are the deaths, oldest first; ``death``
    the first. ``failure`` is what the block raised, if anything: the ``CommandError`` that
    carried the death, or the block's own failure. It is also the exception's ``__cause__``.
    """

    def __init__(self, deaths: Sequence[AppDeath], failure: BaseException | None = None):
        self.deaths: tuple[AppDeath, ...] = tuple(deaths)
        """The deaths, oldest first."""
        self.failure = failure
        """What the block (or test) raised, if anything."""
        first = self.deaths[0]
        lines = [f"{len(self.deaths)} app deaths; first: {first.summary}" if len(self.deaths) > 1 else first.summary]
        lines += [line for line in first.stack_trace.splitlines()[:12] if line.strip()]
        lines += [f"then {death.summary}" for death in self.deaths[1:]]
        super().__init__("\n".join(lines))

    @property
    def death(self) -> AppDeath:
        """The first death."""
        return self.deaths[0]


class WaitTimeoutError(TapError):
    """A condition did not hold within its timeout; the message names the device, the
    condition and what was observed so a log line alone is diagnosable: ``reason`` and
    ``match_count`` for the waits the device runs (``visible``, ``one``, ``gone``, the state waits,
    ``App.await_visible``, ``App.await_screen_stable``), ``last_state`` for a state wait whose node
    never reached the state, ``last_observation`` and ``polls`` for the ones the client polls."""

    def __init__(
        self,
        description: str,
        serial: str,
        elapsed_ms: int,
        polls: int = 0,
        last: str | None = None,
        reason: WaitReason | None = None,
        match_count: int | None = None,
        last_state: ElementSnapshot | None = None,
        condition_parts: tuple[ConditionPart, ...] = (),
        narrowing: SelectorNarrowing | None = None,
    ):
        self.description = description
        self.serial = serial
        self.elapsed_ms = elapsed_ms
        self.polls = polls
        self.last_observation = last
        self.reason = reason
        """Why the device-side condition was still unmet at its last poll; None for client-polled
        waits."""
        self.match_count = match_count
        """Matches at the last poll of ``visible`` / ``one`` / ``gone`` (capped at 1000)."""
        self.last_state = last_state
        """A state wait's node as the device read it at the end (``reason`` ``STATE_NOT_REACHED``);
        None otherwise."""
        self.condition_parts = condition_parts
        """With ``STATE_NOT_REACHED``: each part of the condition on that same node, and whether it
        held. Empty otherwise."""
        self.narrowing = narrowing
        """With ``reason`` ``NO_MATCH`` from ``visible`` / ``one`` / a state wait: which part of
        the selector ruled the nodes out, read after the deadline. None otherwise."""
        why = ""
        if reason is not None:
            why = f"; {reason.value}" + (f" ({match_count} matches)" if match_count is not None else "")
        suffix = f"; last observed: {last}" if last else ""
        polled = f" after {polls} polls" if polls else ""
        super().__init__(
            f"Timed out after {elapsed_ms} ms{polled} waiting for {description} on {serial}{why}{suffix}"
            + "".join(
                f"\n  {p.part}: {'yes' if p.holds else 'no'}" + (f" ({p.observed})" if p.observed is not None and not p.holds else "")
                for p in condition_parts
            )
            + _explained(narrowing)
        )

    @classmethod
    def _from_result(
        cls,
        result: pb.CommandResult,
        description: str,
        serial: str,
        last: str | None = None,
        condition: pb.Node | None = None,
        selectors: Sequence[pb.Selector] = (),
    ) -> WaitTimeoutError:
        """From a device ``WAIT_TIMEOUT`` result: its ``detail``, ``match_count``, ``last_state``
        and, for the ``condition`` a state wait sent, ``condition_holds``."""
        error = result.error
        state = _proto.element_snapshot(error.last_state) if error.HasField("last_state") else None
        if last is None and state is not None:
            last = (
                f"text={state.text!r} enabled={state.enabled} checked={state.checked} focused={state.focused}"
            )
        return cls(
            description,
            serial,
            result.duration_ms,
            last=last,
            reason=_proto.wait_reason(error.detail) if error.HasField("detail") else None,
            match_count=error.match_count if error.HasField("match_count") else None,
            last_state=state,
            condition_parts=(
                _proto.condition_parts(condition, error.condition_holds, error.last_state if error.HasField("last_state") else None)
                if condition is not None
                else ()
            ),
            narrowing=_proto.selector_narrowing(error, selectors),
        )


def _explained(narrowing: SelectorNarrowing | None) -> str:
    return "" if narrowing is None else "\n" + narrowing.explain()


class AppDataError(TapError):
    """Private-app data was refused/failed. ``detail`` is a stable server token (e.g.
    ``APP_RUNNING``, ``SYMLINK``, ``EXISTS``); ``serial`` identifies the device.
    Never relaunch or retry an interrupted database import blindly.
    """

    def __init__(self, detail: str, serial: str, message: str):
        super().__init__(message)
        self.detail = detail
        self.serial = serial


class AppLifecycleError(TapError):
    """Install/launch/stop did not reach the verified end state."""


class DeviceBusyError(TapError):
    """Another session — in this process or any other — holds the device, and ``wait_for_device``
    was zero or ran out. The device is fine; try later or pick another serial."""


class DeviceQuarantinedError(TapError):
    """The device's session journal keeps it out of service (a mutation whose outcome could not
    be proven, a corrupt journal, an uncertain ADB cleanup). It stays unusable until an explicit
    reset; retrying or waiting does not help."""

    def __init__(self, serial: str, details: str):
        super().__init__(details)
        self.serial = serial
