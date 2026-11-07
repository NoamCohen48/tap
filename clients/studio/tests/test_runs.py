"""Per-run results: identity, bounds, honest status and no secrets."""
from __future__ import annotations

import asyncio
import json

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError
from google.protobuf import json_format

from tap_e2e import TapError
from tap_studio import runs
from tap_studio._gen import studio_pb2 as pb

from .test_operations import attached, back, blocked  # noqa: F401 - fixtures

pytestmark = pytest.mark.anyio

SECRET = "hunter2-value"


async def replay_all(studio, **options) -> list[pb.ReplayResponse]:
    return [event async for event in studio.replay(pb.ReplayRequest(**options), None)]


async def latest(studio) -> pb.RunResult:
    summaries = (await studio.list_runs(pb.ListRunsRequest(), None)).runs
    return (await studio.get_run(pb.GetRunRequest(id=summaries[0].id), None)).run


async def test_a_passing_run_has_an_identity_and_every_step(attached):  # noqa: F811
    await attached.perform(back(), None)
    await attached.perform(back(), None)
    events = await replay_all(attached)
    run = await latest(attached)
    assert {event.run_id for event in events} == {run.id}
    assert run.format == "tap-studio-run/1" and run.status == pb.RUN_STATUS_PASSED
    assert (run.planned, run.ran) == (2, 2) and [s.step_id for s in run.steps] == ["s1", "s2"]
    assert run.serial == "emulator-5554" and run.HasField("started_at") and run.HasField("finished_at")


async def test_a_failure_ends_the_run_and_names_the_step(attached, monkeypatch):  # noqa: F811
    await attached.perform(back(), None)
    await attached.perform(back(), None)

    def fail(*args):
        raise TapError("the device went away")

    monkeypatch.setattr("tap_studio.service.steps.run", fail)
    await replay_all(attached)
    run = await latest(attached)
    assert run.status == pb.RUN_STATUS_FAILED and run.failed_step_id == "s1"
    assert (run.planned, run.ran) == (2, 1)
    assert "went away" in run.steps[0].message


async def test_a_cancelled_run_is_stopped_never_passed_and_keeps_its_accepted_step(attached, monkeypatch):  # noqa: F811
    await attached.perform(back(), None)
    await attached.perform(back(), None)
    import threading

    entered, finish = asyncio.Event(), threading.Event()
    loop = asyncio.get_running_loop()

    def run_step(*args):
        loop.call_soon_threadsafe(entered.set)
        assert finish.wait(10)

    monkeypatch.setattr("tap_studio.service.steps.run", run_step)
    stream = attached.replay(pb.ReplayRequest(), None)
    await anext(stream)
    pending = asyncio.ensure_future(anext(stream))
    await asyncio.wait_for(entered.wait(), 5)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    await stream.aclose()
    running = await latest(attached)
    assert running.status == pb.RUN_STATUS_RUNNING  # the accepted step is still going
    finish.set()
    for _ in range(100):
        await asyncio.sleep(0.01)
        if not attached._session().operation:
            break
    run = await latest(attached)
    assert run.status == pb.RUN_STATUS_STOPPED and run.ran == 1 and run.planned == 2


async def test_a_step_only_run_counts_only_that_step(attached):  # noqa: F811
    await attached.perform(back(), None)
    await attached.perform(back(), None)
    await replay_all(attached, from_step_id="s2", only=True)
    run = await latest(attached)
    assert run.planned == 1 and [s.step_id for s in run.steps] == ["s2"]


async def test_a_secret_value_is_scrubbed_from_results_and_responses(attached, monkeypatch):  # noqa: F811
    step = json_format.ParseDict({"type": {"selector": {"node": {"resource": {"name": "x"}}}, "secret": "pw"}}, pb.Step())
    await attached.perform(pb.PerformRequest(step=step, secret_value=SECRET), None)

    def leak(*args):
        raise TapError(f"could not type {SECRET} into the field")

    monkeypatch.setattr("tap_studio.service.steps.run", leak)
    events = await replay_all(attached)
    document = (await attached.get_run(pb.GetRunRequest(id=events[0].run_id), None)).document
    assert SECRET not in document and "***" in document
    assert all(SECRET not in event.message for event in events)
    assert SECRET not in json_format.MessageToJson(attached.recording)
    failed = await attached.perform(pb.PerformRequest(step=step, secret_value=SECRET), None)
    assert SECRET not in failed.message and "***" in failed.message


async def test_a_perform_failure_scrubs_a_new_secret_too(attached, monkeypatch):  # noqa: F811
    def leak(*args):
        raise TapError(f"could not type {SECRET}")

    monkeypatch.setattr("tap_studio.service.steps.run", leak)
    step = json_format.ParseDict({"type": {"selector": {"node": {"resource": {"name": "x"}}}, "secret": "pw"}}, pb.Step())
    failed = await attached.perform(pb.PerformRequest(step=step, secret_value=SECRET), None)
    assert SECRET not in failed.message


async def test_parameters_are_kept_with_the_run(attached):  # noqa: F811
    await attached.perform(back(), None)
    await attached.set_parameters(pb.SetParametersRequest(parameters=[pb.Parameter(name="user", value="ada")]), None)
    await replay_all(attached)
    assert (await latest(attached)).parameters == {"user": "ada"}


async def test_a_list_has_no_steps_and_a_lost_run_is_not_found(attached):  # noqa: F811
    await attached.perform(back(), None)
    await replay_all(attached)
    listed = (await attached.list_runs(pb.ListRunsRequest(), None)).runs
    assert listed[0].ran == 1 and not listed[0].steps
    with pytest.raises(ConnectError) as caught:
        await attached.get_run(pb.GetRunRequest(id="nope"), None)
    assert caught.value.code == Code.NOT_FOUND


def test_the_log_keeps_the_last_runs_only():
    log = runs.RunLog()
    made = [log.begin("s", 0, {}) for _ in range(runs.MAX_RUNS + 5)]
    summaries = log.summaries()
    assert len(summaries) == runs.MAX_RUNS and summaries[0].id == made[-1].result.id
    assert log.get(made[0].result.id) is None


def test_a_run_keeps_a_bounded_number_of_steps_but_counts_them_all():
    run = runs.Run("s", runs.MAX_STEPS + 2, {})
    for index in range(runs.MAX_STEPS + 2):
        run.add(f"s{index}", pb.Outcome())
    run.finish()
    assert len(run.result.steps) == runs.MAX_STEPS and run.result.truncated
    assert run.result.ran == runs.MAX_STEPS + 2 and run.result.status == pb.RUN_STATUS_PASSED


def test_status_is_only_passed_when_everything_ran():
    short = runs.Run("s", 2, {})
    short.add("a", pb.Outcome())
    short.finish()
    assert short.result.status == pb.RUN_STATUS_STOPPED
    empty = runs.Run("s", 1, {})
    empty.finish()
    assert empty.result.status == pb.RUN_STATUS_STOPPED


def test_long_text_is_cut_and_the_longest_secret_goes_first():
    assert len(runs.scrub("x" * 10000, [])) < 4200
    assert runs.scrub("abc-abcdef", ["abc", "abcdef"]) == "***-***"
    assert runs.scrub("nothing", [""]) == "nothing"


def test_the_document_is_json_a_person_can_read():
    run = runs.Run("emulator-5554", 1, {"user": "ada"})
    run.add("s1", pb.Outcome(duration_ms=5))
    run.finish()
    document = json.loads(runs.dumps(run.result))
    assert document["format"] == runs.FORMAT and document["status"] == "RUN_STATUS_PASSED"
    assert document["steps"][0]["step_id"] == "s1" and document["parameters"] == {"user": "ada"}



@pytest.mark.parametrize("value", ["a\\b", "line\nbreak", "both'\"quotes", "tab\there", "é-ünï"])
def test_a_secret_quoted_in_a_message_is_scrubbed_however_it_is_escaped(value):
    import json as _json

    from google.protobuf import text_encoding

    message = f"expected 'x', found {value!r}; as JSON {_json.dumps(value)}; as text {text_encoding.CEscape(value, as_utf8=True)}"
    scrubbed = runs.scrub(message, [value])
    for form in (value, repr(value)[1:-1], _json.dumps(value)[1:-1], text_encoding.CEscape(value, as_utf8=True)):
        assert form not in scrubbed
