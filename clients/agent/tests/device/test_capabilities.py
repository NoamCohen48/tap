"""Opt-in capability discovery on a real device, without an AUT, input, settings changes or reboot.

TAP_AGENT_DEVICE_SERIAL=85e49002 pytest clients/agent/tests/device/test_capabilities.py -s
A daemon built with capability discovery must already be running (tap start).
"""
# pyright: reportMissingImports=false
from __future__ import annotations

import json
import os
import subprocess
import sys
import uuid

import anyio
import pytest
from mcp import Client
from mcp_types import TextContent
from tap_e2e import (
    Capability,
    CapabilityReason,
    CapabilityRequirement,
    CapabilityStatus,
    CommandError,
    ErrorCode,
    FailureReason,
    ServerError,
    TapClient,
    res,
)
from tap_e2e import proto as pb

from tap_agent import Agent
from tap_agent.mcp_server import create_server

SERIAL = os.environ.get("TAP_AGENT_DEVICE_SERIAL")
pytestmark = pytest.mark.skipif(not SERIAL, reason="set TAP_AGENT_DEVICE_SERIAL explicitly; running capability-enabled daemon required")

# Configuration values, not volatile foreground/keyboard state or the system clock.
SETTINGS = (
    "auto_rotate", "animations_enabled", "dark_mode", "font_scale", "density_dpi",
    "airplane_mode", "wifi_enabled", "mobile_data_enabled", "system_locales",
    "stay_awake", "high_contrast_text", "color_inversion", "bold_text",
)


def test_capabilities_on_real_device_preserve_state_and_send_no_driver_command(tmp_path):
    assert SERIAL is not None
    name = f"capabilities-device-{uuid.uuid4().hex}"
    with TapClient.create() as client, client.connect(name, hold=120) as connection:
        with connection.attach_device(SERIAL) as device:
            agent = Agent(name, client=client, out_dir=tmp_path / "agent")

            def info():
                result = client.device_stub.Execute(pb.ExecuteRequest(
                    client_connection_id=connection.id,
                    attached_device_id=device.attached_device_id,
                    command=pb.Command(device_info=pb.DeviceInfoQuery()),
                ), timeout=30).result
                assert result.HasField("device_info")
                return result

            before = info()
            events_before = connection.event_log()
            capabilities = device.capabilities()
            expected_ids = set(Capability) - {Capability.UNKNOWN}
            assert {entry.id for entry in capabilities.entries} == expected_ids
            assert [entry.raw_id for entry in capabilities.entries] == sorted({entry.raw_id for entry in capabilities.entries})
            assert all(entry.detail for entry in capabilities.entries)
            assert capabilities[Capability.DEVICE_INFO].status is CapabilityStatus.SUPPORTED
            assert capabilities[Capability.CLIPBOARD_READ].status is CapabilityStatus.SUPPORTED
            assert CapabilityRequirement.TARGET_OFFERS_ACTION in capabilities[Capability.ACCESSIBILITY_PAGE_DOWN].requirements

            api = before.device_info.api_level
            for entry in capabilities.entries:
                if entry.required_api is not None and api < entry.required_api:
                    assert entry.status is CapabilityStatus.UNSUPPORTED, entry
                    assert CapabilityReason.REQUIRES_API in entry.reasons, entry
                elif entry.required_api is not None:
                    assert CapabilityReason.REQUIRES_API not in entry.reasons, entry
            assert capabilities[Capability.ACCESSIBILITY_PAGE_DOWN].status is (
                CapabilityStatus.CONDITIONAL if api >= 29 else CapabilityStatus.UNSUPPORTED
            )
            assert capabilities[Capability.APP_SET_LOCALES].status is (
                CapabilityStatus.SUPPORTED if api >= 33 else CapabilityStatus.UNSUPPORTED
            )

            expected = json.loads(agent.capabilities(as_json=True))
            cli = subprocess.run(
                [sys.executable, "-m", "tap_agent", "capabilities", "--json", "--session", name, "--device", SERIAL],
                check=True, capture_output=True, text=True, timeout=45,
            )
            assert json.loads(cli.stdout) == expected

            async def mcp_report():
                async with Client(create_server(lambda _: agent)) as mcp:
                    result = await mcp.call_tool("capabilities", {"session": name, "device": SERIAL})
                    assert not result.is_error
                    assert isinstance(result.content[0], TextContent)
                    return json.loads(result.content[0].text)

            assert anyio.run(mcp_report) == expected
            assert device.capabilities() == capabilities
            after = info()
            # Only the two explicit info reads may consume driver request IDs. CLI/MCP resume
            # the held attachment, so this also catches hidden per-adapter device queries/setup.
            assert after.request_id == before.request_id + 1
            assert after.session_generation == before.session_generation
            for field in SETTINGS:
                assert getattr(after.device_info, field) == getattr(before.device_info, field), field
            assert connection.event_log() == events_before

            # On older devices, verify a read-only host API guard and a driver action's
            # pre-input refusal agree with discovery. The target does not have to exist:
            # perform_ime_action checks Android API support before resolving or sending input.
            if api < 33:
                with pytest.raises(ServerError) as locales:
                    device.app("android").locales()
                assert locales.value.reason is FailureReason.UNSUPPORTED_API
            if api < 30:
                with pytest.raises(CommandError) as ime:
                    device.screen.element(res("__tap_capability_probe_missing__")).ime_action()
                assert ime.value.code is ErrorCode.UNSUPPORTED
                assert ime.value.detail == "REQUIRES_API_30"
            final = info()
            for field in SETTINGS:
                assert getattr(final.device_info, field) == getattr(before.device_info, field), field

            (tmp_path / "capabilities.json").write_text(json.dumps(expected, indent=2) + "\n")
            print(f"{SERIAL}: {before.device_info.manufacturer} {before.device_info.model}, API {api}")
            print(f"{len(capabilities.entries)} capabilities; SDK/CLI/MCP reports agree")
            print(f"Driver request IDs across discovery: {before.request_id} -> {after.request_id} (only explicit info reads)")
            for key in (Capability.KEYBOARD_IME_ACTION, Capability.APP_SET_LOCALES,
                        Capability.DEVICE_SET_BOLD_TEXT, Capability.RECORD_AUDIO_OUTPUT,
                        Capability.RECORD_AUDIO_PLAYBACK, Capability.ACCESSIBILITY_PAGE_DOWN):
                print(f"{key.name}: {capabilities[key].status.name}")
            agent.close()
            assert device.detach() is None, "device cleanup was not clean"
        assert connection.attached_devices() == []
        print("Device detached cleanly; settings unchanged")
