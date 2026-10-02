"""Wait error mapping, timeouts and detach state (PY-5, PY-6) over a fake server."""
# pyright: reportMissingImports=false

from __future__ import annotations

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import (
    CommandError,
    DisplayRotation,
    ErrorCode,
    ForegroundActivity,
    FailureReason,
    LocationAccuracy,
    Orientation,
    PermissionChoice,
    PermissionPrompt,
    Range,
    RangeType,
    ServerError,
    StandardAction,
    TapError,
    TapClient,
    Toast,
    WaitReason,
    WaitTimeoutError,
)
from tap_e2e import _gen as pb
from tap_e2e import DOWN, STARTS_WITH, Long, res, text

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    server = TapClient.create(fake.address, TOKEN)
    connection = server.connect("test")
    device = connection.attach_device("emulator-5554")
    yield device
    fake.devices.responder = None
    fake.devices.detach_error = None
    connection.close()
    server.close()


def _fail_with(fake, code: int) -> None:
    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.HasField("device_info"):
            return None
        return pb.CommandResult(error=pb.Error(code=code), duration_ms=5)

    fake.devices.responder = respond


WAITS = {
    "await_visible": lambda d: d.app("com.test").await_visible(),
    "await_screen_stable": lambda d: d.app("com.test").await_screen_stable(),
    "wait_visible": lambda d: d.screen.wait(text("x")).visible(),
    "wait_one": lambda d: d.app("com.test").wait(text("x")).one(),
    "wait_gone": lambda d: d.screen.wait(text("x")).gone(),
}


@pytest.mark.parametrize("wait", WAITS.values(), ids=WAITS.keys())
def test_only_wait_timeout_becomes_wait_timeout_error(fake, device, wait):
    _fail_with(fake, pb.ERR_WAIT_TIMEOUT)
    with pytest.raises(WaitTimeoutError):
        wait(device)
    for code in (pb.ERR_DRIVER_UNHEALTHY, pb.ERR_TRANSPORT_LOST, pb.ERR_INDETERMINATE):
        _fail_with(fake, code)
        with pytest.raises(CommandError) as info:
            wait(device)
        assert info.value.code.name == pb.ErrorCode.Name(code).removeprefix("ERR_")


def test_device_wait_timeouts_carry_the_reason_and_match_count(fake, device):
    from tap_e2e import WaitReason

    sent: list[pb.Command] = []
    detail = {"detail": "AMBIGUOUS", "match_count": 3}

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.HasField("device_info"):
            return None
        sent.append(command)
        return pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT, **detail))

    fake.devices.responder = respond
    with pytest.raises(WaitTimeoutError) as info:
        device.screen.wait(text("Row")).one()
    assert info.value.reason is WaitReason.AMBIGUOUS
    assert info.value.match_count == 3
    assert "AMBIGUOUS (3 matches)" in str(info.value)
    assert sent[-1].wait_visible.exactly_one

    detail = {"detail": "SOMETHING_NEW"}
    with pytest.raises(WaitTimeoutError) as info:
        device.screen.wait(text("Row")).visible()
    assert info.value.reason is None and info.value.match_count is None
    assert not sent[-1].wait_visible.exactly_one


def test_zero_timeout_is_not_replaced_by_the_default(fake, device):
    device._execute(0, device_info=pb.DeviceInfoQuery())
    device.dump_hierarchy(timeout=0)
    device._execute(None, device_info=pb.DeviceInfoQuery())
    assert [c.timeout_ms for c in fake.devices.commands] == [0, 0, 10_000]


def test_failed_detach_keeps_the_device_attached(fake, device):
    fake.devices.detach_error = grpc.StatusCode.UNAVAILABLE
    with pytest.raises(ServerError):
        device.detach()
    device.info()  # still usable: the flag was not set
    fake.devices.detach_error = None
    assert device.detach() is None
    assert device.detach() is None, "idempotent after success"
    assert fake.devices.detaches == ["attached-emulator-5554"] * 2
    with pytest.raises(TapError, match="detached"):
        device.info()


def test_every_call_on_a_device_names_the_owning_connection(fake, device):
    device.info()
    device.screenshot()
    device.driver_log()
    device.app("com.test").is_installed()
    device.app("com.test").force_stop()
    device.detach()
    assert [rpc for rpc, _ in fake.devices.owners] == [
        "execute",
        "screenshot",
        "driver_log",
        "detach",
    ]
    assert [rpc for rpc, _ in fake.apps.owners] == ["is_installed", "force_stop"]
    owner = device.owner_connection.id
    assert all(cid == owner for _, cid in fake.devices.owners + fake.apps.owners)
    assert fake.apps.force_stops[0].timeout_ms == 10_000


def test_attach_sends_no_lease_timeout_unless_waiting(fake, device):
    connection = device.owner_connection
    connection.attach_device("other", wait_for_device=5).detach()
    first, second = fake.devices.attach_requests
    assert not first.HasField("lease_timeout_ms")
    assert second.lease_timeout_ms == 5000
    assert first.client_connection_id == connection.id and first.HasField("default_timeout_ms")


def test_permission_denied_names_the_foreign_connection(fake, device):
    fake.devices.deny = True
    try:
        with pytest.raises(ServerError, match="another client connection") as info:
            device.info()
        assert info.value.code == "PERMISSION_DENIED"
    finally:
        fake.devices.deny = False


def test_screenshot_is_checked_client_side_and_saves(fake, device, tmp_path):
    target = tmp_path / "shots" / "a.png"
    shot = device.screenshot()
    assert shot.bytes == fake.devices.png
    assert shot.media_type == "image/png"
    assert shot.save(target).read_bytes() == fake.devices.png
    fake.devices.corrupt_png = True
    with pytest.raises(TapError, match="checksum"):
        device.screenshot()


def test_capture_keeps_the_parts_it_got_and_records_why_the_others_are_missing(fake, device, tmp_path):
    from tap_e2e import Capture

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.HasField("device_info"):
            return pb.CommandResult(device_info=pb.DeviceInfo(api_level=34))
        if command.HasField("dump_hierarchy"):
            return pb.CommandResult(error=pb.Error(code=pb.ERR_DRIVER_UNHEALTHY))
        return None

    fake.devices.responder = respond
    capture = device.capture()
    assert capture.screenshot is not None and capture.screenshot.bytes == fake.devices.png
    assert capture.info is not None and capture.info.api_level == 34
    assert capture.driver_log is not None and capture.driver_log.lines == ["line"]
    assert capture.hierarchy is None
    assert list(capture.artifacts) == [Capture.SCREENSHOT, Capture.DEVICE_INFO, Capture.DRIVER_LOG]
    assert list(capture.failures) == [Capture.HIERARCHY]
    failure = capture.failures[Capture.HIERARCHY]
    assert isinstance(failure, CommandError) and failure.code is ErrorCode.DRIVER_UNHEALTHY
    saved = capture.save_to(tmp_path / "out", "main-emulator-5554")
    assert [p.name for p in saved] == [
        "main-emulator-5554.screenshot.png",
        "main-emulator-5554.device-info.json",
        "main-emulator-5554.driver-log.txt",
    ]
    assert saved[0].read_bytes() == fake.devices.png


def test_install_streams_a_header_then_1_mib_chunks(fake, device, tmp_path):
    from tap_e2e.app import INSTALL_CHUNK_BYTES

    data = bytes(i % 251 for i in range(INSTALL_CHUNK_BYTES * 2 + 123))
    apk = tmp_path / "app.apk"
    apk.write_bytes(data)
    device.app("com.test").install(apk, timeout=9)
    header, *chunks = fake.apps.install_parts
    assert header.WhichOneof("part") == "header"
    assert header.header.size_bytes == len(data)
    assert header.header.timeout_ms == 9000
    assert header.header.app.package_name == "com.test"
    assert header.header.app.client_connection_id == device.owner_connection.id
    assert [len(c.chunk) for c in chunks] == [INSTALL_CHUNK_BYTES, INSTALL_CHUNK_BYTES, 123]
    assert b"".join(c.chunk for c in chunks) == data


def test_install_of_a_missing_file_fails_before_any_rpc(fake, device, tmp_path):
    with pytest.raises(OSError):
        device.app("com.test").install(tmp_path / "missing.apk")
    assert fake.apps.install_parts == []


def test_scroll_until_scrolls_until_the_target_exists_in_the_container(fake, device):
    ops: list[pb.Command] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.HasField("scroll"):
            ops.append(command)
            return pb.CommandResult(done=pb.Done())
        if command.HasField("exists"):
            ops.append(command)
            scrolls = sum(1 for op in ops if op.HasField("scroll"))
            return pb.CommandResult(bool=scrolls == 3)
        return None

    fake.devices.responder = respond
    lst = device.screen.element(res("list"))
    in_list = res("list").descendant(text("row 40"))
    assert lst.scroll_until(text("row 40")).selector == in_list
    assert [op.WhichOneof("op") for op in ops] == ["exists", "scroll"] * 3 + ["exists"]
    assert all(op.exists.selector == in_list._proto for op in ops if op.HasField("exists"))
    assert all(op.scroll.direction == pb.DIR_DOWN for op in ops if op.HasField("scroll"))
    # A picked container cannot be carried into a relation: the bare target is used.
    assert lst.first().scroll_until(text("row 40")).selector == text("row 40")


def test_type_text_taps_waits_for_focus_then_types_into_the_focus(fake, device):
    ops: list[pb.Command] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        op = command.WhichOneof("op")
        if op not in ("tap", "snapshot", "type_text"):
            return None
        ops.append(command)
        if op == "snapshot":
            snapshots = sum(1 for c in ops if c.HasField("snapshot"))
            return pb.CommandResult(snapshot=pb.ElementSnapshot(focused=snapshots == 2))
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    device.screen.element(res("email")).type_text("abc")
    assert [c.WhichOneof("op") for c in ops] == ["tap", "snapshot", "snapshot", "type_text"]
    assert ops[-1].type_text.text == "abc"

    ops.clear()
    device.screen.element(res("email")).type_text("d", await_focus=False)
    assert [c.WhichOneof("op") for c in ops] == ["tap", "type_text"]


def test_open_notifications_and_quick_settings_send_the_system_panel_command(fake, device):
    panels: list[int] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if not command.HasField("open_system_panel"):
            return None
        panels.append(command.open_system_panel.panel)
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    device.open_notifications()
    device.open_quick_settings()
    assert panels == [pb.SYSTEM_PANEL_NOTIFICATIONS, pb.SYSTEM_PANEL_QUICK_SETTINGS]


def test_rotation_methods_send_one_typed_mutation_each(fake, device):
    commands: list[pb.Command] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.WhichOneof("op") not in (
            "set_orientation",
            "set_display_rotation",
            "unfreeze_rotation",
        ):
            return None
        commands.append(command)
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    device.set_orientation(Orientation.LANDSCAPE)
    device.set_display_rotation(DisplayRotation.UPSIDE_DOWN)
    device.unfreeze_rotation()

    assert [command.WhichOneof("op") for command in commands] == [
        "set_orientation",
        "set_display_rotation",
        "unfreeze_rotation",
    ]
    assert commands[0].set_orientation.orientation == pb.ORIENTATION_LANDSCAPE
    assert commands[1].set_display_rotation.rotation == pb.DISPLAY_ROTATION_UPSIDE_DOWN


def test_screen_permission_and_gesture_methods_send_their_typed_commands(fake, device):
    sent = ("press_key", "dismiss_keyguard", "wait_permission_prompt", "choose_permission", "double_tap", "drag", "pinch", "fling")
    commands: list[pb.Command] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        op = command.WhichOneof("op")
        if op not in sent:
            return None
        commands.append(command)
        if op == "wait_permission_prompt":
            return pb.CommandResult(
                permission_prompt=pb.PermissionPrompt(
                    package_name="com.android.permissioncontroller",
                    choices=[pb.PERMISSION_ALLOW_FOREGROUND_ONLY, pb.PERMISSION_DENY],
                )
            )
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    device.wake()
    device.sleep()
    device.dismiss_keyguard()
    assert device.await_permission_prompt() == PermissionPrompt(
        "com.android.permissioncontroller", (PermissionChoice.ALLOW_FOREGROUND_ONLY, PermissionChoice.DENY)
    )
    device.choose_permission(PermissionChoice.DENY_AND_DONT_ASK_AGAIN)
    card = device.screen.element(res("card"))
    card.double_tap()
    card.drag_to(res("bin"))
    card.pinch_open()
    card.pinch_close(percent=40)
    card.fling(DOWN)

    assert [c.WhichOneof("op") for c in commands] == [
        "press_key", "press_key", "dismiss_keyguard", "wait_permission_prompt", "choose_permission",
        "double_tap", "drag", "pinch", "pinch", "fling",
    ]
    assert [c.press_key.key_code for c in commands[:2]] == [224, 223]
    assert commands[4].choose_permission.choice == pb.PERMISSION_DENY_AND_DONT_ASK_AGAIN
    assert commands[6].drag.selector == res("card")._proto
    assert commands[6].drag.target == res("bin")._proto
    assert [(c.pinch.direction, c.pinch.percent) for c in commands[7:9]] == [(pb.PINCH_OPEN, 80), (pb.PINCH_CLOSE, 40)]
    assert commands[9].fling.direction == pb.DIR_DOWN


def test_accessibility_actions_progress_and_accuracy_send_their_typed_commands(fake, device):
    commands: list[pb.Command] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        commands.append(command)
        if command.HasField("snapshot"):
            return pb.CommandResult(
                snapshot=pb.ElementSnapshot(
                    actions=[pb.A11Y_COLLAPSE, 999],
                    custom_actions=["Archive", "Mark unread"],
                    range=pb.Range(type=pb.RANGE_FLOAT, min=0, max=1, current=0.25),
                )
            )
        if command.HasField("wait_permission_prompt"):
            return pb.CommandResult(
                permission_prompt=pb.PermissionPrompt(package_name="p", choices=[pb.PERMISSION_DENY], accuracies=[pb.LOCATION_PRECISE])
            )
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    card = device.screen.element(res("card"))
    snapshot = card.snapshot()
    assert snapshot.actions == (StandardAction.COLLAPSE,)
    assert snapshot.custom_actions == ("Archive", "Mark unread")
    assert snapshot.range == Range(RangeType.FLOAT, 0.0, 1.0, 0.25)
    card.perform_action(StandardAction.EXPAND)
    card.perform_custom_action("Archive")
    card.set_progress(0.5)
    with pytest.raises(ValueError):
        card.set_progress(float("nan"))
    assert device.await_permission_prompt().accuracies == (LocationAccuracy.PRECISE,)
    device.choose_permission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)

    standard, custom = [c.perform_accessibility_action for c in commands if c.HasField("perform_accessibility_action")]
    assert standard.standard == pb.A11Y_EXPAND and standard.selector == res("card")._proto
    assert custom.custom == "Archive"
    [progress] = [c.set_progress for c in commands if c.HasField("set_progress")]
    assert progress.value == 0.5
    assert commands[-1].choose_permission.accuracy == pb.LOCATION_APPROXIMATE


def test_await_permission_prompt_timeout_carries_no_permission_prompt(fake, device):
    fake.devices.responder = lambda command: (
        pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT, detail="NO_PERMISSION_PROMPT"))
        if command.HasField("wait_permission_prompt")
        else None
    )
    with pytest.raises(WaitTimeoutError) as timeout:
        device.await_permission_prompt(timeout=0.5)
    assert timeout.value.reason is WaitReason.NO_PERMISSION_PROMPT


def test_keyboard_clipboard_and_toast_methods_send_their_typed_commands(fake, device):
    commands: list[pb.Command] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        op = command.WhichOneof("op")
        commands.append(command)
        if op == "get_clipboard":
            return pb.CommandResult(text="copied")
        if op == "await_toast":
            return pb.CommandResult(toast=pb.Toast(text="Saved", package_name="com.test"))
        if op == "device_info":
            return pb.CommandResult(device_info=pb.DeviceInfo(api_level=34, keyboard_shown=True))
        if op in ("hide_keyboard", "perform_ime_action", "set_clipboard"):
            return pb.CommandResult(done=pb.Done())
        return None

    fake.devices.responder = respond
    assert device.keyboard_shown()
    device.hide_keyboard()
    device.screen.element(res("search")).ime_action()
    device.set_clipboard("hello")
    assert device.clipboard() == "copied"
    assert device.await_toast() == Toast("Saved", "com.test")
    device.app("com.test").await_toast("Sav", STARTS_WITH)
    device.await_toast(package_name="com.android.systemui")

    toasts = [c.await_toast for c in commands if c.HasField("await_toast")]
    assert not toasts[0].HasField("text") and toasts[0].mode == pb.MATCH_UNSPECIFIED
    assert not toasts[0].HasField("package_name")
    assert (toasts[1].text, toasts[1].mode, toasts[1].package_name) == ("Sav", pb.MATCH_STARTS_WITH, "com.test")
    assert toasts[2].package_name == "com.android.systemui"
    [ime] = [c for c in commands if c.HasField("perform_ime_action")]
    assert ime.perform_ime_action.selector == res("search")._proto
    [clip] = [c for c in commands if c.HasField("set_clipboard")]
    assert clip.set_clipboard.text == "hello"
    assert any(c.HasField("hide_keyboard") for c in commands)


def test_await_toast_timeout_carries_no_toast(fake, device):
    fake.devices.responder = lambda command: (
        pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT, detail="NO_TOAST"))
        if command.HasField("await_toast")
        else None
    )
    with pytest.raises(WaitTimeoutError) as timeout:
        device.await_toast("Saved", timeout=0.5)
    assert timeout.value.reason is WaitReason.NO_TOAST


def test_launch_extras_and_revoke_permission_reach_the_app_service(fake, device):
    app = device.app("com.test")
    app.launch(extras={"q": "shoes", "id": Long(42)})
    app.cold_launch(".Main", extras={"flag": True})
    app.revoke_permission("android.permission.CAMERA")
    assert app.is_permission_granted("android.permission.CAMERA")
    assert not app.is_permission_granted("android.permission.RECORD_AUDIO")
    with pytest.raises(TypeError):
        app.launch(extras={"bad": None})
    [launch] = fake.apps.launches
    assert [(e.key, e.WhichOneof("value")) for e in launch.extras] == [("q", "string_value"), ("id", "long_value")]
    [cold] = fake.apps.cold_launches
    assert cold.activity == ".Main" and cold.extras[0].bool_value is True
    [revoke] = fake.apps.revokes
    assert revoke.permission == "android.permission.CAMERA" and revoke.app.package_name == "com.test"


def test_device_conditions_name_the_device_and_keep_the_failure_reason(fake, device):
    device.set_animations(False)
    device.set_font_scale(1.3)
    device.set_density(320)
    device.set_density(None)
    with pytest.raises(ServerError) as old:
        device.set_dark_mode(True)
    assert old.value.code == "FAILED_PRECONDITION" and old.value.reason is FailureReason.UNSUPPORTED_API
    with pytest.raises(ServerError) as stuck:
        device.set_density(999)
    assert stuck.value.reason is FailureReason.DEVICE_SETTING
    device.set_network(airplane_mode=True, mobile_data=False)
    with pytest.raises(ValueError):
        device.set_network()
    device.set_system_locales(["fr-FR", "en"])
    with pytest.raises(ValueError):
        device.set_system_locales([])
    with pytest.raises(TypeError):
        device.set_system_locales("fr-FR")
    device.set_location(48.8584, 2.2945, accuracy_m=3.5)
    for bad in ({"latitude": 91, "longitude": 0}, {"latitude": 0, "longitude": float("nan")}):
        with pytest.raises(ValueError):
            device.set_location(**bad)
    with pytest.raises(ValueError):
        device.set_location(0, 0, accuracy_m=0)
    device.set_stay_awake(True)
    device.set_accessibility_display(color_inversion=True, bold_text=False)
    with pytest.raises(ValueError):
        device.set_accessibility_display()
    animations, font, density, reset, dark, _, network, locales, location, awake, a11y = fake.devices.conditions
    assert awake.enabled is True
    assert a11y.color_inversion is True and a11y.bold_text is False and not a11y.HasField("high_contrast_text")
    assert list(locales.locales) == ["fr-FR", "en"]
    assert location.latitude == 48.8584 and location.accuracy_m == pytest.approx(3.5) and not location.HasField("altitude_m")
    assert animations.enabled is False and font.scale == pytest.approx(1.3)
    assert density.dpi == 320 and not reset.HasField("dpi") and dark.enabled is True
    assert network.airplane_mode is True and network.mobile_data is False and not network.HasField("wifi")
    for request in fake.devices.conditions:
        assert request.attached_device_id == device.attached_device_id
        assert request.client_connection_id == device.owner_connection.id


def test_foreground_activity_is_none_when_nothing_is_resumed(fake, device):
    assert device.foreground_activity() is None
    fake.devices.foreground = ("com.example", "com.example.MainActivity")
    assert device.foreground_activity() == ForegroundActivity("com.example", "com.example.MainActivity")


def test_files_stream_in_chunks_both_ways_and_keep_the_failure_reason(fake, device, tmp_path):
    big = bytes(range(256)) * 4097  # over one chunk, so the upload is split
    local = tmp_path / "big.bin"
    local.write_bytes(big)
    device.push_file("/data/local/tmp/a.txt", b"hello")
    device.push_file("/data/local/tmp/big.bin", local)
    device.push_file("/data/local/tmp/str.bin", str(local))
    assert device.pull_file("/data/local/tmp/a.txt") == b"hello"
    target = tmp_path / "pulled.bin"
    assert device.pull_file("/data/local/tmp/big.bin", target) is None
    assert target.read_bytes() == big
    assert [p.name for p in tmp_path.iterdir() if p.suffix == ".part"] == []
    with pytest.raises(ServerError) as taken:
        device.push_file("/data/local/tmp/a.txt", b"x")
    assert taken.value.reason is FailureReason.DEVICE_FILE
    assert device.add_media(b"\x01\x02", "cat.png") == "/sdcard/Pictures/Tap/cat.png"
    assert device.add_media(local) == "/sdcard/Pictures/Tap/big.bin"
    with pytest.raises(TypeError):
        device.add_media(b"\x01")
    with pytest.raises(TypeError):
        device.push_file("/data/local/tmp/n", 42)
    with pytest.raises(FileNotFoundError):
        device.push_file("/data/local/tmp/n", tmp_path / "missing")
    assert fake.devices.file_chunks[1:3] == [1 << 20, len(big) - (1 << 20)]
    sizes = [h.size_bytes for h in fake.devices.file_headers if isinstance(h, pb.PushFileHeader)]
    assert sizes == [5, len(big), len(big), 1]
    for header in fake.devices.file_headers:
        assert header.attached_device_id == device.attached_device_id
        assert header.client_connection_id == device.owner_connection.id


def test_app_locales_round_trip_through_the_app_service(fake, device):
    app = device.app("com.test")
    app.set_locales(["fr-FR", "en"])
    assert app.locales() == ["fr-FR", "en"]
    app.set_locales([])
    assert app.locales() == []
    with pytest.raises(TypeError):
        app.set_locales("fr-FR")


def test_foreground_background_and_open_link_reach_the_app_service(fake, device):
    keys: list[int] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if not command.HasField("press_key"):
            return None
        keys.append(command.press_key.key_code)
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    app = device.app("com.test")
    app.background()
    app.foreground(timeout=7)
    assert app.open_link("test://orders/42") == "com.test/.Link"
    assert app.open_link("https://example.com/x", any_app=True) is None
    assert keys == [3]
    assert [request.timeout_ms for request in fake.apps.foregrounds] == [7_000]
    assert [(r.uri, r.any_app, r.app.package_name) for r in fake.apps.links] == [
        ("test://orders/42", False, "com.test"),
        ("https://example.com/x", True, "com.test"),
    ]


def test_scroll_until_gives_up_after_max_scrolls(fake, device):
    ops: list[str] = []

    def respond(command: pb.Command) -> pb.CommandResult | None:
        op = command.WhichOneof("op")
        if op in ("exists", "scroll"):
            ops.append(op)
            return pb.CommandResult(bool=False) if op == "exists" else pb.CommandResult(done=pb.Done())
        return None

    fake.devices.responder = respond
    with pytest.raises(WaitTimeoutError) as info:
        device.screen.element(res("list")).scroll_until(text("row 40"), max_scrolls=2)
    assert info.value.polls == 2
    assert ops == ["exists", "scroll", "exists", "scroll", "exists"]


def test_scroll_until_propagates_a_failing_step(fake, device):
    _fail_with(fake, pb.ERR_NOT_FOUND)
    with pytest.raises(CommandError) as info:
        device.screen.element(res("list")).scroll_until(text("row 40"))
    assert info.value.code is ErrorCode.NOT_FOUND

def test_an_app_binds_its_package_into_the_selector_and_the_screen_binds_none(fake, device):
    fake.devices.responder = lambda command: pb.CommandResult(done=pb.Done()) if command.HasField("tap") else None
    device.app("com.test").element(text("OK")).tap()
    device.screen.element(text("OK")).tap()
    bound, plain = [c.tap.selector for c in fake.devices.commands if c.HasField("tap")]
    assert bound == text("OK")._in_package("com.test")._proto
    assert plain == text("OK")._proto
