"""Opt-in installed-fixture checks against an explicitly started daemon; no reboot or sync.

TAP_AGENT_DEVICE_SERIAL=emulator-5554 pytest clients/agent/tests/device
Set TAP_AGENT_RECORDING=1 as well to exercise the host's configured scrcpy video capture.
"""
# pyright: reportMissingImports=false
from __future__ import annotations

import json
import os
import stat
import time
import uuid

import pytest

from tap_agent import Agent

SERIAL = os.environ.get("TAP_AGENT_DEVICE_SERIAL")
PACKAGE = "io.github.noamcohen48.tap.fixture"
pytestmark = pytest.mark.skipif(not SERIAL, reason="set TAP_AGENT_DEVICE_SERIAL explicitly; installed fixture and running daemon required")


@pytest.fixture
def real_agent(tmp_path):
    agent = Agent(f"api-coverage-{uuid.uuid4().hex}", out_dir=tmp_path / "evidence")
    try:
        assert SERIAL is not None
        agent.attach(SERIAL)
        assert agent.app("installed", PACKAGE) == "true", "Install the repository fixture explicitly first"
        yield agent
    finally:
        try:
            agent.release()
        finally:
            agent.close()


def test_queries_typing_scrolling_stability_and_detach(real_agent):
    a = real_agent
    assert isinstance(json.loads(a.server_info()), dict)
    assert isinstance(json.loads(a.device_info()), dict)
    # Explicit fixture reset: normal launch can bring an existing task's top activity back.
    a.app("cold-launch", PACKAGE, ".FormActivity", timeout=10)
    a.app("visible", PACKAGE, timeout=5)
    assert json.loads(a.app("process", PACKAGE, timeout=5))["pid"] > 0
    a.wait("id=search_field", timeout=5)
    assert a.exists("id=search_field") == "true"
    assert a.count("id=search_field") == "1"
    assert json.loads(a.inspect('{"id":"search_field","enabled":true}'))["enabled"]
    a.wait("id=search_field", state="enabled", timeout=5)
    a.wait("id=search_field", state="count", value=1, timeout=5)
    # A focused field is not proof that the IME handoff is complete. Observe readiness
    # explicitly; never retry typing if the effect differs from the requested input.
    a.tap("id=search_field")
    deadline = time.monotonic() + 5
    while a.keyboard() != "keyboard shown":
        assert time.monotonic() < deadline, "keyboard did not appear"
        time.sleep(.1)
    a.clear("id=search_field", timeout=5)
    a.type("agent coverage", target="id=search_field", timeout=5, focus_timeout=5, sensitive=True)
    a.wait("id=search_field", state="text", value="agent coverage", timeout=5)
    a.keyboard("hide")
    a.submit("id=search_field", timeout=5)
    a.wait("id=search_status", state="text-contains", value="agent coverage", timeout=5)
    a.snapshot(candidates=True)
    result = a.settle(package=PACKAGE, stable_for=.2, signal="all", timeout=5)
    assert "screen still changing" not in result
    a.app("launch", PACKAGE, ".ViewListActivity", timeout=10)
    a.wait("id=view_list", timeout=5)
    a.scroll("id=view_list", "down", percent=25, timeout=5)
    a.scroll_until("id=view_list", "text=View item 60", max_scrolls=15, percent=70, timeout=20)
    a.wait("text=View item 60", timeout=5)
    for kind in ("device", "driver", "hierarchy"):
        path = a.logs(kind)
        assert path
    events = json.loads(a.export())["events"]
    assert events
    assert json.loads(a.export(after_seq=events[-1]["seq"]))["events"] == []
    assert "detached" in a.detach()
    assert "no device" in a.sessions()


@pytest.mark.skipif(os.environ.get("TAP_AGENT_RECORDING") != "1", reason="set TAP_AGENT_RECORDING=1 explicitly; needs host scrcpy")
def test_recording_saves_verified_private_video(real_agent):
    a = real_agent
    a.app("cold-launch", PACKAGE, ".FormActivity", timeout=10)
    a.app("visible", PACKAGE, timeout=5)
    a.wait("id=search_field", timeout=5)  # package visibility is not destination-screen readiness
    a.recording("start", max_seconds=10)
    # Exercise the UI instead of assuming start acknowledgement means first-frame readiness.
    a.fill("id=search_field", "video evidence", timeout=5)
    a.wait("id=search_field", state="text", value="video evidence", timeout=5)
    a.settle(package=PACKAGE, stable_for=1, timeout=5)
    result = a.recording("stop")
    (path,) = a.out_dir.glob("recording-*/recording.mp4")
    assert str(path) in result and path.stat().st_size > 0
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
