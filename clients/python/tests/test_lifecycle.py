# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false
import pytest  # type: ignore[import-not-found]
from conftest import PACKAGE, launch
from tap_e2e import CommandError, ErrorCode, WaitTimeoutError, desc, res, text


def test_cold_launch_produces_a_new_process_and_survives_clear_data(tap_device):
    app = launch(tap_device)
    first = app.process()

    app.element(res("fault_button")).tap()
    app.wait(text("Fault taps: 1")).visible()

    app.force_stop()
    assert not app.is_running()
    second = app.cold_launch(".MainActivity")
    assert first != second
    # The in-process counter is gone with the old process.
    assert app.element(res("fault_status")).text() == "Fault taps: 0"

    app.clear_data()
    assert not app.is_running()
    app.launch(".MainActivity")
    # launch does not wait for the window; the test does.
    app.wait(res("view_button")).visible()

    assert tap_device.info().current_package == "io.github.noamcohen48.tap.fixture"


def test_a_wait_on_a_crashed_app_says_it_crashed_once(tap_device):
    app = launch(tap_device, ".DyingActivity")
    app.wait(desc("crash_button")).visible().tap()

    with pytest.raises(CommandError) as failure:
        app.wait(res("never_there"), timeout=5).visible()
    assert failure.value.code is ErrorCode.AUT_CRASHED
    assert failure.value.detail == "WAIT_TIMEOUT"
    assert PACKAGE in str(failure.value) and "IllegalStateException" in str(failure.value)

    with pytest.raises(WaitTimeoutError):
        app.wait(res("never_there"), timeout=1).visible()
    app.force_stop()
