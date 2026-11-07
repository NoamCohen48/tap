"""An accepted operation outlives its HTTP caller, never its recording/attachment."""
from __future__ import annotations

import asyncio
import threading

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from google.protobuf import json_format

from tap_studio._gen import studio_pb2 as pb

pytestmark = pytest.mark.anyio


def back() -> pb.PerformRequest:
    return pb.PerformRequest(step=json_format.ParseDict({"action": {"command": {"press_key": {"key_code": 4}}}}, pb.Step()))


@pytest.fixture
async def attached(studio):
    await studio.attach(pb.AttachRequest(serial="emulator-5554"), None)
    yield studio
    await studio.close()


@pytest.fixture
async def blocked(monkeypatch):
    entered, finish = asyncio.Event(), threading.Event()
    loop = asyncio.get_running_loop()

    def run(*args):
        loop.call_soon_threadsafe(entered.set)
        assert finish.wait(10), "test did not release accepted operation"

    monkeypatch.setattr("tap_studio.service.steps.run", run)
    yield entered, finish
    finish.set()


@pytest.mark.parametrize("change", ["perform", "new", "open", "delete", "pause", "attach", "release", "tool", "replay"])
async def test_an_action_excludes_every_conflicting_change(attached, blocked, change):
    entered, finish = blocked
    task = asyncio.create_task(attached.perform(back(), None))
    await asyncio.wait_for(entered.wait(), 5)
    calls = {
        "perform": lambda: attached.perform(back(), None),
        "new": lambda: attached.new_recording(pb.NewRecordingRequest(), None),
        "open": lambda: attached.open_recording(pb.OpenRecordingRequest(document="{}"), None),
        "delete": lambda: attached.delete_step(pb.DeleteStepRequest(step_id="s1"), None),
        "pause": lambda: attached.set_recording(pb.SetRecordingRequest(recording=False), None),
        "attach": lambda: attached.attach(pb.AttachRequest(serial="emulator-5556"), None),
        "release": lambda: attached.release(pb.ReleaseRequest(), None),
        "tool": lambda: attached._tool_call(lambda device: None),
        "replay": lambda: anext(attached.replay(pb.ReplayRequest(), None)),
    }
    try:
        with pytest.raises(ConnectError) as caught:
            await calls[change]()
        assert caught.value.code == Code.FAILED_PRECONDITION
    finally:
        finish.set()
    assert (await task).recorded
    assert attached.recording.device.serial == "emulator-5554"
    assert [s.id for s in attached.recording.steps] == ["s1"]


async def test_canceled_perform_reconciles_success_and_keeps_its_gate(attached, blocked):
    entered, finish = blocked
    task = asyncio.create_task(attached.perform(back(), None))
    await asyncio.wait_for(entered.wait(), 5)
    owner = attached._operation
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    with pytest.raises(ConnectError):
        await attached.new_recording(pb.NewRecordingRequest(), None)
    assert attached._worker._urgent == 1
    finish.set()
    await asyncio.wait_for(asyncio.shield(owner), 5)
    assert attached.recording.steps[0].HasField("outcome")
    assert attached._worker._urgent == 0
    await attached.new_recording(pb.NewRecordingRequest(), None)


async def test_shutdown_drains_the_action_before_clearing_attachment(attached, blocked):
    entered, finish = blocked
    task = asyncio.create_task(attached.perform(back(), None))
    await asyncio.wait_for(entered.wait(), 5)
    closing = asyncio.create_task(attached.close())
    await asyncio.sleep(0)
    assert not closing.done() and attached._attached is not None
    finish.set()
    assert (await task).recorded
    await asyncio.wait_for(closing, 5)
    assert attached._attached is None and attached.recording.steps[0].HasField("outcome")


async def test_canceling_a_replay_settles_only_its_accepted_step(attached, blocked):
    # Seed two validated steps without issuing input.
    from tap_studio.recording import FORMAT
    from tap_studio.steps import prepare

    attached.recording = pb.Recording(format=FORMAT, steps=[prepare(back().step, None), prepare(back().step, None)])
    for i, step in enumerate(attached.recording.steps):
        step.id = f"s{i + 1}"
    entered, finish = blocked
    stream = attached.replay(pb.ReplayRequest(), None)
    assert (await anext(stream)).step_id == "s1"
    receiving = asyncio.create_task(anext(stream))
    await asyncio.wait_for(entered.wait(), 5)
    owner = attached._replay_step
    receiving.cancel()
    with pytest.raises(asyncio.CancelledError):
        await receiving
    with pytest.raises(ConnectError):
        await attached.release(pb.ReleaseRequest(), None)
    finish.set()
    await asyncio.wait_for(asyncio.shield(owner), 5)
    assert attached.recording.steps[0].HasField("outcome")
    assert not attached.recording.steps[1].HasField("outcome")
    assert not attached._replaying
    await attached.release(pb.ReleaseRequest(), None)


async def test_canceling_a_tool_keeps_its_gate_until_completion(attached):
    entered, finish = asyncio.Event(), threading.Event()
    loop = asyncio.get_running_loop()

    def tool(device):
        loop.call_soon_threadsafe(entered.set)
        assert finish.wait(10)

    task = asyncio.create_task(attached._tool_call(tool))
    await asyncio.wait_for(entered.wait(), 5)
    owner = attached._operation
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    try:
        assert attached._tool_running
        with pytest.raises(ConnectError):
            await attached.new_recording(pb.NewRecordingRequest(), None)
    finally:
        finish.set()
    await asyncio.wait_for(asyncio.shield(owner), 5)
    assert not attached._tool_running


async def test_changing_a_shared_secret_invalidates_all_its_old_outcomes(attached, monkeypatch):
    monkeypatch.setattr("tap_studio.service.steps.run", lambda *args: None)
    request = pb.PerformRequest(step=json_format.ParseDict({"type": {
        "selector": {"node": {"resource": {"name": "pin"}}}, "secret": "pin", "skip_focus_wait": True,
    }}, pb.Step()), secret_value="old")
    first = (await attached.perform(request, None)).step
    await attached.perform(request, None)
    response = await attached.update_step(pb.UpdateStepRequest(step=first, secret_value="new"), None)
    assert all(not step.HasField("outcome") for step in response.recording.steps)
    assert "new" not in response.recording.SerializeToString().decode("latin1")


async def test_new_input_with_a_changed_shared_secret_invalidates_previous_results(attached, monkeypatch):
    monkeypatch.setattr("tap_studio.service.steps.run", lambda *args: None)
    request = pb.PerformRequest(step=json_format.ParseDict({"type": {
        "selector": {"node": {"resource": {"name": "pin"}}}, "secret": "pin", "skip_focus_wait": True,
    }}, pb.Step()), secret_value="old")
    await attached.perform(request, None)
    request.secret_value = "new"
    await attached.perform(request, None)
    assert not attached.recording.steps[0].HasField("outcome")
    assert attached.recording.steps[1].HasField("outcome")


async def test_a_step_can_be_added_without_running_it(attached, monkeypatch):
    def run(*args):
        raise AssertionError("adding a step must not run it")

    monkeypatch.setattr("tap_studio.service.steps.run", run)
    step = json_format.ParseDict({"wait": {"selector": {"node": {"resource": {"name": "later"}}}, "condition": "CONDITION_VISIBLE"}}, pb.Step())
    added = await attached.add_step(pb.AddStepRequest(step=step), None)
    assert added.recording.steps[0].id == "s1" and not added.recording.steps[0].HasField("outcome")
    first = added.recording.steps[0].id
    again = await attached.add_step(pb.AddStepRequest(step=step, before_step_id=first), None)
    assert [s.id for s in again.recording.steps] == ["s2", "s1"]


@pytest.mark.parametrize(
    ("document", "code"),
    [
        ({"wait": {"selector": {}, "condition": "CONDITION_VISIBLE"}}, Code.INVALID_ARGUMENT),
        ({"assertion": {"selector": {"node": {"resource": {"name": "x"}}}, "check": "CHECK_COUNT"}}, Code.INVALID_ARGUMENT),
    ],
)
async def test_an_added_step_is_validated_first(attached, document, code):
    with pytest.raises(ConnectError) as caught:
        await attached.add_step(pb.AddStepRequest(step=json_format.ParseDict(document, pb.Step())), None)
    assert caught.value.code == code
    assert attached.recording is None


async def test_adding_needs_a_device_or_a_recording(studio):
    step = json_format.ParseDict({"wait": {"selector": {"node": {"resource": {"name": "later"}}}, "condition": "CONDITION_VISIBLE"}}, pb.Step())
    with pytest.raises(ConnectError) as caught:
        await studio.add_step(pb.AddStepRequest(step=step), None)
    assert caught.value.code == Code.FAILED_PRECONDITION


async def test_zero_is_a_count_and_survives_the_file(attached):
    from tap_studio.recording import dumps, loads

    step = json_format.ParseDict(
        {"assertion": {"selector": {"node": {"resource": {"name": "gone"}}}, "check": "CHECK_COUNT", "count": 0}}, pb.Step()
    )
    added = await attached.add_step(pb.AddStepRequest(step=step), None)
    reread = loads(dumps(added.recording))
    assert reread.steps[0].assertion.WhichOneof("value") == "count" and reread.steps[0].assertion.count == 0


async def test_undo_and_redo_change_the_recording_only(attached, daemon):
    assert (await attached.perform(back(), None)).recorded
    sent = len(daemon.devices.commands)
    assert attached._session().can_undo and not attached._session().can_redo
    undone = await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert not undone.recording.steps
    assert attached._session().can_redo
    redone = await attached.redo_recording(pb.RedoRecordingRequest(), None)
    assert [s.id for s in redone.recording.steps] == ["s1"]
    assert len(daemon.devices.commands) == sent  # nothing ran again


async def test_undo_with_nothing_to_undo_is_a_precondition(studio):
    with pytest.raises(ConnectError) as caught:
        await studio.undo_recording(pb.UndoRecordingRequest(), None)
    assert caught.value.code == Code.FAILED_PRECONDITION
    with pytest.raises(ConnectError):
        await studio.redo_recording(pb.RedoRecordingRequest(), None)


async def test_a_new_change_clears_redo(attached):
    await attached.perform(back(), None)
    await attached.undo_recording(pb.UndoRecordingRequest(), None)
    await attached.perform(back(), None)
    assert not attached._session().can_redo


async def test_undoing_a_new_recording_brings_it_back_but_not_its_secret_values(attached):
    secret = json_format.ParseDict(
        {"type": {"selector": {"node": {"resource": {"name": "x"}}}, "secret": "pw"}}, pb.Step()
    )
    await attached.perform(pb.PerformRequest(step=secret, secret_value="hunter2"), None)
    await attached.new_recording(pb.NewRecordingRequest(), None)
    restored = await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert [s.id for s in restored.recording.steps] == ["s1"]
    assert list(restored.missing_secrets) == ["pw"]
    assert b"hunter2" not in b"".join(entry or b"" for entry in attached._undo + attached._redo)


async def test_an_outcome_older_than_the_step_does_not_come_back(attached):
    done = await attached.perform(back(), None)
    edited = pb.Step()
    edited.CopyFrom(attached.recording.steps[0])
    edited.action.command.press_key.key_code = 5
    await attached.update_step(pb.UpdateStepRequest(step=edited), None)
    restored = await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert done.recorded and not restored.recording.steps[0].HasField("outcome")


async def test_a_moved_step_keeps_its_outcome_through_undo(attached):
    await attached.perform(back(), None)
    await attached.perform(back(), None)
    await attached.move_step(pb.MoveStepRequest(step_id="s2", before_step_id="s1"), None)
    restored = await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert [s.id for s in restored.recording.steps] == ["s1", "s2"]
    assert all(s.HasField("outcome") for s in restored.recording.steps)


async def test_undo_waits_for_a_running_action(attached, blocked):
    entered, finish = blocked
    running = asyncio.create_task(attached.perform(back(), None))
    await asyncio.wait_for(entered.wait(), 5)
    with pytest.raises(ConnectError) as caught:
        await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert caught.value.code == Code.FAILED_PRECONDITION
    finish.set()
    await running


async def test_history_is_bounded(attached, monkeypatch):
    monkeypatch.setattr("tap_studio.service.UNDO_LIMIT_BYTES", 1000)
    for _ in range(30):
        await attached.perform(back(), None)
    assert 1 <= len(attached._undo) < 30
    assert sum(len(entry or b"") for entry in attached._undo) <= 1000 or len(attached._undo) == 1


async def test_a_duplicate_is_a_new_step_that_has_not_run(attached):
    await attached.perform(back(), None)
    await attached.perform(back(), None)
    copied = await attached.duplicate_step(pb.DuplicateStepRequest(step_id="s1"), None)
    assert [s.id for s in copied.recording.steps] == ["s1", "s3", "s2"]
    assert not copied.recording.steps[1].HasField("outcome")
    assert copied.recording.steps[1].action.command == copied.recording.steps[0].action.command
