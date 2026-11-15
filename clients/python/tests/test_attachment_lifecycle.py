"""Attach prepares an awake or sleeping device without retaining the previous session."""

import pytest


@pytest.mark.parametrize("start_asleep", [False, True], ids=["already-awake", "asleep"])
def test_reattach_prepares_screen(tap_connection, tap_config, start_asleep):
    for serial in sorted(tap_config.serials):
        device = tap_connection.attach_device(serial)
        try:
            if start_asleep:
                device.sleep()
                device.await_until("the screen off", lambda device=device: not device.info().screen_on)
            else:
                assert device.info().screen_on
        except BaseException:
            device.wake()
            raise
        finally:
            assert device.detach() is None

        device = tap_connection.attach_device(serial)
        try:
            device.await_until("the reattached screen on", lambda device=device: device.info().screen_on)
        finally:
            assert device.detach() is None
