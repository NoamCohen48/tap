"""Short text for agents: one line per node, and the diff between two snapshots."""

from __future__ import annotations

from collections.abc import Iterable

from tap_e2e import NodeChange, NodeFlag, ScreenNode, ScreenSnapshot

# What `snapshot` shows. INTERACTIVE: nodes an action can target; DEFAULT: those plus nodes with
# text or a description (what a person reads); ALL: every node, layout containers included.
INTERACTIVE = "interactive"
DEFAULT = "default"
ALL = "all"

MAX_TEXT = 80
# Removed nodes listed one by one; the rest are counted (leaving a screen removes all of it).
MAX_REMOVED = 10


def _quote(value: str) -> str:
    value = value.replace("\n", " ")
    if len(value) > MAX_TEXT:
        value = value[: MAX_TEXT - 1] + "…"
    return '"' + value.replace('"', '\\"') + '"'


def _short_id(node: ScreenNode) -> str | None:
    name = node.resource_name
    if not name:
        return None
    package, sep, entry = name.partition(":id/")
    return entry if sep and package == node.window_package else name


def shown(node: ScreenNode, level: str) -> bool:
    if level == ALL:
        return True
    if node.interactive:
        return True
    return level == DEFAULT and bool(node.text or node.content_description)


def node_line(node: ScreenNode) -> str:
    """``@e3  [Button] "Log in"  id=login_button  focused``."""
    parts = [f"@{node.ref}"]
    if node.class_name:
        parts.append(f"[{node.class_name.rsplit('.', 1)[-1]}]")
    if node.text:
        parts.append("password" if node.password else _quote(node.text))
    if node.content_description and node.content_description != node.text:
        parts.append(f"desc={_quote(node.content_description)}")
    if node.hint and node.hint != node.text:
        parts.append(f"hint={_quote(node.hint)}")
    short_id = _short_id(node)
    if short_id:
        parts.append(f"id={short_id}")
    flags = node.flags
    for flag, word in (
        (NodeFlag.FOCUSED, "focused"),
        (NodeFlag.CHECKED, "checked"),
        (NodeFlag.SELECTED, "selected"),
        (NodeFlag.SCROLLABLE, "scrollable"),
    ):
        if flag in flags:
            parts.append(word)
    if NodeFlag.ENABLED not in flags and node.interactive:
        parts.append("disabled")
    if node.selector is None:
        parts.append("(no selector)")
    elif node.by_index:
        parts.append("(by index)")
    return "  ".join(parts)


def _with_windows(nodes: Iterable[ScreenNode], prefix: str = "") -> list[str]:
    lines: list[str] = []
    window = None
    for node in nodes:
        if node.window_package != window:
            window = node.window_package
            lines.append(f"# {window}")
        lines.append(prefix + node_line(node))
    return lines


def snapshot_text(snapshot: ScreenSnapshot, level: str = DEFAULT) -> str:
    """The snapshot as lines, grouped under a ``# <package>`` line per window."""
    nodes = [n for n in snapshot.nodes if shown(n, level)]
    if not nodes:
        return "(no visible nodes)"
    return "\n".join(_with_windows(nodes))


def diff_text(snapshot: ScreenSnapshot, level: str = DEFAULT, full: bool = False) -> str:
    """What changed since the device's previous snapshot: ``+`` added, ``-`` removed (``=``
    unchanged, with ``full``). The whole snapshot when there was no previous one."""
    if all(n.change is NodeChange.NONE for n in snapshot.nodes) and not snapshot.removed:
        return snapshot_text(snapshot, level)
    lines: list[str] = []
    window = None
    for node in snapshot.nodes:
        if not shown(node, level) or (node.change is NodeChange.UNCHANGED and not full):
            continue
        if node.window_package != window:
            window = node.window_package
            lines.append(f"# {window}")
        mark = "=" if node.change is NodeChange.UNCHANGED else "+"
        lines.append(f"{mark} {node_line(node)}")
    removed = [n for n in snapshot.removed if shown(n, level)]
    if removed:
        lines.append("# removed")
        listed = removed if full else removed[:MAX_REMOVED]
        lines.extend(f"- {node_line(n)}" for n in listed)
        if len(listed) < len(removed):
            lines.append(f"- … and {len(removed) - len(listed)} more")
    return "\n".join(lines) if lines else "(no change)"
