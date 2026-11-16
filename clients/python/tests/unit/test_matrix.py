"""Offline pytest matrix collection, exact assignment and report integration."""
from __future__ import annotations

import pathlib
import xml.etree.ElementTree as ET

import pytest  # type: ignore[import-not-found]

from tap_e2e import _gen as pb

from .conftest import TOKEN

pytest_plugins = ["pytester"]


@pytest.fixture
def matrix(pytester, monkeypatch, fake):
    monkeypatch.setenv("PYTEST_DISABLE_PLUGIN_AUTOLOAD", "1")
    monkeypatch.setenv("TAP_SERIALS", "serial-aaa,serial-bbb")
    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", TOKEN)
    monkeypatch.setenv("TAP_EXECUTION", "matrix")
    monkeypatch.setenv("TAP_CAPTURE", "off")
    monkeypatch.setenv("TAP_APP_DEATHS", "off")
    monkeypatch.setenv("TAP_DEVICE_SCOPE", "function")
    monkeypatch.setenv("TAP_REPORT", "on")
    monkeypatch.setenv("TAP_ARTIFACTS", str(pytester.path / "tap-artifacts"))
    # Child pytest must import this worktree rather than any editable install elsewhere.
    monkeypatch.setenv("PYTHONPATH", str(pathlib.Path(__file__).resolve().parents[2]))
    return pytester


def run(matrix, *args):
    return matrix.runpytest_subprocess("-p", "tap_e2e.pytest_plugin", "-v", *args)


def test_two_tests_two_devices_have_four_ids_assignments_and_records(matrix, fake):
    matrix.makepyfile('''
        def test_login(tap_device, request):
            assert tap_device.serial == request.node.callspec.params["_tap_matrix_serial"]
        def test_logout(tap_device, request):
            assert tap_device.serial == request.node.callspec.params["_tap_matrix_serial"]
    ''')
    result = run(matrix, "--junitxml=results.xml")
    result.assert_outcomes(passed=4)
    for name in ("login", "logout"):
        for serial in ("serial-aaa", "serial-bbb"):
            assert f"test_{name}[device={serial}] PASSED" in result.stdout.str()
    assert fake.devices.attaches == [(s, 300000) for s in ["serial-aaa", "serial-bbb"] * 2]
    cases = ET.parse(matrix.path / "results.xml").findall(".//testcase")  # noqa: S314 - trusted pytest output
    assert len(cases) == 4
    for case in cases:
        serial = case.attrib["name"].split("device=")[1].rstrip("]")
        identity = case.find("properties/property[@name='tap.device.device']")
        assert identity is not None and identity.attrib["value"] == serial
    records = list((matrix.path / "tap-artifacts").glob("*/run.json"))
    assert len(records) == 1
    assert "device=serial-aaa" in records[0].read_text()
    assert "device=serial-bbb" in records[0].read_text()


def test_distribution_cli_overrides_environment_and_does_not_multiply(matrix):
    matrix.makepyfile('''
        def test_one(tap_device): pass
        def test_two(tap_device): pass
    ''')
    run(matrix, "--tap-execution=distribute").assert_outcomes(passed=2)


def test_collection_needs_no_server_and_composes_with_wrapped_fixtures(matrix, monkeypatch, fake):
    monkeypatch.setenv("TAP_SERVER", "127.0.0.1:1")
    matrix.makepyfile('''
        import pytest
        @pytest.fixture
        def app(tap_device): return tap_device
        @pytest.mark.parametrize("value", [1, 2])
        def test_data(app, value): pass
        def test_plain(): pass
    ''')
    result = run(matrix, "--collect-only")
    assert "5 tests collected" in result.stdout.str()
    assert "device=serial-aaa" in result.stdout.str()
    assert "device=serial-bbb" in result.stdout.str()
    assert fake.connections.connects == 0


@pytest.mark.parametrize("scope", ["class", "module", "session"])
def test_scoped_reuse_never_uses_another_matrix_serial(matrix, monkeypatch, scope):
    monkeypatch.setenv("TAP_DEVICE_SCOPE", scope)
    matrix.makepyfile('''
        class TestDevices:
            def test_one(self, tap_device, request):
                assert tap_device.serial == request.node.callspec.params["_tap_matrix_serial"]
            def test_two(self, tap_device, request):
                assert tap_device.serial == request.node.callspec.params["_tap_matrix_serial"]
    ''')
    run(matrix).assert_outcomes(passed=4)


def test_busy_target_is_waited_for_without_trying_another_device(matrix, fake):
    fake.devices.busy.add("serial-aaa")
    matrix.makepyfile('''
        def test_one(tap_device): pass
    ''')
    run(matrix).assert_outcomes(passed=2)
    assert fake.devices.attaches == [("serial-aaa", 300000), ("serial-bbb", 300000)]


def test_incompatible_pairs_skip_without_reassignment_and_custom_role_ids(matrix, fake):
    fake.devices.listed = [
        pb.DeviceEntry(serial="serial-aaa", properties=pb.DeviceProperties(api_level=34)),
        pb.DeviceEntry(serial="serial-bbb", properties=pb.DeviceProperties(api_level=29)),
    ]
    matrix.makepyfile('''
        import pytest
        @pytest.mark.tap_devices(phone={"min_api": 30})
        def test_one(tap_devices):
            assert tap_devices["phone"].serial == "serial-aaa"
    ''')
    result = run(matrix, "-rs")
    result.assert_outcomes(passed=1, skipped=1)
    assert "phone=serial-bbb" in result.stdout.str()
    assert "no fit among serial-bbb" in result.stdout.str()
    assert fake.devices.attaches == [("serial-aaa", 300000)]


@pytest.mark.parametrize("setup_error", [False, True])
def test_failures_include_identity_even_before_attach(matrix, setup_error):
    if setup_error:
        matrix.makeconftest('''
            import pytest
            @pytest.fixture(scope="session")
            def tap_connection(): raise RuntimeError("connection unavailable")
        ''')
    matrix.makepyfile('''
        def test_one(tap_device): assert False, "test failure"
    ''')
    result = run(matrix, "--junitxml=results.xml")
    result.assert_outcomes(**({"errors": 2} if setup_error else {"failed": 2}))
    assert "Tap devices" in result.stdout.str()
    for serial in ("serial-aaa", "serial-bbb"):
        assert f"device: {serial}" in result.stdout.str()
    cases = ET.parse(matrix.path / "results.xml").findall(".//testcase")  # noqa: S314 - trusted pytest output
    assert len(cases) == 2
    for case in cases:
        assert case.find("properties/property[@name='tap.device.device']") is not None


@pytest.mark.parametrize("serials,message", [("", "requires explicit"), ("a,a", "duplicate")])
def test_invalid_serial_lists_fail_before_collection(matrix, monkeypatch, serials, message):
    monkeypatch.setenv("TAP_SERIALS", serials)
    matrix.makepyfile("def test_one(tap_device): pass")
    result = run(matrix, "--collect-only")
    assert result.ret != 0
    assert message in result.stderr.str()


def test_multi_role_matrix_is_a_usage_error(matrix):
    matrix.makepyfile('''
        import pytest
        @pytest.mark.tap_devices("sender", "receiver")
        def test_one(tap_devices): pass
    ''')
    result = run(matrix, "--collect-only")
    assert result.ret != 0
    assert "matrix execution supports one role" in result.stdout.str()


def test_ini_execution_and_environment_precedence(matrix, monkeypatch):
    monkeypatch.delenv("TAP_EXECUTION")
    matrix.makeini("[pytest]\ntap_execution = matrix\n")
    matrix.makepyfile("def test_one(tap_device): pass")
    run(matrix).assert_outcomes(passed=2)
    monkeypatch.setenv("TAP_EXECUTION", "distribute")
    run(matrix).assert_outcomes(passed=1)


def test_quarantined_target_errors_without_fallback(matrix, fake):
    fake.devices.quarantined["serial-aaa"] = "unsafe mutation"
    matrix.makepyfile("def test_one(tap_device): pass")
    result = run(matrix)
    result.assert_outcomes(passed=1, errors=1)
    assert "device: serial-aaa" in result.stdout.str()
    assert fake.devices.attaches == [("serial-aaa", 300000), ("serial-bbb", 300000)]


@pytest.mark.parametrize("report", ["on", "off"])
def test_matrix_failures_have_separate_artifact_directories(matrix, monkeypatch, report):
    monkeypatch.setenv("TAP_CAPTURE", "onFailure")
    monkeypatch.setenv("TAP_REPORT", report)
    matrix.makepyfile('''
        def test_one(tap_device): assert False, tap_device.serial
    ''')
    run(matrix).assert_outcomes(failed=2)
    failures = list((matrix.path / "tap-artifacts").rglob("*failure*.txt"))
    assert len(failures) == 2
    assert failures[0].parent != failures[1].parent
    assert any("serial-aaa" in path.read_text() for path in failures)
    assert any("serial-bbb" in path.read_text() for path in failures)
