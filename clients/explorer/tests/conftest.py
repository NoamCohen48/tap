"""Reuse the Python client's in-process gRPC daemon; never start a real server/device."""

import importlib.util
from pathlib import Path

import pytest
from tap_e2e import TapClient

_path = Path(__file__).resolve().parents[2] / "python" / "tests" / "unit" / "conftest.py"
_spec = importlib.util.spec_from_file_location("explorer_fake_daemon", _path)
if _spec is None or _spec.loader is None:
    raise RuntimeError("cannot load test daemon")
_fake = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_fake)
fake = _fake.fake


@pytest.fixture
def pilot_device(fake):
    with TapClient.create(fake.address, _fake.TOKEN) as client, client.connect("explorer-test") as connection:
        yield connection.attach_device("fake", wait_for_device=0)
