"""Device events for agents: the kinds by name, expectations, and one-line renderings.

The CLI and MCP name kinds with short words (``toast``, ``window``, ...) and expectations with
``KIND`` or ``KIND:TEXT``; everything below them is the typed ``tap_e2e`` event API.
"""
from __future__ import annotations

import threading
import time
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from tap_e2e import (  # pyright: ignore[reportMissingImports]
    ContentChangedEvent,
    Device,
    DeviceEvent,
    EventRegistration,
    TextChangedEvent,
    ToastShownEvent,
    ViewClickedEvent,
    ViewScrolledEvent,
    WindowChangedEvent,
)

from .output import json_value
from .targets import UsageError

KINDS: dict[str, type[DeviceEvent]] = {
    "toast": ToastShownEvent,
    "window": WindowChangedEvent,
    "content": ContentChangedEvent,
    "click": ViewClickedEvent,
    "scroll": ViewScrolledEvent,
    "text": TextChangedEvent,
}
"""The kind names the CLI and MCP take, and the event class each stands for."""

KIND_NAMES = tuple(KINDS)
_NAME_OF = {cls: name for name, cls in KINDS.items()}

# How long a step with --events keeps listening after it returned, for events its input causes a
# moment later (a toast is posted after the click is handled).
GRACE_SECONDS = 0.5
# Most event lines a step prints; content changes are summed per package instead.
MAX_LINES = 30
_TEXT_CHARS = 80


def kind(name: str) -> type[DeviceEvent]:
    """The event class a kind name stands for; ``UsageError`` for an unknown one."""
    cls = KINDS.get(name.strip().lower())
    if cls is None:
        raise UsageError(f"unknown event kind {name!r}: use one of {', '.join(KIND_NAMES)}")
    return cls


def kinds(names: Iterable[str]) -> list[type[DeviceEvent]]:
    """Kind names, each also accepted as a comma-separated list (``toast,window``), in order and
    without repeats."""
    chosen: list[type[DeviceEvent]] = []
    for value in names:
        for part in value.split(","):
            if part.strip():
                cls = kind(part)
                if cls not in chosen:
                    chosen.append(cls)
    return chosen


def subject(event: DeviceEvent) -> str:
    """The text an expectation's ``:TEXT`` is looked for in: a toast's message, a window's title
    and class, a click's label, an edit's text, a content change's or scroll's view class."""
    if isinstance(event, ToastShownEvent):
        return event.text
    if isinstance(event, WindowChangedEvent):
        return " ".join(part for part in (event.title, event.class_name) if part)
    if isinstance(event, ViewClickedEvent):
        return event.label or ""
    if isinstance(event, TextChangedEvent):
        return "" if event.password else event.text
    if isinstance(event, (ContentChangedEvent, ViewScrolledEvent)):
        return event.class_name or ""
    return ""


@dataclass(frozen=True)
class Expectation:
    """``KIND`` or ``KIND:TEXT``: an event of that kind (whose subject contains TEXT, ignoring
    case) must happen."""

    kind: type[DeviceEvent]
    text: str | None

    @classmethod
    def parse(cls, spec: str) -> Expectation:
        name, sep, text = spec.partition(":")
        if sep and not text.strip():
            raise UsageError(f"expectation {spec!r} has an empty text: write KIND or KIND:TEXT")
        return cls(kind(name), text.strip() if sep else None)

    def matches(self, event: DeviceEvent) -> bool:
        return isinstance(event, self.kind) and (self.text is None or self.text.lower() in subject(event).lower())

    def __str__(self) -> str:
        name = _NAME_OF[self.kind]
        return f"{name}:{self.text}" if self.text is not None else name


def _quote(text: str | None) -> str:
    if not text:
        return ""
    short = text if len(text) <= _TEXT_CHARS else text[: _TEXT_CHARS - 1] + "…"
    return '"' + short.replace("\n", " ").replace('"', '\\"') + '"'


def _short_class(name: str | None) -> str:
    return (name or "").rsplit(".", 1)[-1]


def _join(*parts: str) -> str:
    return " ".join(part for part in parts if part)


def line(event: DeviceEvent) -> str:
    """One event as one line: its kind, what it is about, and the package in parentheses."""
    package = f"({event.package_name})" if event.package_name else ""
    if isinstance(event, ToastShownEvent):
        return _join("toast", _quote(event.text), package)
    if isinstance(event, WindowChangedEvent):
        changes = "+".join(sorted(change.name.lower() for change in event.changes))
        return _join("window", changes, event.class_name or "", _quote(event.title), package)
    if isinstance(event, ViewClickedEvent):
        return _join("click", _short_class(event.class_name), _quote(event.label), package)
    if isinstance(event, ViewScrolledEvent):
        position = " ".join(
            f"{axis}={value}/{maximum}" if maximum is not None else f"{axis}={value}"
            for axis, value, maximum in (("x", event.scroll_x, event.max_scroll_x), ("y", event.scroll_y, event.max_scroll_y))
            if value is not None
        )
        deltas = " ".join(f"{axis}={value:+d}" for axis, value in (("dx", event.delta_x), ("dy", event.delta_y)) if value)
        items = ""
        if event.from_index is not None and event.to_index is not None:
            items = f"items {event.from_index}-{event.to_index}" + (f"/{event.item_count}" if event.item_count is not None else "")
        return _join("scroll", _short_class(event.class_name), position, deltas, items, package)
    if isinstance(event, TextChangedEvent):
        if event.password:
            return _join("text", _short_class(event.class_name), "(password)", package)
        return _join("text", _short_class(event.class_name), _quote(event.text), f"(was {_quote(event.before_text) or 'empty'})", package)
    if isinstance(event, ContentChangedEvent):
        changes = "+".join(sorted(change.name.lower() for change in event.changes))
        return _join("content", _short_class(event.class_name), changes, package)
    return type(event).__name__


def json_event(event: DeviceEvent) -> dict:
    """An event as JSON data: ``kind`` plus its fields; a password field's texts are left out."""
    data = json_value(event)
    if isinstance(event, TextChangedEvent) and event.password:
        data["text"] = data["before_text"] = None
    return {"kind": _NAME_OF[type(event)], **data}


def block(events: Sequence[DeviceEvent], expectations: Sequence[Expectation] = (), lost: Sequence[str] = ()) -> str:
    """The ``events:`` block after a step: one line per event in order (``✓`` on one an
    expectation was met by), content changes summed per package at the end, then any loss."""
    met = {id(next(e for e in events if x.matches(e))) for x in expectations if any(x.matches(e) for e in events)}
    lines: list[str] = []
    content: dict[str, int] = {}
    for event in events:
        if isinstance(event, ContentChangedEvent):
            content[event.package_name or "?"] = content.get(event.package_name or "?", 0) + 1
            continue
        lines.append(("✓ " if id(event) in met else "  ") + line(event))
    shown = lines[:MAX_LINES]
    if len(lines) > MAX_LINES:
        shown.append(f"  … {len(lines) - MAX_LINES} more")
    shown += [f"  content ×{count} ({package})" for package, count in sorted(content.items())]
    shown += [f"! events lost: {reason}" for reason in lost]
    return "events:\n" + ("\n".join(shown) if shown else "  (none)")


class Observation:
    """Events of some kinds collected around one step; subscribed before it starts, so the
    step's own events are never missed."""

    def __init__(self, device: Device, classes: list[type[DeviceEvent]]) -> None:
        self._lock = threading.Lock()
        self._changed = threading.Condition(self._lock)
        self.events: list[DeviceEvent] = []
        self._registrations: list[EventRegistration] = []
        try:
            for cls in classes:
                self._registrations.append(device.events.register(cls, self._add))
        except BaseException:
            self.close()
            raise

    def _add(self, event: DeviceEvent) -> None:
        with self._changed:
            self.events.append(event)
            self._changed.notify_all()

    def wait(self, expectations: Sequence[Expectation], timeout: float) -> list[Expectation]:
        """Waits up to ``timeout`` seconds for every expectation; returns the unmet ones."""
        deadline = time.monotonic() + timeout
        with self._changed:
            while True:
                unmet = [x for x in expectations if not any(x.matches(e) for e in self.events)]
                remaining = deadline - time.monotonic()
                if not unmet or remaining <= 0 or not self.listening:
                    return unmet
                self._changed.wait(min(remaining, 0.05))

    @property
    def listening(self) -> bool:
        return all(r.active for r in self._registrations)

    def lost(self) -> list[str]:
        """Why a registration ended early (events lost, connection lost)."""
        return [str(r.error) for r in self._registrations if r.error is not None]

    def snapshot(self) -> list[DeviceEvent]:
        with self._lock:
            events = list(self.events)
        # The registrations each deliver in order; together they interleave by sequence.
        return sorted(events, key=lambda e: e.sequence)

    def close(self) -> None:
        for registration in self._registrations:
            registration.close()
