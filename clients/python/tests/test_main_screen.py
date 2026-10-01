# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false
import pytest  # type: ignore[import-not-found]
from conftest import launch
from tap_e2e import (
    UP,
    CommandError,
    ErrorCode,
    WaitReason,
    WaitTimeoutError,
    res,
    text,
    text_matches,
    text_starts_with,
)


def test_taps_view_and_compose_buttons(tap_device):
    app = launch(tap_device)

    app.element(res("view_button")).tap()
    app.wait(text("View tapped")).visible()

    app.element(res("composeButton")).tap()
    app.wait(res("composeStatus")).text_equals("Compose tapped")


def test_types_and_clears_text(tap_device):
    app = launch(tap_device)
    field = app.element(res("view_input"))
    keyboard = app.element(res("keyboard_input"))

    field.set_text("hello tap")
    assert field.text() == "hello tap"

    keyboard.type_text("abc")
    app.wait(text("Keyboard event received")).visible()
    assert keyboard.text() == "abc"

    keyboard.clear_text()
    # Android reports an empty field's hint as its text; the snapshot says so.
    assert keyboard.snapshot().showing_hint


def test_scrolls_compose_list_until_item_is_visible(tap_device):
    app = launch(tap_device)

    lst = app.element(res("composeList"))
    # Up to 30 scrolls: a budget above the 10 s wait default (slow CI emulators).
    item = lst.scroll_until(res("item-40"), max_scrolls=30, timeout=30)
    assert item.exists()
    assert item.text() == "Item 40"

    # Back up: UiAutomator scroll direction names the content edge you move towards.
    first = lst.scroll_until(
        res("item-1"), direction=UP, max_scrolls=30, timeout=30
    )
    assert first.text() == "Item 1"


def test_ambiguous_tap_fails_before_any_input(tap_device):
    app = launch(tap_device)

    # Material buttons expose their all-caps rendering as accessibility text.
    ambiguous = text_matches("(?i)ambiguous tap")
    with pytest.raises(CommandError) as failure:
        app.element(ambiguous).tap()
    assert failure.value.code == ErrorCode.AMBIGUOUS
    assert app.element(ambiguous).count() == 2
    assert (
        app.element(res("ambiguous_status")).text()
        == "Ambiguous taps: left=0 right=0"
    )

    # A disjunction is still one selector: both buttons match, so it is just as AMBIGUOUS.
    either = res("ambiguous_button_left") | res("ambiguous_button_right")
    assert app.element(either).count() == 2
    with pytest.raises(CommandError) as failure:
        app.element(either).tap()
    assert failure.value.code == ErrorCode.AMBIGUOUS

    # Disambiguate by resource id (or by relation/index) instead of relaxing the invariant.
    app.element(res("ambiguous_button_right").and_text("AMBIGUOUS TAP")).tap()
    app.wait(text("Ambiguous taps: left=0 right=1")).visible()
    app.element(
        (res("no_such_button") | res("ambiguous_button_left")) & text("AMBIGUOUS TAP")
    ).tap()
    app.wait(text("Ambiguous taps: left=1 right=1")).visible()


def test_waits_for_app_owned_synchronization(tap_device):
    app = launch(tap_device)

    app.element(res("sync_button")).tap()
    app.wait(text("Synchronized work running")).visible()
    app.await_idle(timeout=15)
    assert app.element(res("view_status")).text() == "Synchronized work complete"


def test_wait_timeout_is_diagnosable(tap_device):
    app = launch(tap_device)

    with pytest.raises(WaitTimeoutError) as timeout:
        app.wait(text_starts_with("Never rendered"), timeout=1).visible()
    assert tap_device.serial in str(timeout.value)
    assert (timeout.value.reason, timeout.value.match_count) == (WaitReason.NO_MATCH, 0)

    # visible() is "one or more"; one() is the explicit exactly-one wait and says how many matched.
    ambiguous = text_matches("(?i)ambiguous tap")
    app.wait(ambiguous).visible()
    with pytest.raises(WaitTimeoutError) as not_one:
        app.wait(ambiguous, timeout=1).one()
    assert (not_one.value.reason, not_one.value.match_count) == (WaitReason.AMBIGUOUS, 2)
    with pytest.raises(WaitTimeoutError) as present:
        app.wait(ambiguous, timeout=1).gone()
    assert (present.value.reason, present.value.match_count) == (WaitReason.STILL_PRESENT, 2)
    app.wait(res("ambiguous_button_left")).one().tap()


def test_back_key(tap_device):
    app = launch(tap_device, ".ViewListActivity")
    app.wait(text("View item 1")).visible()

    tap_device.press_back()
    app.wait(text("View item 1")).gone()
