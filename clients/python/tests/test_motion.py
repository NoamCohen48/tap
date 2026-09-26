# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false
import time

import pytest  # type: ignore[import-not-found]
from conftest import launch
from tap import WaitTimeoutError, res


def test_waits_for_animation_to_end(tap_device):
    launch(tap_device, ".MotionActivity")
    status = tap_device.element(res("motion_status"))

    tap_device.element(res("motion_button")).tap()
    started = time.monotonic()
    tap_device.await_animation_end(stable_for=0.5, timeout=10)
    waited = time.monotonic() - started

    # The box moves for 2 s; the explicit pixel wait must outlive it without any status wait.
    assert status.text() == "Animation done"
    assert waited >= 2.0, f"returned after {waited:.2f}s, before the motion ended"


def test_settles_after_the_hierarchy_stops_moving(tap_device):
    launch(tap_device, ".MotionActivity")
    status = tap_device.element(res("motion_status"))

    tap_device.element(res("motion_button")).tap()
    started = time.monotonic()
    tap_device.await_app_settled(stable_for=0.5, timeout=10)
    waited = time.monotonic() - started

    # The moving box changes its accessibility bounds every frame; no screenshots involved.
    assert status.text() == "Animation done"
    assert waited >= 2.0, f"returned after {waited:.2f}s, before the motion ended"


def test_screen_that_keeps_changing_times_out(tap_device):
    launch(tap_device, ".MotionActivity")
    ticker = tap_device.element(res("ticker_button"))
    ticker.tap()
    try:
        with pytest.raises(WaitTimeoutError) as failure:
            tap_device.await_app_settled(stable_for=0.5, timeout=3)
        assert failure.value.last_observation == "SCREEN_CHANGING"
        assert failure.value.elapsed_ms >= 3000
    finally:
        ticker.tap()
    tap_device.await_screen_stable(timeout=5)
    assert tap_device.element(res("ticker_status")).text() == "Ticker stopped"
