"""The actual on-disk catalog/leases, without a device or daemon."""
from __future__ import annotations

import json
import os
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
    store.start("first", "agent", "connection-1")
    assert [row["name"] for row in store.list()] == ["first"]


def test_a_failed_schema_creation_leaves_nothing_behind(store, monkeypatch):
    monkeypatch.setattr(records, "_SCHEMA", records._SCHEMA + ("CREATE TABLE broken (",))
    with pytest.raises(RecordError, match="storage unavailable"):
        store.start("first", "agent", "connection-1")
    monkeypatch.undo()
    assert store.list() == []  # version 0 and no tables, not a half-made catalog
    store.start("first", "agent", "connection-1")
    assert [row["name"] for row in store.list()] == ["first"]


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
            store.start("first", "agent", "c")
        assert len(attempts) == 1
    else:
        store.start("first", "agent", "c")
        # One retry on creation, then a new connection to read the saved record.
        assert len(attempts) == 3


def test_two_first_starts_share_one_schema(store):
    _empty_catalog(store)
    first, second = RecordStore(store.directory), RecordStore(store.directory)
    holder = sqlite3.connect(store.path, isolation_level=None)
    holder.execute("BEGIN IMMEDIATE")  # both starts queue behind this writer
    results: list[object] = []
    threads = [threading.Thread(target=lambda s=s, n=n: results.append(s.start(n, n, "c"))) for s, n in ((first, "a"), (second, "b"))]
    for thread in threads:
        thread.start()
    holder.execute("COMMIT")
    holder.close()
    for thread in threads:
        thread.join(10)
    assert len(results) == 2
    assert sorted(row["name"] for row in store.list()) == ["a", "b"]


def test_named_records_survive_new_instances_and_export_offline(store):
    metadata = store.start("checkout", "agent", "connection-1")
    with store.lease(metadata["id"]):
        sequence = store.begin(metadata, "tap", {"settle": True})
        store.device(metadata["id"], sequence, "emulator-5554")
        store.target(metadata["id"], sequence, "target", {"node": {"resource": {"name": "save"}}})
        store.finish(metadata["id"], sequence, "succeeded", 12)
    assert not store.export("checkout", "other-session")["complete"]
    resumed = RecordStore(store.directory)
    resumed.stop("checkout", "other-session")
    document = resumed.export(None, "agent")
    assert document["format"] == "tap-agent-recording/1" and document["complete"]
    assert document["connection_id"] == "connection-1"
    (step,) = document["steps"]
    assert step["action"] == "tap" and step["serial"] == "emulator-5554"
    assert step["targets"]["target"]["node"]["resource"]["name"] == "save"
    assert step["duration_ms"] == 12 and step["outcome"]["status"] == "succeeded"
    assert json.loads(json.dumps(document)) == document
    assert stat.S_IMODE(store.directory.stat().st_mode) == 0o700
    assert all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in store.directory.iterdir())


def test_duplicate_names_and_active_session_are_not_overwritten(store):
    store.start("one", "agent", "id-1")
    with pytest.raises(RecordError, match="name already exists"):
        store.start("one", "other", "id-2")
    with pytest.raises(RecordError, match="active recording"):
        store.start("two", "agent", "id-2")
    store.stop(None, "agent")
    store.start("two", "agent", "id-2")
    assert store.select(None, "agent")["name"] == "two"
    assert store.select("one", "agent")["connection_id"] == "id-1"


def test_unfinished_intent_is_not_lost_or_silently_continued(store):
    metadata = store.start("interrupted", "agent", "id")
    with store.lease(metadata["id"]):
        store.begin(metadata, "tap", {})  # process could die after input, before finish
    with store.lease(metadata["id"]), pytest.raises(RecordError, match="unfinished step"):
        store.begin(metadata, "tap", {})
    assert store.export(None, "agent")["steps"][0]["outcome"]["status"] == "running"
    store.stop(None, "agent")
    document = store.export(None, "agent")
    assert not document["complete"] and document["steps"][0]["outcome"]["status"] == "unknown"
    assert document["steps"][0]["finished_at"] is None


def test_live_lease_prevents_stop_and_same_record_execution_but_not_other_sessions(store):
    first = store.start("first", "a", "id-a")
    second = store.start("second", "b", "id-b")
    with store.lease(first["id"]):
        with pytest.raises(RecordError, match="busy"):
            store.stop("first", "a")
        with pytest.raises(RecordError, match="busy"), RecordStore(store.directory).lease(first["id"]):
            pytest.fail("lease must be exclusive")
        with store.lease(second["id"]):
            seq = store.begin(second, "wait", {})
            store.finish(second["id"], seq, "succeeded", 1)
    assert store.stop("first", "a")["state"] == "stopped"


def test_lease_is_exclusive_across_processes(store):
    metadata = store.start("cross-process", "agent", "id")
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
    metadata = store.start("killed", "agent", "id")
    script = """
import sys
import threading
from tap_agent.records import RecordStore
store = RecordStore(sys.argv[1])
recording = store.select('killed', 'agent')
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
    with store.lease(metadata["id"]), pytest.raises(RecordError, match="unfinished step"):
        store.begin(metadata, "tap", {})
    store.stop(None, "agent")
    document = store.export(None, "agent")
    assert not document["complete"] and document["steps"][0]["outcome"]["status"] == "unknown"


def test_clean_requires_explicit_stopped_records(store):
    store.start("one", "a", "a-id")
    with pytest.raises(RecordError, match="stop active"):
        store.clean("one", "a")
    with pytest.raises(RecordError, match="name or explicit"):
        store.clean(None, "a")
    with pytest.raises(RecordError, match="not both"):
        store.clean("one", "a", all_records=True)
    store.stop(None, "a")
    store.start("two", "b", "b-id")
    with pytest.raises(RecordError, match="stop active"):
        store.clean(None, "a", all_records=True)
    assert len(store.list()) == 2
    store.stop(None, "b")
    assert store.clean("one", "a") == 1
    with pytest.raises(RecordError, match="not found"):
        store.export("one", "a")
    assert store.clean(None, "a", all_records=True) == 1
    assert store.list() == []
    assert list(store.directory.glob("*.lease.sqlite3")) == []


@pytest.mark.parametrize("name", ["", "../bad", "a/b", " space", "x" * 81, "秘密"])
def test_names_are_not_paths(store, name):
    with pytest.raises(RecordError, match="name must"):
        store.start(name, "agent", "id")
    assert not store.directory.exists()


def test_bounds_fail_before_inserting_or_replacing_data(store, monkeypatch):
    metadata = store.start("bounded", "a", "id")
    monkeypatch.setattr(records, "MAX_STEPS", 1)
    with store.lease(metadata["id"]):
        seq = store.begin(metadata, "tap", {})
        store.finish(metadata["id"], seq, "succeeded", 1)
        with pytest.raises(RecordError, match="limit reached"):
            store.begin(metadata, "tap", {})
    assert len(store.export(None, "a")["steps"]) == 1
    other = store.start("bytes", "b", "id-b")
    monkeypatch.setattr(records, "MAX_STEP_BYTES", 1200)
    with store.lease(other["id"]):
        seq = store.begin(other, "tap", {})
        with pytest.raises(RecordError, match="limit reached"):
            store.target(other["id"], seq, "target", {"value": "x" * 1200})
    assert store.export(None, "b")["steps"][0]["targets"] == {}


@pytest.mark.parametrize("parameters", [{"bad": float("nan")}, {"bad": object()}, {"bad": "\ud800"}])
def test_invalid_step_data_is_rejected_before_persistence(store, parameters):
    metadata = store.start("invalid", "a", "id")
    with store.lease(metadata["id"]), pytest.raises(RecordError):
        store.begin(metadata, "tap", parameters)
    assert store.export(None, "a")["steps"] == []


def test_symlink_storage_and_loose_permissions_are_rejected(store, tmp_path):
    target = tmp_path / "elsewhere"
    target.mkdir(mode=0o700)
    store.directory.symlink_to(target, target_is_directory=True)
    with pytest.raises(RecordError, match="real directory"):
        store.start("name", "s", "id")
    store.directory.unlink()
    store.directory.mkdir(mode=0o755)
    store.directory.chmod(0o755)
    with pytest.raises(RecordError, match="0700"):
        store.start("name", "s", "id")
    store.directory.chmod(0o700)
    foreign = tmp_path / "foreign.db"
    foreign.write_bytes(b"preserve")
    store.path.symlink_to(foreign)
    with pytest.raises(RecordError, match="regular file"):
        store.active("s")
    assert foreign.read_bytes() == b"preserve"
