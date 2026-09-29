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

pytestmark = pytest.mark.anyio

SEARCH = {"node": {"resource": {"name": "search", "aut_package": True}}}


def step(**kind) -> pb.Step:
    return json_format.ParseDict(kind, pb.Step())


def tap_search() -> pb.PerformRequest:
    return pb.PerformRequest(step=step(action={"command": {"tap": {"selector": SEARCH}}, "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}))


@pytest.fixture
async def attached(studio):
    await studio.attach(attach_request("emulator-5554", "com.example"), None)
    yield studio
    await studio.close()


def attach_request(serial: str, package: str) -> pb.AttachRequest:
    return pb.AttachRequest(serial=serial, aut_package=package)


async def refused(call, code: Code, message: str = "") -> None:
    with pytest.raises(ConnectError) as caught:
        await call
    assert caught.value.code == code, caught.value
    assert message in str(caught.value), caught.value


async def test_attach_describes_the_device_and_release_detaches_it(studio, daemon):
    session = (await studio.attach(attach_request("emulator-5554", "com.example"), None)).session
    assert (session.device.serial, session.device.aut_package, session.device.api_level) == ("emulator-5554", "com.example", 34)
    assert (session.device.model, session.device.display_width, session.recording) == ("sdk_gphone64", 1080, True)
    assert daemon.connections.live[daemon.devices.attach_requests[-1].client_connection_id].name == "tap-studio"
    released = (await studio.release(pb.ReleaseRequest(), None)).session
    assert not released.HasField("device")
    assert daemon.devices.detaches == ["attached-emulator-5554"]
    await studio.close()
    assert len(daemon.connections.disconnects) == 1


async def test_attaching_again_releases_the_device_before(attached, daemon):
    await attached.attach(attach_request("emulator-5556", "com.example"), None)
    assert daemon.devices.detaches == ["attached-emulator-5554"]
    assert (await attached.get_session(pb.GetSessionRequest(), None)).session.device.serial == "emulator-5556"


async def test_no_daemon_is_unavailable_and_nothing_attached_is_a_precondition(daemon):
    def no_server():
        raise TapError("no server running")

    lonely = Studio(no_server)
    await refused(lonely.list_devices(pb.ListDevicesRequest(), None), Code.UNAVAILABLE, "tap start")
    await refused(lonely.attach(attach_request("emulator-5554", "com.example"), None), Code.UNAVAILABLE)
    await refused(lonely.perform(tap_search(), None), Code.FAILED_PRECONDITION, "no device attached")
    await refused(lonely.count(pb.CountRequest(), None), Code.FAILED_PRECONDITION)
    await refused(anext(lonely.frames(pb.FramesRequest(), None)), Code.FAILED_PRECONDITION)
    await refused(lonely.attach(pb.AttachRequest(serial="x"), None), Code.INVALID_ARGUMENT)


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
    assert (recording.format, recording.aut_package, recording.device.serial, recording.device.api_level) == (
        "tap-recording/1", "com.example", "emulator-5554", 34
    )
    assert recording.recorder.startswith("tap-studio ") and recording.recorded_at.seconds > 0
    assert [s.id for s in recording.steps] == ["s1"]
    dumps(recording)  # a valid tap-recording/1 document
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


async def test_a_recording_stays_with_its_app(attached):
    await attached.perform(tap_search(), None)
    await attached.attach(attach_request("emulator-5554", "com.other"), None)
    session = (await attached.get_session(pb.GetSessionRequest(), None)).session
    assert (session.device.aut_package, session.recording_package) == ("com.other", "com.example")
    await refused(attached.perform(tap_search(), None), Code.FAILED_PRECONDITION, "the recording is for com.example")
    await attached.set_recording(pb.SetRecordingRequest(recording=False), None)
    assert not (await attached.perform(tap_search(), None)).recorded  # runs, paused
    await attached.set_recording(pb.SetRecordingRequest(recording=True), None)
    fresh = (await attached.new_recording(pb.NewRecordingRequest(), None)).session
    assert fresh.steps == 0 and fresh.recording_package == ""
    assert (await attached.perform(tap_search(), None)).recorded
    recording = (await attached.get_recording(pb.GetRecordingRequest(), None)).recording
    assert recording.aut_package == "com.other" and [s.id for s in recording.steps] == ["s2"]


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
