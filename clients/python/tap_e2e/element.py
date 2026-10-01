"""Lazy elements and waits. An Element is a selector bound to a device; every method resolves it
again on the device, so nothing goes stale between calls."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .errors import CommandError, WaitTimeoutError
from .models import Direction, ElementSnapshot, ErrorCode
from .selectors import Selector

if TYPE_CHECKING:
    from .device import Device

# Module-level shorthands for the directions: ``element.scroll(DOWN)``.
DOWN = Direction.DOWN
UP = Direction.UP
LEFT = Direction.LEFT
RIGHT = Direction.RIGHT
DEFAULT_GESTURE_PERCENT = 80


class Element:
    """A selector bound to a device.

    Nothing is resolved until a method is called; every call sends the selector to the device
    again, so an Element can be kept for the whole test. Actions require exactly one match
    and raise ``CommandError`` (``AMBIGUOUS``/``NOT_FOUND``) before any input otherwise.
    """

    def __init__(self, device: Device, selector: Selector):
        self.device: Device = device
        """The device this element is looked up on."""
        self.selector: Selector = selector
        """The selector resolved again by every action."""

    def _run(self, timeout: float | None, **op) -> pb.CommandResult:
        return self.device._execute_or_raise(timeout, self.selector, **op)

    @property
    def _target(self) -> pb.Selector:
        return self.selector._proto

    # --- queries ----------------------------------------------------------------------------------

    def exists(self, timeout: float | None = None) -> bool:
        """True when at least one node matches right now (any number of matches is fine)."""
        return self._run(timeout, exists=pb.Exists(selector=self._target)).bool

    def count(self, timeout: float | None = None) -> int:
        """Matches in the selector's scope right now, ignoring its match limit."""
        return self._run(timeout, count=pb.Count(selector=self._target)).count

    def snapshot(self, timeout: float | None = None) -> ElementSnapshot:
        """State of the one matching node at this instant (AMBIGUOUS/NOT_FOUND otherwise)."""
        return _proto.element_snapshot(
            self._run(timeout, snapshot=pb.Snapshot(selector=self._target)).snapshot
        )

    def text(self, timeout: float | None = None) -> str | None:
        """Raw accessibility text of the one matching node, or None when it has none. On API 26+
        an empty field reports its hint here; ``snapshot().showing_hint`` says so."""
        return self.snapshot(timeout).text

    def is_enabled(self, timeout: float | None = None) -> bool:
        """``snapshot().enabled`` of the one matching node."""
        return self.snapshot(timeout).enabled

    def is_checked(self, timeout: float | None = None) -> bool:
        """``snapshot().checked`` of the one matching node."""
        return self.snapshot(timeout).checked

    # --- actions (exactly one match required) -----------------------------------------------------

    def tap(self, timeout: float | None = None) -> None:
        """Click at the centre of the one matching node's visible bounds."""
        self._run(timeout, tap=pb.Tap(selector=self._target))

    def long_tap(self, timeout: float | None = None) -> None:
        """Long click on the one matching node."""
        self._run(timeout, long_tap=pb.LongTap(selector=self._target))

    def set_text(self, value: str, timeout: float | None = None) -> None:
        """Accessibility text replacement (``ACTION_SET_TEXT``) on the one matching node.

        Fails with ``ACTION_REJECTED`` only when the node refuses the action. The field is not
        read back: assert the effect with a selector that survives the edit, e.g.
        ``app.wait(res("email")).text_equals("new")``.
        """
        self._run(timeout, set_text=pb.SetText(selector=self._target, text=value))

    def type_text(
        self, value: str, await_focus: bool = True, timeout: float | None = None
    ) -> None:
        """Tap the one matching node, wait until it reports focus, then type ``value``.

        Three steps (``tap``, ``wait().focused()`` when ``await_focus``, ``Device.type_text``),
        so a failure says which one failed. Pass ``await_focus=False`` when focus goes elsewhere
        (a child or a separate input view) and wait for what that app needs yourself. The field
        is not read back: assert the effect yourself.
        """
        self.tap(timeout)
        if await_focus:
            self.wait().focused()
        self.device.type_text(value, timeout)

    def clear_text(self, timeout: float | None = None) -> None:
        """``set_text("")``: ``ACTION_SET_TEXT`` on the one matching node, not read back."""
        self._run(timeout, clear_text=pb.ClearText(selector=self._target))

    def swipe(
        self,
        direction: Direction,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> None:
        """One swipe gesture across the node in the direction the finger moves (``UP``/``DOWN``/``LEFT``/``RIGHT``)."""
        self._run(
            timeout,
            swipe=pb.Swipe(
                selector=self._target,
                direction=_proto.direction(direction),
                distance_percent=distance_percent,
            ),
        )

    def scroll(
        self,
        direction: Direction,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> None:
        """One scroll gesture on the one matching node towards ``direction``'s content edge
        (UiAutomator semantics: DOWN reveals content below). Nothing is reported about whether
        content moved; observe that with a query or wait."""
        self._run(
            timeout,
            scroll=pb.Scroll(
                selector=self._target,
                direction=_proto.direction(direction),
                distance_percent=distance_percent,
            ),
        )

    def scroll_until(
        self,
        target: Selector,
        direction: Direction = DOWN,
        max_scrolls: int = 20,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> Element:
        """Client-side loop: checks whether ``target`` exists inside this container
        (``descendant``) and, while it does not, ``scroll``s once, up to ``max_scrolls`` scrolls
        within ``timeout`` seconds (default: the device's wait timeout). Returns the target as a
        lazy element scoped to this container, so a later action cannot hit a duplicate
        elsewhere on screen; a container with ``first()``/``at()`` cannot be carried into a
        relation, and then the bare ``target`` is used.

        Raises ``WaitTimeoutError`` when the target never appeared. The list may have scrolled
        by then. Every step is an ordinary command, so a failing step raises its own
        ``CommandError``."""
        if max_scrolls < 0:
            raise ValueError("max_scrolls must not be negative")
        container = self.selector
        found = Element(
            self.device, target if container._has_pick else container.descendant(target)
        )
        budget = self.device.timeouts.wait if timeout is None else timeout
        started = time.monotonic()
        scrolls = 0
        while True:
            if found.exists():
                return found
            if scrolls == max_scrolls or time.monotonic() - started >= budget:
                break
            self.scroll(direction, distance_percent)
            scrolls += 1
        raise WaitTimeoutError(
            f"{target.render()} to scroll into view in {container.render()}",
            self.device.serial,
            int((time.monotonic() - started) * 1000),
            polls=scrolls,
            last=f"not found after {scrolls} scrolls",
        )

    # --- derived ----------------------------------------------------------------------------------

    def wait(self, timeout: float | None = None) -> ElementWait:
        """An ``ElementWait`` on this selector (default timeout ``timeouts.wait``)."""
        return ElementWait(
            self.device,
            self.selector,
            self.device.timeouts.wait if timeout is None else timeout,
        )

    def descendant(self, other: Selector) -> Element:
        """The node matching ``other`` somewhere below this one."""
        return Element(self.device, self.selector.descendant(other))

    def child(self, other: Selector) -> Element:
        """The direct child of this node matching ``other``."""
        return Element(self.device, self.selector.child(other))

    def first(self) -> Element:
        """Accept the first match in accessibility order instead of requiring exactly one."""
        return Element(self.device, self.selector.first())

    def at(self, index: int) -> Element:
        """Accept the ``index``-th match (0-based) in accessibility order."""
        return Element(self.device, self.selector.at(index))

    def __repr__(self) -> str:
        return f"Element({self.selector.render()} on {self.device.serial})"


class ElementWait:
    """Returned by ``Device.wait`` / ``Element.wait``. ``visible`` and ``gone`` poll on the device
    in a single RPC; property waits poll snapshots from the host."""

    def __init__(self, device: Device, selector: Selector, timeout: float):
        self.device: Device = device
        """The device the wait runs on."""
        self.selector: Selector = selector
        """The selector being waited for."""
        self.timeout: float = timeout
        """How long each wait may take, in seconds."""

    def visible(self) -> Element:
        """Wait until at least one node matches; polled on the device in one RPC. A timeout's
        ``reason`` is ``NO_MATCH``."""
        self._device_wait(
            f"{self.selector.render()} to be visible",
            wait_visible=pb.WaitVisible(selector=self.selector._proto),
        )
        return Element(self.device, self.selector)

    def one(self) -> Element:
        """Wait until exactly one node matches — what a mutation such as ``tap()`` needs; polled
        on the device in one RPC. A timeout's ``reason`` is ``NO_MATCH`` or ``AMBIGUOUS`` with
        ``match_count`` from the last poll."""
        self._device_wait(
            f"{self.selector.render()} to match exactly one node",
            wait_visible=pb.WaitVisible(selector=self.selector._proto, exactly_one=True),
        )
        return Element(self.device, self.selector)

    def gone(self) -> None:
        """Wait until no node matches; polled on the device in one RPC. A timeout's ``reason`` is
        ``STILL_PRESENT`` with ``match_count``."""
        self._device_wait(
            f"{self.selector.render()} to be gone",
            wait_gone=pb.WaitGone(selector=self.selector._proto),
        )

    def enabled(self) -> Element:
        """Wait until the one matching node is enabled."""
        return self._property("enabled", lambda s: s.enabled)

    def disabled(self) -> Element:
        """Wait until the one matching node is disabled."""
        return self._property("disabled", lambda s: not s.enabled)

    def checked(self) -> Element:
        """Wait until the one matching node is checked."""
        return self._property("checked", lambda s: s.checked)

    def unchecked(self) -> Element:
        """Wait until the one matching node is unchecked."""
        return self._property("unchecked", lambda s: not s.checked)

    def focused(self) -> Element:
        """Wait until the one matching node has focus."""
        return self._property("focused", lambda s: s.focused)

    def text_equals(self, expected: str) -> Element:
        """Wait until the one matching node's text equals ``expected``."""
        return self._property(
            f'text == "{expected}"', lambda s: s.text == expected
        )

    def text_contains(self, part: str) -> Element:
        """Wait until the one matching node's text contains ``part``."""
        return self._property(
            f'text containing "{part}"', lambda s: s.text is not None and part in s.text
        )

    def count(self, expected: int) -> Element:
        """Wait until exactly ``expected`` nodes match."""
        element = Element(self.device, self.selector)
        last: dict[str, int | None] = {"count": None}

        def check() -> bool:
            last["count"] = element.count()
            return last["count"] == expected

        self.device.await_until(
            f"{self.selector.render()} count == {expected}",
            check,
            self.timeout,
            observe=lambda: f"count={last['count']}",
        )
        return element

    def _device_wait(self, description: str, **op) -> None:
        result = self.device._execute(self.timeout, **op)
        if not result.HasField("error"):
            return
        if result.error.code == pb.ERR_WAIT_TIMEOUT:
            raise WaitTimeoutError._from_result(result, description, self.device.serial)
        (name,) = op
        raise CommandError._from_result(result, name, self.device.serial, self.selector.render())

    def _property(
        self, description: str, predicate: Callable[[ElementSnapshot], bool]
    ) -> Element:
        element = Element(self.device, self.selector)
        last: dict[str, str | None] = {"seen": None}

        def check() -> bool:
            try:
                snapshot = element.snapshot()
            except CommandError as error:
                if error.code is ErrorCode.NOT_FOUND:
                    last["seen"] = "not found"
                    return False
                raise
            last["seen"] = (
                f"text={snapshot.text!r} enabled={snapshot.enabled} checked={snapshot.checked} focused={snapshot.focused}"
            )
            return predicate(snapshot)

        self.device.await_until(
            f"{self.selector.render()} to be {description}",
            check,
            self.timeout,
            observe=lambda: last["seen"],
        )
        return element
