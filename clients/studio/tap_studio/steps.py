"""Steps: completing the page's request into a recordable step, running it, and its outcome.

A step runs through ``tap-e2e``'s public API (``Screen.wait(...).one()``, ``Element.tap``,
``App.cold_launch``, the ``ElementWait`` conditions), never through raw protocol calls. Each
kind maps onto exactly the ``tap.v1`` commands the recording holds, so running a recording in
the studio and replaying its commands elsewhere send the same messages
(``tests/test_steps.py`` checks it command by command).
"""

from __future__ import annotations

from collections.abc import Callable

from tap_e2e import (
    AppLifecycleError,
    StabilitySignal,
    CommandError,
    Device,
    DeviceBusyError,
    DeviceQuarantinedError,
    Direction,
    Selector,
    ServerError,
    TapError,
    WaitTimeoutError,
)
from tap_e2e import proto as tap

from ._gen import studio_pb2 as studio
from .recording import UNTARGETED_OPS, RecordingError, picks, secret_name, validate_step

DEFAULT_GESTURE_PERCENT = 80
"""The distance ``scroll``, ``swipe`` and ``scroll_until`` get when the page leaves it out, as the
SDKs' default."""
DEFAULT_MAX_SCROLLS = 20
"""``scroll_until``'s scroll budget when the page leaves it out, as the SDKs' default."""
DEFAULT_STABLE_FOR_MS = 500
"""``wait_screen_stable``'s quiet period when the page leaves it out, as the SDKs' default."""


class CheckFailed(Exception):
    """An assertion's query ran but its answer was not the expected one; the message says what
    was found."""


def prepare(step: studio.Step, secret_value: str | None) -> studio.Step:
    """The step the page asked for, completed into what the recording holds: no id or outcome,
    the inferred wait before an action on a node (decision 6), the gesture distance made
    explicit. Raises ``RecordingError`` when the request breaks a rule of the format or the secret
    value does not fit the step."""
    prepared = studio.Step()
    prepared.CopyFrom(step)
    prepared.ClearField("id")
    prepared.ClearField("outcome")
    problems: list[str] = []
    if prepared.WhichOneof("kind") == "action":
        if prepared.action.HasField("wait"):
            problems.append("action.wait is inferred by the studio: leave it out")
        problems += _complete_action(prepared.action)
    _complete_defaults(prepared)
    problems += _problems(prepared)
    name = secret_name(prepared)
    if name is not None and secret_value is None:
        problems.append(f"the step types the secret {name!r}: send its value in secret_value")
    if name is None and secret_value is not None:
        problems.append("secret_value is only for a step that names a secret")
    if problems:
        raise RecordingError(problems)
    return prepared


def revise(step: studio.Step, secret_value: str | None) -> studio.Step:
    """An edited step completed as ``prepare`` does, keeping its id and outcome: an action's wait
    is inferred again from its (possibly new) selector, keeping the wait's timeout, and a secret may come without its value
    (a replay asks for it). Raises ``RecordingError`` as ``prepare``."""
    revised = studio.Step()
    revised.CopyFrom(step)
    problems: list[str] = []
    if revised.WhichOneof("kind") == "action":
        action = revised.action
        timeout = action.wait.timeout_ms if action.wait.HasField("timeout_ms") else None
        action.ClearField("wait")
        problems += _complete_action(action)
        if timeout is not None and action.HasField("wait"):
            action.wait.timeout_ms = timeout
    _complete_defaults(revised)
    problems += _problems(revised)
    if secret_name(revised) is None and secret_value is not None:
        problems.append("secret_value is only for a step that names a secret")
    if problems:
        raise RecordingError(problems)
    return revised


def _problems(step: studio.Step) -> list[str]:
    try:
        validate_step(step)
    except RecordingError as error:
        return error.problems
    return []


def _complete_action(action: studio.ActionStep) -> list[str]:
    command = action.command
    op = command.WhichOneof("op")
    if op in UNTARGETED_OPS:
        # Device.press_key and open_notifications take no timeout, so a recorded one could not
        # be replayed as sent.
        return [f"{op} takes no timeout_ms"] if command.HasField("timeout_ms") else []
    if op in ("scroll", "swipe"):
        gesture = getattr(command, op)
        if not gesture.HasField("distance_percent"):
            gesture.distance_percent = DEFAULT_GESTURE_PERCENT
    if op is not None and not action.HasField("wait") and getattr(command, op).HasField("selector"):
        selector = getattr(command, op).selector
        action.wait.CopyFrom(tap.Command(wait_visible=tap.WaitVisible(selector=selector, exactly_one=not picks(selector))))
    return []


def _complete_defaults(step: studio.Step) -> None:
    """The SDK defaults a run sends, made explicit so the recording holds what was sent."""
    kind = step.WhichOneof("kind")
    if kind == "scroll_until":
        if not step.scroll_until.HasField("max_scrolls"):
            step.scroll_until.max_scrolls = DEFAULT_MAX_SCROLLS
        if not step.scroll_until.HasField("distance_percent"):
            step.scroll_until.distance_percent = DEFAULT_GESTURE_PERCENT
    elif kind == "app_wait" and step.app_wait.command.WhichOneof("op") == "wait_screen_stable":
        wait = step.app_wait.command.wait_screen_stable
        if not wait.HasField("stable_for_ms"):
            wait.stable_for_ms = DEFAULT_STABLE_FOR_MS
        if wait.signal == tap.STABILITY_UNSPECIFIED:
            wait.signal = tap.STABILITY_ALL


def run(device: Device, step: studio.Step, secret_value: str | None = None) -> None:
    """Runs a prepared step on ``device`` (blocking, on the device's thread). Raises the
    ``TapError`` of the first command that failed, or ``CheckFailed`` for an assertion that did
    not hold."""
    kind = step.WhichOneof("kind")
    if kind == "app":
        _app(device, step.app)
    elif kind == "action":
        _action(device, step.action, secret_value)
    elif kind == "type":
        _type(device, step.type, secret_value)
    elif kind == "wait":
        _wait(device, step.wait)
    elif kind == "assertion":
        _assertion(device, step.assertion)
    elif kind == "scroll_until":
        _scroll_until(device, step.scroll_until)
    elif kind == "app_wait":
        _app_wait(device, step.app_wait)
    else:
        raise ValueError("a step without a kind")


def _seconds(message) -> float | None:
    return message.timeout_ms / 1000 if message.HasField("timeout_ms") else None


def _app(device: Device, call: tap.AppCall) -> None:
    app = device.app(call.package_name)
    timeout = _seconds(call)
    activity = call.activity if call.HasField("activity") else None
    if call.operation == "cold_launch":
        app.cold_launch(activity, timeout)
    elif call.operation == "launch":
        app.launch(activity, timeout)
    elif call.operation == "force_stop":
        app.force_stop(timeout)
    elif call.operation == "clear_data":
        app.clear_data(timeout)
    elif call.operation == "grant_permission":
        app.grant_permission(call.permission)
    else:
        raise ValueError(f"unknown app operation {call.operation!r}")


def _direction(number: int) -> Direction:
    return Direction[tap.Direction.Name(number).removeprefix("DIR_")]


def _action(device: Device, action: studio.ActionStep, secret_value: str | None) -> None:
    command = action.command
    op = command.WhichOneof("op")
    if op == "press_key":
        device.press_key(command.press_key.key_code)
        return
    if op == "open_system_panel":
        if command.open_system_panel.panel == tap.SYSTEM_PANEL_QUICK_SETTINGS:
            device.open_quick_settings()
        else:
            device.open_notifications()
        return
    message = getattr(command, op)
    timeout = _seconds(command)
    wait = device.screen.wait(Selector.from_proto(action.wait.wait_visible.selector), _seconds(action.wait))
    element = wait.one() if action.wait.wait_visible.exactly_one else wait.visible()
    perform: dict[str, Callable[[], None]] = {
        "tap": lambda: element.tap(timeout),
        "long_tap": lambda: element.long_tap(timeout),
        "set_text": lambda: element.set_text(secret_value if action.HasField("secret") else message.text, timeout),
        "clear_text": lambda: element.clear_text(timeout),
        "scroll": lambda: element.scroll(_direction(message.direction), message.distance_percent, timeout),
        "swipe": lambda: element.swipe(_direction(message.direction), message.distance_percent, timeout),
    }
    perform[op]()


def _type(device: Device, step: studio.TypeStep, secret_value: str | None) -> None:
    value = secret_value if step.WhichOneof("input") == "secret" else step.text
    wait = device.screen.wait(Selector.from_proto(step.selector))
    element = wait.visible() if picks(step.selector) else wait.one()
    element.type_text(value or "", await_focus=not step.skip_focus_wait)


def _wait(device: Device, step: studio.WaitStep) -> None:
    wait = device.screen.wait(Selector.from_proto(step.selector))
    conditions: dict[int, Callable[[], object]] = {
        studio.CONDITION_VISIBLE: wait.visible,
        studio.CONDITION_ONE: wait.one,
        studio.CONDITION_GONE: wait.gone,
        studio.CONDITION_ENABLED: wait.enabled,
        studio.CONDITION_DISABLED: wait.disabled,
        studio.CONDITION_CHECKED: wait.checked,
        studio.CONDITION_UNCHECKED: wait.unchecked,
        studio.CONDITION_FOCUSED: wait.focused,
        studio.CONDITION_TEXT_EQUALS: lambda: wait.text_equals(step.text),
        studio.CONDITION_TEXT_CONTAINS: lambda: wait.text_contains(step.text),
        studio.CONDITION_COUNT: lambda: wait.count(step.count),
    }
    conditions[step.condition]()


def _assertion(device: Device, step: studio.AssertionStep) -> None:
    element = device.screen.element(Selector.from_proto(step.selector))
    check = step.check
    if check == studio.CHECK_EXISTS:
        if not element.exists():
            raise CheckFailed("expected at least one match, found none")
    elif check == studio.CHECK_COUNT:
        count = element.count()
        if count != step.count:
            raise CheckFailed(f"expected {step.count} matches, found {count}")
    elif check in (studio.CHECK_TEXT_EQUALS, studio.CHECK_TEXT_CONTAINS):
        text = element.text()
        holds = text == step.text if check == studio.CHECK_TEXT_EQUALS else step.text in (text or "")
        if not holds:
            expected = "text" if check == studio.CHECK_TEXT_EQUALS else "text containing"
            raise CheckFailed(f"expected {expected} {step.text!r}, found {text!r}")
    elif check in (studio.CHECK_ENABLED, studio.CHECK_DISABLED):
        if element.is_enabled() != (check == studio.CHECK_ENABLED):
            raise CheckFailed(f"expected {'enabled' if check == studio.CHECK_ENABLED else 'disabled'}, it is not")
    elif check in (studio.CHECK_CHECKED, studio.CHECK_UNCHECKED):
        if element.is_checked() != (check == studio.CHECK_CHECKED):
            raise CheckFailed(f"expected {'checked' if check == studio.CHECK_CHECKED else 'unchecked'}, it is not")
    elif check == studio.CHECK_FOCUSED:
        if not element.snapshot().focused:
            raise CheckFailed("expected focused, it is not")
    else:
        raise ValueError(f"unknown check {check}")


def _scroll_until(device: Device, step: studio.ScrollUntilStep) -> None:
    wait = device.screen.wait(Selector.from_proto(step.container))
    container = wait.visible() if picks(step.container) else wait.one()
    container.scroll_until(
        Selector.from_proto(step.target), _direction(step.direction), step.max_scrolls, step.distance_percent
    )


def _app_wait(device: Device, step: studio.AppWaitStep) -> None:
    command = step.command
    timeout = _seconds(command)
    if command.WhichOneof("op") == "wait_app_visible":
        device.app(command.wait_app_visible.package_name).await_visible(timeout)
        return
    wait = command.wait_screen_stable
    signal = StabilitySignal[tap.StabilitySignal.Name(wait.signal).removeprefix("STABILITY_")]
    device.app(wait.package_name).await_screen_stable(wait.stable_for_ms / 1000, timeout, signal)


def outcome(duration_ms: int, error: TapError | CheckFailed | None, serial: str) -> studio.Outcome:
    """How a run went: the duration, and for a failure the driver's ``Error`` (a command or a
    wait), the call's ``Failure`` (the RPC failed) or the assertion's mismatch."""
    result = studio.Outcome(duration_ms=duration_ms)
    if isinstance(error, CheckFailed):
        result.mismatch = str(error)
    elif isinstance(error, CommandError):
        result.error.code = _error_code(error.code.name)
        if error.detail is not None:
            result.error.detail = error.detail
        if error.driver_message is not None:
            result.error.message = error.driver_message
    elif isinstance(error, WaitTimeoutError):
        result.error.code = tap.ERR_WAIT_TIMEOUT
        result.error.message = str(error)
        if error.reason is not None:
            result.error.detail = error.reason.name
        if error.match_count is not None:
            result.error.match_count = error.match_count
    elif error is not None:
        result.failure.serial = serial
        result.failure.reason = _failure_reason(error)
    return result


def _error_code(name: str) -> int:
    try:
        return tap.ErrorCode.Value(f"ERR_{name}")
    except ValueError:
        return tap.ERR_UNKNOWN


def _failure_reason(error: TapError) -> int:
    if isinstance(error, ServerError):
        try:
            return tap.FailureReason.Value(f"FAILURE_REASON_{error.reason.name}")
        except ValueError:
            return tap.FAILURE_REASON_UNSPECIFIED
    by_type = {
        AppLifecycleError: tap.FAILURE_REASON_APP_LIFECYCLE,
        DeviceBusyError: tap.FAILURE_REASON_DEVICE_BUSY,
        DeviceQuarantinedError: tap.FAILURE_REASON_DEVICE_QUARANTINED,
    }
    return next((reason for kind, reason in by_type.items() if isinstance(error, kind)), tap.FAILURE_REASON_UNSPECIFIED)
