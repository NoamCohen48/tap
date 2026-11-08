"""Explicit tools use owned SDK calls, never recording, implicit lifecycle or retry."""
from __future__ import annotations

import asyncio
import json
import threading

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from tap_e2e import App, AppData, AppDataError, AppProcess
from tap_e2e import proto as tap

from tap_studio import tools
from tap_studio._gen import studio_pb2 as pb
from tap_studio.recording import ACTION_OPS, APP_WAIT_OPS, DEVICE_WAIT_OPS

pytestmark = pytest.mark.anyio
PACKAGE = "com.example.fixture"


@pytest.fixture
async def attached(studio):
    await studio.attach(pb.AttachRequest(serial="emulator-5554"), None)
    try:
        yield studio
    finally:
        await studio.close()


async def test_private_capabilities_list_binary_read_write_delete_are_owned_and_unrecorded(attached, daemon):
    daemon.app_data.files["files/state.bin"] = bytes(range(256))
    caps = await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_CAPABILITIES), None)
    assert json.loads(caps.json)["available"]
    listing = await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_LIST, path="files"), None)
    assert json.loads(listing.json)[0]["name"] == "state.bin"
    read = await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_READ, path="files/state.bin", timeout_ms=500), None)
    assert read.data == bytes(range(256))
    await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_WRITE, path="files/new", data=b"\x00\xff", confirmed=True, overwrite=True), None)
    header, chunk = daemon.app_data.uploads[-1]
    assert header.header.overwrite and header.header.path == "files/new" and chunk.chunk == b"\x00\xff"
    await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_DELETE, path="files/state.bin", confirmed=True), None)
    owners = [r.app.client_connection_id for r in daemon.app_data.requests]
    owners.append(header.header.app.client_connection_id)
    assert len(set(owners)) == 1
    assert daemon.connections.live[owners[0]].name == "tap-studio"
    assert attached.recording is None
    assert not daemon.apps.force_stops and not daemon.apps.launches and not daemon.apps.cold_launches


async def test_database_bundle_round_trips_persistent_sidecars_and_optional_presence(attached, daemon):
    daemon.app_data.files.update({"databases/shop.db": b"base", "databases/shop.db-wal": b"", "databases/shop.db-journal": b"rollback", "databases/shop.db-shm": b"not portable"})
    exported = await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_EXPORT_DATABASE, name="shop.db"), None)
    assert exported.bundle.format == tools.BUNDLE_FORMAT
    assert exported.bundle.HasField("wal") and exported.bundle.wal == b""
    assert exported.bundle.journal == b"rollback"
    await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_IMPORT_DATABASE, name="restored.db", bundle=exported.bundle, confirmed=True, overwrite=True), None)
    headers = [part.header for part in daemon.app_data.uploads[-1] if part.HasField("header")]
    assert [h.path for h in headers] == ["databases/restored.db", "databases/restored.db-wal", "databases/restored.db-journal"]
    assert all(h.overwrite for h in headers)
    assert not daemon.apps.force_stops and not daemon.apps.launches
    assert attached.recording is None


@pytest.mark.parametrize("fields", [
    {"operation": pb.PRIVATE_DATA_WRITE, "path": "files/a", "data": b"x"},
    {"operation": pb.PRIVATE_DATA_DELETE, "path": "files/a"},
    {"operation": pb.PRIVATE_DATA_IMPORT_DATABASE, "bundle": pb.DatabaseBundle(format=tools.BUNDLE_FORMAT, name="a.db", database=b"x")},
    {"operation": pb.PRIVATE_DATA_LIST, "overwrite": True},
    {"operation": pb.PRIVATE_DATA_READ, "path": "files/a", "timeout_ms": 0},
    {"operation": pb.PRIVATE_DATA_CAPABILITIES, "timeout_ms": 500},
    {"operation": pb.PRIVATE_DATA_WRITE, "path": "files/a", "data": b"x" * (tools.MAX_BYTES + 1), "confirmed": True},
    {"operation": pb.PRIVATE_DATA_IMPORT_DATABASE, "bundle": pb.DatabaseBundle(format="unknown", name="a.db", database=b"x"), "confirmed": True},
    {"operation": pb.PRIVATE_DATA_OPERATION_UNSPECIFIED},
])
async def test_invalid_private_inputs_never_reach_the_sdk(attached, daemon, fields):
    before = (len(daemon.app_data.requests), len(daemon.app_data.uploads))
    with pytest.raises(ConnectError) as caught:
        await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, **fields), None)
    assert caught.value.code == Code.INVALID_ARGUMENT
    assert before == (len(daemon.app_data.requests), len(daemon.app_data.uploads))


async def test_oversized_verified_download_is_an_explicit_limit_not_truncated(attached, daemon):
    daemon.app_data.files["files/large"] = b"x" * (tools.MAX_BYTES + 1)
    with pytest.raises(ConnectError) as caught:
        await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_READ, path="files/large"), None)
    assert caught.value.code == Code.RESOURCE_EXHAUSTED


async def test_sdk_private_refusal_never_stops_relaunches_or_retries(attached, daemon, monkeypatch):
    calls = []
    def refuse(*args, **kwargs):
        calls.append(1)
        raise AppDataError("APP_RUNNING", "emulator-5554", "explicitly stop the UID first")
    monkeypatch.setattr(AppData, "write", refuse)
    with pytest.raises(ConnectError) as caught:
        await attached.private_data(pb.PrivateDataRequest(package_name=PACKAGE, operation=pb.PRIVATE_DATA_WRITE, path="files/a", data=b"x", confirmed=True), None)
    assert caught.value.code == Code.FAILED_PRECONDITION
    assert calls == [1] and not daemon.apps.force_stops and not daemon.apps.launches
    assert not attached._tool_running


async def test_app_queries_preserve_sdk_semantics_and_process_budget(attached, daemon, monkeypatch):
    installed = await attached.query_app(pb.QueryAppRequest(package_name=PACKAGE, query=pb.APP_QUERY_INSTALLED), None)
    assert json.loads(installed.json) is True
    assert daemon.apps.owners[-1][0] == "is_installed"
    seen = []
    def process(app, timeout):
        seen.append((app.package_name, timeout))
        return AppProcess(42, "42")
    monkeypatch.setattr(App, "process", process)
    monkeypatch.setattr(App, "is_running", lambda app: False)
    identity = await attached.query_app(pb.QueryAppRequest(package_name=PACKAGE, query=pb.APP_QUERY_PROCESS, timeout_ms=123), None)
    assert json.loads(identity.json) == {"pid": 42, "start_token": "42"}
    assert seen == [(PACKAGE, 0.123)]
    assert json.loads((await attached.query_app(pb.QueryAppRequest(package_name=PACKAGE, query=pb.APP_QUERY_RUNNING), None)).json) is False
    assert json.loads((await attached.query_app(pb.QueryAppRequest(package_name=PACKAGE, query=pb.APP_QUERY_PERMISSION, permission="android.permission.CAMERA"), None)).json) is True
    assert json.loads((await attached.query_app(pb.QueryAppRequest(package_name=PACKAGE, query=pb.APP_QUERY_LOCALES), None)).json) == []
    assert not daemon.apps.launches and attached.recording is None


@pytest.mark.parametrize("kind", [pb.DIAGNOSTIC_SCREENSHOT, pb.DIAGNOSTIC_HIERARCHY, pb.DIAGNOSTIC_DEVICE_LOGS, pb.DIAGNOSTIC_DRIVER_LOG, pb.DIAGNOSTIC_EVENTS, pb.DIAGNOSTIC_SERVER_INFO, pb.DIAGNOSTIC_DEVICE_INFO, pb.DIAGNOSTIC_DEVICES])
async def test_diagnostic_downloads_use_sdk_formats_and_do_not_record_steps(attached, kind):
    result = await attached.export_diagnostic(pb.DiagnosticRequest(kind=kind), None)
    assert result.filename and result.content_type
    if kind == pb.DIAGNOSTIC_SCREENSHOT:
        assert result.data.startswith(b"\x89PNG")
    elif result.content_type == "application/json":
        document = json.loads(result.data)
        if kind == pb.DIAGNOSTIC_EVENTS:
            assert document["format"] == "tap-events/1" and "dropped" in document
    assert attached.recording is None


async def test_tool_replay_exclusion_and_error_cleanup(attached, monkeypatch):
    entered, release = threading.Event(), threading.Event()
    def slow(*args):
        entered.set()
        assert release.wait(5)
        return pb.QueryAppResponse(json="true")
    monkeypatch.setattr(tools, "query_app", slow)
    first = asyncio.create_task(attached.query_app(pb.QueryAppRequest(), None))
    try:
        assert await asyncio.to_thread(entered.wait, 5)
        with pytest.raises(ConnectError, match="tool is running"):
            await attached.private_data(pb.PrivateDataRequest(), None)
        with pytest.raises(ConnectError, match="tool is running"):
            await anext(attached.replay(pb.ReplayRequest(), None))
    finally:
        release.set()
        await first
    assert not attached._tool_running
    attached._replaying = True
    try:
        with pytest.raises(ConnectError, match="replay is running"):
            await attached.query_app(pb.QueryAppRequest(), None)
    finally:
        attached._replaying = False


def test_every_execute_operation_has_an_explicit_studio_classification():
    helpers = {"count", "device_info", "dump_hierarchy", "exists", "get_clipboard", "list_notifications", "set_location", "snapshot", "type_text", "wait_gone", "wait_state", "wait_visible"}
    actual = {f.name for f in tap.Command.DESCRIPTOR.oneofs_by_name["op"].fields}
    assert actual == set(ACTION_OPS) | set(APP_WAIT_OPS) | set(DEVICE_WAIT_OPS) | helpers
