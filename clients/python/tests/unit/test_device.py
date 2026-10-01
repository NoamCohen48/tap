"""Wait error mapping, timeouts and detach state (PY-5, PY-6) over a fake server."""
# pyright: reportMissingImports=false

from __future__ import annotations

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import CommandError, ErrorCode, ServerError, TapError, TapClient, WaitTimeoutError
from tap_e2e import _gen as pb
from tap_e2e import res, text

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
