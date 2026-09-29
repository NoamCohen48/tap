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

from ._gen import studio_pb2 as studio

FORMAT = "tap-recording/1"

APP_OPERATIONS = ("cold_launch", "launch", "force_stop", "clear_data", "grant_permission")
"""The app calls a recording holds (``tap.v1.AppCall.operation``)."""

ACTION_OPS = ("tap", "long_tap", "set_text", "clear_text", "scroll", "swipe", "press_key")
"""The ``tap.v1.Command`` ops an action step holds; all but ``press_key`` take a selector."""

_TEXT_CONDITIONS = (studio.CONDITION_TEXT_EQUALS, studio.CONDITION_TEXT_CONTAINS)
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
    if not recording.aut_package:
        problems.append("aut_package is required")

    ids: set[str] = set()
    used: set[str] = set()
    for index, step in enumerate(recording.steps):
        where = f"steps[{index}]" + (f" ({step.id})" if step.id else "")
        if not step.id or len(step.id) > _MAX_ID:
            problems.append(f"{where}: id is required, at most {_MAX_ID} characters")
        elif step.id in ids:
            problems.append(f"{where}: id is used twice")
        ids.add(step.id)
        kind = step.WhichOneof("kind")
        if kind is None:
            problems.append(f"{where}: kind is required (app, action, type or assertion)")
        else:
            check = {"app": _app, "action": _action, "type": _type, "assertion": _assertion}[kind]
            problems += [f"{where}: {problem}" for problem in check(getattr(step, kind), used)]
        if step.outcome.HasField("error") and step.outcome.HasField("failure"):
            problems.append(f"{where}: outcome has at most one of error and failure")

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


def _action(action: studio.ActionStep, used: set[str]) -> list[str]:
    op = action.command.WhichOneof("op")
    if op not in ACTION_OPS:
        return [f"action.command must be one of {', '.join(ACTION_OPS)}, not {op or 'empty'}"]
    problems = []
    if op == "press_key":
        if action.HasField("wait") or action.selector_origin:
            problems.append("press_key has no selector, so no wait or selector_origin")
    else:
        selector = getattr(action.command, op).selector
        if not selector.HasField("node"):
            problems.append(f"action.command.{op}.selector has no node")
        if not action.HasField("wait"):
            problems.append(f"action {op} is recorded with its wait (wait_visible, exactly_one)")
        elif action.wait.WhichOneof("op") != "wait_visible" or not action.wait.wait_visible.exactly_one:
            problems.append("action.wait must be wait_visible with exactly_one")
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


def _assertion(step: studio.AssertionStep, used: set[str]) -> list[str]:
    problems = [] if step.selector.HasField("node") else ["assertion.selector has no node"]
    value = step.WhichOneof("value")
    condition = studio.Condition.Name(step.condition)
    if step.condition == studio.CONDITION_UNSPECIFIED:
        problems.append("assertion.condition is required")
    elif step.condition in _TEXT_CONDITIONS:
        if value != "text":
            problems.append(f"{condition} needs a text value")
    elif step.condition == studio.CONDITION_COUNT:
        if value != "count" or step.count < 0:
            problems.append("CONDITION_COUNT needs a count ≥ 0")
    elif value is not None:
        problems.append(f"{condition} takes no value")
    return problems
