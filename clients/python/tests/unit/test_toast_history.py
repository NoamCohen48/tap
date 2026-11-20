"""Toast history wire/model mapping and validation over the fake daemon."""
# pyright: reportMissingImports=false
import pytest

from tap_e2e import CommandError, TapClient, TapError, Toast
from tap_e2e import _gen as pb

from .conftest import TOKEN


def test_toast_history_model_and_errors(fake):
    with TapClient.create(fake.address, TOKEN) as client, client.connect("history") as connection:
        device = connection.attach_device("emulator-5554")
        fake.devices.responder = lambda _: pb.CommandResult(toast_history=pb.ToastHistory(
            stream_id="stream", next_sequence=12, oldest_sequence=12, gap=True,
            events=[pb.ToastEvent(sequence=12, at_epoch_ms=1234, text_truncated=True,
                                  toast=pb.Toast(text="Saved", package_name="com.example"))],
        ))
        page = device.toast_history(3, stream_id="stream")
        assert page.events[0].toast == Toast("Saved", "com.example")
        assert page.events[0].at_epoch_ms == 1234
        assert page.events[0].text_truncated
        assert page.next_sequence == page.oldest_sequence == 12
        assert page.gap and not page.reset
        assert fake.devices.commands[-1].read_toast_history == pb.ReadToastHistory(after_sequence=3, stream_id="stream")
        for value in (-1, True, 1.5, 2**63):
            with pytest.raises(ValueError):
                device.toast_history(value)  # pyright: ignore[reportArgumentType]
        with pytest.raises(ValueError):
            device.toast_history(stream_id="x" * 129)
        fake.devices.responder = lambda _: pb.CommandResult(error=pb.Error(code=pb.ERR_UNSUPPORTED))
        with pytest.raises(CommandError):
            device.toast_history()
        fake.devices.responder = lambda _: pb.CommandResult(done=pb.Done())
        with pytest.raises(TapError, match="no toast history"):
            device.toast_history()
        fake.devices.responder = None
