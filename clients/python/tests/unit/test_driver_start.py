"""Startup policy is not an installation flag; provenance may be absent on older servers."""

from __future__ import annotations

import pytest  # type: ignore[import-not-found]

from tap_e2e import TapClient, Timeouts
from tap_e2e import _gen as pb
from tap_e2e.device import Device

from .conftest import TOKEN


def test_start_policy_is_forwarded_independently_of_installation(fake):
    with TapClient.create(fake.address, TOKEN) as client, client.connect("driver policy") as connection:
        automatic = connection.attach_device("phone")
        try:
            assert fake.devices.attach_requests[-1].driver_start == pb.DRIVER_START_AUTO
            assert automatic.driver_reused is None
        finally:
            automatic.detach()
        cold = connection.attach_device("phone", driver_start="cold", skip_driver_install=True)
        try:
            request = fake.devices.attach_requests[-1]
            assert request.driver_start == pb.DRIVER_START_COLD
            assert request.skip_driver_install
        finally:
            cold.detach()


def test_unknown_start_policy_is_rejected_before_rpc(fake):
    with TapClient.create(fake.address, TOKEN) as client, client.connect("driver policy") as connection:
        with pytest.raises(ValueError, match="driver_start"):
            connection.attach_device("phone", driver_start="warm")  # type: ignore[arg-type]
        assert fake.devices.attach_requests == []


@pytest.mark.parametrize("reused", [True, False])
def test_new_attachment_exposes_provenance_without_an_extra_query(fake, reused):
    with TapClient.create(fake.address, TOKEN) as client, client.connect("driver policy") as connection:
        device = Device(connection, pb.AttachResponse(serial="phone", driver_reused=reused), Timeouts())
        assert device.driver_reused is reused
        assert fake.devices.attach_requests == []
