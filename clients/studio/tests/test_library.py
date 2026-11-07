"""The private library: atomic, bounded, private and never a store of secrets or results."""
from __future__ import annotations

import asyncio
import json
import os
import stat
import subprocess
import sys

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from google.protobuf import json_format

from tap_studio import library, service
from tap_studio._gen import studio_pb2 as pb
from tap_studio.library import EntryNotFound, InvalidName, Library, LibraryError, NameTaken, RevisionConflict
from tap_studio.recording import loads

from .test_operations import attached, back, blocked  # noqa: F401 - fixtures
from .test_recording import EXAMPLE, text

SECRET = "hunter2-value"


def recording() -> pb.Recording:
    return loads(text(EXAMPLE))


@pytest.fixture
def store(tmp_path) -> Library:
    return Library(tmp_path / "library")


def files(store: Library) -> list:
    return [p for p in store.root.iterdir() if p.name != ".lock"]


def test_save_read_list_rename_delete(store):
    first = store.save(recording(), "Checkout")
    assert first.revision == 1 and first.steps == len(recording().steps)
    entry, read = store.read(first.id)
    assert entry.name == "Checkout" and [s.id for s in read.steps] == [s.id for s in recording().steps]
    renamed = store.rename(first.id, "Basket", first.revision)
    assert renamed.name == "Basket" and renamed.revision == 2
    assert [e.name for e in store.list()[0]] == ["Basket"]
    store.delete(first.id, renamed.revision)
    assert store.list() == ([], 0)
    with pytest.raises(EntryNotFound):
        store.read(first.id)


def test_newest_first(store):
    store.save(recording(), "a")
    store.save(recording(), "b")
    assert [e.name for e in store.list()[0]] == ["b", "a"]


def test_files_are_private_and_nothing_temporary_is_left(store):
    entry = store.save(recording(), "Checkout")
    assert stat.S_IMODE(os.stat(store.root).st_mode) == 0o700
    path = store.root / f"{entry.id}.json"
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    assert files(store) == [path]


def test_an_open_directory_is_made_private(store):
    store.root.mkdir(mode=0o755)
    store.save(recording(), "x")
    assert stat.S_IMODE(os.stat(store.root).st_mode) == 0o700


def test_a_symlinked_store_is_refused(tmp_path):
    (tmp_path / "elsewhere").mkdir()
    (tmp_path / "library").symlink_to(tmp_path / "elsewhere")
    with pytest.raises(LibraryError, match="not a directory of yours"):
        Library(tmp_path / "library").save(recording(), "x")
    assert list((tmp_path / "elsewhere").iterdir()) == []


def test_outcomes_are_not_saved(store):
    source = recording()
    assert any(step.HasField("outcome") for step in source.steps)
    _, read = store.read(store.save(source, "x").id)
    assert not any(step.HasField("outcome") for step in read.steps)


def test_a_stale_revision_is_refused_and_nothing_changes(store):
    entry = store.save(recording(), "x")
    store.save(recording(), "x", entry.id, entry.revision)
    for change in (
        lambda: store.save(recording(), "x", entry.id, entry.revision),
        lambda: store.rename(entry.id, "y", entry.revision),
        lambda: store.delete(entry.id, entry.revision),
    ):
        with pytest.raises(RevisionConflict):
            change()
    assert store.read(entry.id)[0].revision == 2


def test_a_name_in_use_is_refused(store):
    store.save(recording(), "x")
    other = store.save(recording(), "y")
    with pytest.raises(NameTaken):
        store.save(recording(), "x")
    with pytest.raises(NameTaken):
        store.rename(other.id, "x", other.revision)


@pytest.mark.parametrize("name", ["", "   ", "x" * 129, "a\nb", "a\x00b"])
def test_a_bad_name_is_refused(store, name):
    with pytest.raises(InvalidName):
        store.save(recording(), name)


@pytest.mark.parametrize("entry_id", ["../../etc/passwd", "a" * 31, "G" * 32, ""])
def test_an_id_cannot_leave_the_store(store, entry_id):
    with pytest.raises(EntryNotFound):
        store.read(entry_id)


def test_unreadable_files_are_counted_and_left_alone(store):
    good = store.save(recording(), "good")
    bad = store.root / ("a" * 32 + ".json")
    bad.write_text("{not json")
    other = store.root / ("b" * 32 + ".json")
    other.write_text(json.dumps({"format": "someone-else/9", "id": "b" * 32}))
    entries, unreadable = store.list()
    assert [e.id for e in entries] == [good.id] and unreadable == 2
    assert bad.exists() and other.exists()
    with pytest.raises(LibraryError):
        store.read("a" * 32)


def test_a_tampered_recording_does_not_open(store):
    entry = store.save(recording(), "x")
    path = store.root / f"{entry.id}.json"
    data = json.loads(path.read_text())
    data["document"] = "{}"
    path.write_text(json.dumps(data))
    with pytest.raises(LibraryError, match="not a valid recording"):
        store.read(entry.id)


def test_size_and_count_are_bounded(store, monkeypatch):
    monkeypatch.setattr(library, "MAX_ENTRY_BYTES", 100)
    with pytest.raises(library.LibraryFull):
        store.save(recording(), "big")
    assert files(store) == []
    monkeypatch.setattr(library, "MAX_ENTRY_BYTES", 8 * 1024 * 1024)
    monkeypatch.setattr(library, "MAX_ENTRIES", 1)
    store.save(recording(), "one")
    with pytest.raises(library.LibraryFull):
        store.save(recording(), "two")


def test_a_failed_write_keeps_the_old_file(store, monkeypatch):
    entry = store.save(recording(), "x")

    def boom(*args):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(os, "replace", boom)
    with pytest.raises(LibraryError, match="No space left"):
        store.save(recording(), "x", entry.id, entry.revision)
    monkeypatch.undo()
    assert store.read(entry.id)[0].revision == 1
    assert len(files(store)) == 1


def test_two_processes_do_not_lose_each_others_entries(store, tmp_path):
    store.save(recording(), "seed")
    document = tmp_path / "doc.json"
    document.write_text(text(EXAMPLE))
    script = (
        "import sys, pathlib\n"
        "from tap_studio.library import Library\n"
        "from tap_studio.recording import load\n"
        "s = Library(pathlib.Path(sys.argv[1]))\n"
        "r = load(sys.argv[2])\n"
        "for i in range(10): s.save(r, f'{sys.argv[3]}-{i}')\n"
    )
    runs = [subprocess.Popen([sys.executable, "-c", script, str(store.root), str(document), name]) for name in "ab"]
    assert [run.wait(60) for run in runs] == [0, 0]
    assert len(store.list()[0]) == 21


# --- through the service ----------------------------------------------------------------------

pytestmark = pytest.mark.anyio


def typed_secret() -> pb.PerformRequest:
    step = json_format.ParseDict({"type": {"selector": {"node": {"resource": {"name": "x"}}}, "secret": "pw"}}, pb.Step())
    return pb.PerformRequest(step=step, secret_value=SECRET)


async def save(studio, name: str | None = "flow", **options):
    request = pb.SaveToLibraryRequest(**options) if name is None else pb.SaveToLibraryRequest(name=name, **options)
    return (await studio.save_to_library(request, None)).session


async def test_a_secret_value_never_reaches_the_file(attached):  # noqa: F811
    await attached.perform(typed_secret(), None)
    session = await save(attached)
    raw = (attached._store.root / f"{session.library.id}.json").read_text()
    assert SECRET not in raw and '"pw"' in raw.replace('\\"', '"')


async def test_saving_then_changing_shows_unsaved_and_save_updates_the_entry(attached):  # noqa: F811
    await attached.perform(back(), None)
    session = await save(attached)
    assert session.library.name == "flow" and session.library.revision == 1 and not session.library.unsaved
    await attached.perform(back(), None)
    assert attached._session().library.unsaved
    again = await save(attached, None)
    assert again.library.revision == 2 and again.library.id == session.library.id and not again.library.unsaved
    assert attached._store.read(session.library.id)[0].steps == 2


async def test_an_outcome_is_not_an_unsaved_change(attached):  # noqa: F811
    await attached.perform(back(), None)
    await save(attached)
    async for _ in attached.replay(pb.ReplayRequest(), None):
        pass
    assert not attached._session().library.unsaved


async def test_save_as_new_keeps_the_old_entry(attached):  # noqa: F811
    await attached.perform(back(), None)
    first = await save(attached)
    second = await save(attached, "copy", as_new=True)
    assert first.library.id != second.library.id
    assert sorted(e.name for e in attached._store.list()[0]) == ["copy", "flow"]


async def test_a_new_entry_needs_a_name_and_a_recording(attached):  # noqa: F811
    with pytest.raises(ConnectError) as nothing:
        await save(attached)
    assert nothing.value.code == Code.FAILED_PRECONDITION
    await attached.perform(back(), None)
    with pytest.raises(ConnectError) as nameless:
        await save(attached, None)
    assert nameless.value.code == Code.INVALID_ARGUMENT


async def test_another_studio_changing_the_entry_is_a_conflict_not_a_loss(attached):  # noqa: F811
    await attached.perform(back(), None)
    session = await save(attached)
    Library(attached._store.root).rename(session.library.id, "theirs", session.library.revision)
    await attached.perform(back(), None)
    with pytest.raises(ConnectError) as caught:
        await save(attached, None)
    assert caught.value.code == Code.ABORTED
    assert attached._store.read(session.library.id)[0].steps == 1


async def test_opening_needs_no_device_or_server(attached):  # noqa: F811
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    offline = service.Studio(_no_server, store=attached._store)
    listed = await offline.list_library(pb.ListLibraryRequest(), None)
    assert [e.name for e in listed.entries] == ["flow"] and listed.entries[0].steps == 1
    opened = await offline.open_from_library(pb.OpenFromLibraryRequest(id=entry.id), None)
    assert [s.id for s in opened.recording.steps] == ["s1"]
    assert opened.session.library.id == entry.id and not opened.session.library.unsaved
    assert not opened.session.HasField("device")


def _no_server():
    raise AssertionError("the library must not need a Tap server")


async def test_opening_cannot_be_undone_into_saving_over_the_entry(attached):  # noqa: F811
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    await attached.perform(back(), None)
    await attached.perform(back(), None)
    opened = await attached.open_from_library(pb.OpenFromLibraryRequest(id=entry.id), None)
    assert not opened.session.can_undo and not opened.session.can_redo


async def test_opening_an_unknown_entry_keeps_the_recording(attached):  # noqa: F811
    await attached.perform(back(), None)
    with pytest.raises(ConnectError) as caught:
        await attached.open_from_library(pb.OpenFromLibraryRequest(id="0" * 32), None)
    assert caught.value.code == Code.NOT_FOUND and len(attached.recording.steps) == 1


async def test_a_new_or_file_recording_is_not_saved_over_the_entry(attached):  # noqa: F811
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    await attached.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    await attached.new_recording(pb.NewRecordingRequest(), None)
    link = attached._session().library
    assert link.id == "" and not link.autosave
    assert attached._store.read(entry.id)[0].steps == 1
    await attached.open_recording(pb.OpenRecordingRequest(document=text(EXAMPLE)), None)
    assert attached._session().library.id == ""


async def test_renaming_the_open_entry_follows(attached):  # noqa: F811
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    session = (await attached.rename_in_library(pb.RenameInLibraryRequest(id=entry.id, name="new", revision=entry.revision), None)).session
    assert session.library.name == "new" and session.library.revision == entry.revision + 1
    assert (await save(attached, None)).library.name == "new"


async def test_deleting_the_open_entry_keeps_the_recording_as_unsaved(attached):  # noqa: F811
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    session = (await attached.delete_from_library(pb.DeleteFromLibraryRequest(id=entry.id, revision=entry.revision), None)).session
    assert session.library.id == "" and session.library.unsaved and session.steps == 1


async def test_autosave_is_opt_in_and_needs_a_saved_recording(attached, monkeypatch):  # noqa: F811
    monkeypatch.setattr(service, "AUTOSAVE_DELAY_SECONDS", 0.01)
    await attached.perform(back(), None)
    with pytest.raises(ConnectError) as caught:
        await attached.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    assert caught.value.code == Code.FAILED_PRECONDITION
    entry = (await save(attached)).library
    await attached.perform(back(), None)
    await asyncio.sleep(0.05)
    assert attached._store.read(entry.id)[0].steps == 1  # off: nothing is written


async def test_autosave_writes_a_burst_of_changes_once(attached, monkeypatch):  # noqa: F811
    monkeypatch.setattr(service, "AUTOSAVE_DELAY_SECONDS", 0.05)
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    await attached.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    for _ in range(3):
        await attached.perform(back(), None)
    for _ in range(100):
        await asyncio.sleep(0.02)
        if not attached._session().library.unsaved:
            break
    saved, _ = attached._store.read(entry.id)
    assert saved.steps == 4 and saved.revision == entry.revision + 1


async def test_a_failed_autosave_turns_itself_off_and_says_why(attached, monkeypatch):  # noqa: F811
    monkeypatch.setattr(service, "AUTOSAVE_DELAY_SECONDS", 0.01)
    await attached.perform(back(), None)
    entry = (await save(attached)).library
    await attached.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    Library(attached._store.root).rename(entry.id, "theirs", entry.revision)
    await attached.perform(back(), None)
    for _ in range(100):
        await asyncio.sleep(0.01)
        if not attached._session().library.autosave:
            break
    link = attached._session().library
    assert not link.autosave and "another Studio" in link.autosave_error and link.unsaved
    assert attached._store.read(entry.id)[0].name == "theirs"


async def test_save_and_autosave_do_not_conflict_with_each_other(attached, monkeypatch):  # noqa: F811
    monkeypatch.setattr(service, "AUTOSAVE_DELAY_SECONDS", 0)
    await attached.perform(back(), None)
    await save(attached)
    await attached.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    await attached.perform(back(), None)
    await save(attached, None)
    await asyncio.sleep(0.05)
    link = attached._session().library
    assert link.autosave and not link.autosave_error and not link.unsaved


async def test_shutdown_saves_what_autosave_had_pending(studio, monkeypatch):
    monkeypatch.setattr(service, "AUTOSAVE_DELAY_SECONDS", 60)
    await studio.attach(pb.AttachRequest(serial="emulator-5554"), None)
    await studio.perform(back(), None)
    entry = (await save(studio)).library
    await studio.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    await studio.perform(back(), None)
    await studio.close()
    assert studio._store.read(entry.id)[0].steps == 2


async def test_library_changes_need_control_and_wait_for_operations(attached, blocked):  # noqa: F811
    from .test_control import From, watch

    a, _ = await watch(attached, "a")
    other = From("b")
    for call in (
        lambda: attached.save_to_library(pb.SaveToLibraryRequest(name="x"), other),
        lambda: attached.open_from_library(pb.OpenFromLibraryRequest(id="0" * 32), other),
        lambda: attached.rename_in_library(pb.RenameInLibraryRequest(id="0" * 32, name="x"), other),
        lambda: attached.delete_from_library(pb.DeleteFromLibraryRequest(id="0" * 32), other),
        lambda: attached.set_autosave(pb.SetAutosaveRequest(enabled=True), other),
    ):
        with pytest.raises(ConnectError) as caught:
            await call()
        assert "take control" in caught.value.message
    entered, finish = blocked
    running = asyncio.create_task(attached.perform(back(), From("a")))
    await asyncio.wait_for(entered.wait(), 5)
    with pytest.raises(ConnectError) as busy:
        await attached.open_from_library(pb.OpenFromLibraryRequest(id="0" * 32), None)
    assert busy.value.code == Code.FAILED_PRECONDITION
    finish.set()
    await running
    await a.aclose()


class _SlowLibrary(Library):
    """A store whose saves wait for the test, as a slow disk would."""

    def __init__(self, root):
        super().__init__(root)
        self.hold = None

    def save(self, *args, **kwargs):
        if self.hold is not None:
            self.hold.wait(5)
        return super().save(*args, **kwargs)


async def test_an_autosave_that_ends_after_another_open_does_not_link_back(studio, tmp_path):  # noqa: F811
    import threading

    slow = _SlowLibrary(tmp_path / "slow")
    studio._store = slow
    await studio.attach(pb.AttachRequest(serial="emulator-5554"), None)
    await studio.perform(back(), None)
    first = (await save(studio, "A")).library
    await studio.perform(back(), None)
    other = (await save(studio, "B", as_new=True)).library
    await studio.open_from_library(pb.OpenFromLibraryRequest(id=first.id), None)
    await studio.set_autosave(pb.SetAutosaveRequest(enabled=True), None)
    slow.hold = threading.Event()
    await studio.perform(back(), None)  # an edit: autosave starts and waits in the store
    await asyncio.sleep(service.AUTOSAVE_DELAY_SECONDS + 0.2)
    await studio.open_from_library(pb.OpenFromLibraryRequest(id=other.id), None)
    slow.hold.set()
    await asyncio.sleep(0.3)
    link = studio._session().library
    assert (link.id, link.name) == (other.id, "B")
    await studio.perform(back(), None)
    await save(studio, None)  # saves B, never over A
    assert slow.read(first.id)[0].steps == 2 and slow.read(other.id)[0].steps == 3


async def test_a_disk_error_turns_autosave_off_and_says_why(attached, monkeypatch):  # noqa: F811
    await attached.perform(back(), None)
    await save(attached)
    await attached.set_autosave(pb.SetAutosaveRequest(enabled=True), None)

    def full(*args):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(os, "replace", full)
    await attached.perform(back(), None)
    await asyncio.sleep(service.AUTOSAVE_DELAY_SECONDS + 0.3)
    link = attached._session().library
    assert not link.autosave and "No space left on device" in link.autosave_error


def test_a_store_error_is_a_library_error(store, monkeypatch):
    def full(*args):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(os, "replace", full)
    with pytest.raises(LibraryError, match="No space left"):
        store.save(recording(), "x")


@pytest.mark.parametrize("change", [{"name": 7}, {"name": None}, {"updated_at": "yesterday"}, {"revision": True}, {"revision": 0}])
def test_a_malformed_entry_is_unreadable_not_a_broken_library(store, change):
    good = store.save(recording(), "good")
    bad = store.save(recording(), "bad")
    path = store.root / f"{bad.id}.json"
    data = json.loads(path.read_text())
    data.update(change)
    path.write_text(json.dumps(data))
    entries, unreadable = store.list()
    assert [e.id for e in entries] == [good.id] and unreadable == 1
    store.save(recording(), "another")  # saving still works
