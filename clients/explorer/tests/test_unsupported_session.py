"""Unsupported real-app capabilities stay visible without becoming executable input."""

import pytest
from tap_e2e import proto as pb
from tap_explorer.live import PilotStopped
from test_session import setup


def test_swipe_only_control_survives_session_persistence(fake, pilot_device, tmp_path):
    session, graph, taps = setup(fake, pilot_device, tmp_path)
    fake.devices.snapshot.nodes.add(
        ref="e3", window_package="unknown.app", resource_name="unknown.app:id/pager",
        class_name="androidx.viewpager.widget.ViewPager", interactive=True,
        flags=[pb.FLAG_ENABLED, pb.FLAG_SCROLLABLE],
    )
    with graph:
        result = session.observe()
        assert len(result["candidates"]) == 2
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
