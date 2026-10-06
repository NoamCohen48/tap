"""Offline complete-link screen hypotheses; never execution equivalence or approval.

Thresholds are fixed candidate settings, not validated general-purpose constants. Exact typed
literals come from recorded fills, not labels. Their exclusion is a heuristic whose failures
must remain visible in independent evaluation; variants/bindings are not normalized here.
"""

from __future__ import annotations

import hashlib
import heapq
import json
from collections.abc import Iterable, Mapping, Sequence
from itertools import combinations

STRUCTURE_THRESHOLD = 0.9
TEXT_THRESHOLD = 0.5

Evidence = tuple[str, frozenset, frozenset]


def _jaccard(left: frozenset, right: frozenset) -> float:
    union = left | right
    return len(left & right) / len(union) if union else 1.0


def editable(node: Mapping) -> bool:
    """Captured editability, falling back to the EditText class until it is always present."""
    return (node.get("editable") is True or "EDITABLE" in node.get("flags", ())
            or (node.get("class") or "").endswith("EditText"))


def node_evidence(package: str, nodes: Iterable[Mapping], typed: Iterable[str] = ()) -> Evidence:
    """Structure/text sets of one observation's owned nodes; never labels or refs.

    Hint equality excludes a text token from a screen hypothesis; it never declares a field
    empty. Input values remain in exact variant evidence and execution guards.
    """
    typed = set(typed)
    structure, texts = set(), set()
    for node in nodes:
        if node["package"] != package:
            raise ValueError("similarity requires owned snapshot nodes")
        structure.add((node["resource"], node["class"]))
        if editable(node):
            continue
        for field in ("text", "description"):
            value = node.get(field)
            if isinstance(value, str) and value.strip() and value not in typed and value != node.get("hint"):
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
    """Known transient views plus the anonymous wrappers that exist only to hold them.

    Climb from a transient view while the ancestor's subtree has no other resource, text or
    description, then drop that whole subtree (e.g. Snackbar's FrameLayout > LinearLayout).
    """
    def end(index: int) -> int:
        stop = index + 1
        while stop < len(nodes) and nodes[stop]["depth"] > nodes[index]["depth"]:
            stop += 1
        return stop

    def marked(node: Mapping) -> bool:
        return (node["resource"] or "").endswith(TRANSIENT_RESOURCES)

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
    * Known transient library views (Snackbar) are omitted.
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
