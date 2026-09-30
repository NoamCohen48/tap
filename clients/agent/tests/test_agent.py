"""The agent core, the CLI and the MCP server over the in-process fake daemon."""
# pyright: reportAttributeAccessIssue=false

from __future__ import annotations

import pytest  # type: ignore[import-not-found]
from mcp import Client
from mcp_types import ImageContent, TextContent

from tap_agent import Agent, AgentError, cli
from tap_agent.core import EXIT_FAILED, EXIT_NO_DAEMON, EXIT_USAGE
from tap_agent.mcp_server import create_server
from tap_e2e import TapClient, res, text
from tap_e2e import _gen as pb

from .conftest import TOKEN


def _screen(fake, *nodes: pb.ScreenNode, removed=()) -> None:
    fake.devices.snapshot = pb.ScreenSnapshotResponse(snapshot_id=1, nodes=nodes, removed=removed)


def _node(ref: str, value: str, change=pb.NODE_CHANGE_UNSPECIFIED) -> pb.ScreenNode:
    return pb.ScreenNode(
        ref=ref,
        window_package="com.example",
        class_name="android.widget.Button",
        text=value,
        interactive=True,
        flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE],
        selector=text(value)._proto,
        change=change,
    )


def test_attach_creates_a_held_session_and_later_calls_resume_it(fake, agent):
    out = agent.attach("emulator-5554", "com.example", idle=600)
    assert out == "attached emulator-5554 (com.example) to session 'agent', idle timeout 10m"
    (request,) = fake.connections.live.values()
    assert request.name == "agent" and request.hold.idle_timeout_ms == 600_000
    assert agent.attach("emulator-5554", "com.example") == "emulator-5554 is already attached to session 'agent' (com.example)"
    with pytest.raises(AgentError, match="for com.example; `release` first"):
        agent.attach("emulator-5554", "com.other")
    assert fake.connections.connects == 1


def test_attach_launches_on_request(fake, agent):
    out = agent.attach("emulator-5554", "com.example", launch="cold")
    assert out.endswith("cold-launched com.example")


def test_a_failed_first_attach_leaves_no_session(fake, agent):
    fake.devices.busy.add("emulator-5554")
    with pytest.raises(AgentError, match="held by another session"):
        agent.attach("emulator-5554", "com.example")
    assert fake.connections.live == {}


def test_steps_need_a_session_and_pick_the_device(fake, agent):
    with pytest.raises(AgentError, match="no session 'agent'"):
        agent.snapshot()
    agent.attach("emulator-5554", "com.example")
    agent.attach("85e49002", "com.example")
    with pytest.raises(AgentError, match="several devices") as info:
        agent.snapshot()
    assert info.value.exit_code == EXIT_USAGE
    _screen(fake, _node("e1", "OK"))
    assert agent.snapshot("85e49002") == '# com.example\n@e1  [Button]  "OK"'
    assert fake.devices.snapshot_requests[-1].attached_device_id == "attached-85e49002"


def test_tap_by_ref_sends_the_refs_selector(fake, agent):
    agent.attach("emulator-5554", "com.example")
    fake.devices.refs["e3"] = res("login")._proto
    assert agent.tap("@e3") == "tapped @e3"
    assert fake.devices.commands[-1].tap.selector == res("login")._proto
    assert agent.tap("text=OK", long=True) == "long-tapped text=OK"
    assert fake.devices.commands[-1].long_tap.selector.node == text("OK")._proto.node


def test_an_unknown_ref_says_to_snapshot_again(fake, agent):
    agent.attach("emulator-5554", "com.example")
    with pytest.raises(AgentError, match="run `snapshot` for current refs") as info:
        agent.tap("@e99")
    assert info.value.exit_code == EXIT_FAILED


def test_a_driver_not_found_says_to_look_at_the_screen(fake, agent):
    agent.attach("emulator-5554", "com.example")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
    with pytest.raises(AgentError, match="NOT_FOUND.*\nnothing matches now"):
        agent.tap("text=Nope")


def test_settle_waits_for_the_focused_window_then_prints_the_diff(fake, agent):
    agent.attach("emulator-5554", "com.example")

    def respond(command):
        if command.HasField("device_info"):
            return pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.android.permissioncontroller"))
        return None

    fake.devices.responder = respond
    _screen(fake, _node("e1", "OK", pb.NODE_UNCHANGED), _node("e4", "Allow", pb.NODE_ADDED))
    out = agent.fill("text=Name", "Ada", settle=True)
    assert out == 'filled text=Name\n# com.example\n+ @e4  [Button]  "Allow"'
    kinds = [c.WhichOneof("op") for c in fake.devices.commands]
    assert kinds[-3:] == ["set_text", "device_info", "wait_screen_stable"]
    wait = fake.devices.commands[-1].wait_screen_stable
    assert wait.package_name == "com.android.permissioncontroller" and wait.signal == pb.STABILITY_TREE


def test_a_screen_that_keeps_changing_is_reported_not_raised(fake, agent):
    agent.attach("emulator-5554", "com.example")
    fake.devices.responder = lambda c: (
        pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT)) if c.HasField("wait_screen_stable") else None
    )
    _screen(fake, _node("e1", "OK"))
    assert agent.settle().startswith("(screen still changing after 10s)\n")


def test_keys_waits_and_app_actions(fake, agent):
    agent.attach("emulator-5554", "com.example")
    assert agent.key("back") == "pressed back"
    assert fake.devices.commands[-1].press_key.key_code == 4
    assert agent.wait("text=Hi", state="gone", timeout=3) == "text=Hi is gone"
    assert fake.devices.commands[-1].timeout_ms == 3000
    assert agent.app("stop") == "stopped com.example"
    assert fake.apps.force_stops[-1].app.attached_device_id == "attached-emulator-5554"
    with pytest.raises(AgentError) as info:
        agent.app("grant")
    assert info.value.exit_code == EXIT_USAGE
    with pytest.raises(AgentError) as info:
        agent.key("hyper")
    assert info.value.exit_code == EXIT_USAGE


def test_panels_open_the_shade_and_quick_settings(fake, agent):
    agent.attach("emulator-5554", "com.example")
    assert agent.panel("notifications") == "opened notifications"
    assert fake.devices.commands[-1].open_system_panel.panel == pb.SYSTEM_PANEL_NOTIFICATIONS
    assert agent.panel("quick_settings") == "opened quick-settings"
    assert fake.devices.commands[-1].open_system_panel.panel == pb.SYSTEM_PANEL_QUICK_SETTINGS
    assert agent.key("recents") == "pressed recents"
    assert fake.devices.commands[-1].press_key.key_code == 187
    with pytest.raises(AgentError) as info:
        agent.panel("settings")
    assert info.value.exit_code == EXIT_USAGE


def test_screenshot_is_saved_under_the_out_dir(fake, agent, tmp_path):
    agent.attach("emulator-5554", "com.example")
    path = agent.screenshot()
    assert path.parent == tmp_path / "out" and path.read_bytes() == fake.devices.png


def test_sessions_devices_and_release(fake, agent, client):
    agent.attach("emulator-5554", "com.example")
    client.connect("a test run")  # observed: listed only as a count
    assert agent.sessions() == "agent  idle 2s of 15m  emulator-5554 (com.example)\n(1 other connection, e.g. test runs)"
    assert agent.release() == "released session 'agent' (detached emulator-5554)"
    assert agent.release() == "no session 'agent'"


def test_no_daemon_is_exit_code_3(monkeypatch, tmp_path):
    monkeypatch.delenv("TAP_SERVER", raising=False)
    monkeypatch.setenv("TAP_STATE_DIR", str(tmp_path))
    with pytest.raises(AgentError) as info:
        Agent().devices()
    assert info.value.exit_code == EXIT_NO_DAEMON


def test_cli_prints_the_result_and_maps_failures_to_exit_codes(fake, monkeypatch, capsys):
    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", TOKEN)
    assert cli.main(["attach", "emulator-5554", "com.example", "--idle", "90s", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "attached emulator-5554 (com.example) to session 'cli', idle timeout 90s\n"
    _screen(fake, _node("e1", "OK"))
    assert cli.main(["snapshot", "-i", "--session", "cli"]) == 0
    assert capsys.readouterr().out == '# com.example\n@e1  [Button]  "OK"\n'
    assert cli.main(["tap", "@e9", "-s", "cli"]) == EXIT_FAILED
    assert "not on the screen any more" in capsys.readouterr().err
    assert cli.main(["tap", "banana", "-s", "cli"]) == EXIT_USAGE
    with pytest.raises(SystemExit) as info:
        cli.main(["scroll", "@e1", "sideways"])
    assert info.value.code == EXIT_USAGE
    capsys.readouterr()
    assert cli.main(["panel", "quick-settings", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "opened quick-settings\n"
    with pytest.raises(SystemExit) as info:
        cli.main(["panel", "settings"])
    assert info.value.code == EXIT_USAGE
    assert cli.main(["skill"]) == 0
    assert capsys.readouterr().out.startswith("---\nname: tap-android")


def test_export_is_the_session_log_as_json(fake, agent, tmp_path):
    import json

    agent.attach("emulator-5554", "com.example", launch="cold")
    fake.devices.refs["e3"] = res("login")._proto
    agent.tap("@e3")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
    with pytest.raises(AgentError):
        agent.tap("text=Nope")
    document = json.loads(agent.export())
    assert document["format"] == "tap-events/1" and document["session"] == "agent" and document["dropped"] == 0
    cold, tapped, missing = document["events"]
    assert cold["app"] == {"operation": "cold_launch", "package_name": "com.example"} and cold["ok"]
    # The ref is logged as the selector it named.
    assert tapped["command"]["tap"]["selector"] == {"node": {"resource": {"name": "login", "aut_package": True}}}
    assert missing["error"] == {"code": "ERR_NOT_FOUND"} and not missing["ok"]
    out = tmp_path / "log" / "session.json"
    assert agent.export(out) == f"wrote 3 events (1 failed) to {out}"
    assert json.loads(out.read_text())["events"] == document["events"]


# --- MCP ---------------------------------------------------------------------------------------

EXPECTED_TOOLS = {
    "devices", "attach", "sessions", "release", "snapshot", "tap", "fill", "type_text", "clear",
    "scroll", "swipe", "press_key", "open_panel", "wait_for", "settle", "screenshot", "capture", "app", "export",
}


@pytest.fixture
def server(fake, tmp_path):
    agents = []

    def agent_for(session: str) -> Agent:
        agents.append(Agent(session, client=TapClient.create(fake.address, TOKEN), out_dir=tmp_path / "mcp"))
        return agents[-1]

    yield create_server(agent_for, default_session="agent")
    for agent in agents:
        agent.client.close()


@pytest.mark.anyio
async def test_mcp_tools_have_described_schemas(server):
    import jsonschema  # type: ignore[import-untyped]

    async with Client(server, mode="legacy") as client:
        tools = (await client.list_tools()).tools
        assert {t.name for t in tools} == EXPECTED_TOOLS
        for tool in tools:
            assert tool.description, tool.name
            jsonschema.Draft202012Validator.check_schema(tool.input_schema)
        assert "tap start" in (client.instructions or "")


@pytest.mark.anyio
async def test_mcp_tools_call_the_core(fake, server, agent):
    async with Client(server, mode="legacy") as client:
        result = await client.call_tool("attach", {"serial": "emulator-5554", "package": "com.example", "idle_minutes": 2})
        assert not result.is_error
        assert fake.connections.live[next(iter(fake.connections.live))].hold.idle_timeout_ms == 120_000
        _screen(fake, _node("e1", "OK"))
        result = await client.call_tool("snapshot", {"view": "interactive"})
        assert isinstance(result.content[0], TextContent) and "@e1" in result.content[0].text
        # The CLI (another Agent, another client) sees the session the MCP server started.
        assert agent.snapshot().endswith('"OK"')
        result = await client.call_tool("tap", {"target": "@e42"})
        assert result.is_error and "run `snapshot`" in result.content[0].text
        result = await client.call_tool("screenshot", {})
        assert isinstance(result.content[0], ImageContent) and result.content[0].mime_type == "image/png"
        result = await client.call_tool("open_panel", {"panel": "quick_settings"})
        assert not result.is_error and result.content[0].text == "opened quick-settings"
        result = await client.call_tool("export", {})
        assert not result.is_error and '"format": "tap-events/1"' in result.content[0].text
