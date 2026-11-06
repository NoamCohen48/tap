"""SDK query/state adapters, rich selectors, and frontend budget parity."""
# pyright: reportMissingImports=false

from __future__ import annotations

import json

import pytest
from mcp import Client
from mcp_types import TextContent
from tap_e2e import MatchMode, Selector, any_of, res, text
from tap_e2e import proto as pb

from tap_agent import AgentError, cli
from tap_agent.core import EXIT_USAGE, WAIT_STATES
from tap_agent.mcp_server import create_server
from tap_agent.targets import UsageError, parse_duration, parse_target


def _selector(spec: str) -> Selector:
    parsed = parse_target(spec)
    assert parsed.selector is not None
    return parsed.selector


def test_rich_selectors_preserve_sdk_semantics():
    assert _selector(r"text:regex=Order \d+,enabled=true,checked=false").to_proto() == text(r"Order \d+", MatchMode.REGEX).enabled().checked(False).to_proto()
    spec = {"any": [{"text": "Save"}, {"text": {"value": "Submit", "mode": "starts_with"}}],
            "enabled": True, "ancestor": {"id": "dialog"}, "index": 1}
    expected = any_of(text("Save"), text("Submit", MatchMode.STARTS_WITH)).enabled().has_ancestor(res("dialog")).at(1)
    assert _selector(json.dumps(spec)).to_proto() == expected.to_proto()
    assert _selector('{"pkg":"com.example","first":true}').to_proto().WhichOneof("pick") == "first"
    assert _selector("text:ends_with=end").to_proto() == text("end", MatchMode.ENDS_WITH).to_proto()


@pytest.mark.parametrize("spec", [
    '{"text":"a","text":"b"}', '{"any":[{"text":"a"}]}', '{"enabled":1}',
    '{"text":{"value":"x","mode":"bogus"}}', '{"index":0}',
    '{"text":"x","first":true,"index":0}', '{"ancestor":{"id":"x","index":0}}',
    '{"text":"x","index":true}', 'enabled=maybe', 'id:regex=x',
    'text=x,pkg=', 'text=x,index=2147483648', 'text=x,pkg=a,pkg=b',
])
def test_invalid_selector_shapes_are_usage_errors(spec):
    with pytest.raises(UsageError):
        parse_target(spec)


def test_selector_bounds_are_checked_before_rpc():
    for spec in (json.dumps({"all": [{"text": str(i)} for i in range(256)]}),
                 json.dumps({"text": "x" * 1025}), "text=" + "x" * 65536):
        with pytest.raises(UsageError):
            parse_target(spec)
    nested = {"text": "leaf"}
    for _ in range(33):
        nested = {"ancestor": nested}
    with pytest.raises(UsageError):
        parse_target(json.dumps(nested))
    for bad in (float("nan"), float("inf"), True):
        with pytest.raises(UsageError):
            parse_duration(bad)


def test_queries_and_property_waits_use_sdk_operations(fake, agent):
    agent.attach("emulator-5554")

    def respond(command):
        if command.HasField("exists"):
            return pb.CommandResult(bool=True)
        if command.HasField("count"):
            return pb.CommandResult(count=3)
        if command.HasField("snapshot"):
            return pb.CommandResult(snapshot=pb.ElementSnapshot(text="Saved", enabled=True, checked=True, focused=True))
        return None

    fake.devices.responder = respond
    assert agent.exists("text=Saved") == "true"
    assert agent.count("text=Saved") == "3"
    state = json.loads(agent.inspect("text=Saved", timeout=4))
    assert state["text"] == "Saved" and state["enabled"] and state["checked"]
    assert fake.devices.commands[-1].timeout_ms == 4000
    for condition in ("enabled", "checked", "focused"):
        assert condition in agent.wait("text=Saved", state=condition)
    agent.wait("text=Saved", state="text", value="Saved")
    agent.wait("text=Saved", state="text-contains", value="ave")
    agent.wait("text=Saved", state="count", value=3)
    assert all(command.WhichOneof("op") in {"exists", "count", "snapshot"} for command in fake.devices.commands)


@pytest.mark.parametrize("options", [
    {"state": "count", "value": True}, {"state": "count", "value": -1},
    {"state": "text"}, {"state": "checked", "value": "yes"},
    {"state": "unknown"}, {"timeout": 121}, {"timeout": float("nan")},
])
def test_invalid_waits_do_not_contact_a_device(agent, options):
    with pytest.raises(AgentError) as error:
        agent.wait("text=OK", **options)
    assert error.value.exit_code == EXIT_USAGE


@pytest.mark.parametrize("method,args", [
    ("wait", ("text=OK",)), ("settle", ()), ("toast", ()), ("permission", ()),
    ("notification", ()), ("inspect", ("text=OK",)), ("tap", ("text=OK",)),
])
def test_protocol_budget_rejected_before_connection(agent, method, args):
    with pytest.raises(AgentError, match="120 seconds") as error:
        getattr(agent, method)(*args, timeout=600)
    assert error.value.exit_code == EXIT_USAGE


def test_cli_routes_queries_and_state_values(fake, agent):
    agent.attach("emulator-5554")
    fake.devices.responder = lambda command: pb.CommandResult(count=2) if command.HasField("count") else None
    parser = cli.parser()
    assert cli.run(parser.parse_args(["count", "text=Item", "--timeout", "2s"]), agent) == "2"
    assert "count" in cli.run(parser.parse_args(["wait", "text=Item", "--state", "count", "--value", "2"]), agent)
    for verb in ("wait", "settle", "toast", "permission", "notification", "tap"):
        args = [verb] + (["text=OK"] if verb in ("wait", "tap") else []) + ["--timeout", "121s"]
        with pytest.raises(SystemExit) as error:
            parser.parse_args(args)
        assert error.value.code == 2


@pytest.mark.anyio
async def test_mcp_queries_and_budget_schema_match_core(fake, agent):
    agent.attach("emulator-5554")
    def respond(command):
        if command.HasField("exists"):
            return pb.CommandResult(bool=True)
        if command.HasField("device_info"):
            return pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.example"))
        return None

    fake.devices.responder = respond
    async with Client(create_server(lambda _: agent), mode="legacy") as client:
        tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        for name in ("wait_for", "settle", "await_toast", "notification", "permission"):
            assert tools[name].input_schema["properties"]["timeout_seconds"]["maximum"] == 120
        assert set(tools["wait_for"].input_schema["properties"]["state"]["enum"]) == set(WAIT_STATES)
        result = await client.call_tool("exists", {"target": "enabled=true"})
        assert not result.is_error
        assert any(isinstance(part, TextContent) and part.text == "true" for part in result.content)
        # Runtime propagation is covered separately from schema presence.
        settled = await client.call_tool("settle", {"timeout_seconds": 3})
        assert not settled.is_error
        waits = [command for command in fake.devices.commands if command.HasField("wait_screen_stable")]
        assert waits and waits[-1].timeout_ms == 3000
