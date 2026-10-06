"""Offline complete-link screen hypotheses; never execution equivalence or approval.

Thresholds are fixed candidate settings, not validated general-purpose constants. Exact typed
literals come from recorded fills, not labels. Their exclusion is a heuristic whose failures
must remain visible in independent evaluation; variants/bindings are not normalized here.
"""

from __future__ import annotations

import hashlib
import heapq
import json
import re
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from itertools import combinations

STRUCTURE_THRESHOLD = 0.9
TEXT_THRESHOLD = 0.5

Evidence = tuple[str, frozenset, frozenset]


def _jaccard(left: frozenset, right: frozenset) -> float:
    union = left | right
    return len(left & right) / len(union) if union else 1.0


def text_words(text: str) -> list[str]:
    return [word for word in re.split(r"[^0-9a-zA-Z]+", text.lower()) if word]


def shows(text: str, value: str) -> bool:
    """Whether ``text`` shows ``value`` as whole words, ignoring case ("• Grace" shows "Grace")."""
    words, wanted = text_words(text), text_words(value)
    return bool(wanted) and any(words[index:index + len(wanted)] == wanted
                                for index in range(len(words) - len(wanted) + 1))


def echoes(text: str, typed: Iterable[str]) -> bool:
    """A text that is, or shows, a value the explorer typed: data, not the screen.

    Values of one character are too short to recognise inside a longer text.
    """
    return any(text == value or (len(value) > 1 and shows(text, value)) for value in typed)


def editable(node: Mapping) -> bool:
    """Captured editability, falling back to the EditText class until it is always present."""
    return (node.get("editable") is True or "EDITABLE" in node.get("flags", ())
            or (node.get("class") or "").endswith("EditText"))


def node_evidence(package: str, nodes: Iterable[Mapping], typed: Iterable[str] = ()) -> Evidence:
    """Structure/text sets of one observation's owned nodes; never labels or refs.

    Hint equality excludes a text token from a screen hypothesis; it never declares a field
    empty. A text that shows a typed value (a list row "• Grace") is data too. Input values
    remain in exact variant evidence and execution guards. Transient views and window decor
    (``DECOR_RESOURCES``) are not evidence.
    """
    typed = set(typed)
    nodes = list(nodes)
    if any(node["package"] != package for node in nodes):
        raise ValueError("similarity requires owned snapshot nodes")
    skip = _transient_subtrees(nodes, _parents(nodes))
    structure, texts = set(), set()
    for index, node in enumerate(nodes):
        if index in skip:
            continue
        structure.add((node["resource"], node["class"]))
        if editable(node):
            continue
        for field in ("text", "description"):
            value = node.get(field)
            if isinstance(value, str) and value.strip() and not echoes(value, typed) and value != node.get("hint"):
                texts.add(" ".join(value.split()))
    return package, frozenset(structure), frozenset(texts)


def evidence(corpus: dict, case: dict) -> Evidence:
    """Corpus adapter for [node_evidence]; requires owned raw nodes, never consults labels."""
    source = corpus["sources"][case["source"]]
    return node_evidence(source["package"], case["snapshot"]["nodes"], source.get("typed_values", []))


def quality(left: Evidence, right: Evidence, *, structure_threshold: float = STRUCTURE_THRESHOLD,
            text_threshold: float = TEXT_THRESHOLD) -> float:
    """Normalized similarity; >= 1 means both thresholds pass. Different packages never match."""
    if left[0] != right[0]:
        return 0.0
    return min(_jaccard(left[1], right[1]) / structure_threshold, _jaccard(left[2], right[2]) / text_threshold)


def cluster_evidence(items: Mapping[str, Evidence], *, structure_threshold: float = STRUCTURE_THRESHOLD,
                     text_threshold: float = TEXT_THRESHOLD) -> dict[str, tuple[str, ...]]:
    """Deterministic complete-link agglomeration of all items; returns ID -> sorted member IDs.

    A merge requires *every* cross-cluster pair to meet both thresholds. The strongest
    weakest-pair similarity merges first; ties break by sorted member IDs. Arrival order does
    not affect membership. Identical evidence is grouped first (it always merges: its rows are
    equal), and only passing pairs are tracked, because complete-link similarity can only fall
    when clusters merge. Cost is about O(u^2) for u distinct evidence values, not O(n^4).
    """
    if not 0 < structure_threshold <= 1 or not 0 < text_threshold <= 1:
        raise ValueError("similarity thresholds must be in (0, 1]")
    by_evidence: dict[Evidence, list[str]] = {}
    for id in sorted(items):
        by_evidence.setdefault(items[id], []).append(id)
    groups: dict[int, tuple[str, ...]] = {}
    values: list[Evidence] = []
    for value, ids in sorted(by_evidence.items(), key=lambda entry: entry[1]):
        groups[len(values)] = tuple(ids)
        values.append(value)
    # Sparse complete-link similarity between live groups; absent means below threshold.
    links: dict[int, dict[int, float]] = {key: {} for key in groups}
    heap: list[tuple[float, tuple[str, ...], tuple[str, ...], int, int]] = []

    def push(a: int, b: int, value: float) -> None:
        left, right = sorted((a, b), key=lambda key: groups[key])
        heapq.heappush(heap, (-value, groups[left], groups[right], left, right))

    for a, b in combinations(range(len(values)), 2):
        value = quality(values[a], values[b], structure_threshold=structure_threshold,
                        text_threshold=text_threshold)
        if value >= 1:
            links[a][b] = links[b][a] = value
            push(a, b, value)
    next_key = len(values)
    while heap:
        negative, left_ids, right_ids, left, right = heapq.heappop(heap)
        if left not in groups or right not in groups or links[left].get(right) != -negative:
            continue  # Stale entry: a member already merged or the link weakened.
        merged = next_key
        next_key += 1
        groups[merged] = tuple(sorted(left_ids + right_ids))
        # Lance-Williams for complete link: the weakest of the two old links survives.
        links[merged] = {}
        for other in set(links[left]) & set(links[right]):
            if other in (left, right):
                continue
            value = min(links[left][other], links[right][other])
            links[merged][other] = links[other][merged] = value
        for old in (left, right):
            for other in links.pop(old):
                links.get(other, {}).pop(old, None)
            del groups[old]
        for other, value in links[merged].items():
            push(merged, other, value)
    return {id: members for members in groups.values() for id in members}


def join_scrolled(clusters: Mapping[str, tuple[str, ...]], pairs: Iterable[tuple[str, str]],
                  items: Mapping[str, Evidence], *,
                  structure_threshold: float = STRUCTURE_THRESHOLD) -> dict[str, tuple[str, ...]]:
    """Join the clusters of each (before, after) observation pair of a scroll in the app.

    A scroll moves content inside one screen and never navigates, so the rows and texts it
    reveals (the next page of a contributors list) are the same screen even when the texts no
    longer meet the text threshold. The pair must still meet the structure threshold: a pager
    swiped to a page built from other views stays a screen of its own.
    """
    parent = {members: members for members in set(clusters.values())}

    def find(members: tuple[str, ...]) -> tuple[str, ...]:
        while parent[members] != members:
            members = parent[members] = parent[parent[members]]
        return members

    for before, after in pairs:
        if before not in clusters or after not in clusters or items[before][0] != items[after][0]:
            continue
        if _jaccard(items[before][1], items[after][1]) >= structure_threshold:
            left, right = sorted((find(clusters[before]), find(clusters[after])))
            parent[right] = left
    joined: dict[tuple[str, ...], list[str]] = defaultdict(list)
    for members in parent:
        joined[find(members)].extend(members)
    return {id: tuple(sorted(members)) for members in joined.values() for id in members}


def join_in_place(clusters: Mapping[str, tuple[str, ...]], pairs: Iterable[tuple[str, str]]) -> dict[str, tuple[str, ...]]:
    """Join the clusters of each (before, after) pair of an input that edits a screen in place.

    Typing into a field, or checking a radio button or checkbox, that is still on screen
    afterwards did not navigate, whatever else it showed or hid (the Undo button after typing,
    the order options a "custom" sort hides). The caller passes only such pairs: the control
    must be on both sides, so a choice dialog that closes on selection stays its own screen.
    No structure threshold applies; the input itself is the evidence.
    """
    items = {id: ("", frozenset(), frozenset()) for id in clusters}
    return join_scrolled(clusters, pairs, items, structure_threshold=0.0)


WINDOW = "<window>"


def section_evidence(package: str, nodes: Iterable[Mapping], typed: Iterable[str] = ()) -> tuple[frozenset, frozenset]:
    """Structure and texts of one observation that do not depend on the app's data.

    Like [node_evidence], without lists and their rows (a list that is empty may not be drawn
    at all), and with one ``(WINDOW, n)`` item per window root (the first, second, ...), so a
    dialog over a screen is never read as a section of it.
    """
    typed = list(typed)
    nodes = [node for node in nodes if node["package"] == package]
    parents = _parents(nodes)
    skip = _transient_subtrees(nodes, parents)
    for index, node in enumerate(nodes):
        if (node["class"] or "").endswith(LIST_CLASSES) or parents[index] in skip:
            skip.add(index)
    structure, texts = set(), set()
    windows = 0
    for index, node in enumerate(nodes):
        if index in skip:
            continue
        if parents[index] is None:
            windows += 1
            structure.add((WINDOW, windows))
        else:
            structure.add((node["resource"], node["class"]))
        if editable(node):
            continue
        for field in ("text", "description"):
            value = node.get(field)
            if isinstance(value, str) and value.strip() and not echoes(value, typed) and value != node.get("hint"):
                texts.add(" ".join(value.split()))
    return frozenset(structure), frozenset(texts)


def join_sections(clusters: Mapping[str, tuple[str, ...]], sections: Mapping[str, tuple[frozenset, frozenset]],
                  opened: Iterable[tuple[str, str]]) -> dict[str, tuple[str, ...]]:
    """Join a screen with the same screen showing an optional section (a tab strip once there
    are two notes, a reminder's date row, a "checked items" group).

    Screen A is a part of screen B when every view and text A always shows is shown by B at
    least once, and B always shows views A never does: the section. A text A shows that B never
    does (a title) keeps them apart; so does a screen with no text of its own. Window roots count as views, so a dialog is
    never a section; list rows are data and do not count (``section_evidence``). A section an
    input of its own opened (``opened``: a tap or long press, from A's cluster to B's, that is
    not an edit) is a mode, such as a search bar or a selection mode: its screens stay apart,
    and so do any others whose difference includes one of its views.
    """
    groups = sorted(set(clusters.values()))
    core = {members: frozenset.intersection(*(sections[id][0] for id in members)) for members in groups}
    union = {members: frozenset.union(*(sections[id][0] for id in members)) for members in groups}
    texts = {members: frozenset.intersection(*(sections[id][1] for id in members)) for members in groups}
    shown = {members: frozenset.union(*(sections[id][1] for id in members)) for members in groups}

    def section(part: tuple[str, ...], whole: tuple[str, ...]) -> frozenset:
        """The views ``whole`` adds to ``part``, or nothing when ``part`` is not a part of it."""
        if part == whole or not texts[part] or not texts[part] <= shown[whole] or not core[part] <= union[whole]:
            return frozenset()
        added = core[whole] - union[part]
        return frozenset() if any(item[0] == WINDOW for item in added) else added

    modes: set = set()
    for before, after in opened:
        if before in clusters and after in clusters:
            modes |= section(clusters[before], clusters[after])
    pairs = []
    for part in groups:
        for whole in groups:
            added = section(part, whole)
            if added and not added & modes:
                pairs.append((part[0], whole[0]))
    items = {id: ("", frozenset(), frozenset()) for id in clusters}
    return join_scrolled(clusters, pairs, items, structure_threshold=0.0)


def group_id(members: tuple[str, ...]) -> str:
    """Offline membership hash, not a persistent screen handle."""
    return hashlib.sha256(json.dumps(list(members), separators=(",", ":")).encode()).hexdigest()


def cluster(corpus: dict, *, structure_threshold: float = STRUCTURE_THRESHOLD,
            text_threshold: float = TEXT_THRESHOLD) -> dict[str, str]:
    """Re-cluster every corpus case; membership hashes are offline group IDs."""
    items = {case["id"]: evidence(corpus, case) for case in corpus["cases"]}
    return {id: group_id(members) for id, members in cluster_evidence(
        items, structure_threshold=structure_threshold, text_threshold=text_threshold).items()}


# Library views that are transient feedback by construction, not screen conditions. A table
# of known widgets, not a guess from text; app-specific transients need review or idle evidence.
TRANSIENT_RESOURCES = (":id/snackbar_text", ":id/snackbar_action")
# The framework's own views in the app's windows: the text-editing handles (popups that show
# after typing or a tap in a field and fade a few seconds later) and the system bar backgrounds
# (drawn or not with the keyboard and immersive mode). Window decor, not screen content.
TEXT_HANDLES = frozenset({"android:id/insertion_handle", "android:id/selection_start_handle",
                          "android:id/selection_end_handle"})
DECOR_RESOURCES = TEXT_HANDLES | {"android:id/navigationBarBackground", "android:id/statusBarBackground"}
LIST_CLASSES = ("RecyclerView", "ListView", "GridView")
_STATE_FLAGS = ("CHECKABLE", "CHECKED", "SELECTED", "ENABLED")


def _parents(nodes: Sequence[Mapping]) -> list[int | None]:
    parents: list[int | None] = []
    stack: list[int] = []
    for index, node in enumerate(nodes):
        while stack and nodes[stack[-1]]["depth"] >= node["depth"]:
            stack.pop()
        parents.append(stack[-1] if stack else None)
        stack.append(index)
    return parents


def _transient_subtrees(nodes: Sequence[Mapping], parents: list[int | None]) -> set[int]:
    """Known transient views and window decor plus the anonymous wrappers that exist only to hold them.

    Climb from a transient view while the ancestor's subtree has no other resource, text or
    description, then drop that whole subtree (e.g. Snackbar's FrameLayout > LinearLayout).
    """
    def end(index: int) -> int:
        stop = index + 1
        while stop < len(nodes) and nodes[stop]["depth"] > nodes[index]["depth"]:
            stop += 1
        return stop

    def marked(node: Mapping) -> bool:
        resource = node["resource"] or ""
        return resource.endswith(TRANSIENT_RESOURCES) or resource in DECOR_RESOURCES

    def substantive(node: Mapping) -> bool:
        return not marked(node) and bool(node["resource"] or node.get("text") or node.get("description"))

    dropped: set[int] = set()
    for index, node in enumerate(nodes):
        if not marked(node):
            continue
        top = index
        while (parent := parents[top]) is not None and not any(
                substantive(nodes[other]) for other in range(parent, end(parent))):
            top = parent
        dropped.update(range(top, end(top)))
    return dropped


def variant_items(package: str, nodes: Iterable[Mapping], typed: Iterable[str] = ()) -> list[list]:
    """Role-normalized observable conditions of one observation, for use within one screen.

    * List rows (children of RecyclerView/ListView/GridView) collapse to empty/nonempty: their
      count and content are data for a parameterized robot, not a variant.
    * Known transient library views (Snackbar) and window decor are omitted.
    * Editable values stay literal input bindings; captured ``showing_hint`` (or the legacy
      text == hint fallback) reads as empty.
    * Non-editable text equal to a recorded fill is data (``<typed>``), not a condition.
    * Checked/selected/enabled flags and captured error/content-invalid stay conditions.
    """
    typed = set(typed)
    nodes = [node for node in nodes if node["package"] == package]
    parents = _parents(nodes)
    lists: dict[int, bool] = {}
    inside: set[int] = set()
    for index, node in enumerate(nodes):
        parent = parents[index]
        ancestor = parent
        while ancestor is not None:
            if ancestor in lists:
                inside.add(index)
                if parents[index] == ancestor:
                    lists[ancestor] = True
                break
            ancestor = parents[ancestor]
        if (node["class"] or "").endswith(LIST_CLASSES) and index not in inside:
            lists[index] = False
    transient = _transient_subtrees(nodes, parents)
    items = []
    for index, node in enumerate(nodes):
        if index in inside or index in transient:
            continue
        flags = sorted(flag for flag in node["flags"] if flag in _STATE_FLAGS)
        value = node.get("text")
        if editable(node):
            if node.get("showing_hint") is True or value is None or value == node.get("hint"):
                value = ""
        elif value in typed:
            value = "<typed>"
        description = "<typed>" if node.get("description") in typed else node.get("description")
        item = [node["resource"], node["class"], value, description, flags,
                node.get("error"), node.get("content_invalid")]
        if index in lists:
            item.append("nonempty" if lists[index] else "empty")
        items.append(item)
    return sorted(items, key=lambda item: json.dumps(item))


def variant_key(package: str, nodes: Iterable[Mapping], typed: Iterable[str] = ()) -> str:
    return hashlib.sha256(json.dumps(variant_items(package, nodes, typed), separators=(",", ":"))
                          .encode()).hexdigest()
