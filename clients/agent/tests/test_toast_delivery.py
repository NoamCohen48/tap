"""Historical toasts reach the next observation, even across CLI/MCP Agent instances."""
# pyright: reportMissingImports=false
import json

import pytest
from mcp import Client
from tap_e2e import _gen as pb

from tap_agent import Agent, cli
from tap_agent.mcp_server import create_server
from tap_agent.step_capture import category


def responder(command):
    if command.HasField("read_toast_history"):
        query = command.read_toast_history
        reset = bool(query.stream_id and query.stream_id != "one")
        after = 0 if reset else query.after_sequence
        return pb.CommandResult(toast_history=pb.ToastHistory(
            stream_id="one", next_sequence=2, oldest_sequence=1, reset=reset, gap=reset,
            events=[pb.ToastEvent(sequence=i, at_epoch_ms=i * 1000,
                    toast=pb.Toast(text=f"Saved {i}", package_name="com.shop")) for i in (1, 2) if i > after],
        ))
    if command.HasField("device_info"):
        return pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.shop"))
    return None


def test_snapshot_delivers_once_across_instances_and_history_does_not_consume(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = responder
    assert "Saved 1" in agent.toast()
    screen = agent.snapshot()
    assert "history, not current UI" in screen and "Saved 1" in screen and "Saved 2" in screen
    resumed = Agent("agent", client=agent.client, out_dir=agent.out_dir)
    assert "Saved" not in resumed.snapshot()
    assert "Saved 1" in resumed.toast()
    assert json.loads(resumed.snapshot(as_json=True))["toast_events"]["events"] == []


def test_events_command_is_not_exposed():
    assert not hasattr(Agent, "events")
    with pytest.raises(SystemExit) as failure:
        cli.parser().parse_args(["events"])
    assert failure.value.code == 2


def test_explicit_cursor_does_not_consume_and_candidates_stays_json(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = responder
    assert "Saved 2" in agent.toast(after_seq=1, stream="one")
    data = json.loads(agent.snapshot(as_json=True))
    assert [e["sequence"] for e in data["toast_events"]["events"]] == [1, 2]
    assert "nodes" in data
    assert json.loads(agent.snapshot(as_json=True))["toast_events"]["events"] == []


def test_settle_acknowledges_but_cli_toast_history_does_not(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = responder
    assert "Saved 1" in agent.settle()
    assert all(c.WhichOneof("op") in ("device_info", "wait_screen_stable", "read_toast_history") for c in fake.devices.commands)
    args = cli.parser().parse_args(["toast", "--json"])
    assert len(json.loads(cli.run(args, agent))["events"]) == 2
    assert "Saved" not in agent.snapshot()
    assert category("toast", {"wait": False}) is None
    assert category("toast", {"wait": True}) == "wait"
    assert category("notification", {"action": None, "wait": False}) is None
    assert category("notification", {"action": None, "wait": True}) == "wait"


def test_devices_and_new_streams_do_not_share_acknowledgements(fake, agent):
    agent.attach("first")
    agent.attach("second")
    fake.devices.responder = responder
    assert "Saved 1" in agent.snapshot("first")
    assert "Saved 1" in agent.snapshot("second")
    assert "Saved" not in agent.snapshot("first")

    def restarted(command):
        result = responder(command)
        if command.HasField("read_toast_history"):
            assert result is not None
            result.toast_history.stream_id = "new"
            result.toast_history.reset = True
            result.toast_history.gap = True
            result.toast_history.events.add(sequence=1, at_epoch_ms=9000,
                                           toast=pb.Toast(text="After restart", package_name="com.shop"))
        return result
    fake.devices.responder = restarted
    output = agent.snapshot("first")
    assert "stream reset" in output and "After restart" in output


def test_inbox_failure_is_explicit_and_preserves_successful_snapshot(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda _: pb.CommandResult(error=pb.Error(code=pb.ERR_UNSUPPORTED))
    assert "inbox unavailable (cursor not advanced)" in agent.snapshot()
    data = json.loads(agent.snapshot(as_json=True))
    assert "toast_events_error" in data and "nodes" in data
    fake.devices.responder = responder
    assert "Saved 1" in agent.snapshot()


@pytest.mark.anyio
async def test_mcp_snapshot_and_cli_snapshot_share_delivery(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = responder
    async with Client(create_server(lambda _: agent), mode="legacy") as client:
        tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        assert "events" not in tools
        assert tools["snapshot"].annotations.read_only_hint is False
        assert tools["settle"].annotations.read_only_hint is False
        result = await client.call_tool("snapshot", {"as_json": True})
        assert not result.is_error
        assert len(json.loads(result.content[0].text)["toast_events"]["events"]) == 2
    assert "Saved" not in agent.snapshot()
