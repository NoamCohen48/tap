"""pytest plugin role assignment (PY-8): rotation and moving past a busy device."""
# pyright: reportMissingImports=false

from __future__ import annotations

import pathlib

from tap_e2e import TapClient
import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb
from tap_e2e.pytest_plugin import (
    _HELD,
    TapConfig,
    _assign,
    _attach_single,
    _capture_mode,
    _device_scope,
    _Held,
    _reusable,
    _rotate,
    _scope_key,
)

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


def test_device_scope_parses_the_four_scopes_only():
    assert [_device_scope(s) for s in ("function", "Class", " module ", "SESSION")] == [
        "function",
        "class",
        "module",
        "session",
    ]
    with pytest.raises(pytest.UsageError, match="tap_device_scope"):
        _device_scope("package")


def test_scope_key_groups_tests_by_class_module_or_session():
    class InClass:
        path = pathlib.Path("tests/test_a.py")
        cls = type("TestCheckout", (), {})

    class ModuleLevel:
        path = pathlib.Path("tests/test_a.py")
        cls = None

    in_class, module_level = InClass(), ModuleLevel()
    assert _scope_key(in_class, "function") is None  # type: ignore[arg-type]
    assert _scope_key(in_class, "class") == "tests/test_a.py::TestCheckout"  # type: ignore[arg-type]
    assert _scope_key(module_level, "class") is None  # type: ignore[arg-type]
    assert _scope_key(module_level, "module") == _scope_key(in_class, "module") == "tests/test_a.py"  # type: ignore[arg-type]
    assert _scope_key(module_level, "session") == "session"  # type: ignore[arg-type]


def test_held_devices_are_reused_only_while_they_answer(fake, pytestconfig):
    server = TapClient.create(fake.address, TOKEN)
    connection = server.connect("test")
    try:
        device = connection.attach_device("serial-aaa", "com.test")
        pytestconfig.stash[_HELD] = _Held("module", "tests/test_a.py", ["device"], {"device": device})
        assert _reusable(pytestconfig, "tests/test_a.py", ["device"]) == {"device": device}
        # Another scope or other roles: the held device is detached, nothing is reused.
        assert _reusable(pytestconfig, "tests/test_b.py", ["device"]) is None
        assert device.detached and pytestconfig.stash[_HELD] is None

        again = connection.attach_device("serial-aaa", "com.test")
        pytestconfig.stash[_HELD] = _Held("module", "tests/test_a.py", ["device"], {"device": again})
        fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_DRIVER_UNHEALTHY))
        assert _reusable(pytestconfig, "tests/test_a.py", ["device"]) is None
        assert again.detached
    finally:
        fake.devices.responder = None
        pytestconfig.stash[_HELD] = None
        connection.close()
        server.close()
