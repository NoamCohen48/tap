"""Device events over a real gRPC channel: typed kinds, callbacks and one-shot waits."""
import threading
import time

import grpc
import pytest  # pyright: ignore[reportMissingImports]  # Installed in the test venv.

from tap_e2e import (
    ContentChange,
    ContentChangedEvent,
    DeviceEvent,
    FailureReason,
    ServerError,
    TapClient,
    TapError,
    TextChangedEvent,
    ToastShownEvent,
    ViewClickedEvent,
    ViewScrolledEvent,
    WaitTimeoutError,
    WindowChange,
    WindowChangedEvent,
)
from tap_e2e import _gen as pb

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("events") as connection:
            yield connection.attach_device("emulator-5554")


def toast(sequence: int, text: str = "Draft saved", package: str = "com.shop") -> pb.DeviceEvent:
    return pb.DeviceEvent(
        sequence=sequence, uptime_ms=1_000 + sequence, package_name=package, toast_shown=pb.ToastShown(text=text)
    )


def until(condition, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while not condition():
        assert time.monotonic() < deadline, "condition never held"
        time.sleep(0.01)


def test_on_toast_delivers_typed_toasts_until_the_block_exits(fake, device):
    received: list[ToastShownEvent] = []
    with device.events.on_toast(received.append, package="com.shop") as registration:
        request = fake.devices.event_requests[-1]
        assert list(request.types) == [pb.DEVICE_EVENT_TOAST]
        assert request.package_name == "com.shop"
        assert request.attached_device_id == device.attached_device_id
        fake.devices.feed_events([toast(1), toast(2, package="com.other"), toast(3, "Sent")])
        until(lambda: len(received) == 2)
        assert registration.active and registration.kind is ToastShownEvent
    assert received[0] == ToastShownEvent(sequence=1, uptime_ms=1_001, package_name="com.shop", text="Draft saved")
    assert received[1].text == "Sent"
    until(lambda: fake.devices.event_streams == 0)
    assert not registration.active


def test_every_kind_decodes_into_its_own_class(fake, device):
    got: list[DeviceEvent] = []
    with device.events.on_window_change(got.append), device.events.on_content_change(got.append), \
            device.events.on_click(got.append), device.events.on_scroll(got.append), \
            device.events.on_text_change(got.append):
        fake.devices.feed_events([
            pb.DeviceEvent(sequence=1, window_changed=pb.WindowChanged(
                class_name="android.app.Dialog", title="Delete?", window_id=-1,
                changes=[pb.WINDOW_CHANGE_ADDED, pb.WINDOW_CHANGE_FOCUSED])),
            pb.DeviceEvent(sequence=2, content_changed=pb.ContentChanged(window_id=4, changes=[pb.CONTENT_CHANGE_TEXT])),
            pb.DeviceEvent(sequence=3, view_clicked=pb.ViewClicked(class_name="android.widget.Button", label="OK", window_id=4)),
            pb.DeviceEvent(sequence=4, view_scrolled=pb.ViewScrolled(scroll_y=300, max_scroll_y=900, delta_y=120, from_index=3)),
            pb.DeviceEvent(sequence=5, text_changed=pb.TextChanged(
                class_name="android.widget.EditText", text="ab", before_text="a", from_index=1, added_count=1, removed_count=0)),
        ])
        until(lambda: len(got) == 5)
    by_sequence = {e.sequence: e for e in got}
    assert by_sequence[1] == WindowChangedEvent(
        sequence=1, uptime_ms=0, package_name=None, class_name="android.app.Dialog", title="Delete?", window_id=None,
        changes=frozenset({WindowChange.ADDED, WindowChange.FOCUSED}),
    )
    assert by_sequence[2] == ContentChangedEvent(
        sequence=2, uptime_ms=0, package_name=None, class_name=None, window_id=4, changes=frozenset({ContentChange.TEXT})
    )
    assert by_sequence[3] == ViewClickedEvent(
        sequence=3, uptime_ms=0, package_name=None, class_name="android.widget.Button", label="OK", window_id=4
    )
    scrolled = by_sequence[4]
    assert isinstance(scrolled, ViewScrolledEvent)
    assert (scrolled.scroll_y, scrolled.max_scroll_y, scrolled.delta_y, scrolled.delta_x) == (300, 900, 120, None)
    assert (scrolled.from_index, scrolled.to_index, scrolled.item_count) == (3, None, None)
    edited = by_sequence[5]
    assert isinstance(edited, TextChangedEvent)
    assert (edited.text, edited.before_text, edited.from_index, edited.added_count, edited.removed_count, edited.password) == (
        "ab", "a", 1, 1, 0, False,
    )


def test_a_registration_gets_only_its_kind(fake, device):
    toasts: list[ToastShownEvent] = []
    with device.events.register(ToastShownEvent, toasts.append):
        fake.devices.feed_events([pb.DeviceEvent(sequence=1, view_clicked=pb.ViewClicked(label="OK")), toast(2)])
        until(lambda: len(toasts) == 1)
    assert toasts[0].sequence == 2


def test_a_raising_callback_keeps_receiving_and_the_block_reraises_it(fake, device):
    seen: list[int] = []

    def callback(event: ToastShownEvent) -> None:
        seen.append(event.sequence)
        if event.sequence == 1:
            raise RuntimeError("boom")

    with pytest.raises(RuntimeError, match="boom"):
        with device.events.on_toast(callback) as registration:
            fake.devices.feed_events([toast(1), toast(2)])
            until(lambda: seen == [1, 2])
            assert isinstance(registration.callback_error, RuntimeError)


def test_a_stream_that_fails_ends_the_registration_and_the_block_raises(fake, device):
    with pytest.raises(ServerError) as raised:
        with device.events.on_toast(lambda e: None) as registration:
            fake.devices.feed_events((grpc.StatusCode.RESOURCE_EXHAUSTED, pb.FAILURE_REASON_DAEMON_PRECONDITION, "events were lost"))
            until(lambda: not registration.active)
            assert isinstance(registration.error, ServerError)
    assert raised.value.reason is FailureReason.DAEMON_PRECONDITION


def test_a_detach_ends_the_registration_without_an_error(fake, device):
    with device.events.on_toast(lambda e: None) as registration:
        fake.devices.feed_events(None)
        until(lambda: not registration.active)
    assert registration.error is None


def test_close_without_a_block_stops_delivery(fake, device):
    registration = device.events.on_toast(lambda e: None)
    registration.close()
    registration.close()
    until(lambda: fake.devices.event_streams == 0)
    assert not registration.active and registration.error is None


def test_kind_package_and_callback_are_checked_before_any_call(fake, device):
    for bad_kind in ("TOAST", DeviceEvent, int, pb.DEVICE_EVENT_TOAST):
        with pytest.raises(TypeError):
            device.events.register(bad_kind, print)  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        device.events.on_toast(print, package=" ")
    with pytest.raises(TypeError):
        device.events.on_toast("print")  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        device.events.expect(ToastShownEvent, where=True)  # type: ignore[arg-type]
    assert fake.devices.event_requests == []


def test_expect_catches_the_event_an_action_causes(fake, device):
    with device.events.expect(ToastShownEvent, where=lambda e: "sent" in e.text) as sent:
        # The event fires "during the action", before wait is called: it is not missed.
        fake.devices.feed_events([toast(1, "Draft saved"), toast(2, "Message sent")])
        until(lambda: fake.devices.event_feeds[0].empty())
        event = sent.wait(timeout=2)
    assert event.sequence == 2 and event.text == "Message sent"
    until(lambda: fake.devices.event_streams == 0)


def test_wait_for_times_out_naming_what_it_saw(fake, device):
    def later() -> None:
        until(lambda: fake.devices.event_streams == 1)
        fake.devices.feed_events([toast(1, "Draft saved")])

    threading.Thread(target=later, daemon=True).start()
    with pytest.raises(WaitTimeoutError) as raised:
        device.events.wait_for(ToastShownEvent, where=lambda e: "Sent" in e.text, timeout=0.5)
    assert "ToastShownEvent" in str(raised.value) and "did not match" in str(raised.value)
    until(lambda: fake.devices.event_streams == 0)


def test_wait_for_returns_the_first_match_typed(fake, device):
    def later() -> None:
        until(lambda: fake.devices.event_streams == 1)
        fake.devices.feed_events([pb.DeviceEvent(sequence=5, package_name="com.shop", window_changed=pb.WindowChanged(
            class_name="com.shop.CheckoutActivity", changes=[pb.WINDOW_CHANGE_STATE]))])

    threading.Thread(target=later, daemon=True).start()
    event = device.events.wait_for(WindowChangedEvent, package="com.shop", timeout=2)
    assert event.class_name == "com.shop.CheckoutActivity" and event.changes == {WindowChange.STATE}


def test_wait_raises_the_predicates_error_and_a_detach(fake, device):
    def broken(event: ToastShownEvent) -> bool:
        raise KeyError("bad predicate")

    with device.events.expect(ToastShownEvent, where=broken) as expected:
        fake.devices.feed_events([toast(1)])
        with pytest.raises(KeyError):
            expected.wait(timeout=2)
    with device.events.expect(ToastShownEvent) as expected:
        fake.devices.feed_events(None)
        with pytest.raises(TapError, match="detached"):
            expected.wait(timeout=2)


def test_a_detached_device_rejects_registration(fake, device):
    device.detach()
    with pytest.raises(TapError, match="detached"):
        device.events.on_toast(print)
