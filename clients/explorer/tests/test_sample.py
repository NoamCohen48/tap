"""Audit actual protobuf command traces, not assumed JSON field spellings."""

from dataclasses import replace

import pytest
from google.protobuf.json_format import MessageToDict, ParseDict
from tap_e2e import EventLog, res
from tap_e2e import proto as pb

from tap_explorer.sample import PACKAGE, PilotStopped, audit_events
from tap_explorer.store import GraphStore


@pytest.fixture
def audited(pilot_device, tmp_path):
    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"serial": "fake", "package": PACKAGE})
        graph.add_observation("o", {})
        graph.add_state("home", "o", signature="home", depth=0)
        graph.add_action("open", "home", "tap", target={"resource": "open_profile"})
        graph.approve_action("open", "sample policy")
        graph.begin_attempt("a", "open", "o")
        pilot_device.app(PACKAGE).element(res("open_profile")).tap()
        graph.finish_attempt("a", "succeeded", after="o", destination="home")
        yield graph, pilot_device.owner_connection.event_log()


def test_actual_sdk_trace_matches_attempts(audited):
    graph, events = audited
    assert audit_events(graph, events) == {"mutations": 1, "taps": 1, "fills": 0,
        "bootstrap_calls": 0, "dropped": 0, "trace_matches_attempts": True}


def test_camelcase_proto_json_is_also_parsed(audited):
    graph, events = audited
    changed = tuple(replace(event, command=MessageToDict(ParseDict(event.command, pb.Command())))
                    for event in events.events)
    assert audit_events(graph, EventLog(changed, 0))["trace_matches_attempts"] is True


def test_extra_actual_input_is_detected(audited, pilot_device):
    graph, _ = audited
    pilot_device.app(PACKAGE).element(res("open_profile")).tap()
    with pytest.raises(PilotStopped, match="trace differs"):
        audit_events(graph, pilot_device.owner_connection.event_log())


def test_unapproved_command_is_detected(audited, pilot_device):
    graph, _ = audited
    pilot_device.press_key(4)
    with pytest.raises(PilotStopped, match="unapproved command"):
        audit_events(graph, pilot_device.owner_connection.event_log())


def test_other_device_is_detected(audited):
    graph, events = audited
    with pytest.raises(PilotStopped, match="another device"):
        audit_events(graph, EventLog(tuple(replace(event, serial="other") for event in events.events), 0))


def test_evicted_log_is_not_a_successful_audit(audited):
    graph, events = audited
    with pytest.raises(PilotStopped, match="evicted"):
        audit_events(graph, EventLog(events.events, 1))
