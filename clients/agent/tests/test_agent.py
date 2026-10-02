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
    out = agent.attach("emulator-5554", idle=600)
    assert out == "attached emulator-5554 to session 'agent', idle timeout 10m"
    (request,) = fake.connections.live.values()
    assert request.name == "agent" and request.hold.idle_timeout_ms == 600_000
    assert agent.attach("emulator-5554") == "emulator-5554 is already attached to session 'agent'"
    assert fake.connections.connects == 1


def test_a_failed_first_attach_leaves_no_session(fake, agent):
    fake.devices.busy.add("emulator-5554")
    with pytest.raises(AgentError, match="held by another session"):
        agent.attach("emulator-5554")
    assert fake.connections.live == {}


def test_steps_need_a_session_and_pick_the_device(fake, agent):
    with pytest.raises(AgentError, match="no session 'agent'"):
        agent.snapshot()
    agent.attach("emulator-5554")
    agent.attach("85e49002")
    with pytest.raises(AgentError, match="several devices") as info:
        agent.snapshot()
    assert info.value.exit_code == EXIT_USAGE
    _screen(fake, _node("e1", "OK"))
    assert agent.snapshot("85e49002") == '# com.example\n@e1  [Button]  "OK"'
    assert fake.devices.snapshot_requests[-1].attached_device_id == "attached-85e49002"


def test_tap_by_ref_sends_the_refs_selector(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.refs["e3"] = res("login")._proto
    assert agent.tap("@e3") == "tapped @e3"
    assert fake.devices.commands[-1].tap.selector == res("login")._proto
    assert agent.tap("text=OK", long=True) == "long-tapped text=OK"
    assert fake.devices.commands[-1].long_tap.selector.node == text("OK")._proto.node
    # pkg= binds the package through App.element; without it the selector searches the screen.
    assert agent.tap("text=Allow,pkg=com.android.permissioncontroller") == "tapped text=Allow,pkg=com.android.permissioncontroller"
    assert fake.devices.commands[-1].tap.selector == text("Allow")._in_package("com.android.permissioncontroller")._proto


def test_an_obscured_node_says_to_close_the_covering_window(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_INTERACTABLE, detail="OBSCURED"))
    with pytest.raises(AgentError, match="NOT_INTERACTABLE/OBSCURED.*\nanother window covers the node"):
        agent.tap("text=Item 9")


def test_an_unknown_ref_says_to_snapshot_again(fake, agent):
    agent.attach("emulator-5554")
    with pytest.raises(AgentError, match="run `snapshot` for current refs") as info:
        agent.tap("@e99")
    assert info.value.exit_code == EXIT_FAILED


def test_a_driver_not_found_says_to_look_at_the_screen(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
    with pytest.raises(AgentError, match="NOT_FOUND.*\nnothing matches now"):
        agent.tap("text=Nope")


def test_settle_waits_for_the_focused_window_then_prints_the_diff(fake, agent):
    agent.attach("emulator-5554")

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
    agent.attach("emulator-5554")
    fake.devices.responder = lambda c: (
        pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT)) if c.HasField("wait_screen_stable") else None
    )
    _screen(fake, _node("e1", "OK"))
    assert agent.settle().startswith("(screen still changing after 10s)\n")


def test_keys_waits_and_app_actions(fake, agent):
    agent.attach("emulator-5554")
    assert agent.key("back") == "pressed back"
    assert fake.devices.commands[-1].press_key.key_code == 4
    assert agent.wait("text=Hi", state="gone", timeout=3) == "text=Hi is gone"
    assert fake.devices.commands[-1].timeout_ms == 3000
    assert agent.app("stop", "com.example") == "stopped com.example"
    assert fake.apps.force_stops[-1].app.attached_device_id == "attached-emulator-5554"
    assert fake.apps.force_stops[-1].app.package_name == "com.example"
    with pytest.raises(AgentError) as info:
        agent.app("grant", "com.example")
    assert info.value.exit_code == EXIT_USAGE
    with pytest.raises(AgentError) as info:
        agent.key("hyper")
    assert info.value.exit_code == EXIT_USAGE


def test_panels_open_the_shade_and_quick_settings(fake, agent):
    agent.attach("emulator-5554")
    assert agent.panel("notifications") == "opened notifications"
    assert fake.devices.commands[-1].open_system_panel.panel == pb.SYSTEM_PANEL_NOTIFICATIONS
    assert agent.panel("quick_settings") == "opened quick-settings"
    assert fake.devices.commands[-1].open_system_panel.panel == pb.SYSTEM_PANEL_QUICK_SETTINGS
    assert agent.key("recents") == "pressed recents"
    assert fake.devices.commands[-1].press_key.key_code == 187
    with pytest.raises(AgentError) as info:
        agent.panel("settings")
    assert info.value.exit_code == EXIT_USAGE


def test_gestures_double_tap_fling_drag_and_pinch(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.refs["e3"] = res("card")._proto
    fake.devices.refs["e7"] = res("bin")._proto
    assert agent.tap("@e3", double=True) == "double-tapped @e3"
    assert fake.devices.commands[-1].double_tap.selector == res("card")._proto
    assert agent.fling("id=list", "down") == "flung down id=list"
    assert fake.devices.commands[-1].fling.direction == pb.DIR_DOWN
    assert agent.drag("@e3", "@e7") == "dragged @e3 onto @e7"
    drag = fake.devices.commands[-1].drag
    assert (drag.selector, drag.target) == (res("card")._proto, res("bin")._proto)
    assert agent.pinch("id=map", "close", 40) == "pinched closed 40% id=map"
    assert (fake.devices.commands[-1].pinch.direction, fake.devices.commands[-1].pinch.percent) == (pb.PINCH_CLOSE, 40)
    for bad in (lambda: agent.tap("@e3", long=True, double=True), lambda: agent.pinch("id=map", "twist"), lambda: agent.pinch("id=map", "open", 0)):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE


def test_rotate_screen_and_permission_report_what_the_device_says(fake, agent):
    agent.attach("emulator-5554")
    state = {"rotation": 0, "locked": True}

    def respond(command):
        op = command.WhichOneof("op")
        if op == "set_orientation":
            state["rotation"] = 1
        if op == "dismiss_keyguard":
            state["locked"] = False
        if op == "device_info":
            wide = state["rotation"] % 2 == 1
            return pb.CommandResult(
                device_info=pb.DeviceInfo(
                    display_width=2400 if wide else 1080,
                    display_height=1080 if wide else 2400,
                    display_rotation=state["rotation"],
                    screen_on=True,
                    keyguard_locked=state["locked"],
                )
            )
        if op == "wait_permission_prompt":
            return pb.CommandResult(
                permission_prompt=pb.PermissionPrompt(
                    package_name="com.android.permissioncontroller", choices=[pb.PERMISSION_ALLOW_FOREGROUND_ONLY, pb.PERMISSION_DENY]
                )
            )
        return None

    fake.devices.responder = respond
    assert agent.rotate("landscape") == "rotated landscape: display landscape, left (2400x1080)"
    assert fake.devices.commands[-2].set_orientation.orientation == pb.ORIENTATION_LANDSCAPE
    agent.rotate("upside-down")
    assert fake.devices.commands[-2].set_display_rotation.rotation == pb.DISPLAY_ROTATION_UPSIDE_DOWN
    agent.rotate("auto")
    assert fake.devices.commands[-2].HasField("unfreeze_rotation")
    assert agent.screen() == "screen on, keyguard showing"
    assert agent.screen("unlock") == "screen on, keyguard not showing"
    assert [c.WhichOneof("op") for c in fake.devices.commands[-3:]] == ["press_key", "dismiss_keyguard", "device_info"]
    assert fake.devices.commands[-3].press_key.key_code == 224
    agent.screen("off")
    assert fake.devices.commands[-2].press_key.key_code == 223
    assert agent.permission() == "permission dialog (com.android.permissioncontroller) offers: allow-foreground-only, deny"
    assert agent.permission("allow-one-time") == "pressed allow-one-time"
    assert fake.devices.commands[-1].choose_permission.choice == pb.PERMISSION_ALLOW_ONE_TIME
    for bad in (lambda: agent.rotate("sideways"), lambda: agent.screen("dim"), lambda: agent.permission("maybe")):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE


def test_keyboard_submit_clipboard_and_toast(fake, agent):
    agent.attach("emulator-5554")
    state = {"keyboard": True, "clip": ""}

    def respond(command):
        op = command.WhichOneof("op")
        if op == "hide_keyboard":
            state["keyboard"] = False
        if op == "device_info":
            return pb.CommandResult(device_info=pb.DeviceInfo(keyboard_shown=state["keyboard"]))
        if op == "set_clipboard":
            state["clip"] = command.set_clipboard.text
        if op == "get_clipboard":
            return pb.CommandResult(text=state["clip"])
        if op == "await_toast":
            return pb.CommandResult(toast=pb.Toast(text="Saved order", package_name="com.example"))
        return None

    fake.devices.responder = respond
    assert agent.keyboard() == "keyboard shown"
    assert agent.keyboard("hide") == "keyboard hidden"
    assert agent.submit("id=search") == "submitted id=search"
    assert fake.devices.commands[-1].perform_ime_action.selector == res("search")._proto
    assert agent.clipboard("copied") == "clipboard set (6 characters)"
    assert agent.clipboard() == "copied"
    assert agent.toast("Saved", contains=True, timeout=2) == "toast 'Saved order' from com.example"
    toast = fake.devices.commands[-1].await_toast
    assert (toast.text, toast.mode, toast.HasField("package_name")) == ("Saved", pb.MATCH_CONTAINS, False)
    assert fake.devices.commands[-1].timeout_ms == 2000
    agent.toast(package="com.example")
    toast = fake.devices.commands[-1].await_toast
    assert not toast.HasField("text") and toast.package_name == "com.example"
    assert agent.app("revoke", "com.example", "android.permission.CAMERA") == (
        "revoked android.permission.CAMERA from com.example (Android stops its process)"
    )
    assert fake.apps.revokes[-1].permission == "android.permission.CAMERA"
    assert agent.app("granted", "com.example", "android.permission.CAMERA") == "android.permission.CAMERA is granted to com.example"
    for bad in (lambda: agent.keyboard("show"), lambda: agent.toast(contains=True), lambda: agent.app("revoke", "com.example")):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE


def test_app_foreground_background_and_open_link(fake, agent):
    agent.attach("emulator-5554")
    assert agent.app("foreground", "com.example") == "brought com.example to the foreground"
    assert agent.app("background", "com.example") == "sent com.example to the background (pressed home)"
    assert fake.devices.commands[-1].press_key.key_code == 3
    assert agent.app("open-link", "com.example", "example://orders/42") == "opened example://orders/42 in com.example/.Link"
    assert agent.app("open-link", "com.example", "https://example.com", any_app=True) == "opened https://example.com"
    assert [(r.uri, r.any_app) for r in fake.apps.links] == [("example://orders/42", False), ("https://example.com", True)]
    with pytest.raises(AgentError) as info:
        agent.app("open-link", "com.example")
    assert info.value.exit_code == EXIT_USAGE


def test_screenshot_is_saved_under_the_out_dir(fake, agent, tmp_path):
    agent.attach("emulator-5554")
    path = agent.screenshot()
    assert path.parent == tmp_path / "out" and path.read_bytes() == fake.devices.png


def test_sessions_devices_and_release(fake, agent, client):
    agent.attach("emulator-5554")
    client.connect("a test run")  # observed: listed only as a count
    assert agent.sessions() == "agent  idle 2s of 15m  emulator-5554\n(1 other connection, e.g. test runs)"
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
    assert cli.main(["attach", "emulator-5554", "--idle", "90s", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "attached emulator-5554 to session 'cli', idle timeout 90s\n"
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
    assert cli.main(["tap", "text=OK", "--double", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "double-tapped text=OK\n"
    assert cli.main(["pinch", "text=Map", "open", "--percent", "50", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "pinched open 50% text=Map\n"
    assert cli.main(["app", "open-link", "com.example", "example://x", "--any-app", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "opened example://x\n"
    with pytest.raises(SystemExit) as info:
        cli.main(["rotate", "sideways"])
    assert info.value.code == EXIT_USAGE
    with pytest.raises(SystemExit) as info:
        cli.main(["panel", "settings"])
    assert info.value.code == EXIT_USAGE
    assert cli.main(["skill"]) == 0
    assert capsys.readouterr().out.startswith("---\nname: tap-android")


def test_export_is_the_session_log_as_json(fake, agent, tmp_path):
    import json

    agent.attach("emulator-5554")
    agent.app("cold-launch", "com.example")
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
    assert tapped["command"]["tap"]["selector"] == {"node": {"resource": {"name": "login"}}}
    assert missing["error"] == {"code": "ERR_NOT_FOUND"} and not missing["ok"]
    out = tmp_path / "log" / "session.json"
    assert agent.export(out) == f"wrote 3 events (1 failed) to {out}"
    assert json.loads(out.read_text())["events"] == document["events"]

def test_condition_reads_back_and_app_locale(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: (
        pb.CommandResult(device_info=pb.DeviceInfo(animations_enabled=False, dark_mode=False, font_scale=1.25, density_dpi=320, wifi_enabled=True))
        if command.HasField("device_info")
        else None
    )
    assert agent.condition() == (
        "animations off, dark-mode off, font-scale 1.25, density 320 dpi, airplane-mode off, wifi on, mobile-data off"
    )
    assert agent.condition("animations", "off") == "animations off (restored on release)"
    assert agent.condition("font_scale", "1.25") == "font-scale 1.25 (restored on release)"
    assert agent.condition("density", "reset") == "density 320 dpi (restored on release)"
    assert agent.condition("density") == "density 320 dpi"
    animations, font, density = fake.devices.conditions
    assert animations.enabled is False and font.scale == 1.25 and not density.HasField("dpi")
    with pytest.raises(AgentError) as refused:
        agent.condition("dark-mode", "on")
    assert refused.value.exit_code == EXIT_FAILED and "API 29" in str(refused.value)
    for bad in (
        lambda: agent.condition("contrast", "on"),
        lambda: agent.condition("animations", "maybe"),
        lambda: agent.condition("font-scale", "big"),
        lambda: agent.condition("density", "high"),
    ):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE
    assert len(fake.devices.conditions) == 4
    assert agent.condition("mobile-data", "off") == "mobile-data off (restored on release)"
    assert fake.devices.conditions[-1].mobile_data is False and not fake.devices.conditions[-1].HasField("wifi")
    assert agent.app("locale", "com.example") == "com.example follows the system language"
    assert agent.app("locale", "com.example", "fr-FR, en") == "com.example languages: fr-FR, en"
    assert agent.app("locale", "com.example", "system") == "com.example follows the system language"



# --- MCP ---------------------------------------------------------------------------------------

EXPECTED_TOOLS = {
    "devices", "attach", "sessions", "release", "snapshot", "tap", "fill", "type_text", "clear",
    "scroll", "swipe", "fling", "drag", "pinch", "press_key", "open_panel", "rotate", "screen", "permission",
    "submit", "keyboard", "clipboard", "await_toast", "wait_for", "settle", "screenshot", "capture", "app", "export",
    "condition",
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
        result = await client.call_tool("attach", {"serial": "emulator-5554", "idle_minutes": 2})
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
        result = await client.call_tool("screen", {"action": "state"})
        assert not result.is_error and result.content[0].text.startswith("screen off")
        result = await client.call_tool("permission", {"choice": "deny"})
        assert not result.is_error and result.content[0].text == "pressed deny"
        result = await client.call_tool("keyboard", {})
        assert not result.is_error and result.content[0].text == "keyboard hidden"
        result = await client.call_tool("clipboard", {"text": "hi"})
        assert not result.is_error and result.content[0].text == "clipboard set (2 characters)"
        result = await client.call_tool("app", {"action": "open-link", "package": "com.example", "argument": "example://x"})
        assert not result.is_error and result.content[0].text.startswith("opened example://x")
        result = await client.call_tool("export", {})
        assert not result.is_error and '"format": "tap-events/1"' in result.content[0].text
