"""Unsupported real-app capabilities stay visible without becoming executable input."""

import pytest
from tap_e2e import proto as pb
from tap_explorer.live import PilotStopped
from test_session import setup


def test_control_without_a_supported_verb_survives_session_persistence(fake, pilot_device, tmp_path):
    session, graph, taps = setup(fake, pilot_device, tmp_path)
    fake.devices.snapshot.nodes.add(
        ref="e3", window_package="unknown.app", resource_name="unknown.app:id/canvas",
        class_name="android.view.View", interactive=True, flags=[pb.FLAG_ENABLED],
    )
    with graph:
        result = session.observe()
        assert len(result["candidates"]) == 3  # The tap, the unsupported canvas and Back.
        unsupported = next(key for key, action in graph.document()["actions"].items()
                           if action["verb"] == "unsupported")
        action = graph.document()["actions"][unsupported]
        assert action["status"] == "blocked"
        assert action["target"]["blocked_reason"] == "unsupported capability"
        with pytest.raises(PilotStopped, match="unsupported"):
            session.approve(unsupported, reason="cannot override unsupported controls")
        assert session.current == result["state"]
        assert graph.next_action()["action"] is None
        assert not taps and not graph.document()["attempts"]
        supported = next(key for key, action in graph.document()["actions"].items()
                         if action["verb"] == "tap")
        session.approve(supported, reason="reviewed navigation remains available")
        session.step(supported)
        assert taps == ["settings"]
        assert len(graph.document()["states"]) == 2
