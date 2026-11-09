"""Quarantined devices: unquarantine, attaching one to inspect it, and which serials a test gets."""
# pyright: reportAttributeAccessIssue=false, reportMissingImports=false

from __future__ import annotations

import pytest  # type: ignore[import-not-found]

from tap_e2e import DeviceBusyError, DeviceQuarantinedError, TapClient, UnquarantineResult
from tap_e2e import _gen as pb

from .conftest import TOKEN


@pytest.fixture
def client(fake):
    with TapClient.create(fake.address, TOKEN) as tap:
        yield tap


def test_unquarantine_reports_why_and_what_is_still_off(fake, client):
    fake.devices.quarantined["85e49002"] = "BOOT_CHANGED: rebooted"
    assert client.unquarantine("85e49002") == UnquarantineResult(
        was_quarantined=True,
        quarantine_reason="BOOT_CHANGED: rebooted",
        cleaned=["stopped the driver"],
        remaining=["device settings a session changed were not restored"],
    )
    assert client.unquarantine("85e49002").was_quarantined is False
    assert fake.devices.unquarantined == ["85e49002", "85e49002"]


def test_unquarantine_of_a_held_device_is_busy(fake, client):
    fake.devices.busy.add("85e49002")
    with pytest.raises(DeviceBusyError):
        client.unquarantine("85e49002")


def test_a_quarantined_device_attaches_only_to_inspect_it(fake, client):
    fake.devices.quarantined["85e49002"] = "BOOT_CHANGED: rebooted"
    connection = client.connect("inspect")
    try:
        with pytest.raises(DeviceQuarantinedError):
            connection.attach_device("85e49002")
        device = connection.attach_device("85e49002", inspect_quarantined=True)
        assert device.quarantine_reason == "BOOT_CHANGED: rebooted"
        assert fake.devices.attach_requests[-1].inspect_quarantined
        device.detach()
        plain = connection.attach_device("emulator-5554")
        assert plain.quarantine_reason is None
        assert not fake.devices.attach_requests[-1].HasField("inspect_quarantined")
        plain.detach()
    finally:
        connection.close()


def test_available_serials_end_with_quarantines_that_attaching_retries(fake, client):
    def entry(serial: str, state: int, retry: bool = False) -> pb.DeviceEntry:
        return pb.DeviceEntry(serial=serial, state=state, retry_on_attach=retry)

    fake.devices.listed = [
        entry("retry", pb.DEVICE_QUARANTINED, retry=True),
        entry("stuck", pb.DEVICE_QUARANTINED),
        entry("held", pb.DEVICE_LEASED),
        entry("free", pb.DEVICE_FREE),
    ]
    assert client.devices()[0].retry_on_attach is True
    connection = client.connect("pick")
    try:
        assert connection.available_serials() == ["free", "held", "retry"]
    finally:
        connection.close()
