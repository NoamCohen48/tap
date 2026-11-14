"""Record public agent intent once, not its SDK implementation calls or polling."""
from __future__ import annotations

import contextlib
import contextvars
import functools
import inspect
import os
import time
from collections.abc import Callable, Iterator
from typing import TYPE_CHECKING, Any, TypeVar
from warnings import warn

from tap_e2e import (
    CommandError,
    DeviceBusyError,
    DeviceQuarantinedError,
    FailureReason,
    ServerError,
    TapError,
    WaitTimeoutError,
)

from .output import json_value
from .records import RecordError, RecordStore

if TYPE_CHECKING:
    from .core import Agent

# Explicit policies make mixed read/write commands reviewable. A coverage test classifies every
# public Agent method, so new methods cannot accidentally disappear from recordings.
ACTIONS = frozenset({"tap", "fill", "clear", "scroll", "swipe", "fling", "pinch", "submit", "progress", "drag", "type", "key", "panel", "rotate", "location", "push", "media", "scroll_until"})
WAITS = frozenset({"wait", "settle", "toast"})
CONDITIONAL = frozenset({"action", "keyboard", "screen", "condition", "clipboard", "notification", "permission", "app", "app_data"})
EXCLUDED = frozenset({"close", "attach", "sessions", "release", "detach", "devices", "unquarantine", "server_info", "device_info", "capabilities", "snapshot", "inspect", "exists", "count", "screenshot", "capture", "logs", "recording", "pull", "activity", "record", "history", "export"})


def _word(value: Any) -> Any:
    """The policy reads an option as the method will, so " STATE " is not mistaken for input."""
    return value.strip().lower() if isinstance(value, str) else value


def category(name: str, parameters: dict[str, Any]) -> str | None:
    parameters = {key: _word(value) if key == "action" else value for key, value in parameters.items()}
    if name in ACTIONS:
        return "action"
    if name in WAITS:
        return "wait"
    if name == "action":
        return "action" if parameters["name"] is not None else None
    if name in ("keyboard", "screen"):
        return "action" if parameters["action"] != "state" else None
    if name == "condition":
        return "action" if parameters["value"] is not None else None
    if name == "clipboard":
        return "action" if parameters["text"] is not None else None
    if name == "notification":
        action = parameters["action"]
        return None if action == "list" else "wait" if action == "await" else "action"
    if name == "permission":
        return "wait" if parameters["choice"] is None else "action"
    if name == "app":
        action = parameters["action"]
        if action in ("installed", "process", "running", "granted") or (action == "locale" and parameters["argument"] is None):
            return None
        return "wait" if action == "visible" else "action"
    if name == "app_data":
        return "action" if parameters["action"] in ("write", "delete", "import-db") else None
    return None


def _value(value: Any) -> Any:
    if isinstance(value, os.PathLike):
        return os.fsdecode(value)
    if isinstance(value, dict):
        return {str(key): _value(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_value(item) for item in value]
    return json_value(value)


def _has_text_predicate(value: Any) -> bool:
    if isinstance(value, dict):
        match = value.get("match")
        if isinstance(match, dict) and match.get("property") not in ("PROPERTY_PACKAGE_NAME", "PROPERTY_CLASS_NAME"):
            return True
        return any(_has_text_predicate(item) for item in value.values())
    if isinstance(value, list):
        return any(_has_text_predicate(item) for item in value)
    return False


class Capture:
    def __init__(self, store: RecordStore, identifier: str, sequence: int, sensitive: bool, connection_id: str):
        self.store, self.identifier, self.sequence, self.sensitive = store, identifier, sequence, sensitive
        self.connection_id = connection_id
        self.inputs = 0  # SDK calls that send device input, started
        self.returned = 0  # ... and returned normally
        self.compound = False  # one of them may send several inputs

    def device(self, serial: str, connection_id: str) -> None:
        if connection_id != self.connection_id:
            raise RecordError("held connection changed during recording; no new input was sent")
        self.store.device(self.identifier, self.sequence, serial)

    def target(self, slot: str, selector: Any) -> None:
        value = json_value(selector)
        if self.sensitive and _has_text_predicate(value):
            value = {"redacted": True}
        self.store.target(self.identifier, self.sequence, slot, value)


CURRENT: contextvars.ContextVar[Capture | None] = contextvars.ContextVar("tap_agent_step_capture", default=None)


# Driver codes the protocol contract marks "may have mutated: no": nothing was sent, or the
# command was refused before its input. Every other code, including AUT_CRASHED (which replaces
# a no-mutation code when the app died) and INDETERMINATE, leaves the outcome unknown.
_NO_INPUT_CODES = frozenset({
    "INVALID_REQUEST", "INVALID_SELECTOR", "UNSUPPORTED", "UNAUTHENTICATED", "SESSION_MISMATCH",
    "DUPLICATE_OR_STALE", "OVERLOADED", "AUT_MISMATCH", "NOT_FOUND", "AMBIGUOUS", "NOT_INTERACTABLE",
    "STALE_BEFORE_INPUT", "WAIT_TIMEOUT", "CANCELLED", "DEADLINE_EXCEEDED", "AUT_NOT_INSTALLED",
    "SYNC_PROVIDER_UNAVAILABLE", "DRIVER_UNHEALTHY", "TRANSPORT_LOST",
})
# Server refusals made before the call touches the device (server-api.md, status mapping).
# DEVICE_SETTING, DEVICE_FILE, APP_LIFECYCLE, APP_DATA, DRIVER_COMMAND, timeouts and transport
# failures can follow a change, and a status without the daemon's reason came from elsewhere.
_NO_INPUT_REASONS = frozenset({
    FailureReason.INVALID_ARGUMENT, FailureReason.UNAUTHENTICATED, FailureReason.UNKNOWN_CLIENT_CONNECTION,
    FailureReason.UNKNOWN_ATTACHED_DEVICE, FailureReason.NOT_OWNER, FailureReason.DEVICE_BUSY,
    FailureReason.DEVICE_QUARANTINED, FailureReason.DRIVER_BUILD_MISMATCH, FailureReason.DAEMON_PRECONDITION,
    FailureReason.UNKNOWN_REF, FailureReason.REF_NOT_ADDRESSABLE, FailureReason.UNSUPPORTED_API,
})


def _tap_error(error: BaseException | None) -> TapError | None:
    """The SDK error behind the agent's wrapping, if any."""
    for _ in range(8):
        if error is None or isinstance(error, TapError):
            return error
        error = error.__cause__
    return None


def _sent_nothing(error: BaseException) -> bool:
    """Whether a typed error code proves the one command sent changed nothing. Reads only codes
    and reasons, never exception text or command output."""
    cause = _tap_error(error)
    if isinstance(cause, (WaitTimeoutError, DeviceBusyError, DeviceQuarantinedError)):
        return True
    if isinstance(cause, CommandError):
        return getattr(cause.code, "name", None) in _NO_INPUT_CODES
    if isinstance(cause, ServerError):
        return cause.reason in _NO_INPUT_REASONS
    return False


def outcome(kind: str, failure: BaseException | None, capture: Capture) -> str:
    """A recorded step's status. ``failed`` promises that the device was not changed, so an
    action is ``failed`` only when it sent one plain command and the error code proves that
    command had no effect; with nothing sent it was ``rejected_before_input``; any other failure
    of an action is ``unknown`` and blocks further recorded input until the recording stops."""
    if failure is None:
        return "succeeded"
    if not isinstance(failure, Exception):
        return "interrupted"
    if kind != "action":
        return "failed"
    if capture.inputs == 0:
        return "rejected_before_input"
    if capture.inputs == 1 and capture.returned == 0 and not capture.compound and _sent_nothing(failure):
        return "failed"
    return "unknown"


@contextlib.contextmanager
def sending(*, compound: bool = False) -> Iterator[None]:
    """Wraps each SDK call of an agent method that sends device input (``compound``: one call
    that may send several, such as tap-then-type or a scroll loop). A failure after the call
    returned, in a read or settle that follows, leaves the step ``unknown``, not ``failed``."""
    capture = CURRENT.get()
    if capture is None:
        yield
        return
    capture.inputs += 1
    capture.compound = capture.compound or compound
    yield
    capture.returned += 1


def _wrap(method: Callable[..., str]) -> Callable[..., str]:
    signature = inspect.signature(method)
    name = method.__name__

    @functools.wraps(method)
    def invoke(agent: Agent, *args: Any, **kwargs: Any) -> str:
        if CURRENT.get() is not None:
            return method(agent, *args, **kwargs)
        bound = signature.bind(agent, *args, **kwargs)
        bound.apply_defaults()
        parameters = dict(bound.arguments)
        parameters.pop("self")
        kind = category(name, parameters)
        if kind is None:
            return method(agent, *args, **kwargs)
        recording = agent._run(lambda: agent.records.active(agent.session))
        if recording is None:
            return method(agent, *args, **kwargs)
        lease = agent.records.lease(recording["id"])
        with agent._errors():
            lease.__enter__()
        try:
            with agent._errors():
                connection = agent._held()
                if connection is None or connection.id != recording["connection_id"]:
                    raise RecordError("recording belongs to an ended/different held connection; stop it and start a new named recording; no input was sent")
                sensitive = bool(parameters.get("sensitive")) or (name == "clipboard")
                saved = _value(parameters)
                # Never retain transient refs or raw selector strings. _element captures the
                # package-bound SDK selectors actually used, before any mutation is sent.
                for slot in ("target", "destination"):
                    if saved.get(slot) is not None:
                        saved[slot] = {"selector_ref": slot}
                if sensitive and "text" in saved:
                    saved["text"] = {"redacted": True}
                sequence = agent.records.begin(recording, name.replace("_", "-"), saved, kind=kind)
        except BaseException:
            lease.__exit__(None, None, None)
            raise
        capture = Capture(agent.records, recording["id"], sequence, sensitive, recording["connection_id"])
        token = CURRENT.set(capture)
        started = time.monotonic()
        failure: BaseException | None = None
        result = ""
        try:
            result = method(agent, *args, **kwargs)
        except BaseException as error:
            failure = error
        finally:
            CURRENT.reset(token)
        status = outcome(kind, failure, capture)
        if failure is None:
            if name == "settle" and result.startswith("(screen still changing after "):
                status = "wait_timeout"
            elif parameters.get("settle") and "\n(screen still changing after " in result:
                status = "post_settle_timeout"
        warnings = []
        try:
            agent.records.finish(recording["id"], sequence, status, max(0, round((time.monotonic() - started) * 1000)), type(failure).__name__ if failure is not None else None)
        except RecordError:
            warnings.append("recording outcome could not be saved; input may already have executed; do not repeat it; inspect/stop the incomplete recording")
        finally:
            try:
                lease.__exit__(None, None, None)
            except RecordError:
                warnings.append("recording lease cleanup failed; do not repeat device input")
        warning = "\n".join(f"WARNING: {text}" for text in warnings)
        if failure is not None:
            if warning and len(failure.args) == 1 and isinstance(failure.args[0], str):
                failure.args = (failure.args[0] + "\n" + warning,)
            elif warning:
                warn(warning, RuntimeWarning, stacklevel=2)
            raise failure
        return f"{result}\n{warning}" if warning else result

    return invoke


C = TypeVar("C", bound=type)


def instrument(cls: C) -> C:
    """Wrap only declared high-level operations; helpers remain invisible to recordings."""
    for name in ACTIONS | WAITS | CONDITIONAL:
        setattr(cls, name, _wrap(getattr(cls, name)))
    return cls
