"""The Python client's in-process fake daemon (clients/python/tests/unit/conftest.py), reused."""
# pyright: reportMissingImports=false

from __future__ import annotations

import importlib.util
import pathlib

import pytest  # type: ignore[import-not-found]

from tap_agent import Agent
from tap_e2e import TapClient

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


@pytest.fixture
def client(fake):
    with TapClient.create(fake.address, TOKEN) as tap:
        yield tap


@pytest.fixture
def agent(client, tmp_path) -> Agent:
    return Agent("agent", client=client, out_dir=tmp_path / "out")
