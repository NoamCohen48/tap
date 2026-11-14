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
    return level == DEFAULT and bool(node.text or node.content_description or node.content_invalid or node.error)


def node_line(node: ScreenNode) -> str:
    """``@e3  [Button] "Log in"  id=login_button  focused``."""
    parts = [f"@{node.ref}"]
    if node.class_name:
        parts.append(f"[{node.class_name.rsplit('.', 1)[-1]}]")
    if node.showing_hint:
        parts.append("empty")
    elif node.text:
        parts.append("password" if node.password else _quote(node.text))
    if node.content_description and node.content_description != node.text:
        parts.append(f"desc={_quote(node.content_description)}")
    hint = node.hint or (node.text if node.showing_hint else None)
    if hint and (node.showing_hint or hint != node.text):
        parts.append(f"hint={_quote(hint)}")
    if node.content_invalid:
        parts.append("invalid")
    if node.error:
        parts.append(f"error={_quote(node.error)}")
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
    unchanged, with ``full`` or current validation errors). The whole snapshot when there was
    no previous one. Validation state is not ref identity, so invalid nodes are always shown."""
    if all(n.change is NodeChange.NONE for n in snapshot.nodes) and not snapshot.removed:
        return snapshot_text(snapshot, level)
    lines: list[str] = []
    window = None
    for node in snapshot.nodes:
        if not shown(node, level) or (
            node.change is NodeChange.UNCHANGED and not full and not (node.content_invalid or node.error)
        ):
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


def local_time(iso: str | None) -> str:
    """A stored UTC timestamp (``2026-11-14T15:47:21.000Z``) as the host's local
    ``YYYY-MM-DD HH:MM``; ``-`` for none."""
    import datetime

    if not iso:
        return "-"
    moment = datetime.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone()
    return f"{moment:%Y-%m-%d %H:%M}"


def log_time(at: float) -> str:
    """``HH:MM:SS.mmm`` in the host's local time zone."""
    import datetime

    moment = datetime.datetime.fromtimestamp(at)
    return f"{moment:%H:%M:%S}.{moment.microsecond // 1000:03d}"


def clock_skew(entries) -> float | None:
    """How far the device's clock is ahead of the host's (seconds, negative: behind), from the
    log lines' two times; None without lines."""
    gaps = sorted(entry.line.device_time - entry.at for entry in entries if entry.line is not None and entry.at)
    return gaps[len(gaps) // 2] if gaps else None


def _span(seconds: float) -> str:
    seconds = abs(seconds)
    if seconds >= 2 * 86400:
        return f"{seconds / 86400:.0f} days"
    if seconds >= 2 * 3600:
        return f"{seconds / 3600:.0f} hours"
    if seconds >= 120:
        return f"{seconds / 60:.0f} minutes"
    return f"{seconds:.0f} seconds"


def log(entries, steps: dict | None = None, *, dropped: int = 0, omitted: int = 0, more: int = 0) -> str:
    """Device log entries for an agent, one per line, each led by the time the server got it
    (``HH:MM:SS.mmm``, the host's local time: the clock of the steps' ``started_at``): a logcat
    line as ``P Tag: message``, a marker, gap or Tap action as ``── …``. Where the actions of one
    of the session's ``steps`` (by step id: ``seq``, ``action``) begin, a ``── step N <verb>
    [id]`` line names the step. Notes at the top: ``dropped`` entries the server no longer has,
    ``omitted`` earlier entries this read left out, a device clock that disagrees with the host's;
    at the end ``more`` later entries left out and the cursor to read on from."""
    lines = []
    if dropped:
        lines.append(f"({dropped} older entries are no longer kept by the server)")
    if omitted:
        lines.append(f"({omitted} earlier entries not shown; raise --limit or narrow the read)")
    skew = clock_skew(entries)
    if skew is not None and abs(skew) >= 60:
        lines.append(f"(the device's clock is {_span(skew)} {'ahead of' if skew > 0 else 'behind'} the host's; times are the host's)")
    current = None
    for entry in entries:
        at = log_time(entry.at)
        if entry.line is None:
            step = entry.action.step if entry.action is not None else None
            if step != current and step is not None and steps and step in steps:
                lines.append(f"── step {steps[step]['seq']} {steps[step]['action']}  [{step}]")
            if entry.action is not None:
                current = step
            lines.append(f"{at} {entry}")
            continue
        line = entry.line
        letter = line.priority.letter if line.priority else "?"
        lines.append(f"{at} {letter} {line.tag}: {line.message}")
    if not entries:
        lines.append("no entries")
    elif more:
        lines.append(f"({more} more entries after this; read on with --after {entries[-1].cursor})")
        lines.append(f"cursor {entries[-1].cursor}")
    else:
        lines.append(f"cursor {entries[-1].cursor}")
    return "\n".join(lines)
