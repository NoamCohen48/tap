"""CLI/MCP bulk reads use the Python collection API, not diagnostic refs or count/at loops."""
# pyright: reportMissingImports=false

from __future__ import annotations

import json

import pytest
from mcp import Client
from mcp_types import TextContent
from tap_e2e import proto as pb
from tap_e2e import res

from tap_agent import AgentError, cli
from tap_agent.core import EXIT_FAILED, EXIT_USAGE
from tap_agent.mcp_server import create_server
from tap_agent.targets import parse_target


def _values():
    return pb.ElementSnapshotList(elements=[
        pb.ElementSnapshot(text="Email", checkable=True, checked=True, enabled=True, selected=True),
        pb.ElementSnapshot(text="SMS", checkable=True, enabled=False),
        pb.ElementSnapshot(text=""),
        pb.ElementSnapshot(),
        pb.ElementSnapshot(text="Email"),
        pb.ElementSnapshot(text="Name", hint="Name", showing_hint=True),
    ])


def _reply(command):
    assert command.WhichOneof("op") == "snapshots"
    return pb.CommandResult(snapshots=_values())


def _mcp_json(result):
    assert not result.is_error
    assert len(result.content) == 1 and isinstance(result.content[0], TextContent)
    return json.loads(result.content[0].text)


def test_bulk_state_and_text_reads_are_fresh_scoped_and_value_only(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = _reply
    before = len(fake.devices.commands)
    values = json.loads(agent.elements("id=option,pkg=com.shop", timeout=2.5))
    assert len(fake.devices.commands) == before + 1
    assert [(item["text"], item["checkable"], item["checked"], item["enabled"], item["selected"]) for item in values[:2]] == [
        ("Email", True, True, True, True), ("SMS", True, False, False, False),
    ]
    assert all("ref" not in item and "selector" not in item for item in values)
    assert values[-1]["showing_hint"] and values[-1]["hint"] == "Name"
    command = fake.devices.commands[-1]
    assert command.timeout_ms == 2500
    assert command.snapshots.selector == res("option")._in_package("com.shop").to_proto()

    assert json.loads(agent.elements("id=option", texts=True)) == ["Email", "SMS", "", None, "Email", "Name"]
    assert len(fake.devices.commands) == before + 2
    assert fake.devices.commands[-1].snapshots.selector == res("option").to_proto()

    fake.devices.responder = lambda _: pb.CommandResult(snapshots=pb.ElementSnapshotList())
    assert json.loads(agent.elements("id=option")) == []
    assert json.loads(agent.elements("id=option", texts=True)) == []
    assert len(fake.devices.commands) == before + 4
    assert not fake.devices.snapshot_requests
    assert not agent.records.list()  # Reads must not acquire refs or start a step history.


def test_rich_collection_selector_reaches_the_driver_unchanged(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = _reply
    target = '{"id":"title","ancestor":{"id":"products"},"pkg":"com.shop"}'
    agent.elements(target)
    parsed = parse_target(target)
    assert parsed.selector is not None
    assert fake.devices.commands[-1].snapshots.selector == parsed.selector.to_proto()


@pytest.mark.parametrize("target", [
    "@e3", "e3", "id=option,index=0", '{"id":"option","first":true}',
    '{"id":"option","index":1}', "not a selector",
])
def test_invalid_collection_targets_fail_before_any_connection(agent, target):
    with pytest.raises(AgentError) as failure:
        agent.elements(target)
    assert failure.value.exit_code == EXIT_USAGE


@pytest.mark.parametrize("timeout", [121, 0, -1, float("nan"), float("inf"), True])
def test_invalid_budgets_fail_before_any_connection(agent, timeout):
    with pytest.raises(AgentError) as failure:
        agent.elements("id=option", timeout=timeout)
    assert failure.value.exit_code == EXIT_USAGE


@pytest.mark.parametrize("code", [pb.ERR_STALE_BEFORE_INPUT, pb.ERR_PAYLOAD_TOO_LARGE])
@pytest.mark.parametrize("texts", [False, True])
def test_read_failures_propagate_without_retry_or_partial_values(fake, agent, code, texts):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda _: pb.CommandResult(error=pb.Error(code=code, message="bulk read failed"))
    before = len(fake.devices.commands)
    with pytest.raises(AgentError, match="bulk read failed") as failure:
        agent.elements("id=option", texts=texts)
    assert failure.value.exit_code == EXIT_FAILED
    assert len(fake.devices.commands) == before + 1


def test_cli_routes_state_text_projection_device_and_budget(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = _reply
    parser = cli.parser()
    default = cli.run(parser.parse_args(["elements", "id=option"]), agent)
    assert json.loads(default)[0]["checked"] is True
    projected = cli.run(parser.parse_args([
        "elements", "id=option,pkg=com.shop", "--texts", "--device", "emulator-5554", "--timeout", "3s",
    ]), agent)
    assert json.loads(projected) == ["Email", "SMS", "", None, "Email", "Name"]
    assert len(fake.devices.commands) == 2
    assert fake.devices.commands[-1].timeout_ms == 3000
    with pytest.raises(SystemExit) as failure:
        parser.parse_args(["elements", "id=option", "--timeout", "121s"])
    assert failure.value.code == EXIT_USAGE


@pytest.mark.anyio
async def test_mcp_tool_has_read_only_schema_and_forwards_the_same_options(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = _reply
    async with Client(create_server(lambda _: agent), mode="legacy") as client:
        tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        tool = tools["elements"]
        assert tool.annotations.read_only_hint is True
        fields = tool.input_schema["properties"]
        assert fields["texts"]["default"] is False
        budget = next(branch for branch in fields["timeout_seconds"]["anyOf"] if branch.get("type") == "number")
        assert budget["maximum"] == 120 and budget["minimum"] == .001
        assert "not refs" in tool.description or "no refs" in tool.description
        values = _mcp_json(await client.call_tool("elements", {"target": "id=option"}))
        assert values[0]["checked"] is True
        assert all("ref" not in item for item in values)
        before = len(fake.devices.commands)
        values = _mcp_json(await client.call_tool("elements", {
            "target": "id=option,pkg=com.shop", "texts": True,
            "timeout_seconds": 4, "device": "emulator-5554", "session": "agent",
        }))
        assert values == ["Email", "SMS", "", None, "Email", "Name"]
        assert len(fake.devices.commands) == before + 1
        assert fake.devices.commands[-1].timeout_ms == 4000
        assert fake.devices.commands[-1].snapshots.selector == res("option")._in_package("com.shop").to_proto()
        assert not fake.devices.snapshot_requests


@pytest.mark.anyio
async def test_mcp_rejects_refs_and_picks_and_reports_bulk_failures(fake, agent):
    agent.attach("emulator-5554")
    async with Client(create_server(lambda _: agent), mode="legacy") as client:
        before = len(fake.devices.commands)
        for target in ("@e7", "id=option,index=0"):
            result = await client.call_tool("elements", {"target": target})
            assert result.is_error
            assert "invalid arguments" in result.content[0].text
        assert len(fake.devices.commands) == before
        fake.devices.responder = lambda _: pb.CommandResult(error=pb.Error(
            code=pb.ERR_PAYLOAD_TOO_LARGE, message="bulk overflow",
        ))
        result = await client.call_tool("elements", {"target": "id=option", "texts": True})
        assert result.is_error
        assert "bulk overflow" in result.content[0].text
        assert len(fake.devices.commands) == before + 1
