"""The lifecycle probe reports every sample and always detaches after an attached read fails."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

import tap_e2e


@pytest.fixture
def probe():
    path = Path(__file__).parents[2] / "scripts" / "benchmark_attach.py"
    spec = importlib.util.spec_from_file_location("benchmark_attach", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeDevice:
    def __init__(self, *, read_failure=False, detach_detail=None):
        self.read_failure = read_failure
        self.detach_detail = detach_detail
        self.detached = False

    def info(self):
        if self.read_failure:
            raise RuntimeError("read failed")

    def detach(self):
        self.detached = True
        return self.detach_detail


class FakeConnection:
    def __init__(self, devices):
        self.devices = iter(devices)
        self.attaches = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.closed = True

    def attach_device(self, serial):
        self.attaches.append(serial)
        return next(self.devices)


class FakeClient:
    def __init__(self, devices, state="FREE"):
        self.connection = FakeConnection(devices)
        self.state = state
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.closed = True

    def info(self):
        return SimpleNamespace(daemon_version="test", host_build_id="test", protocol_version="5.1")

    def devices(self):
        return [SimpleNamespace(serial="phone", state=SimpleNamespace(name=self.state), properties=None)]

    def connect(self, _):
        return self.connection


def configure(monkeypatch, tmp_path, devices, *, state="FREE"):
    client = FakeClient(devices, state)
    monkeypatch.setattr(tap_e2e, "TapClient", SimpleNamespace(create=lambda: client))
    output = tmp_path / "result.json"
    monkeypatch.setattr("sys.argv", ["benchmark_attach.py", "--serial", "phone", "--cycles", "2", "--output", str(output)])
    return client, output


def test_reports_every_cycle_including_deployment_outliers(probe, monkeypatch, tmp_path):
    devices = [FakeDevice(), FakeDevice()]
    client, output = configure(monkeypatch, tmp_path, devices)
    ticks = iter([1000, 1010, 1010, 1010.1, 1010.1, 1010.6, 2000, 2001, 2001, 2001.1, 2001.1, 2001.6])
    monkeypatch.setattr(probe, "time", SimpleNamespace(perf_counter=lambda: next(ticks), time=lambda: 123))
    assert probe.main() == 0
    report = json.loads(output.read_text())
    assert report["completed_cycles"] == 2
    assert [sample["attach_s"] for sample in report["samples"]] == [10, 1]
    assert report["summary"]["attach_s"] == {"median": 5.5, "min": 1, "max": 10}
    assert report["summary"]["lifecycle_s"]["median"] == pytest.approx(6.0)
    assert client.connection.attaches == ["phone", "phone"]
    assert all(device.detached for device in devices)
    assert client.closed and client.connection.closed


def test_failed_read_detaches_stops_and_preserves_the_failed_sample(probe, monkeypatch, tmp_path):
    device = FakeDevice(read_failure=True)
    client, output = configure(monkeypatch, tmp_path, [device])
    assert probe.main() == 1
    report = json.loads(output.read_text())
    assert report["completed_cycles"] == 0
    assert len(report["samples"]) == 1
    assert "detach_s" in report["samples"][0]
    assert report["error"] == "RuntimeError: read failed"
    assert client.connection.attaches == ["phone"]
    assert device.detached and client.closed and client.connection.closed


def test_uncertain_cleanup_is_not_a_successful_benchmark(probe, monkeypatch, tmp_path):
    device = FakeDevice(detach_detail="unproven rotation restoration")
    _, output = configure(monkeypatch, tmp_path, [device])
    assert probe.main() == 1
    report = json.loads(output.read_text())
    assert report["completed_cycles"] == 0
    assert "detach could not prove cleanup" in report["error"]
    assert device.detached


@pytest.mark.parametrize("state", ["LEASED", "QUARANTINED", "OFFLINE", "UNAUTHORIZED"])
def test_refuses_devices_that_are_not_free(probe, monkeypatch, tmp_path, state):
    client, output = configure(monkeypatch, tmp_path, [], state=state)
    assert probe.main() == 1
    assert client.connection.attaches == []
    assert client.closed
    assert json.loads(output.read_text())["completed_cycles"] == 0
