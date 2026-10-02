"""``tap-recording/1``: what Tap Studio records, as one JSON document.

The document is ``tap.studio.v1.Recording`` (``clients/studio/proto/studio.proto``) in proto3 JSON
with the original field names, as ``tap-events/1``. The proto is the format's definition; this
module reads and writes it and checks the rules proto cannot state (``validate``).
``.docs/recorder.md`` describes the format.
"""

from __future__ import annotations

import os
import pathlib

from google.protobuf import json_format
from tap_e2e import proto as tap

from ._gen import studio_pb2 as studio

FORMAT = "tap-recording/1"

APP_OPERATIONS = ("cold_launch", "launch", "force_stop", "clear_data", "grant_permission")
"""The app calls a recording holds (``tap.v1.AppCall.operation``)."""

ACTION_OPS = ("tap", "long_tap", "set_text", "clear_text", "scroll", "swipe", "press_key", "open_system_panel")
"""The ``tap.v1.Command`` ops an action step holds; all but the ``UNTARGETED_OPS`` take a selector."""
UNTARGETED_OPS = ("press_key", "open_system_panel")
"""The action ops without a selector, so without a wait or a selector origin."""

APP_WAIT_OPS = ("wait_app_visible", "wait_screen_stable")
"""The ``tap.v1.Command`` ops an app wait step holds."""

MAX_SCROLLS = 1000
"""The most scrolls a scroll_until step may take."""

_TEXT_CONDITIONS = (studio.CONDITION_TEXT_EQUALS, studio.CONDITION_TEXT_CONTAINS)
_TEXT_CHECKS = (studio.CHECK_TEXT_EQUALS, studio.CHECK_TEXT_CONTAINS)
_DIRECTIONS = (tap.DIR_UP, tap.DIR_DOWN, tap.DIR_LEFT, tap.DIR_RIGHT)
_MAX_ID = 64


class RecordingError(ValueError):
    """A document that is not valid ``tap-recording/1``; ``problems`` lists every rule it breaks."""

    def __init__(self, problems: list[str]):
        super().__init__("not a valid tap-recording/1 document:\n  " + "\n  ".join(problems))
        self.problems = problems


def loads(text: str | bytes) -> studio.Recording:
    """Parse and validate a document. Unknown fields are ignored: additive changes keep ``/1``."""
    recording = studio.Recording()
    try:
        json_format.Parse(text, recording, ignore_unknown_fields=True)
    except json_format.ParseError as error:
        raise RecordingError([str(error)]) from None
    validate(recording)
    return recording


def load(path: str | os.PathLike[str]) -> studio.Recording:
    return loads(pathlib.Path(path).read_bytes())


def dumps(recording: studio.Recording) -> str:
    """The document as indented JSON in canonical form (enums by name, defaults left out).
    Refuses to write an invalid recording."""
    validate(recording)
    return json_format.MessageToJson(recording, preserving_proto_field_name=True, indent=2)


def dump(recording: studio.Recording, path: str | os.PathLike[str]) -> None:
    text = dumps(recording)
    target = pathlib.Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text + "\n", encoding="utf-8")


def validate(recording: studio.Recording) -> None:
    """Raises ``RecordingError`` with every rule of ``studio.proto`` the recording breaks."""
    problems: list[str] = []
    if recording.format != FORMAT:
        problems.append(f"format must be {FORMAT!r}, not {recording.format!r}")
    if not recording.HasField("recorded_at"):
        problems.append("recorded_at is required")
    if not recording.recorder:
        problems.append("recorder is required")
    if not recording.device.serial:
        problems.append("device.serial is required")

    ids: set[str] = set()
    used: set[str] = set()
    for index, step in enumerate(recording.steps):
        where = f"steps[{index}]" + (f" ({step.id})" if step.id else "")
        if not step.id or len(step.id) > _MAX_ID:
            problems.append(f"{where}: id is required, at most {_MAX_ID} characters")
        elif step.id in ids:
            problems.append(f"{where}: id is used twice")
        ids.add(step.id)
        problems += [f"{where}: {problem}" for problem in _step(step, used)]

    if len(set(recording.secrets)) != len(recording.secrets):
        problems.append("secrets lists a name twice")
    listed = set(recording.secrets)
    if used != listed:
        problems.append(
            "secrets must list exactly the names the steps use "
            f"(missing {sorted(used - listed)}, unused {sorted(listed - used)})"
        )
    if problems:
        raise RecordingError(problems)


def validate_step(step: studio.Step) -> None:
    """Raises ``RecordingError`` with every rule one step breaks, apart from its id."""
    problems = _step(step, set())
    if problems:
        raise RecordingError(problems)


def secret_name(step: studio.Step) -> str | None:
    """The secret a step types, if any."""
    kind = step.WhichOneof("kind")
    if kind == "action" and step.action.HasField("secret"):
        return step.action.secret
    if kind == "type" and step.type.WhichOneof("input") == "secret":
        return step.type.secret
    return None


def _step(step: studio.Step, used: set[str]) -> list[str]:
    problems = []
    kind = step.WhichOneof("kind")
    if kind is None:
        problems.append("kind is required (app, action, type, wait, assertion, scroll_until or app_wait)")
    else:
        check = {
            "app": _app,
            "action": _action,
            "type": _type,
            "wait": _wait,
            "assertion": _assertion,
            "scroll_until": _scroll_until,
            "app_wait": _app_wait,
        }[kind]
        problems += check(getattr(step, kind), used)
    results = [step.outcome.HasField("error"), step.outcome.HasField("failure"), bool(step.outcome.mismatch)]
    if sum(results) > 1:
        problems.append("outcome has at most one of error, failure and mismatch")
    return problems


def _app(call, used: set[str]) -> list[str]:
    problems = []
    if call.operation not in APP_OPERATIONS:
        problems.append(f"app.operation must be one of {', '.join(APP_OPERATIONS)}, not {call.operation!r}")
    if not call.package_name:
        problems.append("app.package_name is required")
    if call.HasField("permission") != (call.operation == "grant_permission"):
        problems.append("app.permission is required by grant_permission and only there")
    if call.HasField("activity") and call.operation not in ("launch", "cold_launch"):
        problems.append("app.activity applies to launch and cold_launch only")
    return problems


def picks(selector: tap.Selector) -> bool:
    """Whether the selector picks among several matches (``first`` or ``at``). The driver's
    waits count every match whatever the pick, so such a selector waits for at least one match
    and the action then picks; any other waits for exactly one."""
    return selector.WhichOneof("pick") in ("first", "at")


def _action(action: studio.ActionStep, used: set[str]) -> list[str]:
    op = action.command.WhichOneof("op")
    if op not in ACTION_OPS:
        return [f"action.command must be one of {', '.join(ACTION_OPS)}, not {op or 'empty'}"]
    problems = []
    if op in UNTARGETED_OPS:
        if action.HasField("wait") or action.selector_origin:
            problems.append(f"{op} has no selector, so no wait or selector_origin")
        panel = action.command.open_system_panel.panel
        if op == "open_system_panel" and panel not in (tap.SYSTEM_PANEL_NOTIFICATIONS, tap.SYSTEM_PANEL_QUICK_SETTINGS):
            problems.append("open_system_panel.panel must be SYSTEM_PANEL_NOTIFICATIONS or SYSTEM_PANEL_QUICK_SETTINGS")
    else:
        selector = getattr(action.command, op).selector
        if not selector.HasField("node"):
            problems.append(f"action.command.{op}.selector has no node")
        if not action.HasField("wait"):
            problems.append(f"action {op} is recorded with its wait (wait_visible)")
        elif action.wait.WhichOneof("op") != "wait_visible":
            problems.append("action.wait must be wait_visible")
        elif action.wait.wait_visible.exactly_one == picks(selector):
            problems.append(
                "action.wait must not be exactly_one: the selector has a pick, and waits count every match"
                if picks(selector)
                else "action.wait must be exactly_one: the selector has no pick"
            )
        elif action.wait.wait_visible.selector != selector:
            problems.append(f"action.wait's selector differs from command.{op}.selector")
    if action.HasField("secret"):
        used.add(action.secret)
        if op != "set_text":
            problems.append("action.secret applies to set_text only")
        elif action.command.set_text.text:
            problems.append("a secret set_text carries no text")
        if not action.secret:
            problems.append("action.secret names a secret")
    return problems


def _type(step: studio.TypeStep, used: set[str]) -> list[str]:
    problems = [] if step.selector.HasField("node") else ["type.selector has no node"]
    which = step.WhichOneof("input")
    if which is None:
        problems.append("type needs text or secret")
    elif which == "secret":
        used.add(step.secret)
        if not step.secret:
            problems.append("type.secret names a secret")
    return problems


def _wait(step: studio.WaitStep, used: set[str]) -> list[str]:
    problems = [] if step.selector.HasField("node") else ["wait.selector has no node"]
    if step.condition == studio.CONDITION_UNSPECIFIED:
        return [*problems, "wait.condition is required"]
    takes = "text" if step.condition in _TEXT_CONDITIONS else "count" if step.condition == studio.CONDITION_COUNT else None
    return problems + _value(step, studio.Condition.Name(step.condition), takes)


def _assertion(step: studio.AssertionStep, used: set[str]) -> list[str]:
    problems = [] if step.selector.HasField("node") else ["assertion.selector has no node"]
    if step.check == studio.CHECK_UNSPECIFIED:
        return [*problems, "assertion.check is required"]
    takes = "text" if step.check in _TEXT_CHECKS else "count" if step.check == studio.CHECK_COUNT else None
    return problems + _value(step, studio.Check.Name(step.check), takes)


def _value(step: studio.WaitStep | studio.AssertionStep, name: str, takes: str | None) -> list[str]:
    """The value a wait's condition or an assertion's check takes: text, a count ≥ 0, or none."""
    value = step.WhichOneof("value")
    if takes == "text" and value != "text":
        return [f"{name} needs a text value"]
    if takes == "count" and (value != "count" or step.count < 0):
        return [f"{name} needs a count ≥ 0"]
    if takes is None and value is not None:
        return [f"{name} takes no value"]
    return []


def _scroll_until(step: studio.ScrollUntilStep, used: set[str]) -> list[str]:
    problems = []
    if not step.container.HasField("node"):
        problems.append("scroll_until.container has no node")
    if not step.target.HasField("node"):
        problems.append("scroll_until.target has no node")
    if step.direction not in _DIRECTIONS:
        problems.append("scroll_until.direction is required")
    if step.HasField("max_scrolls") and not 0 <= step.max_scrolls <= MAX_SCROLLS:
        problems.append(f"scroll_until.max_scrolls must be 0..{MAX_SCROLLS}")
    if step.HasField("distance_percent") and not 1 <= step.distance_percent <= 100:
        problems.append("scroll_until.distance_percent must be 1..100")
    return problems


def _app_wait(step: studio.AppWaitStep, used: set[str]) -> list[str]:
    op = step.command.WhichOneof("op")
    if op not in APP_WAIT_OPS:
        return [f"app_wait.command must be one of {', '.join(APP_WAIT_OPS)}, not {op or 'empty'}"]
    wait = getattr(step.command, op)
    problems = [] if wait.package_name else [f"app_wait.command.{op}.package_name is required"]
    if op == "wait_screen_stable" and wait.HasField("stable_for_ms") and not 1 <= wait.stable_for_ms <= 30000:
        problems.append("wait_screen_stable.stable_for_ms must be 1..30000")
    return problems
