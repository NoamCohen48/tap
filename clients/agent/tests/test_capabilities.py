"""CLI and read-only MCP discovery share the SDK result without input or recorded intent."""
from __future__ import annotations

import json

import pytest
from mcp import Client
from mcp_types import TextContent
from tap_e2e import proto as pb

from tap_agent import cli
from tap_agent.mcp_server import create_server


def _report(fake):
    fake.devices.capabilities = pb.GetDeviceCapabilitiesResponse(capabilities=[
        pb.DeviceCapability(id=pb.CAPABILITY_APP_SET_LOCALES, status=pb.CAPABILITY_STATUS_UNSUPPORTED,
                            required_api=33, reasons=[pb.CAPABILITY_REASON_REQUIRES_API], detail="Requires API 33"),
        pb.DeviceCapability(id=pb.CAPABILITY_CLIPBOARD_READ, status=pb.CAPABILITY_STATUS_SUPPORTED),
    ])


def test_cli_text_and_json_share_sdk_and_do_not_record_steps(fake, agent):
    agent.attach("emulator-5554")
    _report(fake)
    commands = list(fake.devices.commands)
    parser = cli.parser()
    text = cli.run(parser.parse_args(["capabilities"]), agent)
    assert "APP_SET_LOCALES  UNSUPPORTED  Requires API 33" in text
    structured = json.loads(cli.run(parser.parse_args(["capabilities", "--json", "-d", "emulator-5554"]), agent))
    assert structured["entries"][0]["id"] == "APP_SET_LOCALES"
    assert structured["entries"][0]["required_api"] == 33
    assert fake.devices.commands == commands
    assert len(fake.devices.capability_requests) == 2
    assert not agent.records.list()


@pytest.mark.anyio
async def test_mcp_reports_identical_json_and_is_read_only(fake, agent):
    agent.attach("emulator-5554")
    _report(fake)
    expected = json.loads(agent.capabilities(as_json=True))
    commands = list(fake.devices.commands)
    async with Client(create_server(lambda _: agent)) as client:
        tools = await client.list_tools()
        capability = next(tool for tool in tools.tools if tool.name == "capabilities")
        assert capability.annotations is not None
        assert capability.annotations.read_only_hint is True
        result = await client.call_tool("capabilities", {})
        assert not result.is_error
        assert isinstance(result.content[0], TextContent)
        assert json.loads(result.content[0].text) == expected
    assert fake.devices.commands == commands
