"""Wait error mapping, timeouts and detach state (PY-5, PY-6) over a fake server."""
# pyright: reportMissingImports=false

from __future__ import annotations

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import CommandError, ErrorCode, ServerError, TapError, TapServer, WaitTimeoutError
from tap_e2e import _gen as pb
from tap_e2e import raw_res, text

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    server = TapServer(fake.address, TOKEN)
    connection = server.connect("test")
    device = connection.attach_device("emulator-5554", "com.test")
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
    "await_app_visible": lambda d: d.await_app_visible(),
    "await_screen_stable": lambda d: d.await_screen_stable(),
    "scroll_until": lambda d: d.element(raw_res("list")).scroll_until(text("row 40")),
    "wait_visible": lambda d: d.wait(text("x")).visible(),
    "wait_gone": lambda d: d.wait(text("x")).gone(),
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
        assert info.value.code == ErrorCode(code)


def test_zero_timeout_is_not_replaced_by_the_default(fake, device):
    device.execute(0, device_info=pb.DeviceInfoQuery())
    device.dump_hierarchy(timeout=0)
    device.execute(None, device_info=pb.DeviceInfoQuery())
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
    device.app().is_installed()
    device.app().force_stop()
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
    connection.attach_device("other", "com.test", wait_for_device=5).detach()
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


def test_screenshot_is_checked_and_written_client_side(fake, device, tmp_path):
    target = tmp_path / "shots" / "a.png"
    assert device.screenshot(write_to=target) == fake.devices.png
    assert target.read_bytes() == fake.devices.png
    fake.devices.corrupt_png = True
    with pytest.raises(TapError, match="checksum"):
        device.screenshot()


def test_install_streams_a_header_then_1_mib_chunks(fake, device, tmp_path):
    from tap_e2e.app import INSTALL_CHUNK_BYTES

    data = bytes(i % 251 for i in range(INSTALL_CHUNK_BYTES * 2 + 123))
    apk = tmp_path / "app.apk"
    apk.write_bytes(data)
    device.app().install(apk, timeout=9)
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
        device.app().install(tmp_path / "missing.apk")
    assert fake.apps.install_parts == []


def test_scroll_until_returns_the_target_scoped_to_the_container(device):
    lst = device.element(raw_res("list"))
    assert lst.scroll_until(text("row 40")).selector == raw_res("list").descendant(
        text("row 40")
    )
    # A picked container cannot be carried into a relation: the bare target comes back.
    assert lst.first().scroll_until(text("row 40")).selector == text("row 40")
