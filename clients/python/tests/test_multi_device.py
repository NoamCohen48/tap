"""Two roles acquired all-or-none; skipped when tap_serials lists fewer devices. Devices are
independent sessions, so per-device work runs on separate threads and a failure on one does
not disturb the other's session."""
from concurrent.futures import ThreadPoolExecutor

import pytest

from tap import text

from conftest import fixture_id, launch


@pytest.mark.tap_devices("left", "right")
def test_drives_two_devices_concurrently(tap_devices):
    def drive(role: str) -> str:
        device = tap_devices[role]
        launch(device)
        device.element(fixture_id("view_button")).tap()
        device.wait(text("View tapped")).visible()
        return device.info().model

    with ThreadPoolExecutor(max_workers=2) as pool:
        models = list(pool.map(drive, ["left", "right"]))
    assert len(models) == 2
