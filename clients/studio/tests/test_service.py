"""StudioService against the fake daemon: attach, frames, perform and the recording."""

from __future__ import annotations

import asyncio
import json

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from google.protobuf import json_format

from tap_e2e import TapError
from tap_e2e import proto as tap
from tap_studio._gen import studio_pb2 as pb
from tap_studio.recording import dumps
from tap_studio.service import Studio

from .conftest import device_answers

pytestmark = pytest.mark.anyio

SEARCH = {"node": {"resource": {"name": "search"}}}


def step(**kind) -> pb.Step:
    return json_format.ParseDict(kind, pb.Step())


def tap_search() -> pb.PerformRequest:
    return pb.PerformRequest(step=step(action={"command": {"tap": {"selector": SEARCH}}, "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}))


@pytest.fixture
async def attached(studio):
    await studio.attach(attach_request("emulator-5554"), None)
    yield studio
    await studio.close()


def attach_request(serial: str) -> pb.AttachRequest:
    return pb.AttachRequest(serial=serial)


async def refused(call, code: Code, message: str = "") -> None:
    with pytest.raises(ConnectError) as caught:
        await call
    assert caught.value.code == code, caught.value
    assert message in str(caught.value), caught.value


async def test_attach_describes_the_device_and_release_detaches_it(studio, daemon):
    session = (await studio.attach(attach_request("emulator-5554"), None)).session
    assert (session.device.serial, session.device.api_level) == ("emulator-5554", 34)
    assert (session.device.model, session.device.display_width, session.recording) == ("sdk_gphone64", 1080, True)
    assert daemon.connections.live[daemon.devices.attach_requests[-1].client_connection_id].name == "tap-studio"
    released = (await studio.release(pb.ReleaseRequest(), None)).session
    assert not released.HasField("device")
    assert daemon.devices.detaches == ["attached-emulator-5554"]
    await studio.close()
    assert len(daemon.connections.disconnects) == 1


async def test_attaching_again_releases_the_device_before(attached, daemon):
    await attached.attach(attach_request("emulator-5556"), None)
    assert daemon.devices.detaches == ["attached-emulator-5554"]
    assert (await attached.get_session(pb.GetSessionRequest(), None)).session.device.serial == "emulator-5556"


async def test_no_daemon_is_unavailable_and_nothing_attached_is_a_precondition(daemon):
    def no_server():
        raise TapError("no server running")

    lonely = Studio(no_server)
    await refused(lonely.list_devices(pb.ListDevicesRequest(), None), Code.UNAVAILABLE, "tap start")
    await refused(lonely.attach(attach_request("emulator-5554"), None), Code.UNAVAILABLE)
    await refused(lonely.perform(tap_search(), None), Code.FAILED_PRECONDITION, "no device attached")
    await refused(lonely.count(pb.CountRequest(), None), Code.FAILED_PRECONDITION)
    await refused(anext(lonely.frames(pb.FramesRequest(), None)), Code.FAILED_PRECONDITION)
    await refused(lonely.attach(pb.AttachRequest(), None), Code.INVALID_ARGUMENT, "serial")


async def test_list_devices_asks_the_daemon(studio):
    assert list((await studio.list_devices(pb.ListDevicesRequest(), None)).devices) == []


async def test_a_passing_step_is_recorded_with_its_wait_and_the_header(attached, daemon):
    response = await attached.perform(tap_search(), None)
    assert response.recorded and response.message == ""
    assert response.step.id == "s1" and response.step.outcome.duration_ms >= 0
    assert response.step.action.wait.wait_visible.exactly_one
    [first, second] = [c.WhichOneof("op") for c in daemon.devices.commands[-2:]]
    assert (first, second) == ("wait_visible", "tap")
    recording = (await attached.get_recording(pb.GetRecordingRequest(), None)).recording
    assert (recording.format, recording.device.serial, recording.device.api_level) == ("tap-recording/1", "emulator-5554", 34)
    assert recording.recorder.startswith("tap-studio ") and recording.recorded_at.seconds > 0
    assert [s.id for s in recording.steps] == ["s1"]
    document = (await attached.get_recording(pb.GetRecordingRequest(), None)).document
    assert document == dumps(recording) + "\n"  # the file, as the back end writes it
    second_step = await attached.perform(pb.PerformRequest(step=step(action={"command": {"press_key": {"key_code": 4}}})), None)
    assert second_step.step.id == "s2"
    assert (await attached.get_session(pb.GetSessionRequest(), None)).session.steps == 2


async def test_a_failing_step_runs_but_is_not_recorded(attached, daemon):
    daemon.devices.responder = lambda command: (
        tap.CommandResult(error=tap.Error(code=tap.ERR_NOT_FOUND, message="no node")) if command.WhichOneof("op") == "tap" else None
    )
    response = await attached.perform(tap_search(), None)
    assert not response.recorded and response.step.id == ""
    assert response.step.outcome.error.code == tap.ERR_NOT_FOUND
    assert "NOT_FOUND" in response.message
    await refused(attached.get_recording(pb.GetRecordingRequest(), None), Code.NOT_FOUND)


async def test_paused_steps_run_but_are_not_recorded(attached, daemon):
    session = (await attached.set_recording(pb.SetRecordingRequest(recording=False), None)).session
    assert not session.recording
    response = await attached.perform(tap_search(), None)
    assert not response.recorded and daemon.devices.commands[-1].WhichOneof("op") == "tap"
    await attached.set_recording(pb.SetRecordingRequest(recording=True), None)
    assert (await attached.perform(tap_search(), None)).recorded


async def test_a_probe_runs_but_is_never_recorded(attached, daemon):
    request = tap_search()
    request.skip_recording = True
    response = await attached.perform(request, None)
    assert not response.recorded and response.step.id == "" and not response.message
    assert daemon.devices.commands[-1].WhichOneof("op") == "tap"
    await refused(attached.get_recording(pb.GetRecordingRequest(), None), Code.NOT_FOUND)


async def test_a_secret_goes_to_the_device_and_never_into_the_recording(attached, daemon):
    request = pb.PerformRequest(
        step=step(type={"selector": SEARCH, "secret": "password", "skip_focus_wait": True}), secret_value="hunter2"
    )
    response = await attached.perform(request, None)
    assert response.recorded
    assert daemon.devices.commands[-1].type_text.text == "hunter2"
    recording = (await attached.get_recording(pb.GetRecordingRequest(), None)).recording
    assert list(recording.secrets) == ["password"]
    written = dumps(recording)
    assert "hunter2" not in written and json.loads(written)["steps"][0]["type"]["secret"] == "password"


async def test_invalid_steps_are_refused_before_the_device_sees_them(attached, daemon):
    before = len(daemon.devices.commands)
    await refused(attached.perform(pb.PerformRequest(step=step(action={"command": {"tap": {}}})), None), Code.INVALID_ARGUMENT, "no node")
    await refused(
        attached.perform(pb.PerformRequest(step=step(type={"selector": SEARCH, "secret": "pw"})), None),
        Code.INVALID_ARGUMENT,
        "secret_value",
    )
    assert len(daemon.devices.commands) == before


async def test_a_recording_outlives_a_re_attach(attached):
    """A recording spans whatever apps its steps name, not an attach: attaching again keeps
    recording into it."""
    await attached.perform(tap_search(), None)
    await attached.attach(attach_request("emulator-5554"), None)
    session = (await attached.get_session(pb.GetSessionRequest(), None)).session
    assert (session.device.serial, session.steps) == ("emulator-5554", 1)
    await attached.set_recording(pb.SetRecordingRequest(recording=False), None)
    assert not (await attached.perform(tap_search(), None)).recorded  # runs, paused
    await attached.set_recording(pb.SetRecordingRequest(recording=True), None)
    assert (await attached.perform(tap_search(), None)).recorded
    recording = (await attached.get_recording(pb.GetRecordingRequest(), None)).recording
    assert [s.id for s in recording.steps] == ["s1", "s2"]
    fresh = (await attached.new_recording(pb.NewRecordingRequest(), None)).session
    assert fresh.steps == 0


async def test_count_asks_the_device(attached, daemon):
    response = await attached.count(pb.CountRequest(selector=json_format.ParseDict(SEARCH, tap.Selector())), None)
    assert response.count == 1 and daemon.devices.commands[-1].WhichOneof("op") == "count"
    await refused(attached.count(pb.CountRequest(), None), Code.INVALID_ARGUMENT)


async def test_frames_stream_the_screen_and_end_on_release(attached, daemon):
    daemon.devices.snapshot = tap.ScreenSnapshotResponse(
        snapshot_id=7,
        nodes=[tap.ScreenNode(ref="e1", window_package="com.example", selector=json_format.ParseDict(SEARCH, tap.Selector()))],
    )
    stream = attached.frames(pb.FramesRequest(), None)
    frame = await asyncio.wait_for(anext(stream), 5)
    assert frame.snapshot_id == 7 and [n.ref for n in frame.nodes] == ["e1"]
    assert frame.png == daemon.devices.png
    assert daemon.devices.snapshot_requests[-1].selector_candidates
    await attached.release(pb.ReleaseRequest(), None)

    async def rest() -> list:
        return [f async for f in stream]

    assert len(await asyncio.wait_for(rest(), 5)) <= 1


# --- phase 5: editing, opening and replaying -------------------------------------------------------

GO = {"node": {"resource": {"name": "go"}}}


def tap_on(selector: dict) -> pb.Step:
    return step(action={"command": {"tap": {"selector": selector}}, "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"})


def back() -> pb.PerformRequest:
    return pb.PerformRequest(step=step(action={"command": {"press_key": {"key_code": 4}}}))


async def ids(studio) -> list[str]:
    return [s.id for s in (await studio.get_recording(pb.GetRecordingRequest(), None)).recording.steps]


async def replayed(studio, request: pb.ReplayRequest | None = None) -> list[pb.ReplayResponse]:
    return [event async for event in studio.replay(request or pb.ReplayRequest(), None)]


async def test_a_step_can_go_before_another_and_an_unknown_one_runs_nothing(attached, daemon):
    await attached.perform(tap_search(), None)
    await attached.perform(back(), None)
    await attached.perform(pb.PerformRequest(step=tap_on(GO), before_step_id="s2"), None)
    assert await ids(attached) == ["s1", "s3", "s2"]
    before = len(daemon.devices.commands)
    await refused(attached.perform(pb.PerformRequest(step=tap_on(GO), before_step_id="s9"), None), Code.NOT_FOUND, "s9")
    assert len(daemon.devices.commands) == before


async def test_an_edited_action_gets_its_wait_again_and_loses_its_outcome(attached):
    recorded = (await attached.perform(tap_search(), None)).step
    edited = pb.Step()
    edited.CopyFrom(recorded)
    edited.action.command.tap.selector.CopyFrom(json_format.ParseDict(GO, tap.Selector()))
    edited.action.selector_origin = pb.SELECTOR_ORIGIN_EDITED
    response = await attached.update_step(pb.UpdateStepRequest(step=edited), None)
    [changed] = response.recording.steps
    assert changed.id == "s1" and not changed.HasField("outcome")
    assert changed.action.wait.wait_visible.selector == changed.action.command.tap.selector
    assert changed.action.wait.wait_visible.selector.node.resource.name == "go"


async def test_a_note_keeps_the_outcome(attached):
    recorded = (await attached.perform(tap_search(), None)).step
    noted = pb.Step()
    noted.CopyFrom(recorded)
    noted.note = "opens the search"
    [kept] = (await attached.update_step(pb.UpdateStepRequest(step=noted), None)).recording.steps
    assert kept.note == "opens the search" and kept.outcome == recorded.outcome


async def test_editing_refuses_unknown_and_invalid_steps(attached):
    await attached.perform(tap_search(), None)
    await refused(attached.update_step(pb.UpdateStepRequest(step=tap_on(GO)), None), Code.NOT_FOUND)
    broken = step(id="s1", action={"command": {"tap": {}}})
    await refused(attached.update_step(pb.UpdateStepRequest(step=broken), None), Code.INVALID_ARGUMENT, "no node")
    await refused(attached.delete_step(pb.DeleteStepRequest(step_id="s7"), None), Code.NOT_FOUND)
    await refused(attached.move_step(pb.MoveStepRequest(step_id="s1", before_step_id="s7"), None), Code.NOT_FOUND)


async def test_a_step_made_secret_without_its_value_is_a_missing_secret(attached):
    await attached.perform(pb.PerformRequest(step=step(action={"command": {"set_text": {"selector": SEARCH, "text": "wool"}}})), None)
    secret = step(id="s1", action={"command": {"set_text": {"selector": SEARCH}}, "secret": "query"})
    response = await attached.update_step(pb.UpdateStepRequest(step=secret), None)
    assert list(response.recording.secrets) == ["query"] and list(response.missing_secrets) == ["query"]
    given = await attached.update_step(pb.UpdateStepRequest(step=secret, secret_value="wool"), None)
    assert list(given.missing_secrets) == []
    assert "wool" not in dumps(given.recording)


async def test_deleting_the_last_use_of_a_secret_drops_it(attached):
    request = pb.PerformRequest(step=step(type={"selector": SEARCH, "secret": "pin"}), secret_value="1234")
    await attached.perform(request, None)
    await attached.perform(back(), None)
    response = await attached.delete_step(pb.DeleteStepRequest(step_id="s1"), None)
    assert [s.id for s in response.recording.steps] == ["s2"] and list(response.recording.secrets) == []


async def test_steps_move_before_another_or_to_the_end(attached):
    for _ in range(3):
        await attached.perform(back(), None)
    moved = await attached.move_step(pb.MoveStepRequest(step_id="s3", before_step_id="s1"), None)
    assert [s.id for s in moved.recording.steps] == ["s3", "s1", "s2"]
    moved = await attached.move_step(pb.MoveStepRequest(step_id="s3"), None)
    assert [s.id for s in moved.recording.steps] == ["s1", "s2", "s3"]
    moved = await attached.move_step(pb.MoveStepRequest(step_id="s2", before_step_id="s3"), None)
    assert [s.id for s in moved.recording.steps] == ["s1", "s2", "s3"]


async def test_an_opened_recording_replaces_the_one_in_progress(attached):
    await attached.perform(tap_search(), None)
    await attached.perform(pb.PerformRequest(step=step(type={"selector": SEARCH, "secret": "pin"}), secret_value="1234"), None)
    document = (await attached.get_recording(pb.GetRecordingRequest(), None)).document
    await attached.new_recording(pb.NewRecordingRequest(), None)
    opened = await attached.open_recording(pb.OpenRecordingRequest(document=document), None)
    assert [s.id for s in opened.recording.steps] == ["s1", "s2"] and opened.session.steps == 2
    assert list(opened.missing_secrets) == ["pin"]  # the file has no values
    assert (await attached.perform(back(), None)).step.id == "s3"  # new ids do not clash
    await refused(attached.open_recording(pb.OpenRecordingRequest(document='{"format": "x"}'), None), Code.INVALID_ARGUMENT, "format")


async def test_replay_runs_the_steps_in_order_and_keeps_their_outcomes(attached, daemon):
    await attached.perform(tap_search(), None)
    await attached.perform(back(), None)
    before = len(daemon.devices.commands)
    events = await replayed(attached)
    assert [(e.step_id, e.HasField("outcome")) for e in events] == [("s1", False), ("s1", True), ("s2", False), ("s2", True)]
    assert [c.WhichOneof("op") for c in daemon.devices.commands[before:]] == ["wait_visible", "tap", "press_key"]
    only = await replayed(attached, pb.ReplayRequest(from_step_id="s2", only=True))
    assert [e.step_id for e in only] == ["s2", "s2"]


async def test_replay_stops_at_the_first_failure(attached, daemon):
    await attached.perform(tap_search(), None)
    await attached.perform(back(), None)
    daemon.devices.responder = lambda command: (
        tap.CommandResult(error=tap.Error(code=tap.ERR_NOT_FOUND, message="no node")) if command.WhichOneof("op") == "tap" else device_answers(command)
    )
    events = await replayed(attached)
    assert [e.step_id for e in events] == ["s1", "s1"]
    assert events[-1].outcome.error.code == tap.ERR_NOT_FOUND and "NOT_FOUND" in events[-1].message
    [first, _] = (await attached.get_recording(pb.GetRecordingRequest(), None)).recording.steps
    assert first.outcome.error.code == tap.ERR_NOT_FOUND


async def test_replay_needs_the_secret_values_and_takes_them(attached, daemon):
    await attached.perform(pb.PerformRequest(step=step(type={"selector": SEARCH, "secret": "pin", "skip_focus_wait": True}), secret_value="1234"), None)
    document = (await attached.get_recording(pb.GetRecordingRequest(), None)).document
    await attached.open_recording(pb.OpenRecordingRequest(document=document), None)
    await refused(anext(attached.replay(pb.ReplayRequest(), None)), Code.FAILED_PRECONDITION, "pin")
    await replayed(attached, pb.ReplayRequest(secret_values={"pin": "4321"}))
    assert daemon.devices.commands[-1].type_text.text == "4321"
    assert list((await attached.get_recording(pb.GetRecordingRequest(), None)).missing_secrets) == []


async def test_nothing_else_changes_the_recording_while_a_replay_runs(attached):
    await attached.perform(back(), None)
    stream = attached.replay(pb.ReplayRequest(), None)
    assert (await anext(stream)).step_id == "s1"
    await refused(attached.perform(back(), None), Code.FAILED_PRECONDITION, "replay")
    await refused(attached.delete_step(pb.DeleteStepRequest(step_id="s1"), None), Code.FAILED_PRECONDITION)
    await refused(attached.new_recording(pb.NewRecordingRequest(), None), Code.FAILED_PRECONDITION)
    await refused(anext(attached.replay(pb.ReplayRequest(), None)), Code.FAILED_PRECONDITION)
    await stream.aclose()  # the page stopped it
    assert (await attached.perform(back(), None)).recorded


async def test_replay_refuses_an_empty_recording(attached):
    await refused(anext(attached.replay(pb.ReplayRequest(), None)), Code.FAILED_PRECONDITION, "nothing to replay")


async def test_an_assertion_that_does_not_hold_is_reported_and_not_recorded(attached, daemon):
    wrong = pb.PerformRequest(step=step(assertion={"selector": SEARCH, "check": "CHECK_TEXT_EQUALS", "text": "Silk"}))
    response = await attached.perform(wrong, None)
    assert not response.recorded
    assert response.message == "expected text 'Silk', found 'Wool socks'"
    assert response.step.outcome.mismatch == response.message
    right = pb.PerformRequest(step=step(assertion={"selector": SEARCH, "check": "CHECK_TEXT_EQUALS", "text": "Wool socks"}))
    assert (await attached.perform(right, None)).recorded
