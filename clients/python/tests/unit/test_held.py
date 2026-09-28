"""Held connections, resuming them from another client, and ref-addressed screen snapshots."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import pytest  # type: ignore[import-not-found]

from tap_e2e import (
    FailureReason,
    NodeChange,
    NodeFlag,
    ServerError,
    TapClient,
    TapError,
    res,
)
from tap_e2e import _gen as pb
from tap_e2e import _proto

from .conftest import TOKEN


@pytest.fixture
def client(fake):
    with TapClient.create(fake.address, TOKEN) as tap:
        yield tap


def test_a_held_connection_sends_its_timeout_and_opens_no_stream(fake, client):
    connection = client.connect("agent", hold=900)
    assert connection.hold == 900 and connection.name == "agent"
    request = fake.connections.live[connection.id]
    assert request.hold.idle_timeout_ms == 900_000
    assert connection.usable and connection.events == []
    connection.close()
    assert fake.connections.disconnects == [connection.id]


def test_an_observed_connection_sends_no_hold(fake, client):
    with client.connect("test") as connection:
        assert connection.hold is None
        assert not fake.connections.live[connection.id].HasField("hold")


def test_a_taken_held_name_is_a_precondition_failure(client):
    client.connect("agent", hold=60)
    with pytest.raises(ServerError) as info:
        client.connect("agent", hold=60)
    assert info.value.reason is FailureReason.DAEMON_PRECONDITION


def test_connections_list_holds_idleness_and_devices(client):
    held = client.connect("agent", hold=60)
    held.attach_device("emulator-5554", "com.example")
    with client.connect("test") as observed:
        rows = {c.id: c for c in client.connections()}
        assert rows[held.id].hold == 60 and rows[held.id].idle == 1.5
        (device,) = rows[held.id].attached_devices
        assert (device.serial, device.aut_package, device.generation) == ("emulator-5554", "com.example", 1)
        assert rows[observed.id].hold is None and rows[observed.id].attached_devices == ()


def test_another_client_resumes_a_held_connection_and_its_devices(fake, client):
    held = client.connect("agent", hold=60)
    attached = held.attach_device("emulator-5554", "com.example")
    with TapClient.create(fake.address, TOKEN) as later:
        resumed = later.resume("agent")
        assert (resumed.id, resumed.hold) == (held.id, 60)
        (device,) = resumed.attached_devices()
        assert (device.attached_device_id, device.serial, device.aut_package, device.generation) == (
            attached.attached_device_id,
            "emulator-5554",
            "com.example",
            1,
        )
        device.press_back()
        assert fake.devices.owners[-1] == ("execute", held.id)
    assert fake.connections.disconnects == [], "a resumed connection is left alone unless closed"


def test_resume_needs_a_held_connection_with_that_name(client):
    with client.connect("agent") as observed:
        with pytest.raises(TapError, match="no held connection named 'agent'"):
            client.resume("agent")
        assert observed.usable


def test_attached_devices_of_a_connection_that_ended(fake, client):
    held = client.connect("agent", hold=60)
    fake.connections.live.clear()  # expired at the server
    with pytest.raises(TapError, match="no longer exists"):
        held.attached_devices()


def _node(ref: str, **fields) -> pb.ScreenNode:
    return pb.ScreenNode(ref=ref, depth=1, window_package="com.example", **fields)


def test_screen_snapshot_maps_nodes_and_changes(fake, client):
    with client.connect("t") as connection, connection.attach_device("emulator-5554", "com.example") as device:
        fake.devices.snapshot = pb.ScreenSnapshotResponse(
            snapshot_id=4,
            rotation=1,
            nodes=[
                _node(
                    "e3",
                    class_name="android.widget.Button",
                    resource_name="com.example:id/login",
                    text="Log in",
                    bounds=pb.Bounds(left=1, top=2, right=3, bottom=4),
                    flags=[pb.FLAG_CLICKABLE, pb.FLAG_ENABLED],
                    interactive=True,
                    selector=res("login")._proto,
                    change=pb.NODE_ADDED,
                ),
                _node("e4", text="Hello", change=pb.NODE_UNCHANGED, by_index=True),
            ],
            removed=[_node("e1", text="Gone", change=pb.NODE_REMOVED)],
        )
        snapshot = device.screen_snapshot(timeout=5)
        assert fake.devices.snapshot_requests[-1].timeout_ms == 5000
        assert (snapshot.snapshot_id, snapshot.rotation) == (4, 1)
        login = snapshot.node("@e3")
        assert login is not None
        assert (login.text, login.resource_name, login.class_name) == ("Log in", "com.example:id/login", "android.widget.Button")
        assert login.flags == {NodeFlag.CLICKABLE, NodeFlag.ENABLED}
        assert login.interactive and not login.by_index and login.change is NodeChange.ADDED
        assert login.selector is not None and login.selector.render() == res("login").render()
        assert login.bounds.center == (2, 3)
        hello = snapshot.node("e4")
        assert hello is not None and hello.selector is None and hello.by_index
        assert hello.content_description is None and hello.change is NodeChange.UNCHANGED
        assert [n.ref for n in snapshot.removed] == ["e1"]
        assert snapshot.node("e1") is None


def test_resolve_ref_returns_the_selector_or_unknown_ref(fake, client):
    with client.connect("t") as connection, connection.attach_device("emulator-5554", "com.example") as device:
        fake.devices.refs["e3"] = res("login")._proto
        assert device.resolve_ref("@e3").render() == res("login").render()
        with pytest.raises(ServerError) as info:
            device.resolve_ref("e99")
        assert info.value.reason is FailureReason.UNKNOWN_REF


def test_every_node_enum_value_maps_to_a_model_constant():
    for name, number in pb.NodeFlag.items():
        if name != "FLAG_UNSPECIFIED":
            assert _proto.node_flag(number).name == name.removeprefix("FLAG_")
    for name, number in pb.NodeChange.items():
        if name != "NODE_CHANGE_UNSPECIFIED":
            assert _proto.node_change(number).name == name.removeprefix("NODE_")
    assert _proto.node_change(0) is NodeChange.NONE
    assert _proto.node_flag(0) is None


def test_event_log_is_the_calls_as_proto_json(fake, client):
    with client.connect("t") as connection, connection.attach_device("emulator-5554", "com.example") as device:
        device.element(res("login")).tap()
        device.info()  # a diagnostic query: not logged
        fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND, detail="0 matches"))
        with pytest.raises(TapError):
            device.element(res("gone")).tap()
        log = connection.event_log()
        assert log.dropped == 0 and [e.seq for e in log.events] == [1, 2]
        first, second = log.events
        assert first.ok and first.serial == "emulator-5554" and first.app is None
        # Proto3 JSON: .proto field names, enums by name, int64 as a string.
        assert first.command == {
            "timeout_ms": "10000",
            "tap": {"selector": {"node": {"resource": {"name": "login", "aut_package": True}}}},
        }
        assert not second.ok and second.error == {"code": "ERR_NOT_FOUND", "detail": "0 matches"}
        assert second.to_dict() == {
            "seq": 2,
            "at": "2026-09-21T14:13:20.001Z",
            "duration_ms": 5,
            "serial": "emulator-5554",
            "aut_package": "com.example",
            "ok": False,
            "command": second.command,
            "error": second.error,
        }
        assert [e.seq for e in connection.event_log(after_seq=1).events] == [2]
