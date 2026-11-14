"""Capability discovery is one authenticated, read-only RPC with forward-compatible models."""
from __future__ import annotations

import pytest

from tap_e2e import (
    Capability,
    CapabilityReason,
    CapabilityRequirement,
    CapabilityStatus,
    ServerError,
    TapClient,
    TapError,
)
from tap_e2e import _gen as pb
from tap_e2e.capabilities import _from_proto

from .conftest import TOKEN


def test_catalog_enum_matches_contract():
    assert {entry.name for entry in Capability if entry is not Capability.UNKNOWN} == {
        entry.name.removeprefix("CAPABILITY_") for entry in pb.CapabilityId.DESCRIPTOR.values
        if entry.name != "CAPABILITY_UNSPECIFIED"
    }


def test_models_preserve_all_statuses_and_unknown_future_entries():
    result = _from_proto(pb.GetDeviceCapabilitiesResponse(capabilities=[
        pb.DeviceCapability(id=pb.CAPABILITY_APP_SET_LOCALES, status=pb.CAPABILITY_STATUS_UNSUPPORTED,
                            reasons=[pb.CAPABILITY_REASON_REQUIRES_API], required_api=33, detail="Needs API 33"),
        pb.DeviceCapability(id=pb.CAPABILITY_ACCESSIBILITY_PAGE_DOWN, status=pb.CAPABILITY_STATUS_CONDITIONAL,
                            requirements=[pb.CAPABILITY_REQUIREMENT_TARGET_OFFERS_ACTION]),
        pb.DeviceCapability(id=pb.CAPABILITY_CLIPBOARD_READ, status=pb.CAPABILITY_STATUS_SUPPORTED),
        # Future numeric enum values, encoded as an actual newer server would send them.
        pb.DeviceCapability.FromString(b"\x08\xa9F\x10\xaaF\x18\xabF\x28\xacF"),
        pb.DeviceCapability.FromString(b"\x08\xadF\x10\x04"),
    ]))
    locales = result[Capability.APP_SET_LOCALES]
    assert locales.status is CapabilityStatus.UNSUPPORTED
    assert locales.required_api == 33
    assert locales.reasons == (CapabilityReason.REQUIRES_API,)
    assert result[Capability.ACCESSIBILITY_PAGE_DOWN].requirements == (CapabilityRequirement.TARGET_OFFERS_ACTION,)
    assert result[Capability.CLIPBOARD_READ].status is CapabilityStatus.SUPPORTED
    assert result[Capability.KEYBOARD_IME_ACTION].status is CapabilityStatus.UNKNOWN
    assert [entry.raw_id for entry in result.entries[-2:]] == [9001, 9005]
    future = result.entries[-2]
    assert future.id is Capability.UNKNOWN and future.status is CapabilityStatus.UNKNOWN
    assert future.reasons == (CapabilityReason.UNKNOWN,)
    assert future.requirements == (CapabilityRequirement.UNKNOWN,)
    with pytest.raises(KeyError):
        result[Capability.UNKNOWN]


def test_device_discovery_uses_owner_and_attachment_without_execute(fake):
    fake.devices.capabilities = pb.GetDeviceCapabilitiesResponse(capabilities=[
        pb.DeviceCapability(id=pb.CAPABILITY_APP_SET_LOCALES, status=pb.CAPABILITY_STATUS_SUPPORTED),
    ])
    with TapClient.create(fake.address, TOKEN) as client, client.connect("capabilities") as connection:
        device = connection.attach_device("emulator-5554")
        before = list(fake.devices.commands)
        assert device.capabilities()[Capability.APP_SET_LOCALES].status is CapabilityStatus.SUPPORTED
        assert fake.devices.commands == before
        request = fake.devices.capability_requests[-1]
        assert request.client_connection_id == connection.id
        assert request.attached_device_id == device.attached_device_id
        fake.devices.deny = True
        with pytest.raises(ServerError):
            device.capabilities()
        fake.devices.deny = False
        device.detach()
        calls = len(fake.devices.capability_requests)
        with pytest.raises(TapError, match="detached"):
            device.capabilities()
        assert len(fake.devices.capability_requests) == calls
