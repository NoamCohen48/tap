"""Explicit, bounded collection observation; never a dataset-completeness guarantee."""

from __future__ import annotations

import math
import time
from collections.abc import Callable, Hashable
from dataclasses import dataclass, replace
from enum import Enum
from typing import TYPE_CHECKING, Generic, TypeVar

from . import _gen as pb
from .elements import Elements
from .models import Direction, ElementSnapshot

if TYPE_CHECKING:
    from .element import Element
    from .selectors import Selector

K = TypeVar("K", bound=Hashable)


class Movement(str, Enum):  # noqa: UP042 -- Python 3.10 compatibility.
    """Observed movement, not whether the input was accepted."""

    UNKNOWN = "UNKNOWN"
    MOVED = "MOVED"
    UNCHANGED = "UNCHANGED"


class ScrollEdge(str, Enum):  # noqa: UP042 -- Python 3.10 compatibility.
    """An observed directional edge at that moment, not permanent dataset exhaustion."""

    UNKNOWN = "UNKNOWN"
    REACHED = "REACHED"
    NOT_REACHED = "NOT_REACHED"


@dataclass(frozen=True)
class ScrollResult:
    """Source-attributed accessibility evidence; absent evidence remains unknown."""

    movement: Movement = Movement.UNKNOWN
    edge: ScrollEdge = ScrollEdge.UNKNOWN

    @classmethod
    def _from_proto(cls, result: pb.CommandResult) -> ScrollResult:
        evidence = result.scroll_observation
        return cls(
            {1: Movement.MOVED, 2: Movement.UNCHANGED}.get(
                evidence.movement, Movement.UNKNOWN
            ),
            {1: ScrollEdge.REACHED, 2: ScrollEdge.NOT_REACHED}.get(
                evidence.edge, ScrollEdge.UNKNOWN
            ),
        )


class ScanStopReason(str, Enum):  # noqa: UP042 -- Python 3.10 compatibility.
    """Why a successful bounded scan stopped. NO_PROGRESS is not an end."""

    EDGE_DETECTED = "EDGE_DETECTED"
    SCROLL_LIMIT = "SCROLL_LIMIT"
    ITEM_LIMIT = "ITEM_LIMIT"
    TIME_LIMIT = "TIME_LIMIT"
    NO_PROGRESS = "NO_PROGRESS"


@dataclass(frozen=True)
class CollectedObservation(Generic[K]):
    """One caller-defined identity, with first/latest values and number of reads containing it."""

    key: K
    first_snapshot: ElementSnapshot
    last_snapshot: ElementSnapshot
    seen_in_reads: int


@dataclass(frozen=True)
class ScanResult(Generic[K]):
    """Detached observations in first-seen order, not guaranteed dataset order or completeness.

    On an exception, ``exception.scan_partial`` contains this result with ``stop_reason=None``.
    The exception keeps its original type/code, including INDETERMINATE; never replay input.
    """

    observations: tuple[CollectedObservation[K], ...]
    stop_reason: ScanStopReason | None
    scrolls: int
    """Scroll commands attempted, including a failing/indeterminate attempt in partial results."""
    reads: int
    """Successful collection reads (not the separate singular container checks)."""
    elapsed: float
    """Monotonic elapsed time in seconds."""
    last_scroll: ScrollResult | None
    warnings: tuple[str, ...]
    coverage: str = "NOT_GUARANTEED"


def _scan(
    container: Element,
    items: Selector,
    key: Callable[[ElementSnapshot], K],
    *,
    direction: Direction,
    max_scrolls: int,
    max_items: int,
    timeout: float,
    distance_percent: int,
    no_progress_limit: int,
) -> ScanResult[K]:
    for name, value, minimum in (
        ("max_scrolls", max_scrolls, 0),
        ("max_items", max_items, 1),
        ("no_progress_limit", no_progress_limit, 1),
        ("distance_percent", distance_percent, 1),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
            raise ValueError(f"{name} must be an integer >= {minimum}")
    if distance_percent > 100:
        raise ValueError("distance_percent must be <= 100")
    if isinstance(timeout, bool) or not math.isfinite(timeout) or timeout <= 0:
        raise ValueError("timeout must be positive and finite")
    if container.selector._has_pick or items._has_pick:
        raise ValueError("scan does not accept first()/at() selectors")
    if not isinstance(direction, Direction):
        raise ValueError("direction must be a Direction")
    rows = Elements(container.device, container.selector.descendant(items))
    started = time.monotonic()
    deadline = started + timeout
    collected: dict[K, CollectedObservation[K]] = {}
    warnings: set[str] = set()
    scrolls = reads = stalled = 0
    last_scroll: ScrollResult | None = None
    previous: tuple | None = None

    def result(reason: ScanStopReason | None) -> ScanResult[K]:
        return ScanResult(
            tuple(collected.values()),
            reason,
            scrolls,
            reads,
            time.monotonic() - started,
            last_scroll,
            tuple(sorted(warnings)),
        )

    def remaining() -> float:
        return max(0.0, deadline - time.monotonic())

    try:
        while True:
            if remaining() <= 0:
                return result(ScanStopReason.TIME_LIMIT)
            # Fail on an absent/ambiguous container, never silently read the entire screen.
            container.snapshot(timeout=min(remaining(), 120.0))
            if remaining() <= 0:
                return result(ScanStopReason.TIME_LIMIT)
            batch = rows.snapshots(timeout=min(remaining(), 120.0))
            reads += 1
            keys = [key(row) for row in batch]
            if any(value is None for value in keys):
                raise ValueError("scan keys must not be None")
            if len(set(keys)) != len(keys):
                raise ValueError("scan key collision within one collection read")
            fingerprint = tuple(
                (value, row.bounds) for value, row in zip(keys, batch, strict=True)
            )
            for value, row in zip(keys, batch, strict=True):
                old = collected.get(value)
                if old is not None:
                    if replace(old.last_snapshot, bounds=row.bounds) != row:
                        warnings.add("CONTENT_CHANGED")
                    collected[value] = replace(
                        old, last_snapshot=row, seen_in_reads=old.seen_in_reads + 1
                    )
                elif len(collected) < max_items:
                    collected[value] = CollectedObservation(value, row, row, 1)
                else:
                    warnings.add("ITEMS_OMITTED")
            # An item cap takes precedence: even this viewport may not have been retained.
            if len(collected) >= max_items:
                return result(ScanStopReason.ITEM_LIMIT)
            if last_scroll is not None and last_scroll.edge == ScrollEdge.REACHED:
                return result(ScanStopReason.EDGE_DETECTED)
            if remaining() <= 0:
                return result(ScanStopReason.TIME_LIMIT)
            if previous is not None:
                stalled = (
                    stalled + 1
                    if fingerprint == previous
                    and last_scroll is not None
                    and last_scroll.movement != Movement.MOVED
                    else 0
                )
                if stalled >= no_progress_limit:
                    return result(ScanStopReason.NO_PROGRESS)
            previous = fingerprint
            if scrolls >= max_scrolls:
                return result(ScanStopReason.SCROLL_LIMIT)
            step_timeout = min(remaining(), 120.0)
            if step_timeout <= 0:
                return result(ScanStopReason.TIME_LIMIT)
            scrolls += 1
            last_scroll = container.scroll(
                direction, distance_percent, timeout=step_timeout
            )
    except Exception as error:
        error.__dict__["scan_partial"] = result(None)
        raise
