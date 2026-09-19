"""Lazy elements and waits. An Element is a selector bound to a device; every method resolves it
again on the device, so nothing goes stale between calls."""
from __future__ import annotations

from typing import TYPE_CHECKING, Callable

from ._gen import tap_pb2 as pb
from .errors import CommandError, ErrorCode, WaitTimeoutError
from .selectors import Selector

if TYPE_CHECKING:
    from .device import Device

DOWN = pb.DIR_DOWN
UP = pb.DIR_UP
LEFT = pb.DIR_LEFT
RIGHT = pb.DIR_RIGHT
DEFAULT_GESTURE_PERCENT = 80


class Element:
    def __init__(self, device: "Device", selector: Selector):
        self.device = device
        self.selector = selector

    def _run(self, operation: int, timeout: float | None, **fields) -> pb.CommandResult:
        return self.device.execute_or_raise(operation, self.selector, timeout, **fields)

    # --- queries ----------------------------------------------------------------------------------

    def exists(self, timeout: float | None = None) -> bool:
        return self._run(pb.OP_EXISTS, timeout).value

    def count(self, timeout: float | None = None) -> int:
        """Matches in the focused window right now, ignoring the selector's match limit."""
        return self._run(pb.OP_COUNT, timeout).count

    def snapshot(self, timeout: float | None = None) -> pb.ElementSnapshot:
        """State of the one matching node at this instant (AMBIGUOUS/NOT_FOUND otherwise)."""
        return self._run(pb.OP_SNAPSHOT, timeout).snapshot

    def text(self, timeout: float | None = None) -> str | None:
        snapshot = self.snapshot(timeout)
        return snapshot.text if snapshot.HasField("text") else None

    def is_enabled(self, timeout: float | None = None) -> bool:
        return self.snapshot(timeout).enabled

    def is_checked(self, timeout: float | None = None) -> bool:
        return self.snapshot(timeout).checked

    # --- actions (exactly one match required) -----------------------------------------------------

    def tap(self, timeout: float | None = None) -> None:
        self._run(pb.OP_TAP, timeout)

    def long_tap(self, timeout: float | None = None) -> None:
        self._run(pb.OP_LONG_TAP, timeout)

    def set_text(self, value: str, timeout: float | None = None) -> None:
        """Accessibility text replacement, verified on the device."""
        self._run(pb.OP_SET_TEXT, timeout, input_text=value)

    def type_text(self, value: str, timeout: float | None = None) -> None:
        """Focus plus real key events; unsupported characters are rejected before any input."""
        self._run(pb.OP_TYPE_TEXT, timeout, input_text=value)

    def clear_text(self, timeout: float | None = None) -> None:
        self._run(pb.OP_CLEAR_TEXT, timeout)

    def swipe(self, direction: int, distance_percent: int = DEFAULT_GESTURE_PERCENT, timeout: float | None = None) -> None:
        self._run(pb.OP_SWIPE, timeout, direction=direction, distance_percent=distance_percent)

    def scroll(self, direction: int, distance_percent: int = DEFAULT_GESTURE_PERCENT, timeout: float | None = None) -> bool:
        """One scroll segment towards ``direction``'s content edge (UiAutomator semantics: DOWN
        reveals content below). True while more content remains, False at the end or when no
        scroll was observed."""
        return self._run(pb.OP_SCROLL, timeout, direction=direction, distance_percent=distance_percent).value

    def scroll_until(
        self,
        target: Selector,
        direction: int = DOWN,
        max_scrolls: int = 20,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> "Element":
        """Scrolls this container until ``target`` is visible inside it, or raises CommandError
        NOT_FOUND (END_REACHED/MAX_SCROLLS) or WAIT_TIMEOUT. Returns the target as a lazy element."""
        self.device.execute_or_raise(
            pb.OP_SCROLL_UNTIL, target, self.device.timeouts.wait if timeout is None else timeout,
            container_selector=self.selector, direction=direction, max_scrolls=max_scrolls, distance_percent=distance_percent,
        )
        return Element(self.device, target)

    # --- derived ----------------------------------------------------------------------------------

    def wait(self, timeout: float | None = None) -> "ElementWait":
        return ElementWait(self.device, self.selector, self.device.timeouts.wait if timeout is None else timeout)

    def descendant(self, other: Selector) -> "Element":
        return Element(self.device, self.selector.descendant(other))

    def child(self, other: Selector) -> "Element":
        return Element(self.device, self.selector.child(other))

    def first(self) -> "Element":
        return Element(self.device, self.selector.first())

    def at(self, index: int) -> "Element":
        return Element(self.device, self.selector.at(index))

    def __repr__(self) -> str:
        return f"Element({self.selector.render()} on {self.device.serial})"


class ElementWait:
    """Returned by ``Device.wait`` / ``Element.wait``. ``visible`` and ``gone`` poll on the device
    in a single RPC; property waits poll snapshots from the host."""

    def __init__(self, device: "Device", selector: Selector, timeout: float):
        self.device = device
        self.selector = selector
        self.timeout = timeout

    def visible(self) -> Element:
        self._device_wait(pb.OP_WAIT_VISIBLE, f"{self.selector.render()} to be visible")
        return Element(self.device, self.selector)

    def gone(self) -> None:
        self._device_wait(pb.OP_WAIT_GONE, f"{self.selector.render()} to be gone")

    def enabled(self) -> Element:
        return self._property("enabled", lambda s: s.enabled)

    def disabled(self) -> Element:
        return self._property("disabled", lambda s: not s.enabled)

    def checked(self) -> Element:
        return self._property("checked", lambda s: s.checked)

    def unchecked(self) -> Element:
        return self._property("unchecked", lambda s: not s.checked)

    def focused(self) -> Element:
        return self._property("focused", lambda s: s.focused)

    def text_equals(self, expected: str) -> Element:
        return self._property(f'text == "{expected}"', lambda s: s.HasField("text") and s.text == expected)

    def text_contains(self, part: str) -> Element:
        return self._property(f'text containing "{part}"', lambda s: s.HasField("text") and part in s.text)

    def count(self, expected: int) -> Element:
        element = Element(self.device, self.selector)
        last: dict[str, int | None] = {"count": None}

        def check() -> bool:
            last["count"] = element.count()
            return last["count"] == expected

        self.device.await_until(f"{self.selector.render()} count == {expected}", check, self.timeout, observe=lambda: f"count={last['count']}")
        return element

    def _device_wait(self, operation: int, description: str) -> None:
        result = self.device.execute(operation, self.selector, self.timeout)
        if result.ok:
            return
        if result.error_code == pb.ERR_WAIT_TIMEOUT:
            raise WaitTimeoutError(description, self.device.serial, result.duration_ms)
        raise CommandError(result, pb.Operation.Name(operation)[len("OP_"):], self.device.serial, self.selector.render())

    def _property(self, description: str, predicate: Callable[[pb.ElementSnapshot], bool]) -> Element:
        element = Element(self.device, self.selector)
        last: dict[str, str | None] = {"seen": None}

        def check() -> bool:
            try:
                snapshot = element.snapshot()
            except CommandError as error:
                if error.code == ErrorCode.NOT_FOUND:
                    last["seen"] = "not found"
                    return False
                raise
            last["seen"] = f"text={snapshot.text!r} enabled={snapshot.enabled} checked={snapshot.checked} focused={snapshot.focused}"
            return predicate(snapshot)

        self.device.await_until(f"{self.selector.render()} to be {description}", check, self.timeout, observe=lambda: last["seen"])
        return element
