# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false
from conftest import PACKAGE, launch
from tap_e2e import WaitTimeoutError

SYSTEM_UI = "com.android.systemui"


def test_opens_notifications_and_quick_settings_and_back_closes_them(tap_device):
    """Each panel takes the focus from the app, and Back gives it back. From quick settings,
    newer Android (API 34) goes back to the notification shade first, so a second Back may be
    needed. The next panel opens only once the last one has finished closing: a panel action
    sent while the shade is still animating can be accepted and ignored (seen on a Samsung API 29)."""
    launch(tap_device)
    focused = lambda: tap_device.info().current_package  # noqa: E731
    for name, open_panel in (
        ("notifications", tap_device.open_notifications),
        ("quick settings", tap_device.open_quick_settings),
    ):
        open_panel()
        tap_device.await_until(f"{name} focused", lambda: focused() == SYSTEM_UI, observe=focused)
        app_focused = lambda: focused() == PACKAGE  # noqa: E731
        tap_device.press_back()
        try:
            tap_device.await_until(f"the app focused after closing {name}", app_focused, timeout=3, observe=focused)
        except WaitTimeoutError:
            tap_device.press_back()
            tap_device.await_until(f"the app focused after closing {name}", app_focused, observe=focused)
        # The panel is still sliding away: an action sent now may be accepted and ignored.
        tap_device.await_animation_end()
