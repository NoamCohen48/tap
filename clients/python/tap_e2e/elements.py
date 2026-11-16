"""Lazy collection reads, resolved afresh in one device command per call."""

from __future__ import annotations

from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .models import ElementSnapshot
from .selectors import Selector

if TYPE_CHECKING:
    from .device import Device


class Elements:
    """A read-only selector bound to a device, from ``App.elements`` or ``Screen.elements``.

    Construction performs no I/O. Each read resolves all matches afresh; no node handles are
    retained. App collections restrict matches to the app's package, screen collections do
    not. Use selector relations such as ``res("list").descendant(res("title"))`` to restrict
    matches to a container's descendants. ``first()`` / ``at()`` are rejected: use ``element``
    for singular reads. There is no implicit scrolling or iteration over live elements.
    """

    def __init__(self, device: Device, selector: Selector):
        if selector._has_pick:
            raise ValueError("elements does not accept first()/at(); use element for a single match")
        self.device: Device = device
        """The device searched on every read."""
        self.selector: Selector = selector
        """The selector resolved afresh on every read."""

    def snapshots(self, timeout: float | None = None) -> list[ElementSnapshot]:
        """Read all current matches with one device command, or ``[]`` when none match.

        Returns immutable value objects in accessibility order: service-reported window
        order, then post-order inside each window (children before their matching parent).
        This is not necessarily visual order. Matching/visibility rules are the same as
        singular element reads; virtualized lists expose only their current accessibility
        nodes, not the whole dataset.

        Resolves one match set and reads it sequentially, not as an atomic UI transaction.
        A stale lookup/read fails the whole operation with ``STALE_BEFORE_INPUT``: no partial
        results or internal retry. More than 1000 matches or a serialized snapshot list over
        1 MiB raises ``CommandError`` (``PAYLOAD_TOO_LARGE``), never silent truncation.
        ``timeout`` (seconds, default: the device action timeout) bounds the read; it does
        not wait for matches or collection stability.
        """
        result = self.device._execute_or_raise(
            timeout, self.selector, snapshots=pb.Snapshots(selector=self.selector._proto)
        )
        return [_proto.element_snapshot(value) for value in result.snapshots.elements]

    def texts(self, timeout: float | None = None) -> list[str | None]:
        """Raw text of every match, projected from one :meth:`snapshots` call.

        Keeps order, duplicates, empty strings and ``None`` (no text); never filters entries.
        On API 26+ an empty field may report its hint as text, as ``Element.text()`` does.
        Use ``snapshots()`` to inspect ``showing_hint`` or multiple state properties together.
        """
        return [value.text for value in self.snapshots(timeout)]

    def __repr__(self) -> str:
        return f"Elements({self.selector.render()} on {self.device.serial})"
