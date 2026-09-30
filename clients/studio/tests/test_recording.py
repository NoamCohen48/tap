"""tap-recording/1: the documented example round-trips, and every rule rejects what it should."""

from __future__ import annotations

import copy
import json

import pytest

from tap_studio.recording import FORMAT, RecordingError, dump, dumps, load, loads, validate

SEARCH = {"node": {"resource": {"name": "search", "aut_package": True}}}
PASSWORD = {"node": {"resource": {"name": "password", "aut_package": True}}}
ONE = {"wait_visible": {"selector": SEARCH, "exactly_one": True}}
SECOND = {"node": {"match": {"property": "PROPERTY_TEXT", "value": "Add"}}, "at": {"index": 1}}

EXAMPLE = {
    "format": "tap-recording/1",
    "recorded_at": "2026-09-29T17:42:10Z",
    "recorder": "tap-studio 0.0.1",
    "device": {"serial": "emulator-5554", "api_level": 34, "manufacturer": "Google", "model": "sdk_gphone64_x86_64"},
    "aut_package": "com.example.basket",
    "secrets": ["password"],
    "steps": [
        {"id": "s1", "outcome": {"duration_ms": 1830},
         "app": {"operation": "cold_launch", "package_name": "com.example.basket"}},
        {"id": "s2", "action": {
            "command": {"set_text": {"selector": SEARCH, "text": "wool"}},
            "wait": ONE,
            "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}},
        {"id": "s3", "type": {"selector": PASSWORD, "secret": "password",
                              "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}},
        {"id": "s4", "action": {"command": {"press_key": {"key_code": 4}}}},
        {"id": "s5", "note": "the confirmation screen",
         "outcome": {"duration_ms": 5003,
                     "error": {"code": "ERR_WAIT_TIMEOUT", "message": "no match", "match_count": 0}},
         "assertion": {"selector": {"node": {"match": {"property": "PROPERTY_TEXT", "value": "Order placed"}}},
                       "condition": "CONDITION_VISIBLE"}},
    ],
}


def text(document: dict) -> str:
    return json.dumps(document)


def with_steps(*steps: dict, secrets: list[str] | None = None) -> dict:
    document = copy.deepcopy(EXAMPLE)
    document["steps"] = list(steps)
    document["secrets"] = secrets or []
    return document


def rejects(document: dict, message: str) -> None:
    with pytest.raises(RecordingError) as caught:
        loads(text(document))
    assert any(message in problem for problem in caught.value.problems), caught.value.problems


def test_example_round_trips_unchanged():
    recording = loads(text(EXAMPLE))
    assert [s.WhichOneof("kind") for s in recording.steps] == ["app", "action", "type", "action", "assertion"]
    assert json.loads(dumps(recording)) == EXAMPLE
    assert loads(dumps(recording)) == recording


def test_dump_and_load_a_file(tmp_path):
    path = tmp_path / "nested" / "flow.json"
    dump(loads(text(EXAMPLE)), path)
    assert load(path) == loads(text(EXAMPLE))
    assert path.read_text().endswith("}\n")


def test_written_in_canonical_form():
    document = with_steps({"id": "a", "action": {
        "command": {"tap": {"selector": SEARCH}, "timeoutMs": 2000},
        "wait": {"waitVisible": {"selector": SEARCH, "exactlyOne": True}},
        "selectorOrigin": 2}})
    written = json.loads(dumps(loads(text(document))))["steps"][0]["action"]
    assert written == {"command": {"tap": {"selector": SEARCH}, "timeout_ms": "2000"}, "wait": ONE,
                       "selector_origin": "SELECTOR_ORIGIN_ALTERNATIVE"}


def test_unknown_fields_are_ignored():
    document = copy.deepcopy(EXAMPLE)
    document["added_later"] = 1
    document["steps"][0]["added_later"] = {"x": True}
    assert loads(text(document)) == loads(text(EXAMPLE))


def test_recorded_at_is_written_in_utc():
    document = copy.deepcopy(EXAMPLE)
    document["recorded_at"] = "2026-09-29T20:42:10+03:00"
    assert json.loads(dumps(loads(text(document))))["recorded_at"] == "2026-09-29T17:42:10Z"


def test_malformed_json_and_wrong_types_are_recording_errors():
    with pytest.raises(RecordingError):
        loads("{not json")
    rejects({**EXAMPLE, "steps": {"id": "x"}}, "steps")


def test_header_rules():
    document = copy.deepcopy(EXAMPLE)
    document["format"] = "tap-recording/2"
    for key in ("recorded_at", "recorder", "device", "aut_package"):
        del document[key]
    with pytest.raises(RecordingError) as caught:
        loads(text(document))
    assert caught.value.problems[:5] == [
        f"format must be {FORMAT!r}, not 'tap-recording/2'",
        "recorded_at is required",
        "recorder is required",
        "device.serial is required",
        "aut_package is required",
    ]


def test_every_problem_is_reported_with_its_step():
    document = with_steps({"id": "a"}, {"id": "a", "action": {"command": {"tap": {"selector": SEARCH}}}})
    with pytest.raises(RecordingError) as caught:
        loads(text(document))
    assert caught.value.problems == [
        "steps[0] (a): kind is required (app, action, type or assertion)",
        "steps[1] (a): id is used twice",
        "steps[1] (a): action tap is recorded with its wait (wait_visible)",
    ]


def test_step_ids_are_required_and_short():
    rejects(with_steps({"action": {"command": {"press_key": {"key_code": 4}}}}), "steps[0]: id is required")
    rejects(with_steps({"id": "x" * 65, "action": {"command": {"press_key": {"key_code": 4}}}}), "at most 64")


def test_secrets_list_exactly_the_names_used():
    document = copy.deepcopy(EXAMPLE)
    document["secrets"] = []
    rejects(document, "missing ['password']")
    document["secrets"] = ["password", "pin"]
    rejects(document, "unused ['pin']")
    document["secrets"] = ["password", "password"]
    rejects(document, "secrets lists a name twice")


@pytest.mark.parametrize(
    ("app", "message"),
    [
        ({"operation": "install", "package_name": "p"}, "app.operation must be one of"),
        ({"operation": "launch"}, "app.package_name is required"),
        ({"operation": "grant_permission", "package_name": "p"}, "app.permission is required"),
        ({"operation": "launch", "package_name": "p", "permission": "x"}, "app.permission is required"),
        ({"operation": "force_stop", "package_name": "p", "activity": ".Main"}, "app.activity applies"),
    ],
)
def test_app_step_rules(app, message):
    rejects(with_steps({"id": "a", "app": app}), message)


def test_app_step_accepts_grant_and_launch_activity():
    loads(text(with_steps(
        {"id": "a", "app": {"operation": "grant_permission", "package_name": "p", "permission": "android.permission.CAMERA"}},
        {"id": "b", "app": {"operation": "cold_launch", "package_name": "p", "activity": ".Main"}},
    )))


def action(command: dict, wait: dict | None = None, **extra) -> dict:
    step = {"command": command, **extra}
    if wait is not None:
        step["wait"] = wait
    return {"id": "a", "action": step}


@pytest.mark.parametrize(
    ("step", "message"),
    [
        (action({"exists": {"selector": SEARCH}}), "action.command must be one of"),
        (action({}), "action.command must be one of tap, long_tap"),
        (action({"tap": {"selector": SEARCH}}), "tap is recorded with its wait"),
        (action({"tap": {}}, ONE), "action.command.tap.selector has no node"),
        (action({"tap": {"selector": SEARCH}}, {"wait_visible": {"selector": SEARCH}}), "exactly_one"),
        (action({"tap": {"selector": SEARCH}}, {"wait_gone": {"selector": SEARCH}}), "must be wait_visible"),
        (action({"tap": {"selector": SECOND}}, {"wait_visible": {"selector": SECOND, "exactly_one": True}}),
         "must not be exactly_one: the selector has a pick"),
        (action({"tap": {"selector": SEARCH}}, {"wait_visible": {"selector": PASSWORD, "exactly_one": True}}),
         "wait's selector differs"),
        (action({"press_key": {"key_code": 4}}, ONE), "press_key has no selector"),
        (action({"press_key": {"key_code": 4}}, selector_origin="SELECTOR_ORIGIN_EDITED"), "press_key has no selector"),
        (action({"tap": {"selector": SEARCH}}, ONE, secret="pw"), "secret applies to set_text only"),
        (action({"set_text": {"selector": SEARCH, "text": "x"}}, ONE, secret="pw"), "carries no text"),
    ],
)
def test_action_step_rules(step, message):
    secret = step["action"].get("secret")
    rejects(with_steps(step, secrets=[secret] if secret else []), message)


def test_a_picked_selector_waits_for_any_match():
    loads(text(with_steps(action({"tap": {"selector": SECOND}}, {"wait_visible": {"selector": SECOND}}))))


def test_secret_set_text_and_every_action_op_load():
    loads(text(with_steps(action({"set_text": {"selector": SEARCH}}, ONE, secret="pw"), secrets=["pw"])))
    for op in ({"tap": {"selector": SEARCH}}, {"long_tap": {"selector": SEARCH}},
               {"clear_text": {"selector": SEARCH}},
               {"scroll": {"selector": SEARCH, "direction": "DIR_DOWN"}},
               {"swipe": {"selector": SEARCH, "direction": "DIR_LEFT", "distance_percent": 50}}):
        loads(text(with_steps(action(op, ONE, selector_origin="SELECTOR_ORIGIN_ALTERNATIVE"))))


def test_type_step_rules():
    recording = loads(text(with_steps({"id": "a", "type": {"selector": SEARCH, "text": "jonson", "skip_focus_wait": True}})))
    assert recording.steps[0].type.skip_focus_wait
    rejects(with_steps({"id": "a", "type": {"selector": SEARCH}}), "type needs text or secret")
    rejects(with_steps({"id": "a", "type": {"text": "x"}}), "type.selector has no node")


@pytest.mark.parametrize(
    ("condition", "value", "ok"),
    [
        ("CONDITION_VISIBLE", {}, True),
        ("CONDITION_VISIBLE", {"text": "x"}, False),
        ("CONDITION_TEXT_EQUALS", {"text": "Wool socks"}, True),
        ("CONDITION_TEXT_CONTAINS", {"text": ""}, True),
        ("CONDITION_TEXT_EQUALS", {}, False),
        ("CONDITION_TEXT_EQUALS", {"count": 3}, False),
        ("CONDITION_COUNT", {"count": 0}, True),
        ("CONDITION_COUNT", {"count": -1}, False),
        ("CONDITION_COUNT", {"text": "3"}, False),
        ("CONDITION_ENABLED", {}, True),
        ("CONDITION_UNSPECIFIED", {}, False),
    ],
)
def test_assertion_values(condition, value, ok):
    document = with_steps({"id": "a", "assertion": {"selector": SEARCH, "condition": condition, **value}})
    if ok:
        loads(text(document))
    else:
        with pytest.raises(RecordingError):
            loads(text(document))


def test_outcome_has_at_most_one_of_error_and_failure():
    step = {"id": "a", "action": {"command": {"press_key": {"key_code": 3}}}}
    rejects(with_steps({**step, "outcome": {"duration_ms": 1, "error": {"code": "ERR_NOT_FOUND"},
                                           "failure": {"reason": "FAILURE_REASON_ADB_FAILED"}}}),
            "at most one of error and failure")
    loads(text(with_steps({**step, "outcome": {"duration_ms": 1, "failure": {"reason": "FAILURE_REASON_ADB_FAILED"}}})))


def test_dumps_refuses_an_invalid_recording():
    recording = loads(text(EXAMPLE))
    recording.format = "other"
    with pytest.raises(RecordingError):
        dumps(recording)
    with pytest.raises(RecordingError):
        validate(recording)
