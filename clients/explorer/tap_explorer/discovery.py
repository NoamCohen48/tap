"""Unseeded, conservative observation identity and capability-derived candidate proposals.

No semantic screen catalog and no AI decisions determine identity or authorize input. Equal
signatures mean equal retained UI features, not equal hidden/backend state. Volatile-field
normalization is explicit, inspectable, and versioned; unknown values remain distinct.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from google.protobuf.json_format import MessageToDict
from tap_e2e import NodeFlag, ScreenNode, ScreenSnapshot, class_name, text

SIGNATURE_VERSION = "tap-state-signature/2"
# Foreign windows count only by their root and its direct children: enough to tell a dialog,
# keyboard or pulled-down shade from none, but not the status bar's notification icons.
_FOREIGN_DEPTH = 1
_FLAGS = frozenset({NodeFlag.ENABLED, NodeFlag.CHECKED, NodeFlag.CHECKABLE, NodeFlag.CLICKABLE,
                    NodeFlag.LONG_CLICKABLE, NodeFlag.SCROLLABLE, NodeFlag.SELECTED})


def _digest(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":")).encode()).hexdigest()


@dataclass(frozen=True)
class SignaturePolicy:
    """Explicit text-value exclusions by qualified resource ID, never broad regex guessing.

    Resource, class, role, depth and capability flags remain represented for excluded fields.
    Use only reviewed clock/progress fields; never exclude validation/error/form values.
    """

    volatile_resources: tuple[str, ...] = ()

    def features(self, snapshot: ScreenSnapshot, package: str) -> dict:
        """Produce inspectable retained features, excluding refs/selectors/focus/pixel hashes."""
        own = []
        foreign = []
        for node in snapshot.nodes:
            structural = {"package": node.window_package, "depth": node.depth,
                          "resource": node.resource_name, "class": node.class_name,
                          "flags": sorted(flag.value for flag in node.flags & _FLAGS),
                          "password": node.password}
            if node.window_package == package:
                volatile = node.resource_name in self.volatile_resources
                structural.update(text=None if volatile or node.password else node.text,
                                  description=None if volatile or node.password else node.content_description,
                                  hint=None if volatile or node.password else node.hint)
                own.append(structural)
            elif node.depth <= _FOREIGN_DEPTH:
                # Preserve which foreign windows are up (dialogs/IME/shade), not their contents.
                foreign.append(structural)
        if not own:
            raise ValueError("no target-package nodes; cannot classify another app as the target")
        return {"version": SIGNATURE_VERSION, "package": package, "rotation": snapshot.rotation,
                "policy": {"volatile_resources": sorted(set(self.volatile_resources))},
                "nodes": own, "foreign_nodes": foreign}

    def signature(self, snapshot: ScreenSnapshot, package: str) -> str:
        """Full digest; names and short IDs are not identity or merge authority."""
        return f"{SIGNATURE_VERSION}:{_digest(self.features(snapshot, package))}"


@dataclass(frozen=True)
class Candidate:
    """A blocked proposal derived from an owned visible node, not an executable approval."""

    id: str
    source: str
    verb: str
    node_ref: str
    label: str
    selector: dict | None
    blocked_reason: str | None

    provenance: str = "snapshot-capability/1"

    def target(self) -> dict:
        """Graph metadata plus provenance. Native input requires fresh resolution and review.

        Derived provenances (`list-row-text/1`, `ancestor-text/1`) are inferred affordances:
        the reviewer sees that the snapshot did not mark the node itself as the tap target.
        """
        return {"label": self.label, "selector": self.selector, "node_ref": self.node_ref,
                "provenance": self.provenance, "risk": "unknown",
                "blocked_reason": self.blocked_reason}


def _quality(node: ScreenNode) -> str | None:
    if node.password:
        return "password fields are excluded"
    if NodeFlag.ENABLED not in node.flags:
        return "disabled control"
    if node.selector is None:
        return "no unique native selector"
    if node.by_index or node.selector.to_proto().WhichOneof("pick") is not None:
        return "fragile/index selector is excluded"
    return None


def discover(snapshot: ScreenSnapshot, package: str, source: str) -> tuple[Candidate, ...]:
    """Propose tap/fill candidates; unsupported long-tap/scroll controls remain limitations.

    No action is approved by a label, capability, model score or this function. Duplicate
    selectors are refused even if preferred-selector metadata claims uniqueness.
    """
    proposals = []
    for node in snapshot.nodes:
        if node.window_package != package or not node.interactive:
            continue
        verbs = []
        if NodeFlag.CLICKABLE in node.flags:
            verbs.append("tap")
        if node.class_name and node.class_name.endswith("EditText"):
            verbs.append("fill")
        if not verbs:
            verbs.append("unsupported")
        selector = MessageToDict(node.selector.to_proto(), preserving_proto_field_name=True) if node.selector else None
        for verb in verbs:
            # Ref excluded from the stable key. Same selector/verb duplicates are not silently
            # reduced to one input target; mark all duplicate proposals below.
            identity = {"selector": selector, "verb": verb,
                        "fallback": None if selector else [node.resource_name, node.class_name, node.text]}
            key = f"{source}/{verb}-{_digest(identity)[:20]}"
            reason = "unsupported capability" if verb == "unsupported" else _quality(node)
            proposals.append(Candidate(key, source, verb, node.ref,
                "Password field (excluded)" if node.password else
                (node.content_description or node.text or node.hint or node.resource_name or node.class_name or "Unnamed control"),
                selector, reason))
    proposals.extend(_row_proposals(snapshot, package, source))
    counts: dict[str, int] = {}
    for candidate in proposals:
        counts[candidate.id] = counts.get(candidate.id, 0) + 1
    result = []
    for candidate in proposals:
        if counts[candidate.id] > 1:
            # Diagnostic identity only: these cannot be approved or executed by the pilot.
            candidate = Candidate(f"{candidate.id}/duplicate-{candidate.node_ref}", candidate.source,
                candidate.verb, candidate.node_ref, candidate.label, candidate.selector, "duplicate selector",
                candidate.provenance)
        result.append(candidate)
    return tuple(sorted(result, key=lambda candidate: candidate.id))


_LIST_CLASSES = ("RecyclerView", "ListView", "GridView")


def _parents(nodes: tuple[ScreenNode, ...]) -> list[int | None]:
    """Parent index per node, rebuilt from the dump's pre-order depth."""
    parents: list[int | None] = []
    stack: list[int] = []
    for index, node in enumerate(nodes):
        while stack and nodes[stack[-1]].depth >= node.depth:
            stack.pop()
        parents.append(stack[-1] if stack else None)
        stack.append(index)
    return parents


def _ancestors(index: int, parents: list[int | None]):
    parent = parents[index]
    while parent is not None:
        yield parent
        parent = parents[parent]


def _row_label(node: ScreenNode) -> bool:
    return (not node.interactive and not node.password and bool(node.text and node.text.strip())
            and NodeFlag.ENABLED in node.flags)


def _row_proposals(snapshot: ScreenSnapshot, package: str, source: str) -> list[Candidate]:
    """Inferred tap targets for list rows that the capability pass cannot address.

    * ``ancestor-text/1``: a clickable node whose own selector is index-only, addressed as
      ``class & clickable & has_descendant(text)`` when exactly one node in the snapshot
      matches that description. The text comes from a non-interactive label inside it.
    * ``list-row-text/1``: a row of a list container with no clickable node on the path from
      the row down to its label (custom touch handling, e.g. Loop's habit cards). The label's
      own unique, non-index selector is the target; the tap lands inside the row.

    Both stay blocked-by-default proposals: approval, a fresh re-derivation and the native
    exact-one check still precede input. Offline uniqueness is over visible nodes only.
    """
    nodes = tuple(snapshot.nodes)
    parents = _parents(nodes)
    owned = [node.window_package == package for node in nodes]
    proposals: list[Candidate] = []
    # Clickable ancestors without a usable selector of their own.
    below: list[set[str]] = [set() for _ in nodes]
    for index, node in enumerate(nodes):
        if owned[index] and _row_label(node):
            for ancestor in _ancestors(index, parents):
                below[ancestor].add(node.text or "")
    for index, node in enumerate(nodes):
        if (not owned[index] or NodeFlag.CLICKABLE not in node.flags or not node.class_name
                or _quality(node) not in ("fragile/index selector is excluded", "no unique native selector")):
            continue
        for label in sorted(below[index]):
            matches = [other for other in range(len(nodes)) if owned[other]
                       and NodeFlag.CLICKABLE in nodes[other].flags
                       and nodes[other].class_name == node.class_name and label in below[other]]
            if matches != [index]:
                continue
            selector = class_name(node.class_name).clickable().has_descendant(text(label))
            data = MessageToDict(selector.to_proto(), preserving_proto_field_name=True)
            key = f"{source}/tap-{_digest({'selector': data, 'verb': 'tap'})[:20]}"
            proposals.append(Candidate(key, source, "tap", node.ref, label, data, None, "ancestor-text/1"))
            break
    # Rows of list containers whose label has no clickable node above it inside the row.
    for index, node in enumerate(nodes):
        if not owned[index] or not _row_label(node):
            continue
        chain = list(_ancestors(index, parents))
        row = next((position for position, ancestor in enumerate(chain)
                    if (nodes[ancestor].class_name or "").endswith(_LIST_CLASSES)), None)
        if row is None or row == 0:
            continue  # Not inside a list, or the label is itself a direct list child.
        if any(NodeFlag.CLICKABLE in nodes[ancestor].flags for ancestor in chain[:row]):
            continue  # A clickable ancestor exists: the ancestor-text proposal covers it.
        reason = _quality(node)
        selector = (MessageToDict(node.selector.to_proto(), preserving_proto_field_name=True)
                    if node.selector else None)
        key = f"{source}/tap-{_digest({'selector': selector, 'verb': 'tap', 'row': True})[:20]}"
        proposals.append(Candidate(key, source, "tap", node.ref, node.text or "", selector, reason,
                                   "list-row-text/1"))
    return proposals
