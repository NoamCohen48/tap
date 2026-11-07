"""One tab controls a Studio; the server, not the page, enforces it."""
from __future__ import annotations

import asyncio

import pytest
from connectrpc.code import Code
from connectrpc.errors import ConnectError

from tap_studio import service
from tap_studio._gen import studio_pb2 as pb
from tap_studio.service import TAB_HEADER

from .test_operations import attached, back, blocked  # noqa: F401 - fixtures

pytestmark = pytest.mark.anyio


class From:
    """A request from a tab, as the page sends it."""

    def __init__(self, tab: str | None):
        self.tab = tab

    def request_headers(self):
        return {TAB_HEADER: self.tab} if self.tab is not None else {}


async def watch(studio, tab: str):
    stream = studio.watch_session(pb.WatchSessionRequest(tab=tab), None)
    first = await anext(stream)
    return stream, first.session


async def test_the_first_tab_controls_and_a_second_only_watches(studio):
    a, seen_by_a = await watch(studio, "a")
    b, seen_by_b = await watch(studio, "b")
    assert seen_by_a.controller_tab == "a" and seen_by_b.controller_tab == "a"
    await a.aclose()
    await b.aclose()


@pytest.mark.parametrize("tab", ["b", None])
@pytest.mark.parametrize("change", ["perform", "new", "open", "update", "delete", "move", "pause", "attach", "release", "tool", "replay"])
async def test_a_tab_without_control_can_change_nothing(attached, change, tab):  # noqa: F811
    a, _ = await watch(attached, "a")
    b, _ = await watch(attached, "b")
    ctx = From(tab)
    calls = {
        "perform": lambda: attached.perform(back(), ctx),
        "new": lambda: attached.new_recording(pb.NewRecordingRequest(), ctx),
        "open": lambda: attached.open_recording(pb.OpenRecordingRequest(document="{}"), ctx),
        "update": lambda: attached.update_step(pb.UpdateStepRequest(), ctx),
        "delete": lambda: attached.delete_step(pb.DeleteStepRequest(step_id="s1"), ctx),
        "move": lambda: attached.move_step(pb.MoveStepRequest(step_id="s1"), ctx),
        "pause": lambda: attached.set_recording(pb.SetRecordingRequest(recording=False), ctx),
        "attach": lambda: attached.attach(pb.AttachRequest(serial="emulator-5556"), ctx),
        "release": lambda: attached.release(pb.ReleaseRequest(), ctx),
        "tool": lambda: attached.query_app(pb.QueryAppRequest(package_name="p", query=pb.APP_QUERY_INSTALLED), ctx),
        "replay": lambda: anext(attached.replay(pb.ReplayRequest(), ctx)),
    }
    with pytest.raises(ConnectError) as caught:
        await calls[change]()
    assert caught.value.code == Code.FAILED_PRECONDITION and "take control" in caught.value.message
    assert attached._session().device.serial == "emulator-5554"
    await a.aclose()
    await b.aclose()


async def test_the_controlling_tab_changes_things_and_reads_stay_open(attached):  # noqa: F811
    a, _ = await watch(attached, "a")
    b, _ = await watch(attached, "b")
    assert (await attached.perform(back(), From("a"))).recorded
    assert (await attached.get_recording(pb.GetRecordingRequest(), From("b"))).recording.steps
    assert (await attached.get_session(pb.GetSessionRequest(), From("b"))).session.steps == 1
    await a.aclose()
    await b.aclose()


async def test_control_moves_only_when_idle_and_the_old_tab_loses_it(attached, blocked):  # noqa: F811
    entered, finish = blocked
    a, _ = await watch(attached, "a")
    b, _ = await watch(attached, "b")
    running = asyncio.create_task(attached.perform(back(), From("a")))
    await asyncio.wait_for(entered.wait(), 5)
    with pytest.raises(ConnectError) as caught:
        await attached.take_control(pb.TakeControlRequest(tab="b"), None)
    assert caught.value.code == Code.FAILED_PRECONDITION
    finish.set()
    assert (await running).recorded  # the transfer did not interrupt it
    session = (await attached.take_control(pb.TakeControlRequest(tab="b"), None)).session
    assert session.controller_tab == "b"
    with pytest.raises(ConnectError):
        await attached.new_recording(pb.NewRecordingRequest(), From("a"))
    await a.aclose()
    await b.aclose()


async def test_a_tab_that_does_not_watch_cannot_take_control(studio):
    a, _ = await watch(studio, "a")
    with pytest.raises(ConnectError) as caught:
        await studio.take_control(pb.TakeControlRequest(tab="ghost"), None)
    assert caught.value.code == Code.FAILED_PRECONDITION
    await a.aclose()


@pytest.mark.parametrize("tab", ["", "x" * 65])
async def test_a_tab_id_is_bounded(studio, tab):
    with pytest.raises(ConnectError) as caught:
        await anext(studio.watch_session(pb.WatchSessionRequest(tab=tab), None))
    assert caught.value.code == Code.INVALID_ARGUMENT


async def test_a_reloaded_page_gets_control_back_whichever_stream_ends_first(studio):
    old, _ = await watch(studio, "old")
    other, _ = await watch(studio, "other")
    new, _ = await watch(studio, "new")  # the reload opens before the old stream's end is seen
    await old.aclose()
    assert studio._session().controller_tab == "new"
    await new.aclose()
    await other.aclose()
    assert studio._session().controller_tab == ""


async def test_watching_reports_the_operation_as_it_starts_and_ends(attached, blocked):  # noqa: F811
    entered, finish = blocked
    stream, first = await watch(attached, "a")
    assert first.operation == ""
    running = asyncio.create_task(attached.perform(back(), From("a")))
    await asyncio.wait_for(entered.wait(), 5)
    busy = (await asyncio.wait_for(anext(stream), 5)).session
    assert busy.operation == "action" and busy.version > first.version
    finish.set()
    await running
    seen = busy
    while seen.operation:
        seen = (await asyncio.wait_for(anext(stream), 5)).session
    assert seen.recording_revision > busy.recording_revision and seen.steps == 1
    await stream.aclose()


async def test_a_quiet_stream_still_sends_so_a_closed_tab_is_noticed(studio, monkeypatch):
    # The server only sees a disconnect when the stream yields.
    monkeypatch.setattr(service, "WATCH_HEARTBEAT_SECONDS", 0.01)
    stream, first = await watch(studio, "a")
    again = (await asyncio.wait_for(anext(stream), 5)).session
    assert again.version == first.version
    await stream.aclose()


async def test_closing_studio_ends_the_streams(studio):
    stream, _ = await watch(studio, "a")
    await studio.close()
    with pytest.raises(StopAsyncIteration):
        await asyncio.wait_for(anext(stream), 5)
