"""One stdout renderer for retained activity and protected ranges, with stable pagination."""
# pyright: reportMissingImports=false
from __future__ import annotations

import json

import pytest
from mcp import Client
from mcp_types import TextContent
from tap_e2e import TapClient
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.mcp_server import create_server


def attach(fake, agent):
    agent.attach("first")
    owner = next(iter(fake.connections.live))
    fake.device_log.archives.append(pb.RetainedLog(client_connection_id=owner,
        attached_device_id="attached-first", serial="first"))
    return owner


def line(cursor, message, at=0):
    return pb.LogEntry(cursor=cursor, host_time_ms=at, line=pb.LogLine(message=message, tag="App"))


def view(agent, **kwargs):
    return json.loads(agent.history(as_json=True, **kwargs))


def saved(agent, name, **kwargs):
    return json.loads(agent.record("export", name, as_json=True, **kwargs))


def test_recording_protects_the_same_steps_and_events_and_export_does_not_stop(fake, agent):
    attach(fake, agent)
    agent.tap("id=before")
    fake.device_log.entries.append(line(1, "before"))
    agent.record("start", "checkout")
    agent.fill("pkg=com.shop,id=email", "replacement@example.com")
    fake.device_log.entries.append(line(2, "inside"))
    agent.wait("id=success", timeout=4)
    live = saved(agent, "checkout", logs=True)
    assert not live["complete"] and not fake.device_log.recordings["checkout"].HasField("stopped_at_ms")
    assert [s["action"] for s in live["steps"]] == ["fill", "wait"]
    agent.record("stop", "checkout")
    agent.key("back")
    fake.device_log.entries.append(line(3, "after"))
    document = saved(agent, "checkout", logs=True)
    assert document["format"] == "tap-history/1" and document["complete"]
    assert document["steps"][0]["parameters"]["text"] == "replacement@example.com"
    assert document["steps"][1]["parameters"]["timeout"] == 4
    assert [e["line"]["message"] for e in document["log"]["first"]["entries"]] == ["inside"]
    assert [e["step"] for e in document["events"]] == [s["id"] for s in document["steps"]]
    agent.release()
    assert saved(agent, "checkout", logs=True)["log"] == document["log"]
    assert [s["action"] for s in view(agent)["steps"]] == ["tap", "fill", "wait", "key"]


def test_rotation_pruning_and_clean_never_reuse_step_cursors(fake, agent, monkeypatch):
    import tap_agent.records as records
    monkeypatch.setattr(records, "MAX_STEPS", 1)
    monkeypatch.setattr(records, "KEEP_STOPPED", 0)
    attach(fake, agent)
    agent.record("start", "long-flow")
    for _ in range(3):
        agent.key("back")
    agent.record("stop", "long-flow")
    agent.key("home")
    agent.release()
    document = saved(agent, "long-flow")
    assert [s["seq"] for s in document["steps"]] == [1, 2, 3]
    assert [s["cursor"] for s in document["steps"]] == [1, 2, 3]
    assert document["complete"]
    agent.record("clean", "long-flow")
    agent.attach("first")
    agent.key("back")
    assert view(agent)["steps"][-1]["cursor"] == 5
    assert not fake.device_log.recordings


def test_history_pages_across_rotations_and_connections_not_storage_partitions(fake, agent):
    attach(fake, agent)
    for _ in range(3):
        agent.key("back")
    agent.release()
    agent.attach("first")
    for _ in range(2):
        agent.key("home")
    latest = view(agent, limit=2)
    assert [s["cursor"] for s in latest["steps"]] == [4, 5]
    assert latest["older"] == 4
    assert "older: --until 4" in agent.history(limit=2)
    first = view(agent, start="1", limit=2)
    assert [s["cursor"] for s in first["steps"]] == [1, 2] and first["next"] == 3
    second = view(agent, start=first["next"], until="5", limit=2)
    assert [s["cursor"] for s in second["steps"]] == [3, 4] and second["next"] is None
    assert len({s["connection_id"] for s in second["steps"]}) == 2
    assert [s["cursor"] for s in view(agent, until="4", limit=2)["steps"]] == [2, 3]
    assert "next: --from 3" in agent.history(start=1, limit=2)


def test_time_bounds_have_timezone_and_are_from_inclusive_until_exclusive(fake, agent):
    attach(fake, agent)
    for _ in range(3):
        agent.key("back")
    stamps = ["2026-11-17T12:00:00.000Z", "2026-11-17T12:00:01.000Z", "2026-11-17T12:00:02.000Z"]
    with agent.records._db(write=True) as connection:
        for cursor, at in enumerate(stamps, 1):
            connection.execute("UPDATE steps SET started_at=?,finished_at=? WHERE cursor=?", (at, at, cursor))
    page = view(agent, start="2026-11-17T14:00:01+02:00", until=stamps[2])
    assert [s["cursor"] for s in page["steps"]] == [2]
    assert "dropped_steps" not in page  # Excluded timestamps are not retention loss.


@pytest.mark.parametrize("kwargs", [{"start": "not-a-time"}, {"start": "2026-11-17T12:00:00"},
    {"start": "0"}, {"start": True}, {"start": 3, "until": 1}, {"limit": 0}, {"limit": 5001},
    {"limit": True}, {"as_json": "false"}, {"logs": 1}])
def test_invalid_history_bounds_and_flags_are_usage_errors(agent, kwargs):
    with pytest.raises(AgentError) as error:
        agent.history(**kwargs)
    assert error.value.exit_code == 2


def test_ordinary_log_loss_and_missing_archives_are_explicit_after_release(fake, agent):
    attach(fake, agent)
    agent.tap("id=save")
    at = view(agent)["steps"][0]["started_at"]
    from datetime import datetime
    ms = int(datetime.fromisoformat(at.replace("Z", "+00:00")).timestamp() * 1000)
    fake.device_log.entries.append(line(4, "failure evidence", ms))
    fake.device_log.dropped = 3
    fake.device_log.capture_error = "storage limit"
    agent.release()
    document = view(agent, logs=True)
    assert not document["complete"]
    assert document["log"]["first"]["dropped"] == 3
    assert document["log"]["first"]["capture_error"] == "storage limit"
    assert document["log"]["first"]["entries"][0]["line"]["message"] == "failure evidence"


def test_agent_orphan_protection_is_recoverable_but_sdk_only_ranges_do_not_invent_steps(fake, agent):
    attach(fake, agent)
    agent.client.log_recordings.start(agent._connection(), "orphan", agent_steps_expected=True)
    fake.device_log.entries.append(line(1, "captured without local intent"))
    agent.record("stop", "orphan")
    orphan = saved(agent, "orphan", logs=True)
    assert not orphan["complete"] and orphan["step_history_unavailable"]
    assert orphan["steps"] == []
    agent.record("clean", "orphan")
    agent.client.log_recordings.start(agent._connection(), "sdk-only")
    agent.key("back")
    agent.client.log_recordings.stop("sdk-only")
    # Use a separate local store to model SDK-only use, not this agent's unrelated ordinary steps.
    other = Agent("agent", client=agent.client, out_dir=agent.out_dir / "other")
    document = saved(other, "sdk-only")
    assert document["complete"] and document["steps"] == [] and document["events"]
    assert "step_history_unavailable" not in document


def test_capture_failure_is_preserved_when_exporting_local_intent_offline(fake, agent, monkeypatch):
    attach(fake, agent)
    agent.record("start", "incomplete")
    agent.tap("id=save")
    fake.device_log.capture_error = "hard storage limit"
    assert "WARNING: incomplete capture" in agent.record("stop", "incomplete")
    agent.release()
    offline = Agent("agent", out_dir=agent.out_dir)
    def unavailable(*args, **kwargs):
        raise AgentError("daemon unavailable", 3)
    monkeypatch.setattr(TapClient, "create", unavailable)
    document = saved(offline, "incomplete")
    assert not document["complete"] and document["events_unavailable"]
    assert document["log_capture"]["capture_error"] == "hard storage limit"
    assert "WARNING" in offline.record("export", "incomplete")


def test_live_log_does_not_hide_a_durable_capture_failure(fake, agent):
    attach(fake, agent)
    fake.device_log.capture_error = "hard storage limit"
    assert "WARNING: incomplete durable log capture" in agent.log()
    assert json.loads(agent.log(as_json=True))["capture_error"] == "hard storage limit"


def test_cli_is_stdout_only_and_old_overlapping_commands_are_removed(fake, agent, tmp_path):
    attach(fake, agent)
    def run(*args):
        return cli.run(cli.parser().parse_args(list(args)), agent)
    run("record", "start", "flow")
    run("key", "back")
    assert run("record", "list") == "flow"
    assert "started=" in run("record", "list", "-L") and "disk=" in run("record", "list", "-L")
    assert "key" in run("record", "export", "flow")
    assert not fake.device_log.recordings["flow"].HasField("stopped_at_ms")
    document = json.loads(run("record", "export", "flow", "--logs", "--json"))
    assert document["recording"] == "flow"
    assert json.loads(run("history", "--from", "1", "--until", "2", "--limit", "1", "--json"))["steps"]
    for old in [("export",), ("history", "list"), ("history", "clean", "--all"),
                ("record", "export", "flow", "-o", str(tmp_path / "out.json")), ("release", "--export", "out.json")]:
        with pytest.raises(SystemExit) as error:
            cli.parser().parse_args(list(old))
        assert error.value.code == 2
    assert not list(tmp_path.glob("*.json"))
    run("record", "stop", "flow")
    run("release")
    assert saved(agent, "flow")["complete"]
    run("record", "clean", "flow")
    assert run("record", "list") == "no recordings"


@pytest.mark.parametrize(("action", "name", "kwargs"), [("stop", None, {}), ("export", None, {}), ("clean", None, {}),
    ("list", "x", {}), ("start", "x", {"long": True}), ("list", None, {"logs": True}),
    ("list", None, {"as_json": True}), ("list", None, {"long": 1})])
def test_record_controls_reject_ambiguous_names_or_flags_before_use(agent, action, name, kwargs):
    with pytest.raises(AgentError) as error:
        agent.record(action, name, **kwargs)
    assert error.value.exit_code == 2


def test_busy_start_keeps_acknowledged_protection_for_explicit_recovery(fake, agent):
    attach(fake, agent)
    agent.tap("id=save")
    active = agent.records.active(agent.session)
    with agent.records.lease(active["id"]), pytest.raises(AgentError, match="started but local intent"):
        agent.record("start", "blocked")
    assert "blocked" in fake.device_log.recordings
    assert len(fake.devices.commands) == 1
    agent.record("stop", "blocked")
    assert saved(agent, "blocked")["step_history_unavailable"]
    agent.record("clean", "blocked")


def test_new_devices_are_not_silently_added_and_active_recordings_cannot_be_cleaned(fake, agent):
    attach(fake, agent)
    agent.record("start", "fixed-set")
    recording = agent.client.log_recordings.list()[0]
    assert [r.serial for r in recording.ranges if not r.events] == ["first"]
    agent.attach("second")
    with pytest.raises(AgentError, match="not in this recording's start set"):
        agent.tap("id=save", device="second")
    assert not fake.devices.commands
    with pytest.raises(AgentError, match="stop the recording"):
        agent.record("clean", "fixed-set")
    agent.record("stop", "fixed-set")


@pytest.mark.anyio
async def test_mcp_and_cli_share_the_same_renderer_without_an_export_tool(fake, agent):
    attach(fake, agent)
    server = create_server(lambda _: agent, default_session="agent")
    async with Client(server, mode="legacy") as client:
        names = {tool.name for tool in (await client.list_tools()).tools}
        assert "export" not in names
        await client.call_tool("record", {"action": "start", "name": "flow"})
        cli.run(cli.parser().parse_args(["tap", "id=save"]), agent)
        response = await client.call_tool("record", {"action": "export", "name": "flow", "as_json": True})
        assert not response.is_error and isinstance(response.content[0], TextContent)
        document = json.loads(response.content[0].text)
        assert [s["action"] for s in document["steps"]] == ["tap"]
        response = await client.call_tool("history", {"as_json": True, "start": "1", "limit": 1})
        assert not response.is_error and isinstance(response.content[0], TextContent)
        assert json.loads(response.content[0].text)["steps"] == view(agent, start="1", limit=1)["steps"]
