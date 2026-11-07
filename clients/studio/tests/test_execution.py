"""Explicit execution settings survive prepare/export/replay and reject before device input."""
from __future__ import annotations

import json

import pytest
from google.protobuf.json_format import ParseDict, ParseError
from tap_e2e import Element
from tap_e2e import proto as tap

from tap_studio._gen import studio_pb2 as pb
from tap_studio.recording import RecordingError, dumps, loads
from tap_studio.steps import prepare, revise, run

SELECTOR = {"node": {"resource": {"name": "field"}}}


def step(document):
    return ParseDict(document, pb.Step())


@pytest.mark.parametrize("command", [
    {"multi_tap": {"selector": SELECTOR, "count": 3, "interval_ms": "125"}},
    {"hold": {"selector": SELECTOR, "duration_ms": "1200"}},
])
def test_new_touch_commands_forward_exactly_once(daemon, device, command):
    prepared = prepare(step({"action": {"command": {**command, "timeout_ms": "5000"}}}), None, precondition_timeout_ms=750)
    daemon.devices.commands.clear()
    run(device, prepared)
    sent = daemon.devices.commands
    assert len(sent) == 2
    assert sent[0].timeout_ms == 750
    assert sent[1] == prepared.action.command
    assert revise(prepared, None).action.wait.timeout_ms == 750


def test_default_multi_tap_interval_is_persisted(daemon, device):
    prepared = prepare(step({"action": {"command": {"multi_tap": {"selector": SELECTOR, "count": 3}}}}), None)
    assert prepared.action.command.multi_tap.interval_ms == 100
    daemon.devices.commands.clear()
    run(device, prepared)
    assert daemon.devices.commands[-1].multi_tap.interval_ms == 100


@pytest.mark.parametrize("command", [
    {"multi_tap": {"selector": SELECTOR, "count": 1}},
    {"multi_tap": {"selector": SELECTOR, "count": 101}},
    {"multi_tap": {"selector": SELECTOR, "count": 3, "interval_ms": "39"}},
    {"multi_tap": {"selector": SELECTOR, "count": 3, "interval_ms": "1001"}},
    {"multi_tap": {"selector": SELECTOR, "count": 100, "interval_ms": "1000"}},
    {"hold": {"selector": SELECTOR, "duration_ms": "0"}},
    {"hold": {"selector": SELECTOR, "duration_ms": "30001"}},
    {"scroll": {"selector": SELECTOR, "direction": "DIR_DOWN", "distance_percent": 0}},
])
def test_invalid_gestures_reject_before_a_wait_or_input(daemon, command):
    daemon.devices.commands.clear()
    with pytest.raises(RecordingError):
        prepare(step({"action": {"command": command}}), None)
    assert not daemon.devices.commands


@pytest.mark.parametrize("value", [0, -1, 120001])
@pytest.mark.parametrize("kind", ["type", "wait", "scroll_until", "action", "app_wait", "device_wait", "app"])
def test_budget_bounds_apply_to_every_supported_kind(kind, value):
    documents = {
        "type": {"selector": SELECTOR, "text": "x", "timeout_ms": value},
        "wait": {"selector": SELECTOR, "condition": "CONDITION_VISIBLE", "timeout_ms": value},
        "scroll_until": {"container": SELECTOR, "target": SELECTOR, "direction": "DIR_DOWN", "timeout_ms": value},
        "action": {"command": {"tap": {"selector": SELECTOR}, "timeout_ms": value}},
        "app_wait": {"command": {"wait_screen_stable": {}, "timeout_ms": value}},
        "device_wait": {"command": {"wait_permission_prompt": {}, "timeout_ms": value}},
        "app": {"operation": "launch", "package_name": "com.example", "timeout_ms": value},
    }
    with pytest.raises(RecordingError, match="1..120000"):
        prepare(step({kind: documents[kind]}), None)


def test_type_budgets_are_separate_and_defaults_are_restored(daemon, device):
    original = device.timeouts
    prepared = prepare(step({"type": {"selector": SELECTOR, "text": "x", "timeout_ms": "500", "precondition_timeout_ms": "700", "focus_timeout_ms": "900"}}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    sent = daemon.devices.commands
    assert sent[0].timeout_ms == 700
    assert sent[1].timeout_ms == sent[-1].timeout_ms == 500
    assert [c.WhichOneof("op") for c in sent] == ["wait_visible", "tap", "snapshot", "type_text"]
    assert device.timeouts is original
    assert revise(prepared, None) == prepared


def test_focus_default_is_restored_after_input_failure(daemon, device):
    original = device.timeouts
    daemon.devices.responder = lambda c: tap.CommandResult(error=tap.Error(code=tap.ERR_ACTION_REJECTED)) if c.WhichOneof("op") == "tap" else None
    prepared = prepare(step({"type": {"selector": SELECTOR, "text": "x", "focus_timeout_ms": "1"}}), None)
    from tap_e2e import TapError
    with pytest.raises(TapError):
        run(device, prepared)
    assert device.timeouts is original
    assert all(c.WhichOneof("op") != "type_text" for c in daemon.devices.commands)


def test_unused_or_unsupported_budgets_are_refused():
    with pytest.raises(RecordingError, match="requires a focus wait"):
        prepare(step({"type": {"selector": SELECTOR, "text": "x", "skip_focus_wait": True, "focus_timeout_ms": "500"}}), None)
    with pytest.raises(RecordingError, match="requires an element action"):
        prepare(step({"action": {"command": {"press_key": {"key_code": 4}}}}), None, precondition_timeout_ms=500)
    with pytest.raises(RecordingError, match="takes no timeout"):
        prepare(step({"app": {"operation": "grant_permission", "package_name": "com.example", "permission": "p", "timeout_ms": "500"}}), None)


@pytest.mark.parametrize("kind", ["wait", "assertion"])
def test_wait_and_immediate_assertion_budgets_are_forwarded(daemon, device, kind):
    condition = {"condition": "CONDITION_ONE"} if kind == "wait" else {"check": "CHECK_EXISTS"}
    prepared = prepare(step({kind: {"selector": SELECTOR, **condition, "timeout_ms": "321"}}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert daemon.devices.commands[-1].timeout_ms == 321


def test_scroll_search_and_container_budgets_are_forwarded(daemon, device, monkeypatch):
    forwarded = {}
    original = Element.scroll_until

    def capture(element, *args, **kwargs):
        forwarded.update(kwargs)
        return original(element, *args, **kwargs)

    monkeypatch.setattr(Element, "scroll_until", capture)
    prepared = prepare(step({"scroll_until": {"container": SELECTOR, "target": SELECTOR, "direction": "DIR_DOWN", "max_scrolls": 0, "timeout_ms": "350", "precondition_timeout_ms": "450"}}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert daemon.devices.commands[0].timeout_ms == 450
    assert all(c.WhichOneof("op") != "scroll" for c in daemon.devices.commands)
    assert forwarded["timeout"] == 0.350
    # SDK loop budget, not a replacement for its individual command defaults.
    assert daemon.devices.commands[1].WhichOneof("op") == "exists"


@pytest.mark.parametrize("signal", ["STABILITY_TREE", "STABILITY_PIXELS", "STABILITY_ALL"])
def test_whole_screen_quiet_period_signal_and_budget_forward(daemon, device, signal):
    prepared = prepare(step({"app_wait": {"command": {"wait_screen_stable": {"stable_for_ms": "1200", "signal": signal}, "timeout_ms": "5000"}}}), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert daemon.devices.commands == [prepared.app_wait.command]


@pytest.mark.parametrize("selector", [
    {"node": {"resource": {"name": "x"}}, "at": {"index": 2147483648}},
    {"node": {"match": {"property": "PROPERTY_TEXT", "value": "x" * 1025}}},
    {"node": {"any_of": {"nodes": [{"resource": {"name": "x"}}] * 256}}},
])
def test_unsafe_selector_sizes_and_indices_reject_before_input(selector):
    with pytest.raises((RecordingError, ParseError), match="selector"):
        prepare(step({"action": {"command": {"tap": {"selector": selector}}}}), None)


@pytest.mark.anyio
async def test_bounded_gesture_survives_export_open_and_replay(studio, daemon):
    try:
        await studio.attach(pb.AttachRequest(serial="emulator-5554"), None)
        request = pb.PerformRequest(step=step({"action": {"command": {"hold": {"selector": SELECTOR, "duration_ms": "1200"}, "timeout_ms": "5000"}}}), precondition_timeout_ms=800)
        response = await studio.perform(request, None)
        assert response.recorded
        document = (await studio.get_recording(pb.GetRecordingRequest(), None)).document
        assert json.loads(document)["format"] == "tap-recording/2"
        assert loads(document) == loads(dumps(loads(document)))
        await studio.open_recording(pb.OpenRecordingRequest(document=document), None)
        daemon.devices.commands.clear()
        outcomes = [r async for r in studio.replay(pb.ReplayRequest(), None)]
        assert len(outcomes) == 2 and not outcomes[-1].message
        assert daemon.devices.commands[0].timeout_ms == 800
        assert daemon.devices.commands[1].hold.duration_ms == 1200
        assert daemon.devices.commands[1].timeout_ms == 5000
    finally:
        await studio.close()
