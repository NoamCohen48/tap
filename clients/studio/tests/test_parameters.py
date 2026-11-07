"""Named test data: resolved before input, written to the file, never a secret or a selector."""
from __future__ import annotations

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from google.protobuf import json_format

from tap_e2e import proto as tap
from tap_studio._gen import studio_pb2 as pb
from tap_studio.recording import RecordingError, dumps, loads

from .test_operations import attached, back  # noqa: F401 - fixture
from .test_recording import EXAMPLE, text

pytestmark = pytest.mark.anyio

FIELD = {"node": {"resource": {"name": "field"}}}


def step(document: dict) -> pb.Step:
    return json_format.ParseDict(document, pb.Step())


def typed(name: str = "user") -> pb.Step:
    return step({"type": {"selector": FIELD, "parameter": name}})


async def declare(studio, **values: str):
    return await studio.set_parameters(pb.SetParametersRequest(parameters=[pb.Parameter(name=k, value=v) for k, v in values.items()]), None)


def sent_text(daemon) -> list[str]:
    return [c.type_text.text for c in daemon.devices.commands if c.WhichOneof("op") == "type_text"]


async def test_a_run_sends_the_value_and_the_recording_keeps_the_name(attached, daemon):  # noqa: F811
    await declare(attached, user="ada")
    response = await attached.perform(pb.PerformRequest(step=typed()), None)
    assert response.recorded, response.message
    assert sent_text(daemon) == ["ada"]
    assert attached.recording.steps[0].type.parameter == "user"
    assert "ada" not in json_format.MessageToJson(attached.recording.steps[0])


async def test_set_text_resolves_its_parameter(attached, daemon):  # noqa: F811
    await declare(attached, user="ada")
    document = {"action": {"command": {"set_text": {"selector": FIELD}}, "parameter": "user"}}
    assert (await attached.perform(pb.PerformRequest(step=step(document)), None)).recorded
    assert [c.set_text.text for c in daemon.devices.commands if c.WhichOneof("op") == "set_text"] == ["ada"]


async def test_a_wait_compares_with_the_parameter(attached):  # noqa: F811
    await declare(attached, expected="Wool socks")  # the fake device's text
    document = {"wait": {"selector": FIELD, "condition": "CONDITION_TEXT_EQUALS", "parameter": "expected", "timeout_ms": 100}}
    assert (await attached.perform(pb.PerformRequest(step=step(document)), None)).recorded
    await declare(attached, expected="Something else")
    assert not (await attached.perform(pb.PerformRequest(step=step(document)), None)).recorded


async def test_parameters_declared_before_the_first_step_survive_it(attached):  # noqa: F811
    await declare(attached, user="ada")
    await attached.perform(back(), None)
    assert [p.name for p in attached.recording.parameters] == ["user"]


async def test_an_assertion_compares_with_the_parameter(attached):  # noqa: F811
    document = {"assertion": {"selector": FIELD, "check": "CHECK_TEXT_EQUALS", "parameter": "expected"}}
    await declare(attached, expected="Wool socks")
    assert (await attached.perform(pb.PerformRequest(step=step(document)), None)).recorded
    await declare(attached, expected="Something else")
    assert not (await attached.perform(pb.PerformRequest(step=step(document)), None)).recorded


async def test_an_unknown_parameter_is_refused_before_any_input(attached, daemon):  # noqa: F811
    before = len(daemon.devices.commands)
    with pytest.raises(ConnectError) as caught:
        await attached.perform(pb.PerformRequest(step=typed("nobody")), None)
    assert caught.value.code == Code.INVALID_ARGUMENT and "nobody" in caught.value.message
    assert len(daemon.devices.commands) == before


async def test_a_replay_uses_the_value_at_that_moment(attached, daemon):  # noqa: F811
    await declare(attached, user="ada")
    await attached.perform(pb.PerformRequest(step=typed()), None)
    await declare(attached, user="grace")
    assert not attached.recording.steps[0].HasField("outcome")  # the value it ran with has changed
    async for _ in attached.replay(pb.ReplayRequest(), None):
        pass
    assert sent_text(daemon) == ["ada", "grace"]
    assert attached.recording.steps[0].HasField("outcome")


async def test_same_value_and_other_parameters_keep_a_result(attached):  # noqa: F811
    await declare(attached, user="ada", other="x")
    await attached.perform(pb.PerformRequest(step=typed()), None)
    await declare(attached, user="ada", other="y")
    assert attached.recording.steps[0].HasField("outcome")


async def test_a_used_parameter_cannot_be_removed(attached):  # noqa: F811
    await declare(attached, user="ada")
    await attached.perform(pb.PerformRequest(step=typed()), None)
    with pytest.raises(ConnectError) as caught:
        await declare(attached)
    assert caught.value.code == Code.INVALID_ARGUMENT and "user" in caught.value.message
    assert [p.name for p in attached.recording.parameters] == ["user"]


async def test_editing_a_step_to_an_unknown_parameter_is_refused(attached):  # noqa: F811
    await attached.perform(back(), None)
    edited = typed("nobody")
    edited.id = "s1"
    with pytest.raises(ConnectError) as caught:
        await attached.update_step(pb.UpdateStepRequest(step=edited), None)
    assert caught.value.code == Code.INVALID_ARGUMENT


@pytest.mark.parametrize("name", ["", "1a", "a b", "a-b", "${a}", "x" * 65])
async def test_a_bad_name_is_refused(attached, name):  # noqa: F811
    with pytest.raises(ConnectError) as caught:
        await attached.set_parameters(pb.SetParametersRequest(parameters=[pb.Parameter(name=name)]), None)
    assert caught.value.code == Code.INVALID_ARGUMENT


async def test_names_are_unique_and_values_bounded(attached):  # noqa: F811
    for parameters in ([pb.Parameter(name="a"), pb.Parameter(name="a")], [pb.Parameter(name="a", value="x" * 4097)]):
        with pytest.raises(ConnectError):
            await attached.set_parameters(pb.SetParametersRequest(parameters=parameters), None)


async def test_a_name_cannot_be_both_a_parameter_and_a_secret(attached):  # noqa: F811
    secret = step({"type": {"selector": FIELD, "secret": "pw"}})
    await attached.perform(pb.PerformRequest(step=secret, secret_value="x"), None)
    with pytest.raises(ConnectError) as caught:
        await declare(attached, pw="y")
    assert "both" in caught.value.message


async def test_setting_parameters_needs_something_to_set_them_on(studio):
    with pytest.raises(ConnectError) as caught:
        await declare(studio, a="b")
    assert caught.value.code == Code.FAILED_PRECONDITION


async def test_undo_brings_back_the_old_parameters(attached):  # noqa: F811
    await declare(attached, user="ada")
    await declare(attached, user="grace")
    restored = await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert restored.recording.parameters[0].value == "ada"


def document_with(parameters: list[dict], steps: list[dict]) -> str:
    return text({**EXAMPLE, "secrets": [], "parameters": parameters, "steps": steps})


def test_the_file_round_trips_parameters_and_references():
    steps = [{"id": "s1", "type": {"selector": FIELD, "parameter": "user"}}]
    loaded = loads(document_with([{"name": "user", "value": "ada", "description": "who"}], steps))
    again = loads(dumps(loaded))
    assert again.parameters[0].value == "ada" and again.steps[0].type.parameter == "user"


def test_a_file_with_an_undeclared_parameter_is_refused():
    with pytest.raises(RecordingError, match="not declared"):
        loads(document_with([], [{"id": "s1", "type": {"selector": FIELD, "parameter": "user"}}]))


def test_literal_dollar_text_is_not_a_template(daemon, client):
    from tap_studio.steps import resolve

    literal = step({"type": {"selector": FIELD, "text": "${user}"}})
    assert resolve(literal, {"user": "ada"}) is literal


def test_a_selector_is_never_resolved():
    from tap_studio.steps import resolve

    original = step({"type": {"selector": {"node": {"resource": {"name": "${user}"}}}, "parameter": "user"}})
    resolved = resolve(original, {"user": "ada"})
    assert resolved.type.text == "ada"
    assert resolved.type.selector == original.type.selector
    assert isinstance(resolved.type.selector, tap.Selector)



async def test_parameters_declared_before_any_step_are_the_recording(attached):  # noqa: F811
    await attached.set_parameters(pb.SetParametersRequest(parameters=[pb.Parameter(name="user", value="ada")]), None)
    read = await attached.get_recording(pb.GetRecordingRequest(), None)
    assert [p.name for p in read.recording.parameters] == ["user"]


async def test_undo_does_not_bring_back_a_pass_made_with_another_value(attached):  # noqa: F811
    await attached.set_parameters(pb.SetParametersRequest(parameters=[pb.Parameter(name="user", value="a")]), None)
    await attached.perform(pb.PerformRequest(step=typed()), None)
    await attached.set_parameters(pb.SetParametersRequest(parameters=[pb.Parameter(name="user", value="b")]), None)
    async for _ in attached.replay(pb.ReplayRequest(), None):
        pass
    assert attached.recording.steps[0].HasField("outcome")
    await attached.undo_recording(pb.UndoRecordingRequest(), None)
    assert [p.value for p in attached.recording.parameters] == ["a"]
    assert not attached.recording.steps[0].HasField("outcome")
