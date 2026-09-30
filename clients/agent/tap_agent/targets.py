"""Parsing what an agent types: targets (``@e7`` or ``id=login,text=OK``), durations and keys."""

from __future__ import annotations

import re
from dataclasses import dataclass

from tap_e2e import CONTAINS, ENDS_WITH, Selector, all_of, class_name, desc, hint, res, res_id, text


class UsageError(ValueError):
    """The arguments cannot be understood (CLI exit code 2)."""


@dataclass(frozen=True)
class Target:
    """What an action is aimed at: a snapshot ref, or a selector written by the agent."""

    ref: str | None = None
    selector: Selector | None = None
    source: str = ""

    def describe(self) -> str:
        return f"@{self.ref}" if self.ref else self.source


_REF = re.compile(r"@?(e\d+)")
_QUALIFIED_ID = re.compile(r"([\w.]+):id/(.+)")

SELECTOR_KEYS = "id, text, text~, desc, desc~, class, hint, pkg, index"


def _split(spec: str) -> list[str]:
    """Splits on commas not preceded by a backslash; ``\\,`` stands for a literal comma."""
    parts, current, escaped = [], [], False
    for char in spec:
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == ",":
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if escaped:
        current.append("\\")
    parts.append("".join(current))
    return parts


def _term(key: str, value: str) -> Selector:
    if key == "id":
        qualified = _QUALIFIED_ID.fullmatch(value)
        return res_id(qualified.group(1), qualified.group(2)) if qualified else res(value)
    if key == "text":
        return text(value)
    if key == "text~":
        return text(value, CONTAINS)
    if key == "desc":
        return desc(value)
    if key == "desc~":
        return desc(value, CONTAINS)
    if key == "class":
        # A bare simple name (`Button`) matches any package's class of that name.
        return class_name(value) if "." in value else class_name(f".{value}", ENDS_WITH)
    if key == "hint":
        return hint(value)
    raise UsageError(f"unknown selector key {key!r} (known: {SELECTOR_KEYS})")


def parse_target(spec: str) -> Target:
    """``@e7`` / ``e7`` → a ref; ``key=value[,key=value...]`` → a selector matching all terms.

    Keys: ``id`` (the app's resource id, or ``pkg:id/name``), ``text``, ``desc``, ``hint``
    (exact), ``text~`` / ``desc~`` (contains), ``class`` (full name, or a simple name such as
    ``Button``), ``pkg`` (search that package's window instead of the app's) and ``index``
    (the N-th match, 0-based, instead of requiring exactly one)."""
    spec = spec.strip()
    ref = _REF.fullmatch(spec)
    if ref:
        return Target(ref=ref.group(1), source=spec)
    terms: list[Selector] = []
    package: str | None = None
    index: int | None = None
    for part in _split(spec):
        key, sep, value = part.partition("=")
        key = key.strip()
        if not sep or not key:
            raise UsageError(
                f"target {spec!r}: expected a ref (@e7) or key=value terms joined by commas "
                f"(keys: {SELECTOR_KEYS})"
            )
        if key == "pkg":
            package = value
        elif key == "index":
            if not value.isdigit():
                raise UsageError(f"target {spec!r}: index must be a number")
            index = int(value)
        else:
            terms.append(_term(key, value))
    if not terms:
        raise UsageError(f"target {spec!r} has no property to match (keys: {SELECTOR_KEYS})")
    selector = all_of(*terms)
    if package:
        selector = selector.in_package(package)
    if index is not None:
        selector = selector.at(index)
    return Target(selector=selector, source=spec)


_DURATION = re.compile(r"(\d+(?:\.\d+)?)(ms|s|m|h)?")
_UNIT = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0, None: 1.0}


def parse_duration(value: str | float | int) -> float:
    """Seconds from ``2500ms``, ``10s``, ``15m``, ``1h`` or a bare number of seconds."""
    if isinstance(value, (int, float)):
        seconds = float(value)
    else:
        match = _DURATION.fullmatch(value.strip())
        if not match:
            raise UsageError(f"bad duration {value!r} (examples: 500ms, 10s, 15m, 1h)")
        seconds = float(match.group(1)) * _UNIT[match.group(2)]
    if seconds < 0:
        raise UsageError(f"duration {value!r} is negative")
    return seconds


def format_duration(seconds: float) -> str:
    if seconds >= 3600 and seconds % 3600 == 0:
        return f"{int(seconds // 3600)}h"
    if seconds >= 60 and seconds % 60 == 0:
        return f"{int(seconds // 60)}m"
    if float(seconds).is_integer():
        return f"{int(seconds)}s"
    return f"{round(seconds * 1000)}ms"


# Android KeyEvent codes by the names an agent is likely to use.
KEYS = {
    "home": 3,
    "back": 4,
    "up": 19,
    "down": 20,
    "left": 21,
    "right": 22,
    "tab": 61,
    "space": 62,
    "enter": 66,
    "delete": 67,
    "backspace": 67,
    "menu": 82,
    "search": 84,
    "escape": 111,
    "forward-delete": 112,
    "app-switch": 187,
    "recents": 187,
}


def parse_key(name: str) -> int:
    """A key name from ``KEYS`` or a numeric Android key code."""
    lowered = name.strip().lower().replace("_", "-")
    if lowered.isdigit():
        return int(lowered)
    if lowered.startswith("keycode-"):
        lowered = lowered.removeprefix("keycode-")
    try:
        return KEYS[lowered]
    except KeyError:
        raise UsageError(f"unknown key {name!r} (known: {', '.join(KEYS)}, or a key code)") from None
