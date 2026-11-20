"""Device acceptance for live accessibility events; run only with TAP_SERIALS set.

Each test asserts what every Android version reports for its kind of event and prints the
events as the device reported them (``pytest -s``), so a new device's quirks are visible."""
from __future__ import annotations

import threading
import time

from conftest import PACKAGE, launch

from tap_e2e import (
    DOWN,
    ContentChange,
    ContentChangedEvent,
    DeviceEvent,
    TextChangedEvent,
    ToastShownEvent,
    ViewClickedEvent,
    ViewScrolledEvent,
    WindowChange,
    WindowChangedEvent,
    res,
    scrollable,
)

SETTINGS = "com.android.settings"


class Recorder:
    """Every event of the given kinds while the block runs, for the printout."""

    def __init__(self, device, *kinds: type[DeviceEvent]) -> None:
        self.device = device
        self.kinds = kinds
        self.events: list[DeviceEvent] = []
        self._lock = threading.Lock()

    def _add(self, event: DeviceEvent) -> None:
        with self._lock:
            self.events.append(event)

    def __enter__(self) -> Recorder:
        self._registrations = [self.device.events.register(kind, self._add) for kind in self.kinds]
        return self

    def __exit__(self, *exc) -> None:
        for registration in self._registrations:
            registration.__exit__(*exc)
        print(f"\n[{self.device.serial} API {self.device.info().api_level}] {len(self.events)} events")
        for event in sorted(self.events, key=lambda e: e.sequence):
            print(f"  {event}")

    def of(self, kind: type[DeviceEvent]) -> list:
        with self._lock:
            return [e for e in self.events if isinstance(e, kind)]


def test_a_launch_raises_a_window_state_change_for_its_activity(tap_device):
    app = launch(tap_device)
    with Recorder(tap_device, WindowChangedEvent):
        with tap_device.events.expect(
            WindowChangedEvent,
            package=PACKAGE,
            where=lambda e: WindowChange.STATE in e.changes and (e.class_name or "").endswith(".FormActivity"),
        ) as shown:
            app.cold_launch(".FormActivity")
            window = shown.wait(timeout=10)
    assert window.package_name == PACKAGE


def test_a_tap_raises_its_click_and_the_toast_it_shows(tap_device):
    app = launch(tap_device, ".FormActivity")
    with Recorder(tap_device, ViewClickedEvent, ToastShownEvent):
        with tap_device.events.expect(ViewClickedEvent, package=PACKAGE) as clicked, \
                tap_device.events.expect(ToastShownEvent, where=lambda e: e.text == "Saved 1") as saved:
            app.element(res("toast_button")).tap()
            click = clicked.wait(timeout=5)
            toast = saved.wait(timeout=5)
    assert click.class_name == "android.widget.Button"
    # The label as shown: a theme with textAllCaps (Samsung's) reports "TOAST".
    assert (click.label or "").lower() == "toast"
    assert toast.package_name == PACKAGE


def test_set_text_is_a_content_change_and_typing_a_text_change_per_key(tap_device):
    """Text set in one step reaches accessibility as a content change; typed keys as text changes
    with what was there before, where the edit starts and how many characters it added."""
    app = launch(tap_device, ".FormActivity")
    field = app.element(res("search_field"))
    with Recorder(tap_device, TextChangedEvent, ContentChangedEvent) as seen:
        with tap_device.events.expect(
            ContentChangedEvent, package=PACKAGE, where=lambda e: ContentChange.TEXT in e.changes
        ) as changed:
            field.set_text("ab")
            content = changed.wait(timeout=5)
        assert content.class_name == "android.widget.EditText"

        field.tap()
        field.wait().focused()
        with tap_device.events.expect(TextChangedEvent, package=PACKAGE, where=lambda e: e.text == "abc") as typed:
            tap_device.type_text("c")
            event = typed.wait(timeout=5)
        tap_device.hide_keyboard()
    assert event.class_name == "android.widget.EditText"
    assert (event.before_text, event.from_index, event.added_count, event.removed_count) == ("ab", 2, 1, 0)
    assert not event.password


def test_a_scroll_raises_scroll_events_with_a_position(tap_device):
    settings = tap_device.app(SETTINGS)
    settings.cold_launch()
    try:
        container = settings.element(scrollable().first())
        container.wait().visible()
        with Recorder(tap_device, ViewScrolledEvent) as seen:
            with tap_device.events.expect(ViewScrolledEvent, package=SETTINGS) as scrolled:
                container.scroll(DOWN)
                first = scrolled.wait(timeout=5)
            time.sleep(0.5)
        assert first.class_name
        # Every scroll reports where it is: a position, a delta or the visible items of a list.
        assert all(
            e.scroll_y is not None or e.delta_y is not None or e.from_index is not None for e in seen.of(ViewScrolledEvent)
        )
    finally:
        settings.force_stop()
