import time

import pytest

from tap import WaitTimeoutError

from conftest import fixture_id, launch


def test_waits_for_animation_to_end(tap_device):
    launch(tap_device, ".MotionActivity")
    status = tap_device.element(fixture_id("motion_status"))

    tap_device.element(fixture_id("motion_button")).tap()
    started = time.monotonic()
    tap_device.await_screen_stable(stable_for=0.5, timeout=10)
    waited = time.monotonic() - started

    # The box moves for 2 s; the explicit wait must outlive it without any status wait.
    assert status.text() == "Animation done"
    assert waited >= 2.0, f"returned after {waited:.2f}s, before the motion ended"


def test_screen_that_keeps_changing_times_out(tap_device):
    launch(tap_device, ".MotionActivity")
    ticker = tap_device.element(fixture_id("ticker_button"))
    ticker.tap()
    try:
        with pytest.raises(WaitTimeoutError) as failure:
            tap_device.await_screen_stable(stable_for=0.5, timeout=3)
        assert failure.value.last_observation == "SCREEN_CHANGING"
        assert failure.value.elapsed_ms >= 3000
    finally:
        ticker.tap()
    tap_device.await_screen_stable(timeout=5)
    assert tap_device.element(fixture_id("ticker_status")).text() == "Ticker stopped"
