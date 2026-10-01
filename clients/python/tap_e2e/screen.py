"""The visible screen selector context."""

from __future__ import annotations

from typing import TYPE_CHECKING

from .element import Element, ElementWait
from .selectors import Selector

if TYPE_CHECKING:
    from .device import Device


class Screen:
    """Whatever is visible on a device: every window, of any app or the system UI.

    ``device.screen`` adds no package predicate, so a selector matches in any window: a
    permission dialog, the notification shade, a second app. Use ``device.app(package_name)``
    when the node must belong to one app.

    A node another window (the keyboard, a dialog) covers completely is not found; a gesture on
    a node it covers partly, with the touch point underneath, fails with ``CommandError``
    ``NOT_INTERACTABLE`` / ``OBSCURED`` before any input.

    Example::

        device.screen.wait(text("Allow")).visible()
        device.screen.element(text("Allow")).tap()
    """

    def __init__(self, device: Device):
        self.device = device

    def element(self, selector: Selector) -> Element:
        """A lazy element: ``selector`` as is, matched in every visible window. Nothing is
        looked up until an action or query runs."""
        return Element(self.device, selector)

    def wait(self, selector: Selector, timeout: float | None = None) -> ElementWait:
        """A wait on ``selector`` in every visible window; ``timeout`` (seconds) defaults to
        ``device.timeouts.wait``."""
        return ElementWait(
            self.device,
            selector,
            self.device.timeouts.wait if timeout is None else timeout,
        )

    def __repr__(self) -> str:
        return f"Screen({self.device.serial})"
