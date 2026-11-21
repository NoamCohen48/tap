"""`record` and `history --recording`: named recordings of a session's history, on the server."""
# pyright: reportMissingImports=false
from __future__ import annotations

import json
import time

import pytest

from tap_agent import Agent, AgentError


def test_a_recording_follows_devices_attached_after_it_started(fake, agent):
    agent.attach("first")
    assert "devices attached later join it" in agent.record("start", "checkout")
    agent.attach("second")
    agent.tap("id=pay", device="second")  # no refusal: the recording is the connection's, not a device set
    assert agent.record("stop", "checkout") == "stopped recording 'checkout'"

    (saved,) = fake.history.recordings.values()
    assert saved.HasField("stopped_at_ms")
    assert {span.source_id for span in saved.spans} >= {"attached-first", "attached-second"}
    assert agent.record("list") == "checkout"
    assert f"id={saved.id}" in agent.record("list", long=True)


def test_history_reads_a_recording_by_name_also_after_release(fake, agent):
    agent.attach("first")
    agent.record("start", "checkout")
    agent.tap("id=pay")
    agent.release()  # stops the session's recording

    (saved,) = fake.history.recordings.values()
    assert saved.HasField("stopped_at_ms")
    document = json.loads(agent.history(recording="checkout", as_json=True))
    assert fake.history.requests[-1].selection.recording_id == saved.id
    assert document["recording"]["name"] == "checkout" and document["recording"]["stopped_at"]
    assert agent.history(recording="checkout").splitlines()[0] == "recording checkout (stopped)"


def test_the_same_name_on_two_sessions_needs_its_id_outside_them(fake, client, agent, tmp_path):
    other = Agent("other", client=client, out_dir=tmp_path / "other")
    agent.attach("first")
    other.attach("second")
    agent.record("start", "checkout")
    other.record("start", "checkout")
    mine = next(r for r in fake.history.recordings.values() if r.connection_id == next(iter(fake.connections.live)))
    agent.history(recording="checkout")  # the session's own recording wins
    assert fake.history.requests[-1].selection.recording_id == mine.id
    stranger = Agent("stranger", client=client, out_dir=tmp_path / "stranger")
    with pytest.raises(AgentError, match="give its id"):
        stranger.history(recording="checkout")
    stranger.history(recording=mine.id)
    assert fake.history.requests[-1].selection.recording_id == mine.id


def test_clean_needs_a_stopped_recording_and_export_points_at_history(fake, agent):
    agent.attach("first")
    agent.record("start", "checkout")
    with pytest.raises(AgentError, match="stop the recording"):
        agent.record("clean", "checkout")
    agent.record("stop", "checkout")
    assert agent.record("clean", "checkout") == "cleaned recording 'checkout'"
    assert not fake.history.recordings
    with pytest.raises(AgentError, match="history --recording NAME"):
        agent.record("export", "checkout")
    with pytest.raises(AgentError, match="no recording named"):
        agent.record("stop", "checkout")


def test_last_and_explicit_times_are_sent_as_server_bounds(fake, agent):
    agent.attach("first")
    agent.history(last="30s")
    request = fake.history.requests[-1]
    assert request.HasField("from_time_ms") and not request.HasField("until_time_ms") and request.newest_first  # a window ending now
    agent.history(start="2026-11-21T12:00:00Z", until="2026-11-21T12:00:30+00:00")
    request = fake.history.requests[-1]
    assert (request.from_time_ms, request.until_time_ms) == (1_795_262_400_000, 1_795_262_430_000) and not request.newest_first
    for bad in ({"start": "2026-11-21T12:00:00"}, {"last": "30s", "until": "2026-11-21T12:00:00Z"},
                {"start": "2026-11-21T12:00:00Z", "since_marker": "x"}, {"after": "z:abc"}):
        with pytest.raises(AgentError):
            agent.history(**bad)


def test_a_step_prints_as_the_command_with_its_target_and_changed_options_only(fake, agent):
    from tap_e2e import res
    agent.attach("first")
    fake.devices.refs["e1"] = res("pay").to_proto()
    agent.tap("@e1")
    agent.tap("text=Pay,pkg=com.shop", count=2)
    agent.fill("id=card", "4111111111111111", sensitive=True)
    agent.app("cold-launch", "com.shop")
    lines = [line.split("  ", 2)[2] for line in agent.history().splitlines()]
    assert lines == ['step 1: tap res("pay") → succeeded',
        'step 2: tap text("Pay") & app("com.shop") count=2 → succeeded',
        'step 3: fill res("card") <redacted> sensitive → succeeded',
        "step 4: app cold-launch com.shop → succeeded"]
    assert "4111" not in agent.history()


def test_each_step_is_on_the_one_page_that_has_its_first_call(fake, agent):
    agent.attach("first")
    for name in ("a", "b", "c", "d"):
        agent.tap(f"id={name}")
        if name == "b":
            with pytest.raises(AgentError):
                agent.tap("id=x", count=0)  # refused before any call: placed by its time
    shown = []
    text = agent.history(limit=1)
    while True:
        shown += [line for line in text.splitlines() if " step " in line]
        last = text.splitlines()[-1]
        if not last.startswith("older: --after "):
            break
        text = agent.history(after=last.removeprefix("older: --after "), limit=1)
    assert [line.split("step ")[1].split(":")[0] for line in shown] == ["5", "4", "2", "3", "1"]


def test_markers_show_without_logs(fake, agent):
    agent.attach("first")
    agent.tap("id=a")
    agent.history("mark", "paid")
    lines = agent.history().splitlines()
    assert lines[-1].endswith("── marker paid") and "step 1: tap" in lines[0]


def test_a_marker_bound_reads_only_this_session_s_connections(fake, client, agent, tmp_path):
    other = Agent("other", client=client, out_dir=tmp_path / "other")
    agent.attach("first")
    other.attach("second")
    agent.history("mark", "paid")
    agent.history(since_marker="paid")
    request = fake.history.requests[-1]
    mine = {r["connection_id"] for r in agent.records.list() if r["session"] == agent.session}
    assert request.from_marker == "paid" and set(request.selection.connection_ids) == mine


def test_later_pages_keep_the_device_selection_for_the_agent_s_steps(fake, agent):
    agent.attach("first")
    agent.attach("second")
    for _ in range(3):
        agent.tap("id=a", device="first")
        agent.tap("id=b", device="second")
    text = agent.history(devices=("first",), limit=1)
    serials = set()
    while True:
        serials |= {line.split("  ")[1] for line in text.splitlines() if " step " in line}
        last = text.splitlines()[-1]
        if not last.startswith("older: --after "):
            break
        text = agent.history(after=last.removeprefix("older: --after "), limit=1)
    assert serials == {"first"}


def test_json_has_one_continuation_the_after_flag_takes(fake, agent):
    agent.attach("first")
    agent.tap("id=a")
    agent.tap("id=b")
    document = json.loads(agent.history(as_json=True, limit=1))
    assert "next_cursor" not in document and all("cursor" not in entry for entry in document["entries"])
    assert document["next"] and json.loads(agent.history(after=document["next"], as_json=True))["entries"]


def test_step_lines_name_options_and_print_positionals_by_value(fake, agent):
    from tap_e2e import _gen as pb
    agent.attach("first")
    fake.devices.responder = lambda command: pb.CommandResult(bool=True) if command.HasField("exists") else None
    agent.scroll_until("class=RecyclerView", "text=Receipt")
    agent.app("cold-launch", "com.shop")
    with pytest.raises(AgentError):
        agent.app("grant", "com.shop", "android.permission.CAMERA")  # the fake has no permission RPC
    lines = [line.split("  ", 2)[2] for line in agent.history().splitlines() if " step " in line]
    assert lines[0] == 'step 1: scroll-until class_name(".RecyclerView", ENDS_WITH) text("Receipt") → succeeded'
    assert lines[2].startswith("step 3: app grant com.shop android.permission.CAMERA → ")


def test_queries_print_as_short_calls_not_json(fake, agent):
    agent.attach("first")
    agent.exists("text~=Pay")
    text = agent.history()
    assert 'call exists text("Pay", CONTAINS) → ok' in text and "{" not in text


def test_a_step_read_shows_only_markers_written_during_it(fake, agent):
    agent.attach("first")
    agent.tap("id=a")
    agent.history("mark", "after-a")
    agent.tap("id=b")
    assert "marker" not in agent.history(step="1") and "marker after-a" in agent.history()


def test_a_recording_holds_only_the_steps_taken_while_it_recorded(fake, agent):
    agent.attach("first")
    agent.tap("id=before")  # before the recording: its steps are not the recording's
    time.sleep(0.01)
    agent.record("start", "checkout")
    agent.tap("id=pay")
    agent.record("stop", "checkout")
    time.sleep(0.01)
    agent.tap("id=after")

    document = json.loads(agent.history(recording="checkout", as_json=True))
    assert [step["targets"]["target"]["node"]["resource"]["name"] for step in document["steps"]] == ["pay"]
    text = agent.history(recording="checkout")
    assert "pay" in text and "before" not in text and "after" not in text
