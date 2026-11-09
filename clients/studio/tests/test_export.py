"""The pytest export means what a replay means: every kind of step, compared call for call."""
from __future__ import annotations

import re
from typing import Any

import pytest
from google.protobuf import json_format

from tap_e2e import Selector, TapError
from tap_studio import export, steps
from tap_studio._gen import studio_pb2 as pb
from tap_studio.export import ExportError, to_pytest
from tap_studio.recording import (
    ACTION_OPS,
    APP_OPERATIONS,
    APP_WAIT_OPS,
    DEVICE_OPERATIONS,
    DEVICE_WAIT_OPS,
    FORMAT,
)

X = {"node": {"resource": {"name": "x"}}}
Y = {"node": {"match": {"property": "PROPERTY_TEXT", "value": "Add", "mode": "MATCH_CONTAINS"}}}
AT = {"node": {"resource": {"package_name": "com.a", "name": "row"}}, "at": {"index": 2}}
FIRST = {"node": {"match": {"property": "PROPERTY_CONTENT_DESCRIPTION", "value": "Close"}}, "first": {}}
# No builder writes this one the same way, so it goes through its text form.
ODD = {"node": {"any_of": {"nodes": [X["node"], {"flag": {"property": "FLAG_CHECKED", "value": False}}]}}}


def action(op: str, **fields: Any) -> dict:
    selector = fields.pop("selector", X)
    timeout = fields.pop("timeout_ms", None)
    command: dict = {op: {"selector": selector, **fields}}
    if timeout is not None:
        command["timeout_ms"] = timeout
    return {"action": {"command": command}}


def device_action(op: str, **fields: Any) -> dict:
    return {"action": {"command": {op: fields}}}


def app(operation: str, **fields: Any) -> dict:
    return {"app": {"operation": operation, "package_name": "com.a", **fields}}


def device(operation: str, **fields: Any) -> dict:
    return {"device": {"operation": operation, **fields}}


ACTIONS = {
    "tap": action("tap", timeout_ms=1500),
    "long_tap": action("long_tap"),
    "double_tap": action("double_tap"),
    "multi_tap": action("multi_tap", count=3, interval_ms=120),
    "hold": action("hold", duration_ms=800),
    "set_text": action("set_text", text="wool"),
    "clear_text": action("clear_text"),
    "scroll": action("scroll", direction="DIR_DOWN", distance_percent=40),
    "swipe": action("swipe", direction="DIR_LEFT"),
    "fling": action("fling", direction="DIR_UP"),
    "pinch": action("pinch", direction="PINCH_OPEN", percent=30),
    "drag": action("drag", target=FIRST),
    "perform_ime_action": action("perform_ime_action"),
    "perform_accessibility_action": action("perform_accessibility_action", standard="A11Y_FOCUS"),
    "set_progress": action("set_progress", value=2.5),
    "press_key": device_action("press_key", key_code=4),
    "open_system_panel": device_action("open_system_panel", panel="SYSTEM_PANEL_QUICK_SETTINGS"),
    "set_orientation": device_action("set_orientation", orientation="ORIENTATION_LANDSCAPE"),
    "set_display_rotation": device_action("set_display_rotation", rotation="DISPLAY_ROTATION_NATURAL"),
    "unfreeze_rotation": device_action("unfreeze_rotation"),
    "dismiss_keyguard": device_action("dismiss_keyguard"),
    "hide_keyboard": device_action("hide_keyboard"),
    "set_clipboard": device_action("set_clipboard", text="copied"),
    "choose_permission": device_action("choose_permission", choice="PERMISSION_ALLOW"),
    "open_notification": device_action("open_notification", match={"title": "New", "mode": "MATCH_CONTAINS"}, action="Reply"),
    "dismiss_notification": device_action("dismiss_notification", match={"package_name": "com.a"}),
}
APPS = {
    "cold_launch": app("cold_launch", activity=".Main", timeout_ms=9000, extras=[{"key": "n", "long_value": "7"}, {"key": "s", "string_value": "v"}, {"key": "b", "bool_value": True}, {"key": "i", "int_value": 3}, {"key": "f", "float_value": 1.5}]),
    "launch": app("launch"),
    "foreground": app("foreground", timeout_ms=500),
    "force_stop": app("force_stop"),
    "clear_data": app("clear_data"),
    "grant_permission": app("grant_permission", permission="android.permission.CAMERA"),
    "revoke_permission": app("revoke_permission", permission="android.permission.CAMERA"),
    "open_link": app("open_link", uri="a://b", any_app=True),
    "set_locales": app("set_locales", locales=["fr-FR", "en"]),
}
APP_WAITS = {
    "wait_app_visible": {"app_wait": {"command": {"wait_app_visible": {"package_name": "com.a"}, "timeout_ms": 700}}},
    "wait_screen_stable": {"app_wait": {"command": {"wait_screen_stable": {"package_name": "", "stable_for_ms": 250, "signal": "STABILITY_TREE"}}}},
}
DEVICE_WAITS = {
    "await_toast": {"device_wait": {"command": {"await_toast": {"text": "Saved", "mode": "MATCH_EXACT", "package_name": "com.a"}, "timeout_ms": 300}}},
    "await_notification": {"device_wait": {"command": {"await_notification": {"match": {"title": "Hi", "mode": "MATCH_EXACT"}}}}},
    "wait_permission_prompt": {"device_wait": {"command": {"wait_permission_prompt": {}}}},
}
DEVICES = {
    "set_animations": device("set_animations", enabled=False),
    "set_dark_mode": device("set_dark_mode", enabled=True),
    "set_font_scale": device("set_font_scale", font_scale=1.5),
    "set_density": device("set_density", density_dpi=320),
    "set_network": device("set_network", wifi=False, mobile_data=True),
    "set_system_locales": device("set_system_locales", locales=["de-DE"]),
    "set_location": device("set_location", latitude=1.5, longitude=2.5, accuracy_m=3.5),
    "set_stay_awake": device("set_stay_awake", enabled=True),
    "set_accessibility_display": device("set_accessibility_display", bold_text=True),
}


def wait(condition: str, **value: Any) -> dict:
    return {"wait": {"selector": X, "condition": f"CONDITION_{condition}", **value}}


def assertion(check: str, **value: Any) -> dict:
    return {"assertion": {"selector": X, "check": f"CHECK_{check}", **value}}


WAITS = {
    "VISIBLE": wait("VISIBLE", timeout_ms=1200),
    "ONE": wait("ONE"),
    "GONE": wait("GONE"),
    "ENABLED": wait("ENABLED"),
    "DISABLED": wait("DISABLED"),
    "CHECKED": wait("CHECKED"),
    "UNCHECKED": wait("UNCHECKED"),
    "FOCUSED": wait("FOCUSED"),
    "TEXT_EQUALS": wait("TEXT_EQUALS", text="a"),
    "TEXT_CONTAINS": wait("TEXT_CONTAINS", text="a"),
    "COUNT": wait("COUNT", count=0),
    # A builder the export writes as itself, and a condition only the text form gives.
    "UNTIL": wait("UNTIL", until={"flag": {"property": "FLAG_ENABLED", "value": True}}),
}
ASSERTIONS = {
    "EXISTS": assertion("EXISTS", timeout_ms=900),
    "COUNT": assertion("COUNT", count=1),
    "TEXT_EQUALS": assertion("TEXT_EQUALS", text="Wool socks"),
    "TEXT_CONTAINS": assertion("TEXT_CONTAINS", text="Wool"),
    "ENABLED": assertion("ENABLED"),
    "DISABLED": assertion("DISABLED"),
    "CHECKED": assertion("CHECKED"),
    "UNCHECKED": assertion("UNCHECKED"),
    "FOCUSED": assertion("FOCUSED"),
}
DEVICE_ASSERTIONS = {
    "FOREGROUND_ACTIVITY": {"device_assertion": {"check": "DEVICE_CHECK_FOREGROUND_ACTIVITY", "text": "com.a/.Main"}},
    "KEYBOARD_SHOWN": {"device_assertion": {"check": "DEVICE_CHECK_KEYBOARD_SHOWN"}},
    "KEYBOARD_HIDDEN": {"device_assertion": {"check": "DEVICE_CHECK_KEYBOARD_HIDDEN"}},
    "CLIPBOARD_EQUALS": {"device_assertion": {"check": "DEVICE_CHECK_CLIPBOARD_EQUALS", "text": "x"}},
}
OTHERS = {
    "type_text": {"type": {"selector": X, "text": "hi", "timeout_ms": 400, "precondition_timeout_ms": 500}},
    "type_no_focus": {"type": {"selector": AT, "text": "hi", "skip_focus_wait": True}},
    "type_focus_budget": {"type": {"selector": X, "text": "hi", "focus_timeout_ms": 600}},
    "scroll_until": {"scroll_until": {"container": X, "target": Y, "direction": "DIR_DOWN", "max_scrolls": 5, "distance_percent": 60, "timeout_ms": 800, "precondition_timeout_ms": 900}},
    "scroll_until_picked": {"scroll_until": {"container": FIRST, "target": ODD, "direction": "DIR_UP"}},
    "wait_with_budget_selector": {"wait": {"selector": ODD, "condition": "CONDITION_VISIBLE"}},
    "wait_until_compound": wait("UNTIL", until=ODD["node"]),
    "custom_action": action("perform_accessibility_action", custom="Archive"),
    "tap_on_a_pick": action("tap", selector=AT),
    "tap_on_contains": action("tap", selector=Y),
}
EVERY = {
    **{f"action-{k}": v for k, v in ACTIONS.items()},
    **{f"app-{k}": v for k, v in APPS.items()},
    **{f"appwait-{k}": v for k, v in APP_WAITS.items()},
    **{f"devicewait-{k}": v for k, v in DEVICE_WAITS.items()},
    **{f"device-{k}": v for k, v in DEVICES.items()},
    **{f"wait-{k}": v for k, v in WAITS.items()},
    **{f"assert-{k}": v for k, v in ASSERTIONS.items()},
    **{f"deviceassert-{k}": v for k, v in DEVICE_ASSERTIONS.items()},
    **OTHERS,
}


class Spy:
    """Stands for the SDK's objects: records every call in order and answers what checks read."""

    ANSWERS = {
        "count": 1,
        "text": "Wool socks",
        "exists": True,
        "is_enabled": True,
        "is_checked": True,
        "keyboard_shown": True,
        "clipboard": "x",
    }

    def __init__(self, calls: list, path: str = "device"):
        object.__setattr__(self, "_calls", calls)
        object.__setattr__(self, "_path", path)

    def __getattr__(self, name: str):
        if name == "timeouts":
            return _Timeouts()
        return Spy(self._calls, f"{self._path}.{name}")

    def __setattr__(self, name: str, value):
        # Putting the original back changes nothing, whether a run does it or not.
        if value != _Timeouts():
            self._calls.append((f"{self._path}.{name}=", (repr(value),), ()))

    def __call__(self, *args, **kwargs):
        self._calls.append((self._path, tuple(_plain(a) for a in args), tuple(sorted((k, _plain(v)) for k, v in kwargs.items()))))
        leaf = self._path.rsplit(".", 1)[-1]
        if leaf in self.ANSWERS:
            return self.ANSWERS[leaf]
        if leaf == "snapshot":
            return _Snapshot()
        return _Top() if leaf == "foreground_activity" else Spy(self._calls, f"{self._path}()")


class _Snapshot:
    focused = True


class _Top:
    package_name = "com.a"
    class_name = ".Main"


def _plain(value):
    return value.to_proto().SerializeToString() if isinstance(value, Selector) else repr(value)


from dataclasses import dataclass  # noqa: E402


@dataclass(frozen=True)
class _Timeouts:
    wait: float = 10.0


def recording(*documents: dict, parameters: list | None = None, secrets: list | None = None) -> pb.Recording:
    made = pb.Recording(format=FORMAT, recorder="tap-studio 0.0.1", secrets=secrets or [])
    made.recorded_at.GetCurrentTime()
    made.device.serial = "emulator-5554"
    for index, document in enumerate(documents, 1):
        step = steps.prepare(json_format.ParseDict(document, pb.Step()), "s3cret" if "secret" in str(document) else None)
        step.id = f"s{index}"
        made.steps.append(step)
    for parameter in parameters or []:
        made.parameters.add(**parameter)
    return made


def run_exported(source: str, device, monkeypatch=None) -> None:
    """Runs the generated test function exactly as pytest would, with ``tap_device``."""
    module: dict = {}
    exec(compile(source, "<export>", "exec"), module)  # noqa: S102 - our own generated source
    test = next(value for name, value in module.items() if name.startswith("test_"))
    test(device)


def compare(step_document: dict) -> None:
    made = recording(step_document)
    studio_calls: list = []
    studio_failed = False
    try:
        steps.run(Spy(studio_calls), made.steps[0])
    except steps.CheckFailed:
        studio_failed = True
    exported_calls: list = []
    exported_failed = False
    try:
        run_exported(to_pytest(made), Spy(exported_calls))
    except AssertionError:
        exported_failed = True
    assert exported_calls == studio_calls
    assert exported_failed == studio_failed


@pytest.mark.parametrize("name", EVERY)
def test_every_step_makes_the_same_sdk_calls_as_a_replay(name):
    compare(EVERY[name])


@pytest.mark.parametrize(
    ("kinds", "table"),
    [
        (ACTION_OPS, ACTIONS),
        (APP_OPERATIONS, APPS),
        (APP_WAIT_OPS, APP_WAITS),
        (DEVICE_WAIT_OPS, DEVICE_WAITS),
        (DEVICE_OPERATIONS, DEVICES),
        ([n.removeprefix("CONDITION_") for n in pb.Condition.keys() if n != "CONDITION_UNSPECIFIED"], WAITS),
        ([n.removeprefix("CHECK_") for n in pb.Check.keys() if n != "CHECK_UNSPECIFIED"], ASSERTIONS),
        ([n.removeprefix("DEVICE_CHECK_") for n in pb.DeviceCheck.keys() if n != "DEVICE_CHECK_UNSPECIFIED"], DEVICE_ASSERTIONS),
    ],
)
def test_no_kind_of_step_is_left_out_of_the_comparison(kinds, table):
    """A new op or check fails here until it is exported and compared."""
    assert set(kinds) == set(table)


def test_a_wrong_expectation_fails_both_ways():
    compare(assertion("TEXT_EQUALS", text="something else"))
    compare(assertion("COUNT", count=0))
    compare(assertion("UNCHECKED"))


def test_it_sends_the_same_commands_to_a_device(daemon, device):
    from .test_steps import sent

    documents = [action("tap"), {"type": {"selector": X, "text": "hi"}}, wait("VISIBLE"), assertion("EXISTS"), device_action("press_key", key_code=4)]
    made = recording(*documents)
    for step in made.steps:
        steps.run(device, step)
    studio = sent(daemon)
    daemon.devices.commands.clear()
    run_exported(to_pytest(made), device)
    assert sent(daemon) == studio


def test_parameters_are_constants_and_secrets_come_from_the_environment(monkeypatch):
    made = recording(
        {"type": {"selector": X, "parameter": "user"}},
        {"type": {"selector": X, "secret": "pw"}},
        {"wait": {"selector": X, "condition": "CONDITION_TEXT_EQUALS", "parameter": "user"}},
        action("set_text", selector=Y, text="") | {},
        parameters=[{"name": "user", "value": "ada", "description": "who\nsigns in"}, {"name": "unused", "value": "x"}],
        secrets=["pw"],
    )
    source = to_pytest(made)
    assert "'user': 'ada',  # who signs in" in source and "unused" not in source
    assert "s3cret" not in source
    assert "TAP_SECRET_PW" in source
    monkeypatch.setenv("TAP_SECRET_PW", "from-env")
    calls: list = []
    run_exported(source, Spy(calls))
    typed = [call for call in calls if call[0].endswith(".type_text")]
    assert typed[0][1][0] == "'ada'" and typed[1][1][0] == "'from-env'"
    assert ("sensitive", "True") in typed[1][2]


def test_a_missing_secret_fails_before_any_input(monkeypatch):
    monkeypatch.delenv("TAP_SECRET_PW", raising=False)
    calls: list = []
    with pytest.raises(KeyError):
        run_exported(to_pytest(recording({"type": {"selector": X, "secret": "pw"}}, secrets=["pw"])), Spy(calls))
    assert calls == []


def test_a_note_cannot_break_out_of_its_comment():
    made = recording(action("tap"))
    made.steps[0].note = "fine\nimport os; os.system('boom')"
    source = to_pytest(made)
    assert "\nimport os; os.system" not in source
    compile(source, "<export>", "exec")


def test_text_cannot_break_out_of_its_string():
    source = to_pytest(recording(action("set_text", text='a"; raise SystemExit(1) #\n\'')))
    calls: list = []
    run_exported(source, Spy(calls))
    assert any("raise SystemExit" in str(call) for call in calls)


@pytest.mark.parametrize(("name", "expected"), [("My Flow.v2", "test_my_flow_v2"), ("", "test_recording"), ("../../etc", "test_etc"), ("日本語", "test_recording")])
def test_the_function_name_is_safe(name, expected):
    assert export.test_name(name) == expected


def test_an_empty_or_invalid_recording_is_refused():
    with pytest.raises(ExportError, match="no steps"):
        to_pytest(recording())
    broken = recording(action("tap"))
    broken.format = "other/1"
    with pytest.raises(ExportError, match="format"):
        to_pytest(broken)


def test_a_step_it_cannot_write_is_refused_not_dropped(monkeypatch):
    monkeypatch.setattr("tap_studio.export.validate", lambda recording: None)
    empty = recording(action("tap"))
    empty.steps[0].ClearField("action")
    with pytest.raises(ExportError, match="kind"):
        to_pytest(empty)


def test_the_file_is_valid_python_with_only_public_imports():
    source = to_pytest(recording(*list(EVERY.values())))
    compile(source, "<export>", "exec")
    assert not re.search(r"tap_studio|tap_e2e\._|\.execute\(|retry", source.split('"""', 2)[2])


def test_pytest_collects_the_file_with_the_tap_plugin(tmp_path):
    import subprocess
    import sys

    path = tmp_path / "test_exported.py"
    path.write_text(to_pytest(recording(action("tap"), wait("VISIBLE")), "Checkout"))
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "--collect-only", "-q", "-p", "no:cacheprovider", str(path)],
        capture_output=True,
        text=True,
        cwd=tmp_path,
        timeout=60,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "test_checkout" in result.stdout



def test_two_secrets_read_from_one_variable_are_refused():
    made = recording({"type": {"selector": X, "secret": "api-key"}}, {"type": {"selector": X, "secret": "api_key"}}, secrets=["api-key", "api_key"])
    with pytest.raises(export.ExportError, match="TAP_SECRET_API_KEY"):
        export.to_pytest(made)


def test_the_test_says_the_plugin_keeps_evidence_when_it_fails():
    assert "TAP_CAPTURE=off" in export.to_pytest(recording({"action": {"command": {"press_key": {"key_code": 4}}}}))
