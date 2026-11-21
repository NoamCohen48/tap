"""Short text for agents: one line per node, and the diff between two snapshots."""

from __future__ import annotations

from dataclasses import dataclass

from tap_e2e import ElementSnapshot, NodeChange, NodeFlag, ScreenNode, ScreenSnapshot, StandardAction

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


_SCROLLS = frozenset(a for a in StandardAction if a.name.startswith(("SCROLL_", "PAGE_")))
_PASSIVE = frozenset({StandardAction.FOCUS, StandardAction.CLEAR_FOCUS, StandardAction.SELECT,
                      StandardAction.CLEAR_SELECTION, StandardAction.COPY, StandardAction.SHOW_ON_SCREEN})


def accepts(node: ScreenNode) -> list[str]:
    """What the node itself accepts, in a fixed order: ``tap`` (clickable), ``long``
    (long-clickable), ``check`` (checkable), ``edit`` (editable), ``scroll`` (scrollable, or a
    scroll or page action) and ``actions`` (another accessibility action, standard or custom:
    see ``action @eN``). Focus, selection, copy and show-on-screen alone are not listed."""
    flags = node.flags
    words = [word for flag, word in ((NodeFlag.CLICKABLE, "tap"), (NodeFlag.LONG_CLICKABLE, "long"),
                                      (NodeFlag.CHECKABLE, "check")) if flag in flags]
    if node.editable:
        words.append("edit")
    if NodeFlag.SCROLLABLE in flags or any(a in _SCROLLS for a in node.actions):
        words.append("scroll")
    if node.custom_actions or any(a not in _SCROLLS and a not in _PASSIVE for a in node.actions):
        words.append("actions")
    return words


def node_line(node: ScreenNode, context: str = "") -> str:
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
    if context:
        parts.append(f"context={_quote(context)}")
    short_id = _short_id(node)
    if short_id:
        parts.append(f"id={short_id}")
    flags = node.flags
    for flag, word in (
        (NodeFlag.FOCUSED, "focused"),
        (NodeFlag.CHECKED, "checked"),
        (NodeFlag.SELECTED, "selected"),
    ):
        if flag in flags:
            parts.append(word)
    if NodeFlag.ENABLED not in flags and node.interactive:
        parts.append("disabled")
    accepted = accepts(node)
    if accepted:
        parts.append("accepts=" + ",".join(accepted))
    if node.selector is None:
        parts.append("(no selector)")
    elif node.by_index:
        parts.append("(by index)")
    return "  ".join(parts)


@dataclass(frozen=True)
class SnapshotFilter:
    """Presentation-only filters. Unknown window types remain visible."""

    package: str | None = None
    window: int | None = None
    hide_keyboard: bool = False
    hide_system_chrome: bool = False
    keyboard: bool = False
    """Show an input-method window's nodes; by default it is one header line with a count."""

    def collapsed(self, node: ScreenNode) -> bool:
        """The node is in a keyboard window that prints as its header only (``--window N``
        naming it shows it in full)."""
        return node.window_type == 2 and not self.keyboard and self.window is None

    def accepts(self, node: ScreenNode, window: int) -> bool:
        return (
            (self.package is None or node.window_package == self.package)
            and (self.window is None or window == self.window)
            and not (self.hide_keyboard and node.window_type == 2)
            and not (self.hide_system_chrome and node.window_type == 3)
        )


def _labels(node: ScreenNode) -> list[str]:
    # Never borrow password values, hints-as-values, or validation error messages.
    if node.password:
        return []
    return list(dict.fromkeys(
        value for value in (None if node.showing_hint else node.text, node.content_description) if value
    ))


@dataclass
class _Row:
    node: ScreenNode
    window: int
    parent: int | None
    labels: list[str]
    context: str = ""
    indent: int = 0
    visible: bool = False
    changed: bool = False


def _rows(nodes: tuple[ScreenNode, ...], level: str, filters: SnapshotFilter) -> list[_Row]:
    rows: list[_Row] = []
    stack: list[int] = []
    window = 0
    for node in nodes:
        if not rows or node.depth == 0 or node.window_package != rows[-1].node.window_package or (
            node.window_index and node.window_index != rows[-1].window
        ):
            window = node.window_index or window + 1
            stack.clear()
        while stack and rows[stack[-1]].node.depth >= node.depth:
            stack.pop()
        rows.append(_Row(node, window, stack[-1] if stack else None, _labels(node),
                         changed=node.change is not NodeChange.UNCHANGED))
        stack.append(len(rows) - 1)

    # Fold only passive descendants into the nearest interactive control. A scrollable
    # list must not borrow every row's labels. Cap accumulation as well as printed length.
    for row in reversed(rows):
        if row.parent is not None:
            parent = rows[row.parent]
            parent.changed |= row.changed
            if not row.node.interactive and not parent.node.password and NodeFlag.SCROLLABLE not in parent.node.flags:
                own = _labels(parent.node)
                parent.labels = list(dict.fromkeys(own + row.labels + parent.labels))[:8]

    for row in rows:
        node = row.node
        own = _labels(node)
        borrowed = [label for label in row.labels if label not in own] if node.interactive else []
        # A separate, unlabeled switch can use its containing row's descriptive context,
        # but the row never borrows the switch's value or label.
        if node.interactive and not node.password and NodeFlag.SCROLLABLE not in node.flags and not own and not borrowed and row.parent is not None:
            ancestor = row.parent
            while ancestor is not None:
                parent = rows[ancestor]
                if parent.node.interactive:
                    if NodeFlag.SCROLLABLE not in parent.node.flags:
                        borrowed = parent.labels
                    break
                ancestor = parent.parent
        row.context = " · ".join(borrowed)
        row.visible = filters.accepts(node, row.window) and shown(node, level)

    # Keep labeled ancestors as non-actionable grouping in interactive mode.
    for row in rows:
        if not row.visible:
            continue
        ancestor = row.parent
        while ancestor is not None:
            parent = rows[ancestor]
            if _labels(parent.node) and filters.accepts(parent.node, parent.window):
                parent.visible = True
            ancestor = parent.parent
    for row in rows:
        ancestor = row.parent
        while ancestor is not None:
            parent = rows[ancestor]
            if parent.visible:
                row.indent = parent.indent + 1
                break
            ancestor = parent.parent
    return rows


def _header(row: _Row) -> str:
    # Old servers have no ordinals; preserve their existing header format.
    suffix = f" · window {row.window}" if row.node.window_index else ""
    return f"# {row.node.window_package}{suffix}"


def _keyboard_header(row: _Row, count: int) -> str:
    """A collapsed keyboard window: it is up (it covers the screen's bottom), its keys are not shown."""
    nodes = f"{count} node{'s' if count != 1 else ''}"
    return f"{_header(row)} · keyboard ({nodes}, --keyboard to show)"


def _keyboard_counts(rows: list[_Row]) -> dict[int, int]:
    counts: dict[int, int] = {}
    for row in rows:
        if row.visible and row.node.window_type == 2:
            counts[row.window] = counts.get(row.window, 0) + 1
    return counts


def snapshot_text(snapshot: ScreenSnapshot, level: str = DEFAULT, *, filters: SnapshotFilter = SnapshotFilter()) -> str:
    """Ref-addressed outline. ``context`` describes a control, never its selector."""
    lines: list[str] = []
    window = None
    rows = _rows(snapshot.nodes, level, filters)
    keys = _keyboard_counts(rows)
    for row in rows:
        if not row.visible:
            continue
        if row.window != window:
            window = row.window
            lines.append(_keyboard_header(row, keys[row.window]) if filters.collapsed(row.node) else _header(row))
        if filters.collapsed(row.node):
            continue
        group = "  (group)" if level == INTERACTIVE and not row.node.interactive else ""
        lines.append("  " * row.indent + node_line(row.node, row.context) + group)
    return "\n".join(lines) if lines else "(no visible nodes)"


def diff_text(snapshot: ScreenSnapshot, level: str = DEFAULT, full: bool = False, *, filters: SnapshotFilter = SnapshotFilter()) -> str:
    """Contextual diff: changed descendants also surface their containing control.

    Removed labels cannot be reparented from the partial removed list; contextual controls
    in the affected window are conservatively repeated with ``=`` instead.
    """
    if all(n.change is NodeChange.NONE for n in snapshot.nodes) and not snapshot.removed:
        return snapshot_text(snapshot, level, filters=filters)
    removed_labels = {(n.window_index, n.window_package) for n in snapshot.removed if _labels(n)}
    lines: list[str] = []
    window = None
    rows = _rows(snapshot.nodes, level, filters)
    keys = _keyboard_counts(rows)
    selected: set[int] = set()
    for i, row in enumerate(rows):
        node = row.node
        context_changed = node.interactive and NodeFlag.SCROLLABLE not in node.flags and (
            row.changed or (node.window_index, node.window_package) in removed_labels
        )
        if not row.visible or (node.change is NodeChange.UNCHANGED and not full and not (
            node.content_invalid or node.error or context_changed
        )):
            continue
        selected.add(i)
        ancestor = row.parent
        while ancestor is not None:
            if rows[ancestor].visible:
                selected.add(ancestor)
            ancestor = rows[ancestor].parent
    for i, row in enumerate(rows):
        if i not in selected:
            continue
        node = row.node
        if filters.collapsed(node):
            if row.window != window:  # the keyboard changed: say it is up, not each key
                window = row.window
                lines.append(_keyboard_header(row, keys[row.window]))
            continue
        if row.window != window:
            window = row.window
            lines.append(_header(row))
        mark = "=" if node.change is NodeChange.UNCHANGED else "+"
        group = "  (group)" if level == INTERACTIVE and not node.interactive else ""
        lines.append(f"{mark} " + "  " * row.indent + node_line(node, row.context) + group)
    # Removed is a partial forest: never reconstruct descendant context from it.
    removed = [n for n in snapshot.removed if shown(n, level) and filters.accepts(n, n.window_index)]
    gone_keys = [n for n in removed if filters.collapsed(n)]
    removed = [n for n in removed if not filters.collapsed(n)]
    if removed or gone_keys:
        lines.append("# removed")
        listed = removed if full else removed[:MAX_REMOVED]
        lines.extend(f"- {node_line(n)}" for n in listed)
        if gone_keys:
            lines.append(f"- keyboard ({len(gone_keys)} node{'s' if len(gone_keys) != 1 else ''})")
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


def log(entries, steps: dict | None = None, *, dropped: int = 0, omitted: int = 0, more: int = 0, read_on: str | None = None) -> str:
    """Device log entries for an agent, one per line, each led by the time the server got it
    (``HH:MM:SS.mmm``, the host's local time: the clock of the steps' ``started_at``): a logcat
    line as ``P Tag: message``, a marker, gap or Tap action as ``── …``. Where the actions of one
    of the session's ``steps`` (by step id: ``seq``, ``action``) begin, a ``── step N <verb>
    [id]`` line names the step. Notes at the top: ``dropped`` entries the server no longer has,
    ``omitted`` earlier entries this read left out, a device clock that disagrees with the host's;
    at the end ``more`` later entries left out with ``read_on``, the flags that read them, and
    the cursor of the last entry."""
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
            lines.append(f"{at} {entry.summary}")
            continue
        line = entry.line
        letter = line.priority.letter if line.priority else "?"
        lines.append(f"{at} {letter} {line.tag}: {line.message}")
    if not entries:
        lines.append("no entries")
    elif more:
        lines.append(f"({more} more entries after this; read on with: log {read_on or f'--after {entries[-1].cursor}'})")
        lines.append(f"cursor {entries[-1].cursor}")
    else:
        lines.append(f"cursor {entries[-1].cursor}")
    return "\n".join(lines)


def element_line(element: ElementSnapshot) -> str:
    """One element as a snapshot-style line: ``[Button]  "Pay"  hint="…"  id=pay  focused``."""
    parts = []
    if element.class_name:
        parts.append(f"[{element.class_name.rsplit('.', 1)[-1]}]")
    if element.showing_hint:
        parts.append("empty")
    elif element.text:
        parts.append(_quote(element.text))
    if element.content_description and element.content_description != element.text:
        parts.append(f"desc={_quote(element.content_description)}")
    if element.hint and (element.showing_hint or element.hint != element.text):
        parts.append(f"hint={_quote(element.hint)}")
    name = element.resource_name
    if name:
        package, sep, entry = name.partition(":id/")
        parts.append(f"id={entry if sep and package == element.package_name else name}")
    for flag, word in ((element.focused, "focused"), (element.checked, "checked"), (element.selected, "selected"),
                       (element.scrollable, "scrollable"), (not element.enabled, "disabled")):
        if flag:
            parts.append(word)
    return "  ".join(parts) or "(a node with no class, text or id)"


def element_text(element: ElementSnapshot) -> str:
    """``inspect`` as text: the element's line, then only what is set."""
    b = element.bounds
    rows = [element_line(element), f"package   {element.package_name or '-'}", f"bounds    [{b.left},{b.top}][{b.right},{b.bottom}]"]
    state = [word for flag, word in (
        (element.enabled, "enabled"), (element.clickable, "clickable"), (element.long_clickable, "long-clickable"),
        (element.checkable, "checkable"), (element.checked, "checked"), (element.focusable, "focusable"),
        (element.focused, "focused"), (element.scrollable, "scrollable"), (element.selected, "selected")) if flag]
    rows.append(f"state     {' '.join(state) or 'none'}")
    offered = [a.name.lower().replace("_", "-") for a in element.actions] + [repr(label) for label in element.custom_actions]
    if offered:
        rows.append(f"actions   {', '.join(offered)}")
    if element.range is not None:
        r = element.range
        rows.append(f"range     {r.min:g}..{r.max:g}, now {r.current:g} ({r.type.name.lower()})")
    if element.child_count:
        rows.append(f"children  {element.child_count}")
    return "\n".join(rows)


def fields_text(values: dict) -> str:
    """A JSON-ready object as ``key  value`` lines, unset values left out, nested objects on one line."""
    def shown(value) -> str:
        if isinstance(value, bool):
            return "yes" if value else "no"
        if isinstance(value, dict):
            return ", ".join(f"{k} {shown(v)}" for k, v in value.items() if v not in (None, "", [], {}))
        if isinstance(value, (list, tuple)):
            return ", ".join(shown(v) for v in value)
        if isinstance(value, float) and value.is_integer():
            return str(int(value))
        return str(value)
    rows = [(key.replace("_", " "), shown(value)) for key, value in values.items() if value not in (None, "", [], {})]
    width = max((len(key) for key, _ in rows), default=0)
    return "\n".join(f"{key.ljust(width)}  {value}" for key, value in rows)
