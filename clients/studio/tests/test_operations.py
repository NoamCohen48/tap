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
