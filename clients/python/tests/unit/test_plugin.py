"""pytest plugin role assignment (PY-8): rotation and moving past a busy device."""
# pyright: reportMissingImports=false

from __future__ import annotations

import pathlib

from tap_e2e import TapClient
import pytest  # type: ignore[import-not-found]

from tap_e2e.pytest_plugin import TapConfig, _assign, _attach_single, _capture_mode, _rotate

from .conftest import TOKEN


def test_rotation_wraps_and_assigns_in_declaration_order():
    serials = ["s1", "s2", "s3"]
    assert _rotate(serials, 1) == ["s2", "s3", "s1"]
    assert _rotate(serials, 3) == serials
    assert _rotate([], 5) == []
    assert _assign(["a", "b"], serials, 2) == {"a": "s3", "b": "s1"}


def test_capture_mode_parses_on_failure_and_off_only():
    assert _capture_mode("onFailure") is True
    assert _capture_mode(" OFF ") is False
    with pytest.raises(pytest.UsageError, match="tap_capture"):
        _capture_mode("always")


def test_single_role_moves_past_a_busy_device_and_waits_only_when_all_are(fake):
    config = TapConfig(
        aut="com.test",
        serials=[],
        artifacts=pathlib.Path("tap-artifacts"),
        server=fake.address,
        acquire_timeout=7,
        manage_daemon=False,
    )
    server = TapClient.create(fake.address, TOKEN)
    connection = server.connect("test")
    try:
        fake.devices.busy.add("serial-aaa")
        device = _attach_single(connection, config, ["serial-aaa", "serial-bbb"])
        assert device.serial == "serial-bbb"
        assert fake.devices.attaches == [("serial-aaa", 0), ("serial-bbb", 0)]
        device.detach()

        fake.devices.attaches.clear()
        fake.devices.busy.add("serial-bbb")
        device = _attach_single(connection, config, ["serial-bbb", "serial-aaa"])
        # Every device busy: the first candidate again, now waiting.
        assert device.serial == "serial-bbb"
        assert fake.devices.attaches == [
            ("serial-bbb", 0),
            ("serial-aaa", 0),
            ("serial-bbb", 7000),
        ]
        device.detach()
    finally:
        connection.close()
        server.close()
