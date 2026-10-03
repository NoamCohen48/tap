"""Steps: the request completed into the recorded step, and a run sends exactly its commands."""

from __future__ import annotations

import pytest
from google.protobuf import json_format

from tap_e2e import CommandError, WaitTimeoutError
from tap_e2e import proto as tap
from tap_studio._gen import studio_pb2 as studio
from tap_studio.recording import RecordingError

from .conftest import device_answers
from tap_studio.steps import CheckFailed, outcome, prepare, revise, run

SEARCH = {"node": {"resource": {"name": "search"}}}
ONE = {"wait_visible": {"selector": SEARCH, "exactly_one": True}}


def step(**kind) -> studio.Step:
    return json_format.ParseDict(kind, studio.Step())


def command(document: dict) -> tap.Command:
    return json_format.ParseDict(document, tap.Command())


def sent(daemon) -> list[tap.Command]:
    """What the device received, without the timeouts the SDK adds."""
    commands = []
    for received in daemon.devices.commands:
        copy = tap.Command()
        copy.CopyFrom(received)
        copy.ClearField("timeout_ms")
        commands.append(copy)
    return commands


def test_an_action_on_a_node_gets_its_wait_and_loses_id_and_outcome():
    request = step(id="x", outcome={"duration_ms": 3}, action={"command": {"tap": {"selector": SEARCH}}})
    prepared = prepare(request, None)
    assert prepared.id == "" and not prepared.HasField("outcome")
    assert prepared.action.wait == command(ONE)
    assert request.action.HasField("wait") is False  # the request is not changed


def test_a_revised_action_waits_for_its_new_selector_with_the_old_timeout():
    go = {"node": {"resource": {"name": "go"}}}
    edited = step(
        id="s4",
        outcome={"duration_ms": 3},
        action={"command": {"tap": {"selector": go}}, "wait": {**ONE, "timeout_ms": "20000"}, "secret": "pin"},
    )
    with pytest.raises(RecordingError, match="set_text only"):
        revise(edited, None)
    edited.action.ClearField("secret")
    revised = revise(edited, None)
    assert (revised.id, revised.outcome.duration_ms) == ("s4", 3)
    assert revised.action.wait == command({"wait_visible": {"selector": go, "exactly_one": True}, "timeout_ms": "20000"})
    with pytest.raises(RecordingError, match="secret_value is only"):
        revise(edited, "1234")


def test_a_picked_selector_waits_for_any_match_and_the_action_picks(daemon, device):
    # Waits count every match whatever the pick, so exactly one could never hold for `.at(1)`.
    second = {"node": {"match": {"property": "PROPERTY_TEXT", "value": "Add"}}, "at": {"index": 1}}
    prepared = prepare(step(action={"command": {"tap": {"selector": second}}}), None)
    assert prepared.action.wait == command({"wait_visible": {"selector": second}})
    daemon.devices.commands.clear()
    run(device, prepared)
    assert sent(daemon) == [prepared.action.wait, prepared.action.command]
    daemon.devices.commands.clear()
    run(device, prepare(step(type={"selector": second, "text": "x", "skip_focus_wait": True}), None))
    assert sent(daemon)[0] == command({"wait_visible": {"selector": second}})


def test_gestures_get_the_default_distance_and_keys_no_wait():
    prepared = prepare(step(action={"command": {"swipe": {"selector": SEARCH, "direction": "DIR_LEFT"}}}), None)
    assert prepared.action.command.swipe.distance_percent == 80
    assert not prepare(step(action={"command": {"press_key": {"key_code": 4}}}), None).action.HasField("wait")
    panel = {"open_system_panel": {"panel": "SYSTEM_PANEL_QUICK_SETTINGS"}}
    assert not prepare(step(action={"command": panel}), None).action.HasField("wait")
    with pytest.raises(RecordingError, match="open_system_panel takes no timeout_ms"):
        prepare(step(action={"command": {**panel, "timeout_ms": 1000}}), None)


@pytest.mark.parametrize(
    ("request_step", "secret", "message"),
    [
        (step(action={"command": {"tap": {"selector": SEARCH}}, "wait": ONE}), None, "inferred by the studio"),
        (step(action={"command": {"press_key": {"key_code": 4}, "timeout_ms": 5}}), None, "no timeout_ms"),
        (step(action={"command": {"exists": {"selector": SEARCH}}}), None, "action.command must be one of"),
        (step(type={"selector": SEARCH, "secret": "pw"}), None, "send its value in secret_value"),
        (step(type={"selector": SEARCH, "text": "x"}), "hunter2", "only for a step that names a secret"),
        (step(), None, "kind is required"),
    ],
)
def test_requests_that_break_a_rule_are_refused(request_step, secret, message):
    with pytest.raises(RecordingError) as caught:
        prepare(request_step, secret)
    assert any(message in problem for problem in caught.value.problems), caught.value.problems


ACTIONS = [
    {"tap": {"selector": SEARCH}},
    {"long_tap": {"selector": SEARCH}},
    {"set_text": {"selector": SEARCH, "text": "wool"}},
    {"clear_text": {"selector": SEARCH}},
    {"scroll": {"selector": SEARCH, "direction": "DIR_DOWN", "distance_percent": 50}},
    {"swipe": {"selector": SEARCH, "direction": "DIR_RIGHT", "distance_percent": 80}},
    {"double_tap": {"selector": SEARCH}},
    {"fling": {"selector": SEARCH, "direction": "DIR_UP"}},
    {"pinch": {"selector": SEARCH, "direction": "PINCH_OPEN", "percent": 60}},
    {"pinch": {"selector": SEARCH, "direction": "PINCH_CLOSE", "percent": 80}},
    {"drag": {"selector": SEARCH, "target": {"node": {"resource": {"name": "bin"}}}}},
    {"perform_ime_action": {"selector": SEARCH}},
    {"perform_accessibility_action": {"selector": SEARCH, "standard": "A11Y_EXPAND"}},
    {"perform_accessibility_action": {"selector": SEARCH, "custom": "Archive"}},
    {"set_progress": {"selector": SEARCH, "value": 3.5}},
]


@pytest.mark.parametrize("op", ACTIONS, ids=lambda op: next(iter(op)) + "-" + str(next(iter(op.values())).get("direction", next(iter(op.values())).get("custom", ""))))
def test_an_action_sends_its_wait_then_its_command_unchanged(daemon, device, op):
    prepared = prepare(step(action={"command": op}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert sent(daemon) == [prepared.action.wait, prepared.action.command]


@pytest.mark.parametrize(
    "op",
    [
        {"press_key": {"key_code": 4}},
        {"open_system_panel": {"panel": "SYSTEM_PANEL_NOTIFICATIONS"}},
        {"open_system_panel": {"panel": "SYSTEM_PANEL_QUICK_SETTINGS"}},
        {"set_orientation": {"orientation": "ORIENTATION_LANDSCAPE"}},
        {"set_display_rotation": {"rotation": "DISPLAY_ROTATION_UPSIDE_DOWN"}},
        {"unfreeze_rotation": {}},
        {"dismiss_keyguard": {}},
        {"hide_keyboard": {}},
        {"set_clipboard": {"text": "SAVE10"}},
        {"choose_permission": {"choice": "PERMISSION_ALLOW_FOREGROUND_ONLY", "accuracy": "LOCATION_APPROXIMATE"}},
        {"choose_permission": {"choice": "PERMISSION_DENY"}},
        {"open_notification": {"match": {"package_name": "com.example", "title": "New message", "mode": "MATCH_EXACT"}, "action": "Reply"}},
        {"open_notification": {"match": {"text": "Ada", "mode": "MATCH_CONTAINS"}}},
        {"dismiss_notification": {"match": {"title": "New message"}}},
        {"dismiss_notification": {"match": {"package_name": "com.example"}}},
    ],
    ids=[
        "press_key",
        "notifications",
        "quick_settings",
        "orientation",
        "rotation",
        "unfreeze",
        "keyguard",
        "keyboard",
        "clipboard",
        "permission_accuracy",
        "permission_deny",
        "open_notification_button",
        "open_notification",
        "dismiss_notification",
        "dismiss_by_package",
    ],
)
def test_an_untargeted_action_is_one_command(daemon, device, op):
    prepared = prepare(step(action={"command": op}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert sent(daemon) == [prepared.action.command]


def test_a_secret_set_text_sends_the_value_and_records_none(daemon, device):
    prepared = prepare(step(action={"command": {"set_text": {"selector": SEARCH}}, "secret": "password"}), "hunter2")
    assert prepared.action.command.set_text.text == ""
    daemon.devices.commands.clear()
    run(device, prepared, "hunter2")
    assert sent(daemon)[-1] == command({"set_text": {"selector": SEARCH, "text": "hunter2"}})


def test_a_type_step_is_the_sdk_type_text_flow(daemon, device):
    prepared = prepare(step(type={"selector": SEARCH, "text": "jonson"}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    ops = [c.WhichOneof("op") for c in sent(daemon)]
    assert ops == ["wait_visible", "tap", "snapshot", "type_text"]
    assert sent(daemon)[-1] == command({"type_text": {"text": "jonson"}})
    daemon.devices.commands.clear()
    run(device, prepare(step(type={"selector": SEARCH, "secret": "pin", "skip_focus_wait": True}), "1234"), "1234")
    assert [c.WhichOneof("op") for c in sent(daemon)] == ["wait_visible", "tap", "type_text"]
    assert sent(daemon)[-1].type_text.text == "1234"


@pytest.mark.parametrize(
    ("condition", "value", "ops"),
    [
        ("CONDITION_VISIBLE", {}, ["wait_visible"]),
        ("CONDITION_ONE", {}, ["wait_visible"]),
        ("CONDITION_GONE", {}, ["wait_gone"]),
        ("CONDITION_ENABLED", {}, ["snapshot"]),
        ("CONDITION_CHECKED", {}, ["snapshot"]),
        ("CONDITION_FOCUSED", {}, ["snapshot"]),
        ("CONDITION_TEXT_EQUALS", {"text": "Wool socks"}, ["snapshot"]),
        ("CONDITION_TEXT_CONTAINS", {"text": "Wool"}, ["snapshot"]),
        ("CONDITION_COUNT", {"count": 1}, ["count"]),
    ],
)
def test_a_wait_is_the_sdk_wait_of_the_same_name(daemon, device, condition, value, ops):
    prepared = prepare(step(wait={"selector": SEARCH, "condition": condition, **value}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert [c.WhichOneof("op") for c in sent(daemon)] == ops
    if condition in ("CONDITION_VISIBLE", "CONDITION_ONE"):
        assert sent(daemon)[0].wait_visible.exactly_one is (condition == "CONDITION_ONE")


@pytest.mark.parametrize(
    ("check", "value", "op"),
    [
        ("CHECK_EXISTS", {}, "exists"),
        ("CHECK_COUNT", {"count": 1}, "count"),
        ("CHECK_TEXT_EQUALS", {"text": "Wool socks"}, "snapshot"),
        ("CHECK_TEXT_CONTAINS", {"text": "Wool"}, "snapshot"),
        ("CHECK_ENABLED", {}, "snapshot"),
        ("CHECK_CHECKED", {}, "snapshot"),
        ("CHECK_FOCUSED", {}, "snapshot"),
    ],
)
def test_an_assertion_is_one_query_with_no_wait(daemon, device, check, value, op):
    prepared = prepare(step(assertion={"selector": SEARCH, "check": check, **value}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert [c.WhichOneof("op") for c in sent(daemon)] == [op]
    assert getattr(sent(daemon)[0], op).selector == json_format.ParseDict(SEARCH, tap.Selector())


@pytest.mark.parametrize(
    ("check", "value", "found"),
    [
        ("CHECK_COUNT", {"count": 2}, "expected 2 matches, found 1"),
        ("CHECK_TEXT_EQUALS", {"text": "Wool"}, "expected text 'Wool', found 'Wool socks'"),
        ("CHECK_TEXT_CONTAINS", {"text": "silk"}, "expected text containing 'silk', found 'Wool socks'"),
        ("CHECK_DISABLED", {}, "expected disabled"),
        ("CHECK_UNCHECKED", {}, "expected unchecked"),
    ],
)
def test_an_assertion_that_does_not_hold_says_what_was_found(daemon, device, check, value, found):
    with pytest.raises(CheckFailed) as caught:
        run(device, prepare(step(assertion={"selector": SEARCH, "check": check, **value}), None))
    assert str(caught.value).startswith(found)
    result = outcome(3, caught.value, "emulator-5554")
    assert result.mismatch == str(caught.value)
    assert not result.HasField("error") and not result.HasField("failure")


def test_scroll_until_waits_for_its_container_then_scrolls_until_the_target_exists(daemon, device):
    target = {"node": {"match": {"property": "PROPERTY_TEXT", "value": "Row 40"}}}
    prepared = prepare(step(scroll_until={"container": SEARCH, "target": target, "direction": "DIR_DOWN"}), None)
    assert (prepared.scroll_until.max_scrolls, prepared.scroll_until.distance_percent) == (20, 80)
    found = iter([False, False, True])
    daemon.devices.responder = lambda c: (
        tap.CommandResult(bool=next(found)) if c.WhichOneof("op") == "exists" else device_answers(c)
    )
    daemon.devices.commands.clear()
    run(device, prepared)
    ops = [c.WhichOneof("op") for c in sent(daemon)]
    assert ops == ["wait_visible", "exists", "scroll", "exists", "scroll", "exists"]
    assert sent(daemon)[0].wait_visible.exactly_one
    assert sent(daemon)[2] == command({"scroll": {"selector": SEARCH, "direction": "DIR_DOWN", "distance_percent": 80}})
    descendant = sent(daemon)[1].exists.selector
    assert descendant.node.WhichOneof("kind") == "all_of"  # container.descendant(target)


def test_app_waits_are_the_sdk_app_waits(daemon, device):
    visible = prepare(step(app_wait={"command": {"wait_app_visible": {"package_name": "com.example"}}}), None)
    stable = prepare(step(app_wait={"command": {"wait_screen_stable": {"package_name": "com.example", "signal": "STABILITY_PIXELS"}}}), None)
    # The SDK's defaults are made explicit, so the recording holds what was sent.
    assert stable.app_wait.command.wait_screen_stable.stable_for_ms == 500
    settled = prepare(step(app_wait={"command": {"wait_screen_stable": {"package_name": "com.example"}}}), None)
    assert settled.app_wait.command.wait_screen_stable.signal == tap.STABILITY_ALL
    daemon.devices.commands.clear()
    run(device, visible)
    run(device, stable)
    assert sent(daemon) == [visible.app_wait.command, stable.app_wait.command]


@pytest.mark.parametrize(
    ("operation", "extra", "rpc"),
    [
        ("cold_launch", {"activity": ".Main"}, "cold_launch"),
        ("launch", {}, "launch"),
        ("force_stop", {}, "force_stop"),
    ],
)
def test_an_app_step_is_the_app_call(daemon, device, operation, extra, rpc):
    run(device, prepare(step(app={"operation": operation, "package_name": "com.example", **extra}), None))
    assert daemon.apps.owners[-1][0] == rpc
    logged = daemon.connections.logs[device.owner_connection.id][-1].app
    assert (logged.operation, logged.package_name) == (operation, "com.example")


def test_failures_become_the_outcome(daemon, device):
    def not_found(received: tap.Command):
        if received.WhichOneof("op") == "tap":
            return tap.CommandResult(error=tap.Error(code=tap.ERR_NOT_FOUND, detail="GONE", message="no node"))
        return None

    daemon.devices.responder = not_found
    with pytest.raises(CommandError) as caught:
        run(device, prepare(step(action={"command": {"tap": {"selector": SEARCH}}}), None))
    result = outcome(12, caught.value, "emulator-5554")
    assert result.duration_ms == 12
    assert result.error == tap.Error(code=tap.ERR_NOT_FOUND, detail="GONE", message="no node")

    daemon.devices.responder = lambda received: tap.CommandResult(
        error=tap.Error(code=tap.ERR_WAIT_TIMEOUT, detail="AMBIGUOUS", match_count=2)
    )
    with pytest.raises(WaitTimeoutError) as timed_out:
        run(device, prepare(step(wait={"selector": SEARCH, "condition": "CONDITION_ONE"}), None))
    error = outcome(5, timed_out.value, "emulator-5554").error
    assert (error.code, error.detail, error.match_count) == (tap.ERR_WAIT_TIMEOUT, "AMBIGUOUS", 2)
    assert "match exactly one node" in error.message


@pytest.mark.parametrize(
    "op",
    [
        {"await_toast": {"text": "Saved", "mode": "MATCH_EXACT", "package_name": "com.example"}},
        {"await_toast": {"text": "Saved"}},
        {"await_toast": {}},
        {"await_notification": {"match": {"package_name": "com.example", "title": "New message", "mode": "MATCH_EXACT"}}},
        {"await_notification": {"match": {}}},
        {"wait_permission_prompt": {}},
    ],
    ids=["toast", "toast_default_mode", "any_toast", "notification", "any_notification", "permission_prompt"],
)
def test_a_device_wait_is_one_command(daemon, device, op):
    daemon.devices.responder = lambda c: {
        "await_toast": tap.CommandResult(toast=tap.Toast(text="Saved", package_name="com.example")),
        "await_notification": tap.CommandResult(notification=tap.DeviceNotification(package_name="com.example", title="New message")),
        "wait_permission_prompt": tap.CommandResult(permission_prompt=tap.PermissionPrompt(package_name="p", choices=[tap.PERMISSION_ALLOW])),
    }.get(c.WhichOneof("op"))
    prepared = prepare(step(device_wait={"command": op}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert sent(daemon) == [prepared.device_wait.command]


@pytest.mark.parametrize(
    ("call", "rpc"),
    [
        ({"operation": "set_animations", "enabled": False}, tap.SetAnimationsRequest(enabled=False)),
        ({"operation": "set_font_scale", "font_scale": 1.5}, tap.SetFontScaleRequest(scale=1.5)),
        ({"operation": "set_density", "density_dpi": 320}, tap.SetDensityRequest(dpi=320)),
        ({"operation": "set_density"}, tap.SetDensityRequest()),
        ({"operation": "set_network", "wifi": False}, tap.SetNetworkRequest(wifi=False)),
        ({"operation": "set_system_locales", "locales": ["fr-FR", "en"]}, tap.SetSystemLocalesRequest(locales=["fr-FR", "en"])),
        (
            {"operation": "set_location", "latitude": 48.85, "longitude": 2.35, "accuracy_m": 20},
            tap.SetLocationRequest(latitude=48.85, longitude=2.35, accuracy_m=20),
        ),
        ({"operation": "set_stay_awake", "enabled": True}, tap.SetStayAwakeRequest(enabled=True)),
        (
            {"operation": "set_accessibility_display", "bold_text": True, "color_inversion": False},
            tap.SetAccessibilityDisplayRequest(bold_text=True, color_inversion=False),
        ),
    ],
    ids=lambda value: value["operation"] if isinstance(value, dict) else "",
)
def test_a_device_condition_is_the_device_call(daemon, device, call, rpc):
    run(device, prepare(step(device=call), None))
    sent_call = daemon.devices.conditions[-1]
    sent_call.ClearField("client_connection_id")
    sent_call.ClearField("attached_device_id")
    assert sent_call == rpc


@pytest.mark.parametrize(
    ("call", "message"),
    [
        ({"operation": "push_file"}, "device.operation must be one of"),
        ({"operation": "set_animations"}, "set_animations needs device.enabled"),
        ({"operation": "set_animations", "enabled": True, "wifi": True}, "device.wifi does not apply"),
        ({"operation": "set_network"}, "sets at least one of"),
        ({"operation": "set_font_scale", "font_scale": 3}, "0.5..2.0"),
        ({"operation": "set_location", "latitude": 95, "longitude": 0}, "latitude -90..90"),
    ],
)
def test_device_conditions_that_break_a_rule_are_refused(call, message):
    with pytest.raises(RecordingError) as caught:
        prepare(step(device=call), None)
    assert any(message in problem for problem in caught.value.problems), caught.value.problems


@pytest.mark.parametrize(
    ("app", "rpc"),
    [
        ({"operation": "foreground"}, "foreground"),
        ({"operation": "open_link", "uri": "myapp://orders/42"}, "open_link"),
        ({"operation": "revoke_permission", "permission": "android.permission.CAMERA"}, "revoke_permission"),
        ({"operation": "set_locales", "locales": ["fr-FR"]}, "set_locales"),
    ],
)
def test_the_other_app_calls(daemon, device, app, rpc):
    run(device, prepare(step(app={"package_name": "com.example", **app}), None))
    assert daemon.apps.owners[-1][0] == rpc


def test_launch_extras_keep_their_types(daemon, device):
    extras = [
        {"key": "user", "string_value": "ada"},
        {"key": "debug", "bool_value": True},
        {"key": "count", "int_value": 3},
        {"key": "id", "long_value": "9000000000"},
        {"key": "ratio", "float_value": 0.5},
    ]
    run(device, prepare(step(app={"operation": "launch", "package_name": "com.example", "extras": extras}), None))
    assert list(daemon.apps.launches[-1].extras) == [json_format.ParseDict(e, tap.IntentExtra()) for e in extras]


@pytest.mark.parametrize(
    ("app", "message"),
    [
        ({"operation": "open_link"}, "uri is required by open_link"),
        ({"operation": "force_stop", "uri": "x://y"}, "uri is required by open_link and only there"),
        ({"operation": "revoke_permission"}, "permission is required"),
        ({"operation": "foreground", "extras": [{"key": "a", "int_value": 1}]}, "extras apply to launch"),
        ({"operation": "launch", "extras": [{"key": "a"}]}, "every extra has a key and a value"),
        ({"operation": "launch", "locales": ["fr"]}, "locales apply to set_locales only"),
    ],
)
def test_app_calls_that_break_a_rule_are_refused(app, message):
    with pytest.raises(RecordingError) as caught:
        prepare(step(app={"package_name": "com.example", **app}), None)
    assert any(message in problem for problem in caught.value.problems), caught.value.problems


@pytest.mark.parametrize(
    ("assertion", "holds"),
    [
        ({"check": "DEVICE_CHECK_FOREGROUND_ACTIVITY", "text": "com.example/com.example.Main"}, True),
        ({"check": "DEVICE_CHECK_FOREGROUND_ACTIVITY", "text": "com.example/com.example.Other"}, False),
        ({"check": "DEVICE_CHECK_KEYBOARD_SHOWN"}, False),
        ({"check": "DEVICE_CHECK_KEYBOARD_HIDDEN"}, True),
        ({"check": "DEVICE_CHECK_CLIPBOARD_EQUALS", "text": "SAVE10"}, True),
        ({"check": "DEVICE_CHECK_CLIPBOARD_EQUALS", "text": "other"}, False),
    ],
)
def test_a_device_assertion_is_one_query(daemon, device, assertion, holds):
    daemon.devices.foreground = ("com.example", "com.example.Main")
    daemon.devices.responder = lambda c: (
        tap.CommandResult(text="SAVE10") if c.WhichOneof("op") == "get_clipboard" else device_answers(c)
    )
    prepared = prepare(step(device_assertion=assertion), None)
    if holds:
        run(device, prepared)
    else:
        with pytest.raises(CheckFailed, match="expected"):
            run(device, prepared)


@pytest.mark.parametrize(
    ("assertion", "message"),
    [
        ({}, "check is required"),
        ({"check": "DEVICE_CHECK_FOREGROUND_ACTIVITY"}, "needs a text value"),
        ({"check": "DEVICE_CHECK_FOREGROUND_ACTIVITY", "text": "Main"}, "takes package/class"),
        ({"check": "DEVICE_CHECK_KEYBOARD_SHOWN", "text": "x"}, "takes no value"),
    ],
)
def test_device_assertions_that_break_a_rule_are_refused(assertion, message):
    with pytest.raises(RecordingError) as caught:
        prepare(step(device_assertion=assertion), None)
    assert any(message in problem for problem in caught.value.problems), caught.value.problems


@pytest.mark.parametrize(
    ("op", "message"),
    [
        ({"pinch": {"selector": SEARCH}}, "pinch.direction is required"),
        ({"pinch": {"selector": SEARCH, "direction": "PINCH_OPEN", "percent": 0}}, "pinch.percent must be 1..100"),
        ({"fling": {"selector": SEARCH}}, "fling.direction is required"),
        ({"drag": {"selector": SEARCH}}, "drag.target has no node"),
        ({"perform_accessibility_action": {"selector": SEARCH}}, "a standard action or a custom label"),
        ({"set_orientation": {}}, "orientation is required"),
        ({"choose_permission": {}}, "choice is required"),
        ({"dismiss_notification": {"match": {}}}, "needs a title, a text or a package_name"),
        ({"dismiss_notification": {"match": {"package_name": "p", "mode": "MATCH_CONTAINS"}}}, "mode is set with a title or text"),
    ],
)
def test_new_actions_that_break_a_rule_are_refused(op, message):
    with pytest.raises(RecordingError) as caught:
        prepare(step(action={"command": op}), None)
    assert any(message in problem for problem in caught.value.problems), caught.value.problems


def test_a_pinch_gets_the_default_percent():
    prepared = prepare(step(action={"command": {"pinch": {"selector": SEARCH, "direction": "PINCH_CLOSE"}}}), None)
    assert prepared.action.command.pinch.percent == 80
    assert prepared.action.wait == command(ONE)
