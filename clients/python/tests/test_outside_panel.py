# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false
"""``Element.tap_outside_panel()`` through the server, on the fixture's Views and Compose panels.

The fixture's status line counts every click a stray touch could cause, so each test reads it
after the panels have closed.
"""

import pytest  # type: ignore[import-not-found]
from conftest import launch
from tap_e2e import App, CommandError, ErrorCode, text


def _open(app: App, opener: str, inside: str) -> None:
    app.element(text(opener)).tap()
    app.wait(text(inside)).visible()
    app.await_screen_stable()  # the point is placed from where the panel is now


def test_tap_outside_closes_a_dialog_and_a_sheet(tap_device):
    app = launch(tap_device, ".OutsidePanelActivity")

    _open(app, "Open dialog", "Dialog title")
    touch = app.element(text("Dialog title")).tap_outside_panel()
    assert touch.panel.startswith("window"), touch
    app.wait(text("Dialog title")).gone()

    # Fully expanded, the sheet covers its scrim's centre: the touch still lands beside it.
    _open(app, "Open sheet", "Sheet row 2")
    touch = app.element(text("Sheet row 2")).tap_outside_panel()
    assert touch.panel.startswith("dismissible"), touch
    app.wait(text("Sheet row 2")).gone()

    app.wait(text("Outside: under=0 rows=0 menu=0 closed=dialog,sheet")).visible()


def test_tap_outside_refuses_a_plain_screen_and_a_pass_through_popup(tap_device):
    app = launch(tap_device, ".OutsidePanelActivity")
    with pytest.raises(CommandError) as plain:
        app.element(text("Under")).tap_outside_panel()
    assert plain.value.code is ErrorCode.NOT_INTERACTABLE and plain.value.detail == "NO_OUTSIDE_POINT"

    _open(app, "Open pass-through", "Pass-through body")
    popup = app.element(text("Pass-through body"))
    with pytest.raises(CommandError) as refused:
        popup.tap_outside_panel()
    assert refused.value.code is ErrorCode.NOT_INTERACTABLE and refused.value.detail == "TOUCH_REACHES_BELOW"
    popup.tap_outside_panel(allow_reach_below=True)
    app.wait(text("Pass-through body")).gone()
    # The opted-in touch also reached the button under the popup.
    app.wait(text("Outside: under=1 rows=0 menu=0 closed=pass-through")).visible()


def test_tap_outside_closes_compose_panels(tap_device):
    app = launch(tap_device, ".ComposeOutsidePanelActivity")
    for opener, inside, found_by in [
        ("Open compose drawer", "Compose drawer body", "pane"),
        ("Open compose sheet", "Compose sheet body", "pane"),
        ("Open compose dialog", "Compose dialog title", "window"),
    ]:
        _open(app, opener, inside)
        touch = app.element(text(inside)).tap_outside_panel()
        assert touch.panel.startswith(found_by), touch
        app.wait(text(inside)).gone()
    app.wait(text("Compose: under=0 rows=0 closed=drawer,sheet,dialog")).visible()
