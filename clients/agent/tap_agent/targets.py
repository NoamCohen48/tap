"""Parsing what an agent types: targets (``@e7`` or ``id=login,text=OK``), durations and keys."""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from typing import Any

from tap_e2e import (  # pyright: ignore[reportMissingImports]
    ENDS_WITH,
    MatchMode,
    Selector,
    all_of,
    any_of,
    class_name,
    desc,
    hint,
    res,
    res_id,
    text,
)
from tap_e2e import proto as pb  # pyright: ignore[reportMissingImports]


class UsageError(ValueError):
    """The arguments cannot be understood (CLI exit code 2)."""


@dataclass(frozen=True)
class Target:
    """What an action is aimed at: a snapshot ref, or a selector written by the agent and the
    package whose nodes it searches (None: the whole screen)."""

    ref: str | None = None
    selector: Selector | None = None
    package: str | None = None
    source: str = ""

    def describe(self) -> str:
        return f"@{self.ref}" if self.ref else self.source


_REF = re.compile(r"@?(e\d+)")
_QUALIFIED_ID = re.compile(r"([\w.]+):id/(.+)")

FLAGS = ("enabled", "checked", "checkable", "clickable", "focused", "focusable", "long_clickable", "scrollable", "selected")
SELECTOR_KEYS = "id, text, text~, desc, desc~, class, hint, pkg, index; boolean flags; text/desc/hint/class/pkg:MODE"
MAX_TARGET_BYTES = 65536
MODES = {mode.name.lower(): mode for mode in MatchMode}


def _split(spec: str) -> list[str]:
    """Splits on commas not preceded by a backslash; ``\\,`` stands for a literal comma."""
    parts, current, escaped = [], [], False
    for char in spec:
        if escaped:
            if char not in (",", "\\"):
                current.append("\\")
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


def _string_length(value: str) -> int:
    """Protocol string bounds count Kotlin/Java UTF-16 units, not Python code points."""
    try:
        return len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError as error:
        raise UsageError("selector strings must contain valid Unicode") from error


def _term(key: str, value: str | bool | dict[str, Any]) -> Selector:
    if key in FLAGS:
        if isinstance(value, str) and value.strip().lower() in ("true", "false"):
            value = value.strip().lower() == "true"
        if not isinstance(value, bool):
            raise UsageError(f"{key} must be true or false")
        return Selector.from_proto(pb.Selector(node=pb.Node(flag=pb.Flag(
            property=getattr(pb, f"FLAG_{key.upper()}"), value=value))))
    base, colon, mode_name = key.partition(":")
    if base.endswith("~"):
        base, mode_name = base[:-1], "contains"
    if isinstance(value, dict):
        if set(value) != {"value", "mode"} or not isinstance(value["mode"], str):
            raise UsageError("a text match is a string or {value: string, mode: string}")
        value, mode_name = value["value"], value["mode"]
        colon = ":"
    if not isinstance(value, str) or _string_length(value) > 1024:
        raise UsageError(f"{key} must be a string of at most 1024 characters")
    mode = MODES.get(mode_name.lower().replace("-", "_") if mode_name else "exact")
    if mode is None:
        raise UsageError(f"unknown match mode {mode_name!r}; use {', '.join(MODES)}")
    if base == "id" and not colon and not mode_name:
        qualified = _QUALIFIED_ID.fullmatch(value)
        return res_id(qualified.group(1), qualified.group(2)) if qualified else res(value)
    if base in ("text", "desc", "hint", "class"):
        if base == "class" and not colon and not mode_name and "." not in value:
            return class_name(f".{value}", ENDS_WITH)
        return {"text": text, "desc": desc, "hint": hint, "class": class_name}[base](value, mode)
    if base == "pkg":
        if not value:
            raise UsageError("pkg must not be empty")
        return Selector.from_proto(pb.Selector(node=pb.Node(match=pb.Match(
            property=pb.PROPERTY_PACKAGE_NAME, value=value, mode=getattr(pb, f"MATCH_{mode.name}")))))
    raise UsageError(f"unknown selector key {key!r} (known: {SELECTOR_KEYS})")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise UsageError(f"duplicate selector key {key!r}")
        result[key] = value
    return result


def _structured(spec: Any, depth: int = 1) -> Selector:
    if not isinstance(spec, dict) or not spec or depth > 32:
        raise UsageError("a selector is a nonempty object, nested at most 32 levels")
    terms: list[Selector] = []
    for key, value in spec.items():
        if key in ("index", "first"):
            if depth != 1:
                raise UsageError("index/first are allowed only on the outer selector")
            continue
        if key in ("all", "any"):
            if not isinstance(value, list) or not 2 <= len(value) <= 256:
                raise UsageError(f"{key} needs 2..256 selector objects")
            operands = [_structured(item, depth + 1) for item in value]
            terms.append((all_of if key == "all" else any_of)(*operands))
        elif key in ("parent", "ancestor", "child", "descendant"):
            related = _structured(value, depth + 1)
            relation = pb.Related(relation=getattr(pb, f"RELATION_{key.upper()}"), node=related.to_proto().node)
            terms.append(Selector.from_proto(pb.Selector(node=pb.Node(related=relation))))
        else:
            terms.append(_term(key, value))
    if not terms:
        raise UsageError("a selector needs at least one predicate")
    selector = all_of(*terms)
    if "index" in spec and "first" in spec:
        raise UsageError("choose index or first, not both")
    if "index" in spec:
        index = spec["index"]
        if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < 2**31:
            raise UsageError("index must be an integer in 0..2147483647")
        selector = selector.at(index)
    if "first" in spec:
        if spec["first"] is not True:
            raise UsageError("first must be true")
        selector = selector.first()
    return selector


def _bounded(selector: Selector) -> Selector:
    stack = [(selector.to_proto().node, 1)]
    count = 0
    while stack:
        node, depth = stack.pop()
        count += 1
        if depth > 32 or count > 256:
            raise UsageError("selector exceeds the 32-level / 256-node limit")
        kind = node.WhichOneof("kind")
        if kind in ("all_of", "any_of"):
            stack.extend((child, depth + 1) for child in getattr(node, kind).nodes)
        elif kind == "related":
            stack.append((node.related.node, depth + 1))
        elif kind == "match" and _string_length(node.match.value) > 1024:
            raise UsageError("selector strings must be at most 1024 UTF-16 units")
    return selector


def parse_int32(value: object, name: str) -> int:
    """A bounded decimal integer, rejecting bools, digit-like Unicode and oversized strings."""
    message = f"{name} must be an integer in 0..2147483647"
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise UsageError(message)
    if isinstance(value, str):
        if re.fullmatch(r"[0-9]{1,10}", value) is None:
            raise UsageError(message)
        value = int(value)
    if not 0 <= value < 2**31:
        raise UsageError(message)
    return value


def parse_target(spec: str) -> Target:
    """``@e7`` / ``e7`` → a ref; ``key=value[,key=value...]`` → a selector matching all terms.

    Keys: ``id`` (a resource id in any package, or ``pkg:id/name``), ``text``, ``desc``,
    ``hint`` (exact), ``text~`` / ``desc~`` (contains), ``class`` (full name, or a simple name
    such as ``Button``), ``pkg`` (only that package's nodes; without it, the whole screen) and
    ``index`` (the N-th match, 0-based, instead of requiring exactly one)."""
    try:
        size = len(spec.encode("utf-8"))
    except UnicodeEncodeError as error:
        raise UsageError("target must contain valid Unicode") from error
    if size > MAX_TARGET_BYTES:
        raise UsageError("target exceeds 64 KiB")
    spec = spec.strip()
    if spec.startswith("{"):
        try:
            selector = _structured(json.loads(spec, object_pairs_hook=_unique_object))
        except UsageError:
            raise
        except (ValueError, RecursionError) as error:
            raise UsageError("invalid JSON selector") from error
        return Target(selector=_bounded(selector), source=spec)
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
            if package is not None or not value or _string_length(value) > 1024:
                raise UsageError("pkg must be nonempty, at most 1024 characters, and given once")
            package = value
        elif key == "index":
            if index is not None:
                raise UsageError("index must be given once")
            index = parse_int32(value, "index")
        else:
            terms.append(_term(key, value))
    if not terms:
        raise UsageError(f"target {spec!r} has no property to match (keys: {SELECTOR_KEYS})")
    selector = all_of(*terms)
    if index is not None:
        selector = selector.at(index)
    return Target(selector=_bounded(selector), package=package, source=spec)


def parse_condition(spec: str) -> Selector:
    """A wait's condition, in the target grammar (``enabled=true,text=Pay`` or a JSON selector):
    what the one node the target picks must satisfy. It has no ref, ``index`` / ``first`` or
    ``pkg``: those choose nodes, and the condition is checked on the node the target chose."""
    target = parse_target(spec)
    if target.ref is not None:
        raise UsageError("a condition is key=value terms or a JSON selector, not a ref")
    if target.package is not None:
        raise UsageError("a condition has no pkg: put the package on the target")
    assert target.selector is not None
    if target.selector.to_proto().WhichOneof("pick") is not None:
        raise UsageError("a condition has no index/first: it is checked on the node the target picks, so put the pick on the target")
    return target.selector


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
    if isinstance(value, bool) or not math.isfinite(seconds) or seconds < 0:
        raise UsageError(f"duration {value!r} must be finite and nonnegative")
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
        return parse_int32(lowered, "key code")
    if lowered.startswith("keycode-"):
        lowered = lowered.removeprefix("keycode-")
    try:
        return KEYS[lowered]
    except KeyError:
        raise UsageError(f"unknown key {name!r} (known: {', '.join(KEYS)}, or a key code)") from None
