import pytest

from tap import UP, CommandError, ErrorCode, WaitTimeoutError, raw_res, text, text_matches, text_starts_with

from conftest import fixture_id, launch


def test_taps_view_and_compose_buttons(tap_device):
    launch(tap_device)

    tap_device.element(fixture_id("view_button")).tap()
    tap_device.wait(text("View tapped")).visible()

    tap_device.element(raw_res("composeButton")).tap()
    tap_device.wait(raw_res("composeStatus")).text_equals("Compose tapped")


def test_types_and_clears_text(tap_device):
    launch(tap_device)
    field = tap_device.element(fixture_id("view_input"))
    keyboard = tap_device.element(fixture_id("keyboard_input"))

    field.set_text("hello tap")
    assert field.text() == "hello tap"

    keyboard.type_text("abc")
    tap_device.wait(text("Keyboard event received")).visible()
    assert keyboard.text() == "abc"

    keyboard.clear_text()
    assert (keyboard.text() or "") == ""


def test_scrolls_compose_list_until_item_is_visible(tap_device):
    launch(tap_device)

    lst = tap_device.element(raw_res("composeList"))
    item = lst.scroll_until(raw_res("item-40"), max_scrolls=30)
    assert item.exists()
    assert item.text() == "Item 40"

    # Back up: UiAutomator scroll direction names the content edge you move towards.
    first = lst.scroll_until(raw_res("item-1"), direction=UP, max_scrolls=30)
    assert first.text() == "Item 1"


def test_ambiguous_tap_fails_before_any_input(tap_device):
    launch(tap_device)

    # Material buttons expose their all-caps rendering as accessibility text.
    ambiguous = text_matches("(?i)ambiguous tap")
    with pytest.raises(CommandError) as failure:
        tap_device.element(ambiguous).tap()
    assert failure.value.code == ErrorCode.AMBIGUOUS
    assert tap_device.element(ambiguous).count() == 2
    assert tap_device.element(fixture_id("ambiguous_status")).text() == "Ambiguous taps: left=0 right=0"

    # Disambiguate by resource id (or by relation/index) instead of relaxing the invariant.
    tap_device.element(fixture_id("ambiguous_button_right").and_text("AMBIGUOUS TAP")).tap()
    tap_device.wait(text("Ambiguous taps: left=0 right=1")).visible()


def test_waits_for_app_owned_synchronization(tap_device):
    app = launch(tap_device)

    tap_device.element(fixture_id("sync_button")).tap()
    tap_device.wait(text("Synchronized work running")).visible()
    app.await_idle(timeout=15)
    assert tap_device.element(fixture_id("view_status")).text() == "Synchronized work complete"


def test_wait_timeout_is_diagnosable(tap_device):
    launch(tap_device)

    with pytest.raises(WaitTimeoutError) as timeout:
        tap_device.wait(text_starts_with("Never rendered"), timeout=1).visible()
    assert tap_device.serial in str(timeout.value)


def test_back_key(tap_device):
    launch(tap_device, ".ViewListActivity")
    tap_device.wait(text("View item 1")).visible()

    tap_device.press_back()
    tap_device.wait(text("View item 1")).gone()
