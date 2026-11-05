"""The structured run format and real pytest lifecycle, without a device."""
# pyright: reportMissingImports=false

import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from typing import cast

import pytest

from tap_e2e._run_records import RunRecords
from tap_e2e.device import Device
from tap_e2e.models import Capture, DeviceLogs
from tap_e2e.pytest_plugin import TestDevices as DeviceState, _capture_run_artifacts, pytest_runtest_protocol

pytest_plugins = ["pytester"]


def read(path):
    return json.loads(path.read_text())


def test_non_python_items_do_not_require_fixture_names():
    # Custom pytest.Item collectors need not implement pytest.Function.fixturenames.
    item = cast(pytest.Item, SimpleNamespace(stash=pytest.Stash()))
    hook = pytest_runtest_protocol(item, None)
    next(hook)
    with pytest.raises(StopIteration):
        next(hook)


def test_records_are_incremental_and_repeated_attempts_do_not_overwrite(tmp_path):
    run = RunRecords(tmp_path)
    first = run.start_test("tests/test_a.py::test_a[param/../x]")
    second = run.start_test("tests/test_a.py::test_a[param/../x]")
    assert first != second
    for key in (first, second):
        run.artifact(key, "screenshot", b"png", "png", "image/png", role="../left", serial="host:5555", generation=7)
        run.finish_test(key, "failed")
    # The files are usable before finalization (and survive a process that never finalizes).
    assert read(run.directory / "run.json")["status"] == "running"
    manifest = read(run.directory / "manifest.json")
    assert len({a["path"] for a in manifest["artifacts"]}) == 2
    for artifact in manifest["artifacts"]:
        path = run.directory / artifact["path"]
        assert path.is_relative_to(run.directory)
        assert path.read_bytes() == b"png"
        assert artifact["sha256"] == hashlib.sha256(b"png").hexdigest()
        assert artifact["size_bytes"] == 3
    run.finish("failed")
    events = [json.loads(line) for line in (run.directory / "events.jsonl").read_text().splitlines()]
    assert [event["seq"] for event in events] == list(range(1, len(events) + 1))
    assert events[-1]["type"] == "run_finished"
    assert read(run.directory / "run.json")["finished_at_epoch_ms"] is not None
    assert not list(run.directory.glob("*.tmp"))


def test_parallel_tests_have_complete_jsonl_records_and_distinct_paths(tmp_path):
    run = RunRecords(tmp_path)

    def record(index):
        key = run.start_test(f"test_{index}")
        run.artifact(key, "logcat", b"log", "txt", "text/plain")
        run.finish_test(key, "passed")

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(record, range(20)))
    run.finish("passed")
    assert len(read(run.directory / "manifest.json")["artifacts"]) == 20
    events = [json.loads(line) for line in (run.directory / "events.jsonl").read_text().splitlines()]
    assert len({event["seq"] for event in events}) == len(events)
    assert len({event["run_id"] for event in events}) == 1
    other = RunRecords(tmp_path)
    assert other.run_id != run.run_id


def test_interrupted_tests_and_reporting_errors_are_not_published_as_complete(tmp_path):
    run = RunRecords(tmp_path)
    run.start_test("never_finished")
    run.mark_incomplete()
    run.finish("interrupted")
    metadata = read(run.directory / "run.json")
    assert metadata["recording_status"] == "incomplete"
    assert metadata["tests"][0]["status"] == "incomplete"
    assert metadata["status"] == "interrupted"


def test_capture_keeps_good_parts_and_records_missing_parts_without_error_text(tmp_path):
    run = RunRecords(tmp_path)
    key = run.start_test("test_failure")
    captured = Capture("s1", None, None, None, None, {"screenshot": RuntimeError("secret-value")},
                       logs=DeviceLogs("logcat", False, "", "activities", "windows"))
    device = SimpleNamespace(serial="s1", generation=4, capture=lambda: captured)
    item = SimpleNamespace(config=SimpleNamespace(stash=pytest.Stash()))
    state = DeviceState({"device": cast(Device, device)}, ["s1"], failure=AssertionError("original failure"))
    _capture_run_artifacts(cast(pytest.Item, item), state, run, key)
    manifest = read(run.directory / "manifest.json")
    assert {a["part"] for a in manifest["artifacts"]} == {"failure", "logcat", "activities", "windows"}
    assert manifest["errors"][0]["part"] == "screenshot"
    assert manifest["errors"][0]["generation"] == 4
    assert "secret-value" not in (run.directory / "events.jsonl").read_text()


@pytest.mark.parametrize("report", ["on", "off"])
def test_pytest_records_final_setup_call_teardown_and_skip_outcomes(pytester, monkeypatch, report):
    monkeypatch.setenv("PYTEST_DISABLE_PLUGIN_AUTOLOAD", "1")
    monkeypatch.delenv("TAP_REPORT", raising=False)
    monkeypatch.delenv("TAP_ARTIFACTS", raising=False)
    pytester.makeconftest('''
import pytest
@pytest.fixture
def tap_client():
    return object()
@pytest.fixture
def broken(tap_client):
    raise RuntimeError("setup secret")
@pytest.fixture
def cleanup(tap_client):
    yield
    raise RuntimeError("teardown secret")
''')
    pytester.makepyfile('''
import pytest
def test_pass(tap_client): pass
def test_fail(tap_client): assert False, "call secret"
def test_setup(broken): pass
def test_teardown(cleanup): pass
@pytest.mark.skip(reason="skip secret")
def test_skip(tap_client): pass
''')
    result = pytester.runpytest_subprocess("-p", "tap_e2e.pytest_plugin", "-q", "-o", f"tap_report={report}")
    result.assert_outcomes(passed=2, failed=1, errors=2, skipped=1)
    root = pytester.path / "tap-artifacts"
    if report == "off":
        assert not root.exists()
        return
    runs = list(root.glob("*/run.json"))
    assert len(runs) == 1
    metadata = read(runs[0])
    assert metadata["status"] == "failed"
    outcomes = {test["identity"].split("::")[-1]: test["status"] for test in metadata["tests"]}
    assert outcomes == {"test_pass": "passed", "test_fail": "failed", "test_setup": "failed", "test_teardown": "failed", "test_skip": "skipped"}
    failure = next(test for test in metadata["tests"] if test["identity"].endswith("::test_fail"))
    assert failure["error_type"] == "AssertionError"
    text = (runs[0].parent / "events.jsonl").read_text()
    assert "call secret" not in text and "setup secret" not in text and "skip secret" not in text


def test_retry_plugin_internal_protocol_loop_gets_separate_attempts(pytester, monkeypatch):
    monkeypatch.setenv("PYTEST_DISABLE_PLUGIN_AUTOLOAD", "1")
    monkeypatch.delenv("TAP_REPORT", raising=False)
    monkeypatch.delenv("TAP_ARTIFACTS", raising=False)
    pytester.makeconftest('''
from _pytest.runner import runtestprotocol
def pytest_runtest_protocol(item, nextitem):
    runtestprotocol(item, nextitem=nextitem)
    runtestprotocol(item, nextitem=nextitem)
    return True
''')
    pytester.makepyfile('''
import pytest
@pytest.fixture
def tap_client(): return object()
def test_repeated(tap_client): pass
''')
    result = pytester.runpytest_subprocess("-p", "tap_e2e.pytest_plugin", "-q")
    result.assert_outcomes(passed=2)
    run_file = next((pytester.path / "tap-artifacts").glob("*/run.json"))
    attempts = read(run_file)["tests"]
    assert [test["attempt"] for test in attempts] == [1, 2]
    assert [test["status"] for test in attempts] == ["passed", "passed"]


def test_reporting_io_error_does_not_fail_a_passing_test(pytester, monkeypatch):
    monkeypatch.setenv("PYTEST_DISABLE_PLUGIN_AUTOLOAD", "1")
    monkeypatch.delenv("TAP_REPORT", raising=False)
    monkeypatch.delenv("TAP_ARTIFACTS", raising=False)
    root = pytester.path / "not-a-directory"
    root.write_text("occupied")
    pytester.makepyfile('''
import pytest
@pytest.fixture
def tap_client(): return object()
def test_ok(tap_client): pass
''')
    result = pytester.runpytest_subprocess("-p", "tap_e2e.pytest_plugin", "-q", "-o", f"tap_artifacts={root}")
    result.assert_outcomes(passed=1)
    assert "Tap run records incomplete" in result.stderr.str()
