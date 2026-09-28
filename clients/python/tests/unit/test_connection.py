"""TapConnection liveness, token discovery and error mapping over a fake server."""
# pyright: reportMissingImports=false

from __future__ import annotations

import json
import time

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import ServerError, TapError, TapClient
from tap_e2e import client as client_module

from .conftest import TOKEN


def _descriptor(tmp_path, port: int, token: str | None = TOKEN) -> None:
    data = {"port": port, "pid": 1, "daemonVersion": "test", "adb": "adb"}
    if token is not None:
        data["token"] = token
    (tmp_path / "daemon.json").write_text(json.dumps(data))


def _wait(predicate, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        assert time.monotonic() < deadline, "condition never held"
        time.sleep(0.01)


def test_token_comes_from_daemon_json(fake, tmp_path):
    _descriptor(tmp_path, fake.port)
    server = TapClient.create()
    try:
        assert server.address == fake.address
        server.info()
        assert fake.connections.seen_tokens[-1] == f"Bearer {TOKEN}"
    finally:
        server.close()


def test_explicit_address_takes_token_from_env(fake, monkeypatch):
    monkeypatch.setenv("TAP_SERVER", fake.address)
    monkeypatch.setenv("TAP_TOKEN", TOKEN)
    server = TapClient.create()
    try:
        server.info()
    finally:
        server.close()


def test_missing_token_is_a_clear_error(fake):
    server = TapClient.create(fake.address)
    try:
        with pytest.raises(ServerError, match="wrong or missing daemon token") as info:
            server.info()
        assert info.value.code == "UNAUTHENTICATED"
        assert isinstance(info.value.__cause__, grpc.RpcError), "the gRPC error is chained"
    finally:
        server.close()


def test_descriptor_without_live_server_is_not_used(fake, tmp_path):
    _descriptor(tmp_path, fake.port, token="wrong")
    with pytest.raises(TapError, match="run `tap start`"):
        TapClient.create()


def test_malformed_descriptor_is_ignored(tmp_path):
    (tmp_path / "daemon.json").write_text('{"port": "not a number"}')
    assert client_module._read_descriptor(tmp_path) is None
    (tmp_path / "daemon.json").write_text("not json")
    assert client_module._read_descriptor(tmp_path) is None


def test_connect_observes_before_returning(fake):
    server = TapClient.create(fake.address, TOKEN)
    try:
        connection = server.connect("test")
        assert connection.events == ["observing"]
        assert connection.usable
        connection.close()
        connection.close()  # idempotent
        assert fake.connections.disconnects == [connection.id]
    finally:
        server.close()


def test_connect_fails_and_disconnects_when_the_stream_is_empty(fake):
    fake.connections.observe_mode = "empty"
    server = TapClient.create(fake.address, TOKEN)
    try:
        with pytest.raises(TapError, match="ended unexpectedly"):
            server.connect("test")
        assert fake.connections.disconnects == ["conn-1"]
    finally:
        server.close()


def test_connect_has_a_first_event_deadline(fake):
    fake.connections.observe_mode = "silent"
    server = TapClient.create(fake.address, TOKEN)
    try:
        started = time.monotonic()
        with pytest.raises(TapError, match="no liveness event"):
            server.connect("test", first_event_timeout=0.3)
        assert time.monotonic() - started < 5
        assert fake.connections.disconnects == ["conn-1"]
    finally:
        server.close()


def test_heartbeats_are_not_recorded(fake):
    fake.connections.extra_events = 5
    server = TapClient.create(fake.address, TOKEN)
    try:
        with server.connect("test") as connection:
            time.sleep(0.2)
            assert connection.events == ["observing"]
            assert connection.usable
    finally:
        server.close()


def test_closing_makes_the_connection_unusable_with_the_reason(fake):
    server = TapClient.create(fake.address, TOKEN)
    try:
        connection = server.connect("test")
        fake.connections.close_with(connection.id, "reaped: no heartbeat")
        _wait(lambda: connection.broken is not None)
        assert "reaped: no heartbeat" in str(connection.broken)
        assert connection.events == ["observing", "closing: reaped: no heartbeat"]
        with pytest.raises(TapError, match="reaped"):
            connection.attach_device("emulator-5554", "com.test")
        connection.close()
    finally:
        server.close()


def test_a_closing_first_event_fails_connect(fake):
    fake.connections.observe_mode = "closing"
    server = TapClient.create(fake.address, TOKEN)
    try:
        with pytest.raises(TapError, match="daemon shutting down"):
            server.connect("test")
        assert fake.connections.disconnects == ["conn-1"]
    finally:
        server.close()


def test_a_dropped_stream_makes_the_connection_unusable(fake):
    server = TapClient.create(fake.address, TOKEN)
    try:
        connection = server.connect("test")
        device = connection.attach_device("emulator-5554", "com.test")
        fake.connections.drop(connection.id)
        _wait(lambda: connection.broken is not None)
        assert not connection.usable
        with pytest.raises(TapError, match="liveness stream"):
            connection.attach_device("emulator-5556", "com.test")
        calls = len(fake.devices.commands)
        with pytest.raises(TapError, match="liveness stream"):
            device.info()
        assert len(fake.devices.commands) == calls, "rejected without an RPC"
        connection.close()
    finally:
        server.close()


def test_exit_never_suppresses_close_errors(fake):
    server = TapClient.create(fake.address, TOKEN)
    try:
        connection = server.connect("test")
        fake.connections.disconnect_error = grpc.StatusCode.INTERNAL
        with pytest.raises(ServerError, match="disconnect boom"):
            with connection:
                pass
    finally:
        server.close()


def test_exit_does_not_hide_the_body_exception(fake):
    server = TapClient.create(fake.address, TOKEN)
    try:
        with pytest.raises(KeyError):
            with server.connect("test"):
                raise KeyError("body")
    finally:
        server.close()
