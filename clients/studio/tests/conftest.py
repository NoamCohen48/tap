"""The Python client's in-process fake daemon (clients/python/tests/unit/conftest.py), reused."""
# pyright: reportMissingImports=false

from __future__ import annotations

import importlib.util
import pathlib

import pytest

from tap_e2e import TapClient
from tap_e2e import proto as tap
from tap_studio.service import Studio

_path = pathlib.Path(__file__).resolve().parents[2] / "python" / "tests" / "unit" / "conftest.py"
_spec = importlib.util.spec_from_file_location("tap_fake_daemon", _path)
assert _spec is not None and _spec.loader is not None
fake_daemon = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fake_daemon)

TOKEN = fake_daemon.TOKEN
fake = fake_daemon.fake  # the fixture


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


def device_answers(command: tap.Command) -> tap.CommandResult | None:
    """A device where everything is there, focused, enabled and checked, with the text
    "Wool socks", and every selector matches one node."""
    op = command.WhichOneof("op")
    if op == "device_info":
        return tap.CommandResult(
            device_info=tap.DeviceInfo(
                api_level=34, manufacturer="Google", model="sdk_gphone64", display_width=1080, display_height=2400
            )
        )
    if op == "snapshot":
        return tap.CommandResult(snapshot=tap.ElementSnapshot(text="Wool socks", enabled=True, checked=True, focused=True))
    if op == "count":
        return tap.CommandResult(count=1)
    if op == "exists":
        return tap.CommandResult(bool=True)
    if op == "wait_gone":
        return tap.CommandResult(done=tap.Done())
    return None


@pytest.fixture
def daemon(fake):
    fake.devices.responder = device_answers
    return fake


@pytest.fixture
def client(daemon):
    with TapClient.create(daemon.address, TOKEN) as tap_client:
        yield tap_client


@pytest.fixture
def device(client):
    with client.connect("t") as connection, connection.attach_device("emulator-5554") as attached:
        yield attached


@pytest.fixture
def studio(daemon):
    return Studio(lambda: TapClient.create(daemon.address, TOKEN), slow=0.05, backoff_start=0.01)
