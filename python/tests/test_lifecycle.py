from tap import text

from conftest import fixture_id, launch


def test_cold_launch_produces_a_new_process_and_survives_clear_data(tap_device):
    app = launch(tap_device)
    first = app.process()

    tap_device.element(fixture_id("fault_button")).tap()
    tap_device.wait(text("Fault taps: 1")).visible()

    app.force_stop()
    assert not app.is_running()
    second = app.cold_launch(".MainActivity")
    assert first != second
    # The in-process counter is gone with the old process.
    assert tap_device.element(fixture_id("fault_status")).text() == "Fault taps: 0"

    app.clear_data()
    assert not app.is_running()
    app.launch(".MainActivity")
    assert tap_device.element(fixture_id("view_button")).exists()

    assert tap_device.info().current_package == "com.company.tap.fixture"
