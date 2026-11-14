"""The actual on-disk catalog/leases, without a device or daemon."""
from __future__ import annotations

import json
import os
import re
import select
import sqlite3
import stat
import subprocess
import sys
import threading

import pytest

from tap_agent import records
from tap_agent.records import RecordError, RecordStore


@pytest.fixture
def store(tmp_path):
    return RecordStore(tmp_path / "records")


def test_no_recording_does_not_create_storage(store):
    assert store.active("agent") is None
    assert store.list() == []
    assert not store.directory.exists()


def _empty_catalog(store):
    """A catalog file another process has created but not yet given its schema."""
    store.directory.mkdir(mode=0o700)
    os.close(os.open(store.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))


def test_a_catalog_without_a_schema_reads_as_empty_and_can_still_start(store):
    _empty_catalog(store)
    assert store.active("agent") is None
    assert store.list() == []
    with pytest.raises(RecordError, match="no saved recordings"):
        store.select(None, "agent")
    store.ensure("agent", "connection-1")
    assert [row["session"] for row in store.list()] == ["agent"]


def test_a_failed_schema_creation_leaves_nothing_behind(store, monkeypatch):
    monkeypatch.setattr(records, "_SCHEMA", records._SCHEMA + ("CREATE TABLE broken (",))
    with pytest.raises(RecordError, match="storage unavailable"):
        store.ensure("agent", "connection-1")
    monkeypatch.undo()
    assert store.list() == []  # version 0 and no tables, not a half-made catalog
    store.ensure("agent", "connection-1")
    assert [row["session"] for row in store.list()] == ["agent"]


@pytest.mark.parametrize("failure,expire", [("database is locked", False), ("database is locked", True), ("disk I/O error", False)])
def test_journal_mode_retries_only_busy_setup_with_a_deadline(store, monkeypatch, failure, expire):
    connect = sqlite3.connect
    attempts = []

    class ContendedConnection(sqlite3.Connection):
        def execute(self, sql, *args, **kwargs):
            if sql == "PRAGMA journal_mode=WAL":
                attempts.append(sql)
                if len(attempts) == 1 or expire:
                    raise sqlite3.OperationalError(failure)
            return super().execute(sql, *args, **kwargs)

    monkeypatch.setattr(records.sqlite3, "connect", lambda *a, **kw: connect(*a, factory=ContendedConnection, **kw))
    monkeypatch.setattr(records.time, "sleep", lambda _: None)
    clock = iter([0, 6])
    monkeypatch.setattr(records.time, "monotonic", lambda: next(clock) if expire else 0)
    if failure != "database is locked" or expire:
        with pytest.raises(RecordError, match=failure):
            store.ensure("agent", "c")
        assert len(attempts) == 1
    else:
        store.ensure("agent", "c")
        assert len(attempts) == 2  # one retry, on creation


def test_two_first_starts_share_one_schema(store):
    _empty_catalog(store)
    first, second = RecordStore(store.directory), RecordStore(store.directory)
    holder = sqlite3.connect(store.path, isolation_level=None)
    holder.execute("BEGIN IMMEDIATE")  # both starts queue behind this writer
    results: list[object] = []
    threads = [threading.Thread(target=lambda s=s, n=n: results.append(s.ensure(n, "c"))) for s, n in ((first, "a"), (second, "b"))]
    for thread in threads:
        thread.start()
    holder.execute("COMMIT")
    holder.close()
    for thread in threads:
        thread.join(10)
    assert len(results) == 2
    assert sorted(row["session"] for row in store.list()) == ["a", "b"]


def test_a_history_survives_new_instances_and_exports_offline(store):
    metadata = store.ensure("agent", "connection-1")
    assert store.ensure("agent", "connection-1")["id"] == metadata["id"]
    assert re.fullmatch(r"agent-\d{8}-\d{6}", metadata["name"])
    with store.lease(metadata["id"]):
        sequence = store.begin(metadata, "tap", {"settle": True}, step_id="0123456789abcdef")
        store.device(metadata["id"], sequence, "emulator-5554")
        store.target(metadata["id"], sequence, "target", {"node": {"resource": {"name": "save"}}})
        store.finish(metadata["id"], sequence, "succeeded", 12)
    assert not store.export(metadata["name"], "other-session")["complete"]
    assert store.steps("agent") == {"0123456789abcdef": {"seq": 1, "action": "tap"}}
    resumed = RecordStore(store.directory)
    resumed.stop(metadata["name"], "other-session")
    assert resumed.steps("agent") == {}
    document = resumed.export(None, "agent")
    assert document["format"] == "tap-agent-recording/2" and document["complete"]
    assert document["connection_id"] == "connection-1" and document["markers"] == []
    (step,) = document["steps"]
    assert step["action"] == "tap" and step["serial"] == "emulator-5554" and step["id"] == "0123456789abcdef"
    assert step["targets"]["target"]["node"]["resource"]["name"] == "save"
    assert step["duration_ms"] == 12 and step["outcome"]["status"] == "succeeded"
    assert json.loads(json.dumps(document)) == document
    assert stat.S_IMODE(store.directory.stat().st_mode) == 0o700
    assert all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in store.directory.iterdir())


def test_a_new_connection_or_a_full_history_starts_the_next_one(store, monkeypatch):
    first = store.ensure("agent", "id-1")
    second = store.ensure("agent", "id-2")  # the session was released and attached again
    assert second["id"] != first["id"] and second["name"] != first["name"]
    assert store.select(first["name"], "agent")["stop_reason"] == "connection ended"
    assert store.active("agent")["id"] == second["id"]
    monkeypatch.setattr(records, "MAX_STEPS", 1)
    with store.lease(second["id"]):
        seq = store.begin(second, "tap", {})
        store.finish(second["id"], seq, "succeeded", 1)
    third = store.ensure("agent", "id-2")
    assert third["id"] != second["id"] and store.select(second["name"], "agent")["stop_reason"] == "full"
    assert [row["state"] for row in store.list()] == ["stopped", "stopped", "recording"]


def test_starting_a_history_removes_stopped_ones_beyond_the_newest(store, monkeypatch):
    monkeypatch.setattr(records, "KEEP_STOPPED", 2)
    names = [store.ensure("agent", f"id-{index}")["name"] for index in range(5)]
    assert [row["name"] for row in store.list()] == names[2:]
    assert len(list(store.directory.glob("*.lease.sqlite3"))) == 3


def test_markers_sit_between_steps_and_cut_an_export(store):
    metadata = store.ensure("agent", "id")

    def run(action):
        with store.lease(metadata["id"]):
            store.finish(metadata["id"], store.begin(metadata, action, {}), "succeeded", 1)

    run("launch")
    store.mark(metadata, "checkout", "emulator-5554", 41)
    run("tap")
    run("fill")
    store.mark(metadata, "paid", "emulator-5554", 90)
    run("back")
    store.mark(metadata, "checkout", "emulator-5554", 120)

    def actions(**bounds):
        return [step["action"] for step in store.export(None, "agent", **bounds)["steps"]]

    assert actions() == ["launch", "tap", "fill", "back"]
    assert [m["name"] for m in store.export(None, "agent")["markers"]] == ["checkout", "paid", "checkout"]
    assert actions(until="paid") == ["launch", "tap", "fill"]
    assert actions(since="paid") == ["back"]
    assert actions(since="paid", until="checkout") == ["back"]
    assert actions(since="checkout") == []  # the newest marker with the name
    ranged = store.export(None, "agent", since="paid", until="checkout")
    assert ranged["range"] == {"since": {"name": "paid", "cursor": 90}, "until": {"name": "checkout", "cursor": 120}}
    assert [(m["name"], m["after_seq"], m["cursor"]) for m in ranged["markers"]] == [("paid", 3, 90), ("checkout", 4, 120)]
    with pytest.raises(RecordError, match="no marker named 'never'"):
        store.export(None, "agent", since="never")
    with pytest.raises(RecordError, match="before the --since"):
        store.export(None, "agent", since="checkout", until="paid")


def test_a_catalog_of_the_previous_version_keeps_its_recordings(store):
    store.directory.mkdir(mode=0o700)
    os.close(os.open(store.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))
    old = sqlite3.connect(store.path)
    for statement in records._SCHEMA:
        old.execute(statement)
    old.execute("INSERT INTO records(id,name,session,connection_id,started_at,stopped_at,state) VALUES('" + "a" * 32 + "','checkout','agent','c','2026-01-01T00:00:00.000Z','2026-01-01T00:01:00.000Z','stopped')")
    old.execute("INSERT INTO steps(record_id,seq,action,kind,parameters,started_at,status) VALUES('" + "a" * 32 + "',1,'tap','action','{}','2026-01-01T00:00:01.000Z','succeeded')")
    old.execute("PRAGMA user_version=1")
    old.commit()
    old.close()

    document = store.export("checkout", "agent")
    assert document["steps"][0]["action"] == "tap" and document["steps"][0]["id"] is None
    assert document["markers"] == []


def test_unfinished_intent_is_kept_as_unknown_and_does_not_block_the_next_step(store):
    metadata = store.ensure("agent", "id")
    with store.lease(metadata["id"]):
        store.begin(metadata, "tap", {})  # process could die after input, before finish
    assert store.export(None, "agent")["steps"][0]["outcome"]["status"] == "running"
    with store.lease(metadata["id"]):
        sequence = store.begin(metadata, "tap", {})
        store.finish(metadata["id"], sequence, "succeeded", 1)
    store.stop(None, "agent")
    document = store.export(None, "agent")
    assert not document["complete"]
    assert [step["outcome"]["status"] for step in document["steps"]] == ["unknown", "succeeded"]
    assert document["steps"][0]["finished_at"] is None


def test_live_lease_prevents_stop_and_same_record_execution_but_not_other_sessions(store):
    first = store.ensure("a", "id-a")
    second = store.ensure("b", "id-b")
    with store.lease(first["id"]):
        with pytest.raises(RecordError, match="busy"):
            store.stop(None, "a")
        with pytest.raises(RecordError, match="busy"), RecordStore(store.directory).lease(first["id"]):
            pytest.fail("lease must be exclusive")
        with store.lease(second["id"]):
            seq = store.begin(second, "wait", {})
            store.finish(second["id"], seq, "succeeded", 1)
    assert store.stop(None, "a")["state"] == "stopped"


def test_lease_is_exclusive_across_processes(store):
    metadata = store.ensure("agent", "id")
    script = """
import sys
import threading
from tap_agent.records import RecordStore, RecordError
try:
    with RecordStore(sys.argv[1]).lease(sys.argv[2]):
        print('acquired')
except RecordError:
    print('busy')
"""
    def attempt():
        return subprocess.run([sys.executable, "-c", script, str(store.directory), metadata["id"]], capture_output=True, text=True, check=True, timeout=15).stdout.strip()
    with store.lease(metadata["id"]):
        assert attempt() == "busy"
    assert attempt() == "acquired"


@pytest.mark.skipif(os.name == "nt", reason="private recording storage requires POSIX permissions")
def test_killed_process_releases_lease_but_keeps_committed_intent(store):
    metadata = store.ensure("agent", "id")
    script = """
import sys
import threading
from tap_agent.records import RecordStore
store = RecordStore(sys.argv[1])
recording = store.active('agent')
with store.lease(recording['id']):
    store.begin(recording, 'tap', {})
    print('ready', flush=True)
    sys.stdin.read(1)
"""
    child = subprocess.Popen([sys.executable, "-c", script, str(store.directory)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        assert child.stdout is not None
        assert select.select([child.stdout], [], [], 10)[0], "child did not commit intent"
        assert os.read(child.stdout.fileno(), 128) == b"ready\n"
        child.kill()
        child.communicate(timeout=10)
    finally:
        if child.poll() is None:
            child.kill()
        child.communicate(timeout=10)
    store.stop(None, "agent")
    document = store.export(None, "agent")
    assert not document["complete"] and document["steps"][0]["outcome"]["status"] == "unknown"


def test_clean_takes_a_name_or_all_and_never_a_live_history(store):
    one = store.ensure("a", "a-id")["name"]
    with pytest.raises(RecordError, match="live session"):
        store.clean(one, "a")
    with pytest.raises(RecordError, match="name or explicit"):
        store.clean(None, "a")
    with pytest.raises(RecordError, match="not both"):
        store.clean(one, "a", all_records=True)
    store.stop(None, "a")
    two = store.ensure("b", "b-id")["name"]
    assert store.clean(None, "a", all_records=True) == 1  # the stopped one; the live one stays
    assert [row["name"] for row in store.list()] == [two]
    with pytest.raises(RecordError, match="not found"):
        store.export(one, "a")
    store.stop(None, "b")
    assert store.clean(two, "b") == 1
    assert store.list() == []
    assert list(store.directory.glob("*.lease.sqlite3")) == []


def test_a_session_name_is_not_a_path(store):
    name = store.ensure("../秘密 a/b", "id")["name"]
    assert re.fullmatch(r"a-b-\d{8}-\d{6}", name)
    assert sorted(path.name for path in store.directory.iterdir() if not path.name.endswith(("-wal", "-shm"))) == sorted(["catalog.sqlite3", f"{store.active('../秘密 a/b')['id']}.lease.sqlite3"])


def test_bounds_fail_before_inserting_or_replacing_data(store, monkeypatch):
    metadata = store.ensure("a", "id")
    monkeypatch.setattr(records, "MAX_STEPS", 1)
    with store.lease(metadata["id"]):
        seq = store.begin(metadata, "tap", {})
        store.finish(metadata["id"], seq, "succeeded", 1)
        with pytest.raises(RecordError, match="limit reached"):
            store.begin(metadata, "tap", {})
    assert len(store.export(None, "a")["steps"]) == 1
    other = store.ensure("b", "id-b")
    monkeypatch.setattr(records, "MAX_STEP_BYTES", 1200)
    with store.lease(other["id"]):
        seq = store.begin(other, "tap", {})
        with pytest.raises(RecordError, match="limit reached"):
            store.target(other["id"], seq, "target", {"value": "x" * 1200})
    assert store.export(None, "b")["steps"][0]["targets"] == {}


@pytest.mark.parametrize("parameters", [{"bad": float("nan")}, {"bad": object()}, {"bad": "\ud800"}])
def test_invalid_step_data_is_rejected_before_persistence(store, parameters):
    metadata = store.ensure("a", "id")
    with store.lease(metadata["id"]), pytest.raises(RecordError):
        store.begin(metadata, "tap", parameters)
    assert store.export(None, "a")["steps"] == []


def test_symlink_storage_and_loose_permissions_are_rejected(store, tmp_path):
    target = tmp_path / "elsewhere"
    target.mkdir(mode=0o700)
    store.directory.symlink_to(target, target_is_directory=True)
    with pytest.raises(RecordError, match="real directory"):
        store.ensure("s", "id")
    store.directory.unlink()
    store.directory.mkdir(mode=0o755)
    store.directory.chmod(0o755)
    with pytest.raises(RecordError, match="0700"):
        store.ensure("s", "id")
    store.directory.chmod(0o700)
    foreign = tmp_path / "foreign.db"
    foreign.write_bytes(b"preserve")
    store.path.symlink_to(foreign)
    with pytest.raises(RecordError, match="regular file"):
        store.active("s")
    assert foreign.read_bytes() == b"preserve"
