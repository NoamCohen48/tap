"""The driver's field state reaches ScreenSnapshot through the daemon and Python mapping."""

from tap_e2e import NodeChange, res

from conftest import PACKAGE, launch


def test_screen_snapshot_reports_hint_validation_and_typed_value(tap_device):
    app = launch(tap_device, ".FieldStateActivity")
    app.wait(res("field_state_input")).visible()

    def field():
        return next(
            n for n in tap_device.screen_snapshot().nodes
            if n.resource_name == f"{PACKAGE}:id/field_state_input"
        )

    empty = field()
    assert empty.showing_hint and empty.text == empty.hint == "Name"
    assert not empty.content_invalid and empty.error is None

    app.element(res("validate_field")).tap()
    tap_device.await_until("the validation error", lambda: field().error == "Cannot be blank")
    invalid = field()
    assert invalid.showing_hint and invalid.content_invalid and invalid.error == "Cannot be blank"
    # Validation is state, not identity: the same ref must survive it.
    assert invalid.ref == empty.ref and invalid.change is NodeChange.UNCHANGED

    app.element(res("field_state_input")).set_text("Name")
    app.element(res("validate_field")).tap()
    tap_device.await_until("validation cleared", lambda: field().error is None)
    filled = field()
    assert filled.text == filled.hint == "Name" and not filled.showing_hint
    assert not filled.content_invalid and filled.error is None
