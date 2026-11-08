"""Practical SDK adapters: parameter forwarding, bounded loops and private artifacts."""
# pyright: reportMissingImports=false, reportAttributeAccessIssue=false
from __future__ import annotations

import hashlib
import json
import pathlib
import stat

import pytest
from mcp import Client
from tap_e2e import TapError, res
from tap_e2e import _gen as pb

from tap_agent import AgentError, cli
from tap_agent.core import EXIT_USAGE
from tap_agent.mcp_server import create_server


def test_detach_preserves_other_devices_and_the_session(fake, agent):
    agent.attach("first")
    agent.attach("second")
    with pytest.raises(AgentError, match="several devices"):
        agent.detach()
    assert "first" in agent.detach("first")
    assert fake.devices.detaches == ["attached-first"]
    assert len(fake.connections.live) == 1
    assert "second" in agent.sessions()
    agent.snapshot()  # the remaining device no longer needs an explicit serial
    assert fake.devices.snapshot_requests[-1].attached_device_id == "attached-second"
    agent.detach()
    assert "no device" in agent.sessions()


def test_metadata_candidates_and_incremental_history(fake, agent):
    fake.devices.listed = [pb.DeviceEntry(serial="first", state=pb.DEVICE_FREE,
        properties=pb.DeviceProperties(api_level=34, manufacturer="Google", model="Phone", emulator=True))]
    assert "API 34  Google Phone  emulator" in agent.devices()
    assert isinstance(json.loads(agent.server_info()), dict)
    agent.attach("first")
    fake.devices.responder = lambda c: pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.example")) if c.WhichOneof("op") == "device_info" else None
    assert json.loads(agent.device_info())["current_package"] == "com.example"
    agent.snapshot(candidates=True)
    assert fake.devices.snapshot_requests[-1].selector_candidates
    agent.tap("id=save")
    agent.tap("id=cancel")
    events = json.loads(agent.history())["events"]
    assert len(events) == 2
    later = json.loads(agent.history(after_seq=events[0]["seq"]))["events"]
    assert later == events[1:]


def test_app_queries_and_visibility_reuse_sdk(fake, agent, client, monkeypatch):
    agent.attach("first")
    assert agent.app("installed", "com.example") == "true"
    calls = []
    def process(request, timeout):
        calls.append(request)
        return pb.ProcessResponse(process=pb.ProcessIdentity(pid=42, start_token="identity"))
    monkeypatch.setattr(client.apps, "Process", process)
    identity = json.loads(agent.app("process", "com.example", timeout=4))
    assert identity == {"pid": 42, "start_token": "identity"}
    assert calls[0].timeout_ms == 4000
    assert calls[0].app.client_connection_id in fake.connections.live
    assert calls[0].app.attached_device_id == "attached-first"
    agent.app("visible", "com.example", timeout=3)
    assert fake.devices.commands[-1].WhichOneof("op") == "wait_app_visible"
    assert fake.devices.commands[-1].timeout_ms == 3000
    agent.app("launch", "com.example", timeout=5)
    assert fake.apps.launches[-1].timeout_ms == 5000


def test_configurable_stability_uses_explicit_package(fake, agent):
    agent.attach("first")
    agent.settle(package="com.example", timeout=2, stable_for=0.2, signal="pixels")
    (command,) = fake.devices.commands
    assert command.WhichOneof("op") == "wait_screen_stable"
    assert command.timeout_ms == 2000
    assert command.wait_screen_stable.package_name == "com.example"
    assert command.wait_screen_stable.stable_for_ms == 200
    assert command.wait_screen_stable.signal == pb.STABILITY_PIXELS


def test_gesture_distance_and_targeted_typing(fake, agent):
    agent.attach("first")
    agent.scroll("id=list", "down", percent=25, timeout=4)
    assert fake.devices.commands[-1].scroll.distance_percent == 25
    assert fake.devices.commands[-1].timeout_ms == 4000
    agent.swipe("id=item", "left", percent=35, timeout=3)
    assert fake.devices.commands[-1].swipe.distance_percent == 35
    agent.fling("id=list", "up", timeout=2)
    assert fake.devices.commands[-1].timeout_ms == 2000
    before = len(fake.devices.commands)
    agent.type("secret", target="id=input", timeout=5, focus_timeout=2, sensitive=True)
    commands = fake.devices.commands[before:]
    assert [c.WhichOneof("op") for c in commands] == ["tap", "wait_state", "type_text"]
    assert commands[1].wait_state.condition.flag.property == pb.FLAG_FOCUSED and commands[1].timeout_ms == 2000
    assert commands[0].tap.selector.node == res("input")._proto.node
    assert commands[-1].type_text.sensitive
    assert commands[0].timeout_ms == commands[-1].timeout_ms == 5000
    before = len(fake.devices.commands)
    agent.type("more", target="id=input", await_focus=False)
    assert [c.WhichOneof("op") for c in fake.devices.commands[before:]] == ["tap", "type_text"]


def test_scroll_until_has_count_and_deadline_bounds(fake, agent):
    agent.attach("first")
    checks = 0
    def respond(command):
        nonlocal checks
        if command.WhichOneof("op") == "exists":
            checks += 1
            return pb.CommandResult(bool=checks == 3)
    fake.devices.responder = respond
    assert "found" in agent.scroll_until("id=list", "text=Last", max_scrolls=2, percent=30, timeout=3)
    assert [c.WhichOneof("op") for c in fake.devices.commands] == ["exists", "scroll", "exists", "scroll", "exists"]
    assert all(c.scroll.distance_percent == 30 for c in fake.devices.commands if c.WhichOneof("op") == "scroll")
    fake.devices.responder = lambda c: pb.CommandResult(bool=False) if c.WhichOneof("op") == "exists" else None
    before = len(fake.devices.commands)
    with pytest.raises(AgentError):
        agent.scroll_until("id=list", "text=Missing", max_scrolls=1, timeout=1)
    assert len([c for c in fake.devices.commands[before:] if c.WhichOneof("op") == "scroll"]) == 1


@pytest.mark.parametrize("kind", ["device", "driver", "hierarchy"])
def test_diagnostics_save_private_new_outputs(fake, agent, tmp_path, kind):
    agent.attach("first")
    fake.devices.responder = lambda c: pb.CommandResult(text="<hierarchy/>") if c.WhichOneof("op") == "dump_hierarchy" else None
    path = pathlib.Path(agent.logs(kind))
    assert path.exists() and stat.S_IMODE(path.stat().st_mode) == 0o600
    assert path.read_bytes()
    before = len(fake.devices.owners)
    with pytest.raises(AgentError, match="already exists"):
        agent.logs(kind, out=str(path))
    assert len(fake.devices.owners) == before


@pytest.fixture
def recorder(client, monkeypatch):
    class Stub:
        payload = b"recording content"
        digest = hashlib.sha256(payload).hexdigest()
        starts = []
        stops = []
        failure = False
        start_failure = False
        def start(self, request, timeout):
            self.starts.append(request)
            if self.start_failure:
                raise TapError("lost start acknowledgement")
            return pb.StartRecordingResponse()
        def stop(self, request, timeout):
            self.stops.append(request)
            if self.failure:
                raise TapError("lost recording acknowledgement")
            return pb.StopRecordingResponse(data=self.payload, format="mkv", sha256=self.digest)
    stub = Stub()
    monkeypatch.setattr(client.device_stub, "StartRecording", stub.start)
    monkeypatch.setattr(client.device_stub, "StopRecording", stub.stop)
    return stub


def test_recording_start_failure_is_not_replayed(agent, recorder):
    agent.attach("first")
    recorder.start_failure = True
    with pytest.raises(AgentError, match="do not repeat start automatically"):
        agent.recording("start")
    assert len(recorder.starts) == 1 and not recorder.stops


def test_targeted_typing_does_not_type_after_failed_focus(fake, agent):
    agent.attach("first")
    fake.devices.responder = lambda c: (
        pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT, detail="STATE_NOT_REACHED", last_state=pb.ElementSnapshot()))
        if c.WhichOneof("op") == "wait_state"
        else None
    )
    with pytest.raises(AgentError, match="focused"):
        agent.type("not injected", target="id=input", focus_timeout=.02)
    assert sum(c.WhichOneof("op") == "tap" for c in fake.devices.commands) == 1
    assert all(c.WhichOneof("op") != "type_text" for c in fake.devices.commands)


@pytest.mark.parametrize("verb,arguments", [
    ("fill", ["id=input", "text"]), ("clear", ["id=input"]),
    ("submit", ["id=input"]), ("action", ["id=input", "expand"]),
    ("progress", ["id=input", "4"]),
])
def test_element_input_budgets_are_forwarded(fake, agent, verb, arguments):
    agent.attach("first")
    cli.run(cli.parser().parse_args([verb, *arguments, "--timeout", "2s"]), agent)
    assert fake.devices.commands[-1].timeout_ms == 2000


def test_recording_tracks_identity_checksum_and_private_outputs(agent, recorder, tmp_path):
    agent.attach("first")
    assert "microphone" in agent.recording("start", video=False, audio_source="mic", max_seconds=15)
    request = recorder.starts[0]
    assert not request.video and request.audio_source == "mic" and request.max_seconds == 15
    assert request.attached_device_id == "attached-first"
    output = tmp_path / "out.mkv"
    assert str(output) in agent.recording("stop", out=str(output))
    canonical = list(agent.out_dir.glob("recording-*/recording.mkv"))
    assert len(canonical) == 1
    for path in [output, canonical[0]]:
        assert path.read_bytes() == recorder.payload
        assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(canonical[0].parent.stat().st_mode) == 0o700
    assert recorder.stops[0].client_connection_id == request.client_connection_id
    with pytest.raises(AgentError, match="already exists"):
        agent.recording("stop", out=str(output))
    assert len(recorder.stops) == 1


def test_recording_failures_never_replay_and_custom_copy_keeps_canonical(agent, recorder, tmp_path):
    agent.attach("first")
    recorder.failure = True
    with pytest.raises(AgentError, match="lost recording acknowledgement"):
        agent.recording("stop")
    assert len(recorder.stops) == 1 and not recorder.starts
    recorder.failure = False
    invalid = tmp_path / "not-a-directory"
    invalid.write_text("preserve")
    with pytest.raises(AgentError, match="artifact is saved at"):
        agent.recording("stop", out=str(invalid / "capture.mkv"))
    assert len(recorder.stops) == 2 and not recorder.starts
    assert list(agent.out_dir.glob("recording-*/recording.mkv"))[0].read_bytes() == recorder.payload
    recorder.digest = "bad"
    with pytest.raises(AgentError, match="checksum"):
        agent.recording("stop")
    assert len(recorder.stops) == 3


@pytest.mark.parametrize("method,kwargs", [
    ("scroll", {"target": "id=list", "direction": "down", "percent": True}),
    ("swipe", {"target": "id=list", "direction": "down", "percent": 101}),
    ("scroll_until", {"target": "id=list", "destination": "id=end", "max_scrolls": 0}),
    ("scroll_until", {"target": "id=list", "destination": "id=end", "timeout": 121}),
    ("type", {"text": "x", "await_focus": False}),
    ("type", {"text": "x", "target": "id=input", "await_focus": False, "focus_timeout": 1}),
    ("fill", {"target": "id=input", "text": "x", "timeout": .0001}),
    ("settle", {"stable_for": 31}), ("settle", {"stable_for": .0001}),
    ("settle", {"stable_for": 2, "timeout": 1}), ("settle", {"signal": "unknown"}),
    ("snapshot", {"candidates": True, "level": "all"}),
    ("history", {"after_seq": -1}), ("history", {"after_seq": True}),
    ("wait", {"target": "id=input", "state": "count", "value": "²"}),
    ("wait", {"target": "id=input", "state": "count", "value": "9" * 5000}),
    ("wait", {"target": "id=input", "state": "count", "value": 10**5000}),
    ("recording", {"action": "start", "video": False}),
    ("recording", {"action": "start", "max_seconds": 31}),
    ("recording", {"action": "stop", "audio_source": "mic"}),
    ("app", {"action": "installed", "package": "com.example", "timeout": 2}),
])
def test_invalid_workflows_fail_before_connection_lookup(agent, method, kwargs):
    with pytest.raises(AgentError) as error:
        getattr(agent, method)(**kwargs)
    assert error.value.exit_code == EXIT_USAGE


def test_cli_forwards_new_parameters(fake, agent):
    agent.attach("first")
    def run(args):
        return cli.run(cli.parser().parse_args(args), agent)
    run(["scroll", "id=list", "down", "--percent", "40", "--timeout", "4s"])
    assert fake.devices.commands[-1].scroll.distance_percent == 40
    assert fake.devices.commands[-1].timeout_ms == 4000
    run(["type", "hello", "--target", "id=input", "--no-await-focus", "--sensitive"])
    assert fake.devices.commands[-1].type_text.text == "hello"
    assert fake.devices.commands[-1].type_text.sensitive
    run(["settle", "--package", "com.example", "--signal", "all", "--stable-for", "100ms"])
    assert fake.devices.commands[-1].wait_screen_stable.stable_for_ms == 100
    assert fake.devices.commands[-1].wait_screen_stable.signal == pb.STABILITY_ALL
    assert json.loads(run(["app", "installed", "com.example"])) is True
    assert json.loads(run(["history", "--after-seq", "999"]))["events"] == []
    assert "detached" in run(["detach"])


@pytest.mark.anyio
async def test_mcp_workflow_schema_and_forwarding(fake, agent):
    agent.attach("first")
    server = create_server(lambda _: agent, default_session="agent")
    async with Client(server, mode="legacy") as client:
        tools = {t.name: t for t in (await client.list_tools()).tools}
        assert "installed" in tools["app"].input_schema["properties"]["action"]["enum"]
        result = await client.call_tool("scroll", {"target": "id=list", "direction": "down", "percent": 45, "timeout_seconds": 4})
        assert not result.is_error
        assert fake.devices.commands[-1].scroll.distance_percent == 45
        assert fake.devices.commands[-1].timeout_ms == 4000
        result = await client.call_tool("settle", {"package": "com.example", "signal": "pixels", "stable_for": .1})
        assert not result.is_error
        assert fake.devices.commands[-1].wait_screen_stable.signal == pb.STABILITY_PIXELS
        result = await client.call_tool("type_text", {"text": "hello", "target": "id=input", "await_focus": False})
        assert not result.is_error
        assert fake.devices.commands[-1].WhichOneof("op") == "type_text"
        result = await client.call_tool("detach", {})
        assert not result.is_error and fake.devices.detaches == ["attached-first"]


def test_private_outputs_appear_whole_or_not_at_all(tmp_path, monkeypatch):
    from tap_agent import output

    target = tmp_path / "out" / "history.json"
    assert output.save_private(target, b"data") == target
    assert target.read_bytes() == b"data" and stat.S_IMODE(target.stat().st_mode) == 0o600
    with pytest.raises(FileExistsError):
        output.save_private(target, b"other")
    assert target.read_bytes() == b"data"
    link = tmp_path / "out" / "link.json"
    link.symlink_to(tmp_path / "elsewhere")
    with pytest.raises(FileExistsError):
        output.save_private(link, b"other")
    assert not (tmp_path / "elsewhere").exists()

    def full_disk(descriptor):
        raise OSError(28, "No space left on device")
    monkeypatch.setattr(output.os, "fsync", full_disk)
    with pytest.raises(OSError, match="No space"):
        output.save_private(tmp_path / "out" / "partial.json", b"data")
    assert sorted(p.name for p in (tmp_path / "out").iterdir()) == ["history.json", "link.json"]
