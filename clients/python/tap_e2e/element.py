"""Lazy elements and waits. An Element is a selector bound to a device; every method resolves it
again on the device, so nothing goes stale between calls."""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING

from . import _gen as pb
from .errors import CommandError, WaitTimeoutError
from .selectors import Selector

if TYPE_CHECKING:
    from .device import Device

DOWN = pb.DIR_DOWN
UP = pb.DIR_UP
LEFT = pb.DIR_LEFT
RIGHT = pb.DIR_RIGHT
DEFAULT_GESTURE_PERCENT = 80


class Element:
    """A selector bound to a device.

    Nothing is resolved until a method is called; every call sends the selector to the device
    again, so an Element can be kept for the whole test. Actions require exactly one match
    and raise ``CommandError`` (``AMBIGUOUS``/``NOT_FOUND``) before any input otherwise.
    """

    def __init__(self, device: Device, selector: Selector):
        self.device = device
        self.selector = selector

    def _run(self, timeout: float | None, **op) -> pb.CommandResult:
        return self.device.execute_or_raise(timeout, self.selector, **op)

    @property
    def _target(self) -> pb.Selector:
        return self.selector.proto

    # --- queries ----------------------------------------------------------------------------------

    def exists(self, timeout: float | None = None) -> bool:
        """True when at least one node matches right now (any number of matches is fine)."""
        return self._run(timeout, exists=pb.Exists(selector=self._target)).bool

    def count(self, timeout: float | None = None) -> int:
        """Matches in the focused window right now, ignoring the selector's match limit."""
        return self._run(timeout, count=pb.Count(selector=self._target)).count

    def snapshot(self, timeout: float | None = None) -> pb.ElementSnapshot:
        """State of the one matching node at this instant (AMBIGUOUS/NOT_FOUND otherwise)."""
        return self._run(timeout, snapshot=pb.Snapshot(selector=self._target)).snapshot

    def text(self, timeout: float | None = None) -> str | None:
        """Text of the one matching node, or None when it has none (an empty field's hint is not text)."""
        snapshot = self.snapshot(timeout)
        return snapshot.text if snapshot.HasField("text") else None

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
        ``d.element(resource_id("email")).text_equals("new")``.
        """
        self._run(timeout, set_text=pb.SetText(selector=self._target, text=value))

    def type_text(self, value: str, timeout: float | None = None) -> None:
        """Click the one matching node, then type ``value`` as key events wherever focus is.

        Unsupported characters are rejected before any input; otherwise it reports whether every
        key event was accepted. The field is not read back: assert the effect yourself.
        """
        self._run(timeout, type_text=pb.TypeText(selector=self._target, text=value))

    def clear_text(self, timeout: float | None = None) -> None:
        """``set_text("")``: ``ACTION_SET_TEXT`` on the one matching node, not read back."""
        self._run(timeout, clear_text=pb.ClearText(selector=self._target))

    def swipe(
        self,
        direction: pb.Direction,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> None:
        """One swipe gesture across the node in the direction the finger moves (``UP``/``DOWN``/``LEFT``/``RIGHT``)."""
        self._run(
            timeout,
            swipe=pb.Swipe(
                selector=self._target,
                direction=direction,
                distance_percent=distance_percent,
            ),
        )

    def scroll(
        self,
        direction: pb.Direction,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> bool:
        """One scroll segment towards ``direction``'s content edge (UiAutomator semantics: DOWN
        reveals content below). True while more content remains, False at the end or when no
        scroll was observed."""
        return self._run(
            timeout,
            scroll=pb.Scroll(
                selector=self._target,
                direction=direction,
                distance_percent=distance_percent,
            ),
        ).moved

    def scroll_until(
        self,
        target: Selector,
        direction: pb.Direction = DOWN,
        max_scrolls: int = 20,
        distance_percent: int = DEFAULT_GESTURE_PERCENT,
        timeout: float | None = None,
    ) -> Element:
        """Scrolls this container until ``target`` is visible inside it and returns the target as
        a lazy element scoped to this container (``descendant``), so a later action cannot hit a
        duplicate elsewhere on screen; a container with ``first()``/``at()`` cannot be carried
        into a relation, and then the bare ``target`` is returned. Once it has scrolled, a failure is ``CommandError`` INDETERMINATE whose
        detail says why (END_REACHED, MAX_SCROLLS or WAIT_TIMEOUT): the list moved, so it is not
        side-effect free and never a plain wait timeout. Before the first scroll it fails like
        any command (NOT_FOUND/AMBIGUOUS for the container), and a device WAIT_TIMEOUT then raises
        ``WaitTimeoutError``."""
        result = self.device.execute(
            self.device.timeouts.wait if timeout is None else timeout,
            scroll_until=pb.ScrollUntil(
                selector=target.proto,
                container=self._target,
                direction=direction,
                max_scrolls=max_scrolls,
                distance_percent=distance_percent,
            ),
        )
        if result.HasField("error"):
            if result.error.code == pb.ERR_WAIT_TIMEOUT:
                raise WaitTimeoutError(
                    f"{target.render()} to scroll into view in {self.selector.render()}",
                    self.device.serial,
                    result.duration_ms,
                )
            raise CommandError(result, "scroll_until", self.device.serial, target.render())
        container = self.selector
        return Element(
            self.device, target if container._has_pick else container.descendant(target)
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
        self.device = device
        self.selector = selector
        self.timeout = timeout

    def visible(self) -> Element:
        """Wait until at least one node matches; polled on the device in one RPC."""
        self._device_wait(
            f"{self.selector.render()} to be visible",
            wait_visible=pb.WaitVisible(selector=self.selector.proto),
        )
        return Element(self.device, self.selector)

    def gone(self) -> None:
        """Wait until no node matches; polled on the device in one RPC."""
        self._device_wait(
            f"{self.selector.render()} to be gone",
            wait_gone=pb.WaitGone(selector=self.selector.proto),
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
            f'text == "{expected}"', lambda s: s.HasField("text") and s.text == expected
        )

    def text_contains(self, part: str) -> Element:
        """Wait until the one matching node's text contains ``part``."""
        return self._property(
            f'text containing "{part}"', lambda s: s.HasField("text") and part in s.text
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
        result = self.device.execute(self.timeout, **op)
        if not result.HasField("error"):
            return
        if result.error.code == pb.ERR_WAIT_TIMEOUT:
            raise WaitTimeoutError(description, self.device.serial, result.duration_ms)
        (name,) = op
        raise CommandError(result, name, self.device.serial, self.selector.render())

    def _property(
        self, description: str, predicate: Callable[[pb.ElementSnapshot], bool]
    ) -> Element:
        element = Element(self.device, self.selector)
        last: dict[str, str | None] = {"seen": None}

        def check() -> bool:
            try:
                snapshot = element.snapshot()
            except CommandError as error:
                if error.code.name == "NOT_FOUND":
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
