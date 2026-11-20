"""Live accessibility events from an attached device: callbacks and one-shot waits."""
# pyright: reportAttributeAccessIssue=false, reportArgumentType=false, reportCallIssue=false

from __future__ import annotations

import enum
import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Generic, TypeVar

import grpc

from . import _gen as pb
from .client import _map_rpc_error
from .errors import TapError, WaitTimeoutError

if TYPE_CHECKING:
    from typing_extensions import Self

    from .device import Device

_log = logging.getLogger(__name__)

# How long ``register`` waits for the server to confirm the subscription.
READY_TIMEOUT = 30.0


class WindowChange(enum.Enum):
    """What changed about a window (``WindowChangedEvent.changes``)."""

    STATE = "STATE"
    """An activity, dialog, popup or pane appeared or changed state (``TYPE_WINDOW_STATE_CHANGED``)."""
    ADDED = "ADDED"
    REMOVED = "REMOVED"
    TITLE = "TITLE"
    BOUNDS = "BOUNDS"
    LAYER = "LAYER"
    ACTIVE = "ACTIVE"
    FOCUSED = "FOCUSED"
    ACCESSIBILITY_FOCUSED = "ACCESSIBILITY_FOCUSED"
    PARENT = "PARENT"
    CHILDREN = "CHILDREN"
    PIP = "PIP"
    """Picture-in-picture changed."""


class ContentChange(enum.Enum):
    """What changed inside a window (``ContentChangedEvent.changes``)."""

    SUBTREE = "SUBTREE"
    """Nodes were added, removed or rearranged."""
    TEXT = "TEXT"
    CONTENT_DESCRIPTION = "CONTENT_DESCRIPTION"
    STATE_DESCRIPTION = "STATE_DESCRIPTION"
    PANE_TITLE = "PANE_TITLE"
    PANE_APPEARED = "PANE_APPEARED"
    PANE_DISAPPEARED = "PANE_DISAPPEARED"
    DRAG_STARTED = "DRAG_STARTED"
    DRAG_DROPPED = "DRAG_DROPPED"
    DRAG_CANCELLED = "DRAG_CANCELLED"
    CONTENT_INVALID = "CONTENT_INVALID"
    ERROR = "ERROR"
    ENABLED = "ENABLED"


@dataclass(frozen=True)
class DeviceEvent:
    """What every accessibility event has; each kind is a subclass (``ToastShownEvent``,
    ``WindowChangedEvent``, ...). A hint that the screen changed, not the screen's state:
    Android merges and drops events, and an event holds no element. To act on one, read the
    screen again through a selector."""

    sequence: int
    """Increases by one per event the driver forwards; a gap only means other events did not
    match this registration."""
    uptime_ms: int
    """Android uptime when the event fired (``SystemClock.uptimeMillis``), not the wall clock."""
    package_name: str | None
    """The package that raised it; None when Android names none."""


@dataclass(frozen=True)
class ToastShownEvent(DeviceEvent):
    """A toast was shown, as it happened. (``ToastEvent`` is a different thing: an entry of
    ``Device.toast_history``, which keeps the last toasts for reading later.)"""

    text: str
    """The toast's message (at most 512 characters)."""


@dataclass(frozen=True)
class WindowChangedEvent(DeviceEvent):
    """A window appeared, went away or changed: an activity, dialog, popup or pane."""

    class_name: str | None
    """The activity, dialog or view class; None when Android names none."""
    title: str | None
    """The window's title or the pane's text; None when there is none."""
    window_id: int | None
    changes: frozenset[WindowChange]
    """What changed; never empty. ``STATE`` alone for a new activity, dialog or pane; the other
    flags come from Android's window list changes (API 28+)."""


@dataclass(frozen=True)
class ContentChangedEvent(DeviceEvent):
    """Content inside a window changed. Frequent: an animation can raise one per frame."""

    class_name: str | None
    window_id: int | None
    changes: frozenset[ContentChange]
    """What changed; empty when Android did not say."""


@dataclass(frozen=True)
class ViewClickedEvent(DeviceEvent):
    """A view was clicked, by Tap or by anything else."""

    class_name: str | None
    """The clicked view's class (``android.widget.Button``)."""
    label: str | None
    """The view's text; None when it has none."""
    window_id: int | None


@dataclass(frozen=True)
class ViewScrolledEvent(DeviceEvent):
    """A view scrolled. The values a view does not report are None."""

    class_name: str | None
    window_id: int | None
    scroll_x: int | None
    """The scroll position in pixels; a list such as RecyclerView reports items instead."""
    scroll_y: int | None
    max_scroll_x: int | None
    max_scroll_y: int | None
    delta_x: int | None
    """How far this scroll moved horizontally, in pixels (API 28+)."""
    delta_y: int | None
    from_index: int | None
    """For a list: the first visible item."""
    to_index: int | None
    """For a list: the last visible item."""
    item_count: int | None


@dataclass(frozen=True)
class TextChangedEvent(DeviceEvent):
    """An editable view's text changed. ``text`` and ``before_text`` hold what was typed
    (at most 512 characters each); Android masks a password field's text itself. Android raises
    it for typed input, once per key (``type_text``); text set in one step (``set_text``) is a
    ``ContentChangedEvent`` with ``ContentChange.TEXT`` instead."""

    class_name: str | None
    text: str
    before_text: str
    from_index: int | None
    """Where the change starts."""
    added_count: int | None
    removed_count: int | None
    password: bool
    window_id: int | None


E = TypeVar("E", bound=DeviceEvent)
"""An event kind: one of the ``DeviceEvent`` subclasses."""


def _none(value: str) -> str | None:
    return value or None


def _window(value: int) -> int | None:
    return None if value == -1 else value


def _optional(message, field: str) -> int | None:
    return getattr(message, field) if message.HasField(field) else None


def _window_changes(values) -> frozenset[WindowChange]:
    return frozenset(WindowChange[pb.WindowChange.Name(v).removeprefix("WINDOW_CHANGE_")] for v in values if v)


def _content_changes(values) -> frozenset[ContentChange]:
    return frozenset(ContentChange[pb.ContentChange.Name(v).removeprefix("CONTENT_CHANGE_")] for v in values if v)


def _decode(event: pb.DeviceEvent) -> DeviceEvent | None:
    """The typed event, or None for a kind this client does not know (never asked for)."""
    common = dict(sequence=event.sequence, uptime_ms=event.uptime_ms, package_name=_none(event.package_name))
    kind = event.WhichOneof("kind")
    if kind == "toast_shown":
        return ToastShownEvent(**common, text=event.toast_shown.text)
    if kind == "window_changed":
        w = event.window_changed
        return WindowChangedEvent(
            **common, class_name=_none(w.class_name), title=_none(w.title), window_id=_window(w.window_id),
            changes=_window_changes(w.changes),
        )
    if kind == "content_changed":
        c = event.content_changed
        return ContentChangedEvent(
            **common, class_name=_none(c.class_name), window_id=_window(c.window_id), changes=_content_changes(c.changes)
        )
    if kind == "view_clicked":
        v = event.view_clicked
        return ViewClickedEvent(**common, class_name=_none(v.class_name), label=_none(v.label), window_id=_window(v.window_id))
    if kind == "view_scrolled":
        v = event.view_scrolled
        return ViewScrolledEvent(
            **common, class_name=_none(v.class_name), window_id=_window(v.window_id),
            scroll_x=_optional(v, "scroll_x"), scroll_y=_optional(v, "scroll_y"),
            max_scroll_x=_optional(v, "max_scroll_x"), max_scroll_y=_optional(v, "max_scroll_y"),
            delta_x=_optional(v, "delta_x"), delta_y=_optional(v, "delta_y"),
            from_index=_optional(v, "from_index"), to_index=_optional(v, "to_index"), item_count=_optional(v, "item_count"),
        )
    if kind == "text_changed":
        t = event.text_changed
        return TextChangedEvent(
            **common, class_name=_none(t.class_name), text=t.text, before_text=t.before_text,
            from_index=_optional(t, "from_index"), added_count=_optional(t, "added_count"),
            removed_count=_optional(t, "removed_count"), password=t.password, window_id=_window(t.window_id),
        )
    return None


# The subscription type the server filters by, for each event class.
_TYPES: dict[type[DeviceEvent], int] = {
    ToastShownEvent: pb.DEVICE_EVENT_TOAST,
    WindowChangedEvent: pb.DEVICE_EVENT_WINDOW_CHANGED,
    ContentChangedEvent: pb.DEVICE_EVENT_CONTENT_CHANGED,
    ViewClickedEvent: pb.DEVICE_EVENT_VIEW_CLICKED,
    ViewScrolledEvent: pb.DEVICE_EVENT_VIEW_SCROLLED,
    TextChangedEvent: pb.DEVICE_EVENT_TEXT_CHANGED,
}


def _kind(kind: type[E]) -> type[E]:
    if not (isinstance(kind, type) and kind in _TYPES):
        names = ", ".join(k.__name__ for k in _TYPES)
        raise TypeError(f"an event kind is one of {names}, not {kind!r}")
    return kind


def _package(package: str | None) -> str | None:
    if package is None:
        return None
    if not isinstance(package, str):
        raise TypeError(f"package is a package name, not {package!r}")
    if not package.strip():
        raise ValueError("package must not be blank")
    return package


class EventRegistration(Generic[E]):
    """A live registration made by ``DeviceEvents.register`` (or an ``on_*`` helper). Its
    callback runs on one background thread of its own, once per matching event, in the order
    the events fired, until ``close()``, the device's detach, or a failure that ends it
    (``error``).

    A callback that raises is logged and does not stop the registration; the first such
    exception is kept in ``callback_error``. Used as a context manager, the registration is
    closed when the block exits, and if it ended with an ``error`` or a callback raised, the
    block re-raises that (unless the block itself raised)::

        with device.events.on_toast(lambda e: toasts.append(e.text)):
            checkout.tap()
    """

    def __init__(
        self,
        device: Device,
        kind: type[E],
        callback: Callable[[E], object],
        package: str | None,
    ) -> None:
        self.kind: type[E] = kind
        """The event class this registration receives."""
        self.package: str | None = package
        """The only package whose events it receives; None for every package."""
        self._callback = callback
        self._serial = device.serial
        self._closed = threading.Event()
        self._ready = threading.Event()
        self._finished = threading.Event()
        self._lock = threading.Lock()
        self._error: TapError | None = None
        self._callback_error: BaseException | None = None
        request = pb.WatchDeviceEventsRequest(
            client_connection_id=device.owner_connection.id,
            attached_device_id=device.attached_device_id,
            types=[_TYPES[kind]],
        )
        if package is not None:
            request.package_name = package
        self._call = device.client.device_stub.WatchDeviceEvents(request)
        self._thread = threading.Thread(
            target=self._run, name=f"tap-events-{device.serial}", daemon=True
        )
        self._thread.start()
        if not self._ready.wait(READY_TIMEOUT) or self._error is not None:
            self._call.cancel()
            self._closed.set()
            raise self._error or TapError(
                f"Device({self._serial}): the server did not confirm the event subscription within {READY_TIMEOUT:g} s"
            )

    def _run(self) -> None:
        try:
            for response in self._call:
                if response.WhichOneof("kind") == "ready":
                    self._ready.set()
                    continue
                for message in response.batch.events:
                    if self._closed.is_set():
                        return
                    event = _decode(message)
                    if isinstance(event, self.kind):
                        self._dispatch(event)
        except grpc.RpcError as error:
            # A closed registration ends quietly: the cancellation is its own doing.
            if not (self._closed.is_set() and error.code() is grpc.StatusCode.CANCELLED):
                self._error = _map_rpc_error(error, self._serial)
                if self._ready.is_set():
                    _log.warning("Device(%s): event registration ended: %s", self._serial, self._error)
        finally:
            self._finished.set()
            self._ready.set()

    def _dispatch(self, event: E) -> None:
        try:
            self._callback(event)
        except Exception as error:  # noqa: BLE001 — reported, never fatal to delivery
            _log.exception("Device(%s): event callback raised on %s", self._serial, event)
            with self._lock:
                if self._callback_error is None:
                    self._callback_error = error

    @property
    def active(self) -> bool:
        """True until it is closed or ended (detach, ``error``)."""
        return not (self._closed.is_set() or self._finished.is_set())

    @property
    def error(self) -> TapError | None:
        """What ended the registration when it was not closed or detached: ``ServerError`` with
        ``RESOURCE_EXHAUSTED`` when events were lost (the callback fell more than 1024 events
        behind, or the device produced them faster than they could be sent), ``SESSION_UNUSABLE``
        when the driver connection was lost. None otherwise."""
        return self._error

    @property
    def callback_error(self) -> BaseException | None:
        """The first exception the callback raised; None when it never did."""
        with self._lock:
            return self._callback_error

    def close(self, timeout: float = 5.0) -> None:
        """Stops delivery and waits up to ``timeout`` seconds for a callback in progress to
        return (not when called from the callback itself). Idempotent."""
        self._closed.set()
        self._call.cancel()
        if threading.current_thread() is not self._thread:
            self._thread.join(timeout)

    def __enter__(self) -> Self:
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()
        if exc_type is not None:
            return
        if self._error is not None:
            raise self._error
        if self.callback_error is not None:
            raise self.callback_error


class ExpectedEvent(Generic[E]):
    """A one-shot wait made by ``DeviceEvents.expect``: subscribed when it was created, so an
    event the next action causes cannot be missed. ``wait`` returns the first matching event.
    Close it (or use it as a context manager) when it is no longer needed."""

    def __init__(
        self,
        device: Device,
        kind: type[E],
        where: Callable[[E], bool] | None,
        package: str | None,
        default_timeout: float,
    ) -> None:
        self._serial = device.serial
        self._where = where
        self._default_timeout = default_timeout
        self._matched = threading.Event()
        self._event: E | None = None
        self._seen = 0
        self._description = kind.__name__ + (f" from {package}" if package else "")
        self._registration: EventRegistration[E] = EventRegistration(device, kind, self._offer, package)

    def _offer(self, event: E) -> None:
        if self._matched.is_set():
            return
        self._seen += 1
        # A predicate that raises is the caller's bug: it reaches ``wait`` as callback_error.
        if self._where is None or self._where(event):
            self._event = event
            self._matched.set()

    def wait(self, timeout: float | None = None) -> E:
        """The first matching event, waiting up to ``timeout`` seconds (default: the device's
        ``timeouts.wait``) from this call. Raises ``WaitTimeoutError`` when none arrived, the
        registration's ``error`` when events were lost or the driver connection was, the
        predicate's exception when ``where`` raised, and ``TapError`` when the device was
        detached first."""
        timeout = self._default_timeout if timeout is None else timeout
        started = time.monotonic()
        deadline = started + timeout
        while not self._matched.is_set():
            if self._registration.callback_error is not None:
                raise self._registration.callback_error
            if not self._registration.active:
                if self._registration.error is not None:
                    raise self._registration.error
                raise TapError(f"Device({self._serial}) was detached while waiting for a {self._description}")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise WaitTimeoutError(
                    self._description + (" matching the predicate" if self._where else ""),
                    self._serial,
                    int((time.monotonic() - started) * 1000),
                    polls=self._seen,
                    last=f"{self._seen} events of that kind did not match" if self._seen else "no events of that kind",
                )
            self._matched.wait(min(remaining, 0.05))
        assert self._event is not None
        self._registration.close()
        return self._event

    def close(self) -> None:
        """Unsubscribes; idempotent."""
        self._registration.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc) -> None:
        self.close()


class DeviceEvents:
    """Live accessibility events from the device (``Device.events``): Android tells the
    driver when a toast shows, a window changes, a view is clicked or scrolled, or text or
    content changes, and the server streams the kinds a registration asks for. Each kind is its
    own event class, and the class is what you subscribe with, so a callback or a wait gets that
    class and its fields::

        device.events.on_toast(lambda e: print(e.text))           # e: ToastShownEvent
        device.events.wait_for(WindowChangedEvent, package="com.shop")

    Events are hints, not proof: Android merges and drops them. React to one by reading the
    screen again (``device.screen.element(...)``); never assume the event alone says the screen
    is ready. Watching is read-only and changes nothing on the device.
    """

    def __init__(self, device: Device) -> None:
        self._device = device

    def register(
        self,
        kind: type[E],
        callback: Callable[[E], object],
        *,
        package: str | None = None,
    ) -> EventRegistration[E]:
        """Calls ``callback`` with every event of ``kind`` (an event class such as
        ``ToastShownEvent``) from now on, on a background thread, until the returned registration is
        closed or the device detached. When this returns the server is subscribed: every event
        an action started afterwards causes is delivered. ``package`` keeps only that app's.

        Registrations are independent of each other; at most 64 per attached device. Use one as
        a context manager to close it, and to have its failures raised, at the end of a block::

            with device.events.register(WindowChangedEvent, print):
                app.element(text("Settings")).tap()
        """
        kind = _kind(kind)
        if not callable(callback):
            raise TypeError("callback must be callable")
        package = _package(package)
        self._device._ensure_usable("events.register")
        return EventRegistration(self._device, kind, callback, package)

    def on_toast(self, callback: Callable[[ToastShownEvent], object], *, package: str | None = None) -> EventRegistration[ToastShownEvent]:
        """``register(ToastShownEvent, callback)``: every toast shown."""
        return self.register(ToastShownEvent, callback, package=package)

    def on_window_change(
        self, callback: Callable[[WindowChangedEvent], object], *, package: str | None = None
    ) -> EventRegistration[WindowChangedEvent]:
        """``register(WindowChangedEvent, callback)``: every window that appears, goes away or changes."""
        return self.register(WindowChangedEvent, callback, package=package)

    def on_content_change(
        self, callback: Callable[[ContentChangedEvent], object], *, package: str | None = None
    ) -> EventRegistration[ContentChangedEvent]:
        """``register(ContentChangedEvent, callback)``: every change inside a window (frequent)."""
        return self.register(ContentChangedEvent, callback, package=package)

    def on_click(
        self, callback: Callable[[ViewClickedEvent], object], *, package: str | None = None
    ) -> EventRegistration[ViewClickedEvent]:
        """``register(ViewClickedEvent, callback)``: every clicked view."""
        return self.register(ViewClickedEvent, callback, package=package)

    def on_scroll(
        self, callback: Callable[[ViewScrolledEvent], object], *, package: str | None = None
    ) -> EventRegistration[ViewScrolledEvent]:
        """``register(ViewScrolledEvent, callback)``: every scroll."""
        return self.register(ViewScrolledEvent, callback, package=package)

    def on_text_change(
        self, callback: Callable[[TextChangedEvent], object], *, package: str | None = None
    ) -> EventRegistration[TextChangedEvent]:
        """``register(TextChangedEvent, callback)``: every edit of an editable view's text."""
        return self.register(TextChangedEvent, callback, package=package)

    def expect(
        self,
        kind: type[E],
        *,
        where: Callable[[E], bool] | None = None,
        package: str | None = None,
    ) -> ExpectedEvent[E]:
        """Subscribes now for the first event of ``kind`` that ``where`` accepts (every event
        when it is None), to ``wait`` for after an action — the event cannot slip past between
        the action and the wait::

            with device.events.expect(ToastShownEvent, where=lambda e: "Saved" in e.text) as saved:
                app.element(text("Save")).tap()
                toast = saved.wait(timeout=5)
        """
        kind = _kind(kind)
        if where is not None and not callable(where):
            raise TypeError("where must be callable")
        package = _package(package)
        self._device._ensure_usable("events.expect")
        return ExpectedEvent(self._device, kind, where, package, self._device.timeouts.wait)

    def wait_for(
        self,
        kind: type[E],
        *,
        where: Callable[[E], bool] | None = None,
        package: str | None = None,
        timeout: float | None = None,
    ) -> E:
        """Blocks for the first event of ``kind`` that ``where`` accepts, from now on, up to
        ``timeout`` seconds (default: ``timeouts.wait``). Only events that fire after the call
        count: to wait for what an action causes, use ``expect`` before the action."""
        with self.expect(kind, where=where, package=package) as expected:
            return expected.wait(timeout)
