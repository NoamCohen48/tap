"""The agent core, the CLI and the MCP server over the in-process fake daemon."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import pytest  # type: ignore[import-not-found]
from mcp import Client
from mcp_types import ImageContent, TextContent
from tap_e2e import TapClient, res, text
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.targets import UsageError
from tap_agent.core import EXIT_FAILED, EXIT_NO_DAEMON, EXIT_USAGE
from tap_agent.mcp_server import create_server

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


def _context_screen(fake):
    row = pb.ScreenNode(ref="e1", depth=0, window_index=1, window_type=1,
                        window_package="com.android.settings", interactive=True,
                        selector=res("settings_row")._proto, flags=[pb.FLAG_ENABLED])
    label = pb.ScreenNode(ref="e2", depth=1, window_index=1, window_type=1,
                          window_package="com.android.settings", text="Network & internet")
    keyboard = pb.ScreenNode(ref="e3", depth=0, window_index=2, window_type=2,
                             window_package="keyboard", text="Keyboard", interactive=True)
    chrome = pb.ScreenNode(ref="e4", depth=0, window_index=3, window_type=3,
                           window_package="com.android.systemui", text="Status bar", interactive=True,
                           bounds=pb.Bounds(left=0, top=0, right=1080, bottom=63))
    fake.devices.snapshot = pb.ScreenSnapshotResponse(snapshot_id=1, nodes=[row, label, keyboard, chrome],
                                                      display_width=1080, display_height=2400, density_dpi=420)
    fake.devices.refs["e1"] = row.selector
    return row.selector


def test_context_snapshot_filters_and_ref_action_use_original_selector(fake, agent):
    agent.attach("emulator-5554")
    selector = _context_screen(fake)
    result = agent.snapshot(level="interactive", hide_keyboard=True, hide_system_chrome=True)
    assert result == '# com.android.settings · window 1\n@e1  context="Network & internet"'
    assert "Keyboard" in agent.snapshot(window=2)
    assert "Keyboard" not in agent.snapshot(package="com.android.settings")
    agent.tap("@e1")
    assert fake.devices.commands[-1].tap.selector == selector


def test_cli_snapshot_context_and_filters(fake, monkeypatch, capsys):
    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", TOKEN)
    assert cli.main(["attach", "emulator-5554", "-s", "context"]) == 0
    capsys.readouterr()
    _context_screen(fake)
    assert cli.main(["snapshot", "-i", "-s", "context", "--package", "com.android.settings",
                     "--window", "1", "--hide-keyboard", "--hide-system-chrome"]) == 0
    assert capsys.readouterr().out == '# com.android.settings · window 1\n@e1  context="Network & internet"\n'


@pytest.mark.anyio
async def test_mcp_snapshot_context_and_filters(fake, server):
    async with Client(server, mode="legacy") as client:
        await client.call_tool("attach", {"serial": "emulator-5554"})
        _context_screen(fake)
        result = await client.call_tool("snapshot", {"view": "interactive", "package": "com.android.settings",
                                                    "window": 1, "hide_keyboard": True, "hide_system_chrome": True})
        assert not result.is_error
        assert result.content[0].text == '# com.android.settings · window 1\n@e1  context="Network & internet"'
        result = await client.call_tool("snapshot", {"as_json": True, "hide_keyboard": True})
        assert result.is_error and "no view level or filter" in result.content[0].text


def test_cold_start_policy_is_forwarded_and_never_replaces_an_active_attachment(fake, agent):
    agent.attach("emulator-5554", driver_start="cold")
    assert fake.devices.attach_requests[-1].driver_start == pb.DRIVER_START_COLD
    before = len(fake.devices.attach_requests)
    with pytest.raises(AgentError, match="existing attachment") as failure:
        agent.attach("emulator-5554", driver_start="cold")
    assert failure.value.exit_code == EXIT_USAGE
    assert len(fake.devices.attach_requests) == before
    with pytest.raises(AgentError, match="driver_start"):
        agent.attach("85e49002", driver_start="warm")


def test_cli_cold_attach_flag(fake, agent):
    args = cli.parser().parse_args(["attach", "emulator-5554", "--driver-start", "cold"])
    cli.run(args, agent)
    assert fake.devices.attach_requests[-1].driver_start == pb.DRIVER_START_COLD
    with pytest.raises(SystemExit):
        cli.parser().parse_args(["attach", "phone", "--driver-start", "warm"])


@pytest.mark.anyio
async def test_mcp_cold_attach_policy(fake, server):
    async with Client(server, mode="legacy") as client:
        result = await client.call_tool("attach", {"serial": "emulator-5554", "driver_start": "cold"})
        assert not result.is_error
        assert fake.devices.attach_requests[-1].driver_start == pb.DRIVER_START_COLD
        result = await client.call_tool("attach", {"serial": "emulator-5554", "driver_start": "cold"})
        assert result.is_error
        assert len(fake.devices.attach_requests) == 1


def test_attach_creates_a_held_session_and_later_calls_resume_it(fake, agent):
    out = agent.attach("emulator-5554", idle=600)
    assert out == "attached emulator-5554 to session 'agent', idle timeout 10m"
    (request,) = fake.connections.live.values()
    assert request.name == "agent" and request.hold.idle_timeout_ms == 600_000
    assert agent.attach("emulator-5554") == "emulator-5554 is already attached to session 'agent'"
    assert fake.connections.connects == 1


def test_a_quarantined_device_is_inspected_then_unquarantined(fake, agent):
    fake.devices.quarantined["85e49002"] = "BOOT_CHANGED: rebooted"
    with pytest.raises(AgentError, match="quarantined"):
        agent.attach("85e49002")
    out = agent.attach("85e49002", inspect_quarantined=True)
    assert "85e49002 is quarantined (BOOT_CHANGED: rebooted)" in out
    assert "until `devices unquarantine 85e49002`" in out
    agent.detach()
    assert agent.unquarantine("85e49002") == (
        "85e49002 is back in service (it was quarantined: BOOT_CHANGED: rebooted)\n"
        "  - stopped the driver\n"
        "  - still off: device settings a session changed were not restored"
    )
    assert agent.unquarantine("85e49002") == "85e49002 is not quarantined; nothing to do"


def test_devices_say_which_quarantines_are_retried_or_inspected(fake, agent):
    fake.devices.listed = [
        pb.DeviceEntry(serial="a", state=pb.DEVICE_QUARANTINED, quarantine_reason="x", retry_on_attach=True),
        pb.DeviceEntry(serial="b", state=pb.DEVICE_QUARANTINED, quarantine_reason="y", under_inspection=True),
    ]
    assert agent.devices() == "a  QUARANTINED  (x)  retried on attach\nb  QUARANTINED  (y)  under inspection"


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
    assert agent.snapshot("85e49002") == '# com.example\n@e1  [Button]  "OK"  accepts=tap'
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


def test_counted_taps_timed_press_and_invalid_combinations(fake, agent):
    agent.attach("emulator-5554")
    assert agent.tap("text=OK", count=3, interval=0.2) == "tapped 3 times text=OK"
    assert fake.devices.commands[-1].multi_tap.count == 3
    assert fake.devices.commands[-1].multi_tap.interval_ms == 200
    assert agent.tap("text=OK", duration=2, timeout=4) == "long-tapped text=OK"
    assert fake.devices.commands[-1].hold.duration_ms == 2000
    assert fake.devices.commands[-1].timeout_ms == 4000
    for options in ({"count": 0}, {"long": True, "count": 3}, {"double": True, "duration": 2}, {"duration": 0}):
        before = len(fake.devices.commands)
        with pytest.raises(AgentError) as error:
            agent.tap("text=OK", **options)
        assert error.value.exit_code == EXIT_USAGE
        assert len(fake.devices.commands) == before


def test_tap_outside_panel_sends_one_command_and_refuses_other_options(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(outside_touch=pb.OutsideTouch(x=540, y=1519, panel="window [28,943][1052,1456], below"))
    assert agent.tap("text=Title", outside_panel=True) == "tapped outside the panel of text=Title at (540, 1519): window [28,943][1052,1456], below"
    assert fake.devices.commands[-1].tap_outside_panel.selector.node.match.value == "Title"
    assert not fake.devices.commands[-1].tap_outside_panel.allow_reach_below
    agent.tap("text=Title", outside_panel=True, allow_reach_below=True, timeout=3)
    assert fake.devices.commands[-1].tap_outside_panel.allow_reach_below
    assert fake.devices.commands[-1].timeout_ms == 3000
    for options in ({"long": True}, {"double": True}, {"count": 2}, {"duration": 1}, {"outside_panel": False, "allow_reach_below": True}):
        before = len(fake.devices.commands)
        with pytest.raises(AgentError) as error:
            agent.tap("text=Title", **{"outside_panel": True, **options})
        assert error.value.exit_code == EXIT_USAGE
        assert len(fake.devices.commands) == before


@pytest.mark.parametrize(("detail", "hint"), [
    ("NO_OUTSIDE_POINT", "target a node inside the panel"),
    ("TOUCH_REACHES_BELOW", "add --allow-reach-below"),
])
def test_a_refused_outside_tap_says_what_to_do(fake, agent, detail, hint):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_INTERACTABLE, detail=detail))
    with pytest.raises(AgentError, match=f"NOT_INTERACTABLE/{detail}.*\n.*{hint}"):
        agent.tap("text=Title", outside_panel=True)


def test_an_obscured_node_says_to_close_the_covering_window(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_INTERACTABLE, detail="OBSCURED"))
    with pytest.raises(AgentError, match="NOT_INTERACTABLE/OBSCURED.*\nanother window covers the node"):
        agent.tap("text=Item 9")


def test_a_stale_node_says_nothing_changed_and_to_run_it_again(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_STALE_BEFORE_INPUT))
    with pytest.raises(AgentError, match="STALE_BEFORE_INPUT.*\nthe node was re-rendered before any input"):
        agent.tap("text=Item 9")


def test_a_dead_app_says_to_launch_it_again(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_AUT_CRASHED))
    with pytest.raises(AgentError, match="AUT_CRASHED.*\nthe app died"):
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
    assert out == 'filled text=Name\n# com.example\n+ @e4  [Button]  "Allow"  accepts=tap'
    kinds = [c.WhichOneof("op") for c in fake.devices.commands]
    assert kinds[-4:] == ["set_text", "device_info", "wait_screen_stable", "read_toast_history"]
    wait = fake.devices.commands[-2].wait_screen_stable
    assert wait.package_name == "com.android.permissioncontroller" and wait.signal == pb.STABILITY_TREE


def test_settle_without_a_focused_app_reports_an_actionable_error(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: (
        pb.CommandResult(device_info=pb.DeviceInfo()) if command.HasField("device_info") else None
    )
    with pytest.raises(AgentError, match="no focused app to settle") as error:
        agent.settle()
    assert error.value.exit_code == EXIT_FAILED
    assert not any(c.HasField("wait_screen_stable") for c in fake.devices.commands)


def test_fill_and_type_pass_sensitive_through(fake, agent):
    agent.attach("emulator-5554")
    agent.fill("text=Password", "hunter2", sensitive=True)
    agent.type("s3cret", sensitive=True)
    filled, typed = fake.devices.commands[-2:]
    assert filled.set_text.sensitive and filled.set_text.text == "hunter2"
    assert typed.type_text.sensitive and typed.type_text.text == "s3cret"


def test_a_screen_that_keeps_changing_is_reported_not_raised(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda c: (
        pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT)) if c.HasField("wait_screen_stable")
        else pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.example")) if c.HasField("device_info")
        else None
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
    assert agent.fling("id=list", "down") == "flung down (finger up) id=list"
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
                    package_name="com.android.permissioncontroller",
                    choices=[pb.PERMISSION_ALLOW_FOREGROUND_ONLY, pb.PERMISSION_DENY],
                    accuracies=[pb.LOCATION_PRECISE, pb.LOCATION_APPROXIMATE],
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
    assert agent.permission() == (
        "permission dialog (com.android.permissioncontroller) offers: allow-foreground-only, deny; accuracy: precise, approximate"
    )
    assert agent.permission("allow-one-time") == "pressed allow-one-time"
    assert fake.devices.commands[-1].choose_permission.choice == pb.PERMISSION_ALLOW_ONE_TIME
    assert fake.devices.commands[-1].choose_permission.accuracy == pb.LOCATION_ACCURACY_UNSPECIFIED
    assert agent.permission("allow-foreground-only", accuracy="approximate") == "pressed allow-foreground-only (approximate)"
    assert fake.devices.commands[-1].choose_permission.accuracy == pb.LOCATION_APPROXIMATE
    for bad in (
        lambda: agent.rotate("sideways"),
        lambda: agent.screen("dim"),
        lambda: agent.permission("maybe"),
        lambda: agent.permission(accuracy="precise"),
        lambda: agent.permission("allow", accuracy="exact"),
    ):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE


def test_accessibility_actions_and_progress(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: (
        pb.CommandResult(
            snapshot=pb.ElementSnapshot(
                actions=[pb.A11Y_EXPAND, pb.A11Y_SCROLL_FORWARD],
                custom_actions=["Archive"],
                range=pb.Range(type=pb.RANGE_INT, min=0, max=100, current=20),
            )
        )
        if command.HasField("snapshot")
        else None
    )
    assert agent.action("id=card") == "id=card offers: expand, scroll-forward, 'Archive'\nrange 0..100, now 20 (int)"
    assert agent.action("id=card", "expand") == "performed expand on id=card"
    assert fake.devices.commands[-1].perform_accessibility_action.standard == pb.A11Y_EXPAND
    assert agent.action("id=card", "Archive", custom=True) == "performed 'Archive' on id=card"
    assert fake.devices.commands[-1].perform_accessibility_action.custom == "Archive"
    assert agent.progress("id=volume", 55) == "set progress 55 on id=volume"
    assert fake.devices.commands[-1].set_progress.value == 55
    for bad in (lambda: agent.action("id=card", "explode"), lambda: agent.action("id=card", custom=True)):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE


def test_notifications_are_listed_awaited_opened_and_dismissed(fake, agent):
    agent.attach("emulator-5554")
    sent = []
    message = pb.DeviceNotification(package_name="com.example", title="New message", text="from Ada", actions=["Reply"], clearable=True)
    service = pb.DeviceNotification(package_name="android", title="USB debugging connected")

    def respond(command):
        op = command.WhichOneof("op")
        sent.append(command)
        if op == "list_notifications":
            return pb.CommandResult(notifications=pb.NotificationList(notifications=[message, service]))
        if op == "await_notification":
            return pb.CommandResult(notification=message)
        if op in ("open_notification", "dismiss_notification"):
            return pb.CommandResult(done=pb.Done())
        return None

    fake.devices.responder = respond
    assert agent.notification() == (
        "com.example: 'New message' - 'from Ada' [Reply]\nandroid: 'USB debugging connected' (ongoing)"
    )
    assert agent.notification(package="android") == "android: 'USB debugging connected' (ongoing)"
    assert agent.notification(title="New", contains=True, wait=True) == "com.example: 'New message' - 'from Ada' [Reply]"
    assert agent.notification("open", "New message", button="Reply") == "pressed 'Reply' on the notification"
    assert agent.notification("dismiss", text="from Ada") == "dismissed the notification"
    assert sent[2].await_notification.match.mode == pb.MATCH_CONTAINS
    assert sent[3].open_notification.action == "Reply"
    assert sent[4].dismiss_notification.match.text == "from Ada"
    for bad in (
        lambda: agent.notification("peek"),
        lambda: agent.notification("await"),
        lambda: agent.notification(contains=True, wait=True),
        lambda: agent.notification("open", "x", wait=True),
        lambda: agent.notification(settle=True),
        lambda: agent.notification("dismiss", "x", button="Reply"),
    ):
        with pytest.raises(AgentError) as info:
            bad()
        assert info.value.exit_code == EXIT_USAGE
    assert len(sent) == 5


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
    assert agent.toast("Saved", contains=True, timeout=2, wait=True) == "toast 'Saved order' from com.example"
    toast = fake.devices.commands[-1].await_toast
    assert (toast.text, toast.mode, toast.HasField("package_name")) == ("Saved", pb.MATCH_CONTAINS, False)
    assert fake.devices.commands[-1].timeout_ms == 2000
    agent.toast(package="com.example", wait=True)
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
    assert capsys.readouterr().out == '# com.example\n@e1  [Button]  "OK"  accepts=tap\n'
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
    assert cli.main(["tap", "text=OK", "--count", "3", "--interval", "200ms", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "tapped 3 times text=OK\n"
    assert fake.devices.commands[-1].multi_tap.interval_ms == 200
    assert cli.main(["tap", "text=OK", "--duration", "1500ms", "--timeout", "4s", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "long-tapped text=OK\n"
    assert fake.devices.commands[-1].hold.duration_ms == 1500
    fake.devices.responder = lambda command: pb.CommandResult(outside_touch=pb.OutsideTouch(x=1, y=2, panel="window [0,0][9,9], below"))
    assert cli.main(["tap", "text=Title", "--outside-panel", "--allow-reach-below", "-s", "cli"]) == 0
    assert capsys.readouterr().out == "tapped outside the panel of text=Title at (1, 2): window [0,0][9,9], below\n"
    fake.devices.responder = None
    assert fake.devices.commands[-1].tap_outside_panel.allow_reach_below
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


def test_an_export_has_the_servers_record_of_each_call(fake, agent, tmp_path):
    import json

    agent.attach("emulator-5554")
    agent.app("cold-launch", "com.example")
    fake.devices.refs["e3"] = res("login")._proto
    agent.tap("@e3")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
    with pytest.raises(AgentError):
        agent.tap("text=Nope")
    document = json.loads(agent.history(as_json=True))
    assert document["format"] == "tap-history/2" and not document["issues"]
    assert all(step["session"] == "agent" for step in document["steps"])
    cold, tapped, missing = [e["call"] for e in document["entries"] if e["kind"] == "call"]
    assert cold["app"] == {"operation": "cold_launch", "package_name": "com.example"} and cold["ok"]
    # The ref is logged as the selector it named.
    assert tapped["command"]["tap"]["selector"] == {"node": {"resource": {"name": "login"}}}
    assert missing["error"] == {"code": "ERR_NOT_FOUND"} and not missing["ok"]
    assert [cold["step"], tapped["step"], missing["step"]] == [step["id"] for step in document["steps"]]
    out = tmp_path / "log" / "session.json"
    assert not out.exists(), "history returns stdout data, not a file"
    out.parent.mkdir()
    out.write_text(agent.history(as_json=True))  # Explicit caller-side redirection/storage.
    assert json.loads(out.read_text())["entries"] == document["entries"]

def test_condition_reads_back_and_app_locale(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: (
        pb.CommandResult(device_info=pb.DeviceInfo(animations_enabled=False, dark_mode=False, font_scale=1.25, density_dpi=320, wifi_enabled=True, system_locales=["fr-FR", "en"], color_inversion=True))
        if command.HasField("device_info")
        else None
    )
    assert agent.condition() == (
        "animations off, dark-mode off, font-scale 1.25, density 320 dpi, airplane-mode off, wifi on, mobile-data off, locale fr-FR,en, "
        "stay-awake off, ime-with-hard-keyboard unknown, high-contrast-text unknown, color-inversion on, bold-text off"
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
    assert agent.condition("locale", "fr-FR, en") == "locale fr-FR,en (restored on release)"
    assert list(fake.devices.conditions[-1].locales) == ["fr-FR", "en"]
    with pytest.raises(AgentError) as empty:
        agent.condition("locale", " , ")
    assert empty.value.exit_code == EXIT_USAGE
    assert agent.condition("color-inversion", "on") == "color-inversion on (restored on release)"
    assert fake.devices.conditions[-1].color_inversion is True and not fake.devices.conditions[-1].HasField("bold_text")
    assert agent.condition("stay_awake", "on") == "stay-awake off (restored on release)"
    assert fake.devices.conditions[-1].enabled is True
    assert agent.condition("ime-with-hard-keyboard", "on") == "ime-with-hard-keyboard unknown (restored on release)"
    assert type(fake.devices.conditions[-1]).__name__ == "SetShowImeWithHardKeyboardRequest" and fake.devices.conditions[-1].enabled is True
    assert agent.activity() == "no activity is resumed"
    fake.devices.foreground = ("com.example", "com.example.MainActivity")
    assert agent.activity() == "com.example/com.example.MainActivity"
    assert agent.location(48.8584, 2.2945, 3.5) == "location mocked at 48.8584, 2.2945 ±3.5 m (ends on release)"
    assert fake.devices.conditions[-1].latitude == 48.8584 and fake.devices.conditions[-1].accuracy_m == 3.5
    assert agent.location(48.8584, 2.2945, device=None, altitude=35) == "location mocked at 48.8584, 2.2945, 35 m up (ends on release)"
    assert fake.devices.conditions[-1].altitude_m == 35 and not fake.devices.conditions[-1].HasField("accuracy_m")
    with pytest.raises(AgentError) as far:
        agent.location(95, 0)
    assert far.value.exit_code == EXIT_USAGE
    assert agent.app("locale", "com.example") == "com.example follows the system language"
    assert agent.app("locale", "com.example", "fr-FR, en") == "com.example languages: fr-FR, en"
    assert agent.app("locale", "com.example", "system") == "com.example follows the system language"


def test_files_push_pull_and_media(fake, agent, tmp_path):
    agent.attach("emulator-5554")
    local = tmp_path / "cat.png"
    local.write_bytes(b"\x89PNG")
    assert agent.push(local, "/sdcard/Download/cat.png") == "pushed 4 bytes to /sdcard/Download/cat.png (removed on release)"
    out = tmp_path / "back.png"
    assert agent.pull("/sdcard/Download/cat.png", out) == str(out)
    assert out.read_bytes() == b"\x89PNG"
    pulled = agent.pull("/sdcard/Download/cat.png")
    assert pulled.endswith("-cat.png")
    with open(pulled, "rb") as stream:
        assert stream.read() == b"\x89PNG"
    assert agent.media(local) == "added /sdcard/Pictures/Tap/cat.png to the gallery (removed on release)"
    assert agent.media(local, "dog.png") == "added /sdcard/Pictures/Tap/dog.png to the gallery (removed on release)"
    with pytest.raises(AgentError) as missing:
        agent.push(tmp_path / "nope", "/sdcard/Download/x")
    assert missing.value.exit_code == EXIT_USAGE
    with pytest.raises(AgentError) as taken:
        agent.push(local, "/sdcard/Download/cat.png")
    assert taken.value.exit_code == EXIT_FAILED


# --- MCP ---------------------------------------------------------------------------------------

EXPECTED_TOOLS = {
    "devices", "attach", "sessions", "release", "snapshot", "tap", "fill", "type_text", "clear",
    "scroll", "swipe", "fling", "drag", "pinch", "press_key", "open_panel", "rotate", "screen", "permission",
    "submit", "keyboard", "clipboard", "toast", "wait_for", "settle", "screenshot", "capture", "app",
    "condition", "accessibility_action", "set_progress", "set_location", "push_file", "pull_file", "add_media",
    "foreground_activity", "notification", "app_data", "inspect", "exists", "count", "elements",
    "detach", "server_info", "device_info", "capabilities", "media_record", "scroll_until", "history",
    "unquarantine", "deaths", "watch",
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
        assert agent.snapshot().endswith('"OK"  accepts=tap')
        result = await client.call_tool("tap", {"target": "@e42"})
        assert result.is_error and "run `snapshot`" in result.content[0].text
        result = await client.call_tool("tap", {"target": "text=OK", "count": 3, "interval": 0.2})
        assert not result.is_error and _last(fake, "multi_tap").multi_tap.count == 3
        result = await client.call_tool("tap", {"target": "text=OK", "duration": 1.5, "timeout": 4})
        assert not result.is_error and _last(fake, "hold").hold.duration_ms == 1500
        result = await client.call_tool("tap", {"target": "text=OK", "duration": 1.5, "double": True})
        assert result.is_error
        result = await client.call_tool("tap", {"target": "text=OK", "outside_panel": True})
        assert not result.is_error and _last(fake, "tap_outside_panel") is not None
        result = await client.call_tool("screenshot", {})
        assert isinstance(result.content[0], ImageContent) and result.content[0].mime_type == "image/png"
        result = await client.call_tool("open_panel", {"panel": "quick_settings"})
        assert not result.is_error and result.content[0].text.splitlines()[0] == "opened quick-settings"
        result = await client.call_tool("screen", {})
        assert not result.is_error and result.content[0].text.startswith("screen off")
        assert (await client.call_tool("screen", {"action": "state"})).is_error
        result = await client.call_tool("permission", {"choice": "deny"})
        assert not result.is_error and result.content[0].text.splitlines()[0] == "pressed deny"
        result = await client.call_tool("keyboard", {})
        assert not result.is_error and result.content[0].text.splitlines()[0] == "keyboard hidden"
        result = await client.call_tool("clipboard", {"text": "hi"})
        assert not result.is_error and result.content[0].text.splitlines()[0] == "clipboard set (2 characters)"
        result = await client.call_tool("app", {"action": "open-link", "package": "com.example", "argument": "example://x"})
        assert not result.is_error and result.content[0].text.startswith("opened example://x")
        result = await client.call_tool("history", {})
        assert not result.is_error and "tap" in result.content[0].text and "→" in result.content[0].text


def test_launch_extras_are_typed_and_checked(fake, agent, capsys):
    agent.attach("emulator-5554")
    assert agent.app("launch", "com.example", ".Item", extras=["user=ada", "n:int=3", "id:long=42", "ratio:float=0.5", "on:bool=true"]) == "launched com.example"
    request = fake.apps.launches[-1]
    shown = {e.key: (e.WhichOneof("value"), getattr(e, e.WhichOneof("value"))) for e in request.extras}
    assert shown == {
        "user": ("string_value", "ada"),
        "n": ("int_value", 3),
        "id": ("long_value", 42),
        "ratio": ("float_value", 0.5),
        "on": ("bool_value", True),
    }
    agent.app("cold-launch", "com.example", extras=["url=a=b"])
    assert [(e.key, e.string_value) for e in fake.apps.cold_launches[-1].extras] == [("url", "a=b")]
    for bad in (["novalue"], ["n:int=x"], ["n:int=3000000000"], ["x:short=1"], ["b:bool=yes"], ["f:float=nan"], ["k=1", "k=2"], ["=v"]):
        with pytest.raises(AgentError) as info:
            agent.app("launch", "com.example", extras=bad)
        assert info.value.exit_code == EXIT_USAGE, bad
    with pytest.raises(AgentError) as info:
        agent.app("stop", "com.example", extras=["a=b"])
    assert info.value.exit_code == EXIT_USAGE
    assert len(fake.apps.launches) == 1


def test_keyboard_and_screen_read_without_a_word_and_refuse_state(fake, agent):
    agent.attach("emulator-5554")
    assert agent.keyboard() == "keyboard hidden"
    assert agent.screen().startswith("screen ")
    for call in (lambda: agent.keyboard("state"), lambda: agent.screen("state")):
        with pytest.raises(AgentError, match="or nothing to read"):
            call()
    for call in (lambda: agent.keyboard(settle=True), lambda: agent.screen(settle=True)):
        with pytest.raises(AgentError, match="--settle goes with"):
            call()
    with pytest.raises(SystemExit):
        cli.parser().parse_args(["keyboard", "state"])
    assert cli.run(cli.parser().parse_args(["keyboard"]), agent) == "keyboard hidden"


def test_scroll_and_swipe_say_which_way_the_finger_moved(fake, agent):
    agent.attach("emulator-5554")
    assert agent.scroll("id=list", "down") == "scrolled down (finger up) id=list"
    assert agent.swipe("id=list", "up") == "swiped finger up id=list"
    assert fake.devices.commands[-2].scroll.direction == pb.DIR_DOWN  # the content to reveal
    assert fake.devices.commands[-1].swipe.direction == pb.DIR_UP  # the finger
    parser = cli.parser()
    assert cli.run(parser.parse_args(["swipe", "id=list", "--finger", "left"]), agent) == "swiped finger left id=list"
    with pytest.raises(SystemExit):
        parser.parse_args(["swipe", "id=list", "left"])


def test_a_failed_settle_after_input_is_reported_not_raised(fake, agent):
    agent.attach("emulator-5554")
    # The fake reports no focused package, so the settle after the tap cannot run.
    output = agent.tap("text=OK", settle=True)
    assert output.splitlines()[0] == "tapped text=OK"
    assert "! not settled (no focused app to settle" in output and "do not repeat the step" in output
    assert [c.WhichOneof("op") for c in fake.devices.commands].count("tap") == 1
    with pytest.raises(AgentError, match="no focused app to settle"):
        agent.settle()  # the settle command itself still fails


def _last(fake, op):
    """The newest command of ``op``: an MCP action settles by default, so commands follow it."""
    return next((c for c in reversed(fake.devices.commands) if c.WhichOneof("op") == op), None)


@pytest.mark.anyio
async def test_mcp_actions_settle_by_default_and_reads_do_not(fake, server):
    async with Client(server, mode="legacy") as client:
        await client.call_tool("attach", {"serial": "emulator-5554"})
        fake.devices.responder = lambda c: pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.example")) if c.WhichOneof("op") == "device_info" else None
        before = len(fake.devices.commands)
        result = await client.call_tool("tap", {"target": "text=OK"})
        ops = [c.WhichOneof("op") for c in fake.devices.commands[before:]]
        assert not result.is_error and ops[0] == "tap" and "wait_screen_stable" in ops
        before = len(fake.devices.commands)
        result = await client.call_tool("tap", {"target": "text=OK", "settle": False})
        assert result.content[0].text == "tapped text=OK"
        assert "wait_screen_stable" not in [c.WhichOneof("op") for c in fake.devices.commands[before:]]
        for read in ("keyboard", "screen"):
            assert not (await client.call_tool(read, {})).is_error


def test_devices_unquarantine_is_the_cli_form_and_the_verb_is_gone(fake, agent):
    parser = cli.parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["unquarantine", "85e49002"])
    assert "not quarantined" in cli.run(parser.parse_args(["devices", "unquarantine", "85e49002"]), agent)
    with pytest.raises(UsageError):
        cli.run(parser.parse_args(["devices", "unquarantine"]), agent)
    with pytest.raises(SystemExit):
        parser.parse_args(["devices", "85e49002"])


@pytest.mark.parametrize("fields,words", [
    ({"flags": [pb.FLAG_CLICKABLE, pb.FLAG_LONG_CLICKABLE, pb.FLAG_CHECKABLE]}, "accepts=tap,long,check"),
    ({"editable": True}, "accepts=edit"),
    ({"flags": [pb.FLAG_SCROLLABLE]}, "accepts=scroll"),
    ({"actions": [pb.A11Y_SCROLL_FORWARD]}, "accepts=scroll"),
    ({"actions": [pb.A11Y_EXPAND]}, "accepts=actions"),
    ({"custom_actions": ["Archive"]}, "accepts=actions"),
    ({"editable": True, "flags": [pb.FLAG_CLICKABLE], "actions": [pb.A11Y_PASTE]}, "accepts=tap,edit,actions"),
])
def test_snapshot_lines_say_what_a_node_accepts(fields, words):
    from tap_e2e._proto import screen_node
    from tap_agent.render import node_line
    line = node_line(screen_node(pb.ScreenNode(ref="e1", window_package="p", text="x", interactive=True, **fields)))
    assert line.split("  ")[-1] == words or words in line.split("  ")


def test_focus_selection_and_copy_alone_accept_nothing():
    from tap_e2e._proto import screen_node
    from tap_agent.render import node_line
    line = node_line(screen_node(pb.ScreenNode(ref="e1", window_package="p", text="Total", flags=[pb.FLAG_ENABLED],
        actions=[pb.A11Y_FOCUS, pb.A11Y_SELECT, pb.A11Y_COPY, pb.A11Y_SHOW_ON_SCREEN])))
    assert "accepts=" not in line


@pytest.mark.parametrize("name,code", [
    ("back", 4), ("volume-up", 24), ("VOLUME_UP", 24), ("KEYCODE_VOLUME_UP", 24), ("media-play-pause", 85),
    ("dpad-center", 23), ("delete", 67), ("del", 67), ("backspace", 67), ("recents", 187), ("app-switch", 187),
    ("keycode-0", 7), ("0", 7), ("7", 14), ("11", 227), ("code:5", 5), ("CODE: 187", 187), ("camera", 27),
])
def test_every_android_key_name_and_the_aliases_resolve(name, code):
    from tap_agent.targets import parse_key
    assert parse_key(name) == code


def test_key_list_needs_no_device_and_names_every_key(agent):
    from tap_agent.targets import CODES
    listing = agent.key(list_keys=True)
    assert len(listing.splitlines()) > 330 and "volume-up  24" in listing and "backspace  67  (= del)" in listing
    assert "\n0  7\n" in listing and len(CODES) > 300
    with pytest.raises(AgentError, match="give code:42"):
        agent.key("42")
    with pytest.raises(AgentError, match="close: volume-up"):
        agent.key("volume-u")
    with pytest.raises(AgentError):
        agent.key("back", list_keys=True)
    assert cli.run(cli.parser().parse_args(["key", "--list"]), agent) == listing


def test_key_long_held_and_counted_presses(fake, agent):
    agent.attach("emulator-5554")
    assert agent.key("power", long=True) == "long-pressed power"
    assert _last(fake, "press_key").press_key.long_press
    assert agent.key("volume-down", duration=2.0) == "long-pressed volume-down for 2s"
    assert _last(fake, "press_key").press_key.duration_ms == 2000
    assert agent.key("back", count=2, interval=0.3) == "pressed back x2"
    key = _last(fake, "press_key").press_key
    assert (key.count, key.interval_ms) == (2, 300)
    sent = len(fake.devices.commands)
    # A long press counts at its worst case (1.5 s), as the server counts it: 20 do not fit in 30 s.
    for bad in ({"count": 0}, {"interval": 0.3}, {"duration": 31.0}, {"count": 2, "interval": 2.0},
                {"count": 100, "duration": 1.0}, {"long": True, "count": 20}):
        with pytest.raises(AgentError):
            agent.key("back", **bad)
    assert len(fake.devices.commands) == sent
    args = cli.parser().parse_args(["key", "volume-up", "--count", "2", "--interval", "250ms", "--duration", "1s"])
    assert (args.count, args.interval, args.duration) == (2, 0.25, 1.0)
    for gone in ("--double", "--times", "--hold"):
        with pytest.raises(SystemExit):
            cli.parser().parse_args(["key", "power", gone, "2"])


def test_keyboard_hide_reads_back_once_the_keyboard_has_gone(fake, agent, monkeypatch):
    agent.attach("emulator-5554")
    reads = {"after_hide": 0, "hidden": False}

    def respond(command):
        op = command.WhichOneof("op")
        if op == "hide_keyboard":
            reads["hidden"] = True
        if op == "device_info":
            # The keyboard's window stays on screen for two reads while it animates out.
            if reads["hidden"]:
                reads["after_hide"] += 1
            return pb.CommandResult(device_info=pb.DeviceInfo(keyboard_shown=not reads["hidden"] or reads["after_hide"] <= 2))
        return None

    fake.devices.responder = respond
    monkeypatch.setattr("tap_agent.core.time.sleep", lambda _: None)
    assert agent.keyboard("hide") == "keyboard hidden"
    assert reads["after_hide"] == 3
    assert sum(c.WhichOneof("op") == "hide_keyboard" for c in fake.devices.commands) == 1


def test_keyboard_hide_says_when_the_keyboard_stays(fake, agent, monkeypatch):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: (pb.CommandResult(device_info=pb.DeviceInfo(keyboard_shown=True))
                                              if command.WhichOneof("op") == "device_info" else None)
    clock = iter(range(0, 1000))
    monkeypatch.setattr("tap_agent.core.time.monotonic", lambda: float(next(clock)))
    monkeypatch.setattr("tap_agent.core.time.sleep", lambda _: None)
    assert agent.keyboard("hide").startswith("keyboard still shown 2s after Back; read it again")


def test_key_list_needs_no_server(monkeypatch, capsys):
    from tap_agent import core

    def no_server(*_args, **_kwargs):
        raise AssertionError("key --list must not reach the server")

    monkeypatch.setattr(core.Agent, "reporting_deaths", no_server)
    monkeypatch.setattr(core.Agent, "_run", no_server)
    assert cli.main(["key", "--list", "--session", "nowhere"]) == 0
    assert "volume-up  24" in capsys.readouterr().out
    assert cli.main(["key", "back", "--list", "--session", "nowhere"]) == 2
