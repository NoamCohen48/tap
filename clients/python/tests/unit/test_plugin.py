"""pytest plugin role assignment (PY-8): rotation and moving past a busy device."""
# pyright: reportMissingImports=false

from __future__ import annotations

import pathlib

from tap_e2e import TapClient
import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb
from tap_e2e.models import DeviceEntry, DeviceProperties, DeviceState
from tap_e2e.pytest_plugin import (
    _HELD,
    DeviceCriteria,
    TapConfig,
    _assign,
    _attach_matching,
    _choose,
    _criteria,
    _declared,
    _attach_single,
    _capture_mode,
    _device_scope,
    _Held,
    _mark_logs,
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
        device = connection.attach_device("serial-aaa")
        pytestconfig.stash[_HELD] = _Held("module", "tests/test_a.py", ["device"], {"device": device})
        assert _reusable(pytestconfig, "tests/test_a.py", ["device"]) == {"device": device}
        # Another scope or other roles: the held device is detached, nothing is reused.
        assert _reusable(pytestconfig, "tests/test_b.py", ["device"]) is None
        assert device.detached and pytestconfig.stash[_HELD] is None

        again = connection.attach_device("serial-aaa")
        pytestconfig.stash[_HELD] = _Held("module", "tests/test_a.py", ["device"], {"device": again})
        fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_DRIVER_UNHEALTHY))
        assert _reusable(pytestconfig, "tests/test_a.py", ["device"]) is None
        assert again.detached
    finally:
        fake.devices.responder = None
        pytestconfig.stash[_HELD] = None
        connection.close()
        server.close()


def _entry(serial: str, api: int | None, emulator: bool = False, model: str = "Pixel 8") -> DeviceEntry:
    props = DeviceProperties(api, "Google", model, emulator) if api is not None else None
    return DeviceEntry(serial, DeviceState.FREE, None, None, props)


def test_criteria_match_every_field_that_is_set():
    emu = _entry("emu", 34, emulator=True, model="sdk_gphone64")
    phone = _entry("phone", 29, model="SM-J810G")
    unknown = _entry("unknown", None)
    assert DeviceCriteria(min_api=30).matches(emu) and not DeviceCriteria(min_api=30).matches(phone)
    assert DeviceCriteria(max_api=29, emulator=False).matches(phone)
    assert DeviceCriteria(model_contains="j810").matches(phone)
    assert DeviceCriteria(manufacturer="GOOGLE").matches(emu)
    # Unknown properties match nothing but the empty criteria.
    assert DeviceCriteria().matches(unknown) and DeviceCriteria().matches(None)
    assert not DeviceCriteria(emulator=False).matches(unknown)
    assert str(DeviceCriteria(min_api=30, emulator=False)) == "min_api=30, emulator=False"


def test_criteria_reject_unknown_names_and_wrong_types():
    assert _criteria("left", {"min_api": 30}) == DeviceCriteria(min_api=30)
    with pytest.raises(pytest.UsageError, match="unknown criterion 'api'"):
        _criteria("left", {"api": 30})
    with pytest.raises(pytest.UsageError, match="min_api must be int"):
        _criteria("left", {"min_api": True})
    with pytest.raises(pytest.UsageError, match="emulator must be bool"):
        _criteria("left", {"emulator": 1})
    with pytest.raises(pytest.UsageError, match="takes a dict"):
        _criteria("left", 30)


def test_choose_places_the_narrowest_role_first_and_keeps_rotation_order():
    entries = {e.serial: e for e in (_entry("a", 34, emulator=True), _entry("b", 34), _entry("c", 29))}
    # "any" would take "a" first; "phone34" can only have "b".
    assert _choose(["any", "phone34"], {"phone34": DeviceCriteria(min_api=34, emulator=False)}, ["a", "b", "c"], entries) == {
        "any": "a",
        "phone34": "b",
    }
    assert _choose(["x", "y"], {"x": DeviceCriteria(min_api=34), "y": DeviceCriteria(min_api=34)}, ["c", "b", "a"], entries) == {
        "x": "b",
        "y": "a",
    }
    assert _choose(["x", "y"], {"x": DeviceCriteria(max_api=29), "y": DeviceCriteria(max_api=29)}, ["a", "b", "c"], entries) is None


class _Request:
    class node:  # noqa: N801 - mimics pytest's request.node
        name = "test_x"


def test_attach_matching_attaches_a_fitting_device_or_skips_naming_what_is_there(fake):
    fake.devices.listed = [
        pb.DeviceEntry(serial="emu", state=pb.DEVICE_FREE, properties=pb.DeviceProperties(api_level=34, emulator=True)),
        pb.DeviceEntry(serial="phone", state=pb.DEVICE_FREE, properties=pb.DeviceProperties(api_level=29, manufacturer="samsung", model="SM-J810G")),
    ]
    config = TapConfig(serials=[], artifacts=pathlib.Path("tap-artifacts"), server=fake.address, acquire_timeout=0, manage_daemon=False)
    server = TapClient.create(fake.address, TOKEN)
    connection = server.connect("test")
    try:
        devices = _attach_matching(_Request(), connection, config, ["device"], {"device": DeviceCriteria(emulator=False)}, ["emu", "phone"])
        assert {role: d.serial for role, d in devices.items()} == {"device": "phone"}
        devices["device"].detach()
        with pytest.raises(pytest.skip.Exception, match=r"device: min_api=35; no fit among emu \(API 34, emulator\), phone \(API 29, samsung SM-J810G\)"):
            _attach_matching(_Request(), connection, config, ["device"], {"device": DeviceCriteria(min_api=35)}, ["emu", "phone"])
    finally:
        connection.close()
        server.close()


class _Item:
    def __init__(self, mark):
        self.mark = mark

    def get_closest_marker(self, name):
        return self.mark.mark if self.mark is not None else None


def test_declared_roles_take_positional_names_then_keyword_criteria():
    assert _declared(_Item(None)) == (["device"], {})
    assert _declared(_Item(pytest.mark.tap_devices("left", "right"))) == (["left", "right"], {})
    roles, criteria = _declared(_Item(pytest.mark.tap_devices("left", right={"emulator": False})))
    assert roles == ["left", "right"] and criteria == {"right": DeviceCriteria(emulator=False)}
    assert _declared(_Item(pytest.mark.tap_devices(device={"min_api": 30}))) == (
        ["device"],
        {"device": DeviceCriteria(min_api=30)},
    )


def test_test_markers_are_written_to_every_device_and_never_fail_the_test(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("test") as connection:
            devices = {"left": connection.attach_device("a"), "right": connection.attach_device("b")}
            _mark_logs(devices, "test tests/test_pay.py::test_pay[a\nb]" + "x" * 300)
            fake.device_log.capture_off = True
            _mark_logs(devices, "test passed")

    names = [request.name for request in fake.device_log.requests]
    assert len(names) == 4 and names[0] == names[1]
    assert names[0].startswith("test tests/test_pay.py::test_pay[a b]") and len(names[0]) == 200
