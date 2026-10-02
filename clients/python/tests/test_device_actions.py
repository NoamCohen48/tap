# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false
import pytest  # type: ignore[import-not-found]
from conftest import PACKAGE, launch
from tap_e2e import (
    AppLifecycleError,
    CommandError,
    ErrorCode,
    FailureReason,
    LocationAccuracy,
    Long,
    Orientation,
    PermissionChoice,
    ServerError,
    StandardAction,
    Toast,
    res,
    text,
    text_contains,
)


def test_deep_link_then_background_and_foreground_returns_to_it(tap_device):
    """A deep link opens inside the app; Home sends the app away and ``foreground`` brings it
    back where it was: the link screen, not a new launcher activity on top."""
    app = launch(tap_device)
    app.force_stop()
    app.foreground()
    app.wait(res("view_button")).visible()

    assert app.open_link("tapfixture://link/orders/42") == f"{PACKAGE}/.LinkActivity"
    app.wait(text("Link: tapfixture://link/orders/42")).visible()
    with pytest.raises(AppLifecycleError):
        app.open_link("tapfixture://nowhere/1")

    app.background()
    focused = lambda: tap_device.info().current_package  # noqa: E731
    tap_device.await_until("the app in the background", lambda: focused() != PACKAGE, observe=focused)
    app.foreground()
    app.wait(text("Link: tapfixture://link/orders/42")).visible()


def test_gestures_reach_the_app_as_gestures(tap_device):
    app = launch(tap_device, ".GestureActivity")
    app.element(res("double_tap_target")).double_tap()
    app.wait(text("Double taps: 1")).visible()
    app.element(res("drag_source")).drag_to(res("drop_target"))
    app.wait(text("Dropped Card")).visible()
    app.element(res("pinch_target")).pinch_open()
    app.wait(text("Zoomed in")).visible()
    app.element(res("pinch_target")).pinch_close()
    app.wait(text("Zoomed out")).visible()


def test_permission_prompt_is_answered_by_choice(tap_device):
    app = launch(tap_device)
    app.clear_data()  # resets the permission, so the dialog shows on every run
    app.launch(".PermissionActivity")
    app.element(res("request_camera_permission")).tap()
    prompt = tap_device.await_permission_prompt()
    allow = PermissionChoice.ALLOW_FOREGROUND_ONLY if PermissionChoice.ALLOW_FOREGROUND_ONLY in prompt.choices else PermissionChoice.ALLOW
    assert allow in prompt.choices and PermissionChoice.DENY in prompt.choices, prompt
    tap_device.choose_permission(allow)
    app.wait(text("Camera granted")).visible()


def test_sleep_wake_and_dismiss_the_keyguard(tap_device):
    app = launch(tap_device)
    if tap_device.info().keyguard_secure:
        pytest.skip("the device has a secure lock screen")
    tap_device.sleep()
    tap_device.await_until("the screen off", lambda: not tap_device.info().screen_on)
    tap_device.wake()
    tap_device.await_until("the screen on", lambda: tap_device.info().screen_on)
    tap_device.dismiss_keyguard()
    tap_device.await_until("the keyguard gone", lambda: not tap_device.info().keyguard_locked, observe=lambda: str(tap_device.info()))
    app.wait(res("view_button")).visible()


def test_rotation_holds_until_detach_then_is_restored(tap_client, tap_config):
    """The session freezes the rotation for as long as it holds the device and restores the
    device's own rotation settings when it detaches."""
    connection = tap_client.connect("rotation")
    try:
        serial = (tap_config.serials or connection.available_serials())[0]
        with connection.attach_device(serial) as device:
            launch(device)
            before = device.info()
            turned = Orientation.LANDSCAPE if before.orientation is Orientation.PORTRAIT else Orientation.PORTRAIT
            device.set_orientation(turned)
            device.await_until(f"the display in {turned.name}", lambda: device.info().orientation is turned, observe=lambda: str(device.info()))
        with connection.attach_device(serial) as device:
            # With auto-rotate on, the sensor (how the phone lies) picks the rotation, so only the
            # setting itself can be compared; with it off, the frozen rotation comes back.
            def restored() -> bool:
                now = device.info()
                return now.auto_rotate == before.auto_rotate and (before.auto_rotate or now.display_rotation is before.display_rotation)

            expected = "auto-rotate back on" if before.auto_rotate else f"the display back at {before.display_rotation.name}"
            device.await_until(expected, restored, observe=lambda: str(device.info()))
    finally:
        connection.close()


def test_conditions_hold_until_detach_then_are_restored(tap_client, tap_config):
    """Animations, dark mode, font scale, density and (API 33+) the app's language hold for the
    session, read back in ``info()``, reach the app's configuration, and come back on detach."""
    connection = tap_client.connect("conditions")
    try:
        serial = (tap_config.serials or connection.available_serials())[0]
        with connection.attach_device(serial) as device:
            app = launch(device)
            before = device.info()
            per_app_locales = before.api_level >= 33
            locales_before = app.locales() if per_app_locales else None
            density = 360 if before.density_dpi == 320 else 320
            device.set_animations(False)
            # Some devices lock the day/night mode (Samsung's One UI): the change is refused, never faked.
            try:
                device.set_dark_mode(not before.dark_mode)
                dark = not before.dark_mode
            except ServerError as refused:
                assert refused.reason is FailureReason.DEVICE_SETTING and "locks the day/night mode" in str(refused)
                dark = before.dark_mode
            device.set_font_scale(1.3)
            device.set_density(density)
            if per_app_locales:
                app.set_locales(["fr-fr"])
                assert app.locales() == ["fr-FR"]
            else:
                with pytest.raises(ServerError) as refused:
                    app.set_locales(["fr-FR"])
                assert refused.value.reason is FailureReason.UNSUPPORTED_API
            now = device.info()
            assert (now.animations_enabled, now.dark_mode, now.font_scale, now.density_dpi) == (False, dark, 1.3, density)
            app.launch(".FormActivity")
            app.wait(text_contains(f"night={str(dark).lower()} ")).visible()
            app.wait(text_contains(f" fontScale=1.3 density={density} animators=false")).visible()
            if per_app_locales:
                app.wait(text_contains(" locale=fr-FR ")).visible()
        with connection.attach_device(serial) as device:
            now = device.info()
            assert (now.animations_enabled, now.dark_mode, now.font_scale, now.density_dpi) == (
                before.animations_enabled,
                before.dark_mode,
                before.font_scale,
                before.density_dpi,
            )
            if per_app_locales:
                assert device.app(PACKAGE).locales() == locales_before
    finally:
        connection.close()


def test_launch_extras_reach_the_app_typed(tap_device):
    app = launch(tap_device)
    app.launch(".FormActivity", extras={"query": "red shoes", "flag": True, "count": 3, "id": Long(9_000_000_000), "ratio": 0.5})
    app.wait(text("Extras: query=red shoes flag=true count=3 id=9000000000 ratio=0.5")).visible()


def test_revoked_permission_is_denied_to_the_app(tap_device):
    app = launch(tap_device)
    app.grant_permission("android.permission.CAMERA")
    assert app.is_permission_granted("android.permission.CAMERA")
    app.launch(".FormActivity")
    app.wait(text("Camera: granted")).visible()
    app.revoke_permission("android.permission.CAMERA")
    assert not app.is_permission_granted("android.permission.CAMERA")
    app.cold_launch(".FormActivity")
    app.wait(text("Camera: denied")).visible()


def test_keyboard_clipboard_and_toast(tap_device):
    app = launch(tap_device, ".FormActivity")
    field = app.element(res("search_field"))
    field.tap()
    tap_device.await_until("the keyboard shown", tap_device.keyboard_shown)
    field.set_text("shoes")
    if tap_device.info().api_level >= 30:
        field.ime_action()
        app.wait(text("Searched: shoes")).visible()
    tap_device.hide_keyboard()
    tap_device.await_until("the keyboard hidden", lambda: not tap_device.keyboard_shown())

    tap_device.set_clipboard("tap clip")
    app.element(res("paste_button")).tap()
    app.wait(text("Pasted: tap clip")).visible()
    app.element(res("copy_button")).tap()
    app.wait(text("Copied: shoes")).visible()
    assert tap_device.clipboard() == "shoes"

    app.element(res("toast_button")).tap()
    assert app.await_toast("Saved 1") == Toast("Saved 1", PACKAGE)


def test_accessibility_actions_and_slider_progress(tap_device):
    """Named actions run as a screen reader runs them: standard (expand/collapse) and the app's
    own custom actions; an action the node does not offer is refused before input, and a slider
    takes a value in its own range and refuses one outside it."""
    app = launch(tap_device, ".ControlsActivity")
    header = app.element(res("details_header"))
    assert StandardAction.EXPAND in header.snapshot().actions
    header.perform_action(StandardAction.EXPAND)
    app.wait(text("Details (expanded)")).visible()
    assert StandardAction.COLLAPSE in header.snapshot().actions
    with pytest.raises(CommandError) as refused:
        header.perform_action(StandardAction.DISMISS)
    assert refused.value.code is ErrorCode.ACTION_REJECTED and refused.value.detail == "ACTION_NOT_OFFERED"

    card = app.element(res("message_card"))
    assert set(card.snapshot().custom_actions) >= {"Archive", "Mark unread"}
    card.perform_custom_action("Archive")
    app.wait(text("Archived")).visible()

    slider = app.element(res("volume_slider"))
    assert slider.snapshot().range is not None and slider.snapshot().range.max == 100
    slider.set_progress(55)
    app.wait(text("Volume: 55")).visible()
    with pytest.raises(CommandError) as outside:
        slider.set_progress(150)
    assert outside.value.detail == "OUT_OF_RANGE"


def test_location_prompt_takes_approximate(tap_device):
    """API 31+: the location dialog offers Precise / Approximate, and the choice reaches the app."""
    if tap_device.info().api_level < 31:
        pytest.skip("the location accuracy choice is API 31+")
    app = launch(tap_device)
    app.clear_data()
    app.launch(".PermissionActivity")
    app.element(res("request_location_permission")).tap()
    prompt = tap_device.await_permission_prompt()
    assert set(prompt.accuracies) == {LocationAccuracy.PRECISE, LocationAccuracy.APPROXIMATE}, prompt
    tap_device.choose_permission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)
    app.wait(text("Location approximate")).visible()


def test_device_locale_and_mock_location_reach_the_app_then_are_restored(tap_client, tap_config):
    """The device language reaches an app that follows the system; a mocked fix reaches the app's
    gps listener and moves when set again; detach puts the language back."""
    connection = tap_client.connect("locale-location")
    try:
        serial = (tap_config.serials or connection.available_serials())[0]
        with connection.attach_device(serial) as device:
            app = launch(device)
            before = device.info().system_locales
            target = "de-DE" if before[:1] != ("de-DE",) else "fr-FR"
            device.set_system_locales([target, "en-US"])
            assert device.info().system_locales[:2] == (target, "en-US")
            app.launch(".FormActivity")
            app.wait(text_contains(f" locale={target} ")).visible()

            app.grant_permission("android.permission.ACCESS_FINE_LOCATION")
            app.launch(".PermissionActivity")
            device.set_location(48.8584, 2.2945, accuracy_m=3)
            app.element(res("read_location")).tap()
            app.wait(text("At 48.85840, 2.29450"), timeout=15).visible()
            device.set_location(51.5007, -0.1246)
            app.element(res("read_location")).tap()
            app.wait(text("At 51.50070, -0.12460"), timeout=15).visible()
        with connection.attach_device(serial) as device:
            assert device.info().system_locales == before
    finally:
        connection.close()


def test_network_switches_read_back_then_are_restored(tap_client, tap_config):
    """Airplane mode and Wi-Fi are the device's real switches: set, read back, restored."""
    connection = tap_client.connect("network")
    try:
        serial = (tap_config.serials or connection.available_serials())[0]
        with connection.attach_device(serial) as device:
            before = device.info()
            if before.api_level < 29:
                pytest.skip("network switches are API 29+")
            device.set_network(airplane_mode=not before.airplane_mode, wifi=not before.wifi_enabled)
            now = device.info()
            assert (now.airplane_mode, now.wifi_enabled) == (not before.airplane_mode, not before.wifi_enabled)
        with connection.attach_device(serial) as device:
            now = device.info()
            assert (now.airplane_mode, now.wifi_enabled, now.mobile_data_enabled) == (
                before.airplane_mode,
                before.wifi_enabled,
                before.mobile_data_enabled,
            )
    finally:
        connection.close()
