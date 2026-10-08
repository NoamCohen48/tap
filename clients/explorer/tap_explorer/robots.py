"""Unverified robot (page-object) drafts from a retained exploration run. No device or AI.

Screens are complete-link similarity clusters of observations, so one robot covers a page's
variants. Methods come only from succeeded attempts; landmarks and expectations come only from
retained snapshots. Every output is a *draft* for human review: generated names, observed
conditions and inferred selectors are proposals, never business assertions or approvals.

Outputs: a language-neutral `tap-robots/1` document, a Python module on the public `tap_e2e`
API, and a review checklist. A `review.json` file of human decisions (names, dropped methods,
promoted expectations) is applied on regeneration and never rewritten by this module.
"""

from __future__ import annotations

import hashlib
import json
import keyword
import re
from collections import defaultdict
from collections.abc import Mapping
from pathlib import Path

from .similarity import (
    LIST_CLASSES,
    _parents,
    _transient_subtrees,
    cluster_evidence,
    editable,
    node_evidence,
    variant_items,
)

FORMAT = "tap-robots/1"
REVIEW_FORMAT = "tap-robots-review/1"
_FLAG_METHODS = {"FLAG_CHECKABLE": "checkable", "FLAG_CHECKED": "checked", "FLAG_CLICKABLE": "clickable",
                 "FLAG_ENABLED": "enabled", "FLAG_FOCUSABLE": "focusable", "FLAG_FOCUSED": "focused",
                 "FLAG_LONG_CLICKABLE": "long_clickable", "FLAG_SCROLLABLE": "scrollable",
                 "FLAG_SELECTED": "selected"}
_MATCH_FUNCTIONS = {"PROPERTY_TEXT": "text", "PROPERTY_DESC": "desc", "PROPERTY_CONTENT_DESCRIPTION": "desc",
                    "PROPERTY_CLASS_NAME": "class_name", "PROPERTY_HINT": "hint"}
_RELATIONS = {"RELATION_DESCENDANT": "has_descendant", "RELATION_CHILD": "has_child",
              "RELATION_PARENT": "has_parent", "RELATION_ANCESTOR": "has_ancestor"}


# --- selector rendering -------------------------------------------------------------------


def _quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def _render_node(node: Mapping, package: str, parameters: Mapping[str, str]) -> str | None:
    """One `tap.v1.Node` as Python source; None when it cannot be expressed without a pick."""
    if "resource" in node:
        resource = node["resource"]
        owner = resource.get("package") or resource.get("package_name")
        if owner and owner != package:
            return f"res_id({_quote(owner)}, {_quote(resource['name'])})"
        return f"res({_quote(resource['name'])})"
    if "match" in node:
        match = node["match"]
        function = _MATCH_FUNCTIONS.get(match.get("property", ""))
        if function is None or match.get("mode", "MATCH_EXACT") != "MATCH_EXACT":
            return None
        value = match.get("value", "")
        argument = parameters.get(value, _quote(value))
        return f"{function}({argument})"
    if "related" in node:
        return None  # Only rendered as a chained relation inside all_of, below.
    if "all_of" in node:
        parts = node["all_of"].get("nodes", [])
        base: list[str] = []
        flags: list[str] = []
        relations: list[str] = []
        for part in parts:
            if "match" in part and part["match"].get("property") == "PROPERTY_PACKAGE_NAME":
                if part["match"].get("value") != package:
                    return None
                continue  # The robot's app binds the package.
            if "flag" in part:
                method = _FLAG_METHODS.get(part["flag"].get("property", ""))
                if method is None:
                    return None
                flags.append(f".{method}()" if part["flag"].get("value", False) else f".{method}(False)")
                continue
            if "related" in part:
                related = part["related"]
                inner = _render_node(related.get("node", {}), package, parameters)
                method = _RELATIONS.get(related.get("relation", ""))
                if inner is None or method is None:
                    return None
                relations.append(f".{method}({inner})")
                continue
            rendered = _render_node(part, package, parameters)
            if rendered is None:
                return None
            base.append(rendered)
        if not base:
            return None  # A bare flag/relation has no public constructor; keep it explicit.
        expression = base[0] if len(base) == 1 else "(" + " & ".join(base) + ")"
        return expression + "".join(flags) + "".join(relations)
    if "any_of" in node:
        parts = [_render_node(part, package, parameters) for part in node["any_of"].get("nodes", [])]
        if not parts or any(part is None for part in parts):
            return None
        return "any_of(" + ", ".join(part for part in parts if part) + ")"
    return None


def render_selector(selector: Mapping | None, package: str, parameters: Mapping[str, str] | None = None) -> str | None:
    """Python source for a stored selector, or None for index picks / unsupported shapes.

    ``parameters`` maps literal match values to parameter names (list rows: data → argument).
    """
    if not isinstance(selector, Mapping) or "at" in selector or "pick" in selector or "node" not in selector:
        return None
    return _render_node(selector["node"], package, parameters or {})


# --- naming ---------------------------------------------------------------------------------


def _words(value: str) -> list[str]:
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", value)
    value = re.sub(r"([A-Z])([A-Z][a-z])", r"\1 \2", value)  # "APlan" -> "A Plan"
    return [word for word in re.split(r"[^0-9A-Za-z]+", value) if word][:6]


def snake(value: str, fallback: str = "control") -> str:
    words = [word.lower() for word in _words(value)] or [fallback]
    name = "_".join(words)
    if name[0].isdigit() or keyword.iskeyword(name):
        name = f"{fallback}_{name}"
    return name


def pascal(value: str, fallback: str = "Screen") -> str:
    words = _words(value) or [fallback]
    name = "".join(word[:1].upper() + word[1:].lower() for word in words)
    return name if not name[0].isdigit() else f"{fallback}{name}"


def _short(resource: str | None) -> str | None:
    return resource.rsplit("/", 1)[-1] if resource else None


# --- evidence ---------------------------------------------------------------------------------


def _snapshot(run: Path, evidence: Mapping) -> list[dict] | None:
    for candidate in (Path(evidence.get("snapshot", "")), run / "observations" / Path(evidence.get("snapshot", "")).name):
        try:
            if candidate.is_file():
                return json.loads(candidate.read_text(encoding="utf-8"))["nodes"]
        except (OSError, ValueError, KeyError):
            return None
    return None


def _owned(nodes: list[dict], package: str) -> list[dict]:
    return [node for node in nodes if node.get("package") == package]


def _glyph(value: str) -> bool:
    """Icon-font text (Unicode private use area) is not a readable label."""
    return all(0xE000 <= ord(char) <= 0xF8FF or char.isspace() for char in value)


def _readable(node: Mapping, typed: set[str]) -> str | None:
    value = node.get("text")
    if (isinstance(value, str) and value.strip() and not _glyph(value) and value not in typed
            and value != node.get("hint") and not editable(node)):
        return value
    return None


def _landmark_items(nodes: list[dict], package: str, typed: set[str]) -> dict[str, tuple[str, str]]:
    """Recognition predicates one observation offers: key -> (kind, value).

    App resource IDs and static texts. Row texts count too: list data drops out because a
    landmark must be common to *every* member observation, and typed values are excluded.
    """
    owned = _owned(nodes, package)
    skip = _transient_subtrees(owned, _parents(owned))
    counts: dict[str, int] = defaultdict(int)
    items: dict[str, tuple[str, str]] = {}
    for index, node in enumerate(owned):
        if index in skip:
            continue
        resource = node.get("resource")
        if resource and resource.startswith(f"{package}:id/"):
            key = f"res:{_short(resource)}"
            items[key] = ("res", _short(resource) or "")
            counts[key] += 1
        value = _readable(node, typed)
        if value is not None:
            key = f"text:{value}"
            items[key] = ("text", value)
            counts[key] += 1
    # Only predicates that identify exactly one node make a `.one()` landmark.
    return {key: item for key, item in items.items() if counts[key] == 1}


def _row_classes(nodes: list[dict], package: str) -> dict[str, set[str]]:
    """List resource (short) -> classes of its direct children: what a row looks like."""
    owned = _owned(nodes, package)
    result: dict[str, set[str]] = defaultdict(set)
    for node, parent in zip(owned, _parents(owned)):
        if parent is None:
            continue
        holder = owned[parent]
        short = _short(holder.get("resource"))
        if short and (holder.get("class") or "").endswith(LIST_CLASSES) and node.get("class"):
            result[short].add(node["class"])
    return result


def _titles(nodes: list[dict], package: str, typed: set[str]) -> list[str]:
    """Short static non-clickable texts in screen order: title candidates for naming."""
    result = []
    for node in _owned(nodes, package):
        value = _readable(node, typed)
        if (value and "CLICKABLE" not in node.get("flags", ()) and len(value) <= 32
                and len(value.split()) <= 4 and value not in result):
            result.append(value)
    return result


def _options(nodes: list[dict], package: str, typed: set[str], limit: int = 2) -> list[str]:
    return [value for node in _owned(nodes, package) if "CLICKABLE" in node.get("flags", ())
            and (value := _readable(node, typed)) and len(value) <= 24][:limit]


def _landmark(members: list[dict[str, tuple[str, str]]], others: list[dict[str, tuple[str, str]]]):
    """Greedy discriminating set: common to all members, together absent from every non-member."""
    common = set(members[0]).intersection(*members[1:]) if members else set()
    remaining = list(range(len(others)))
    chosen: list[str] = []

    def rank(key: str) -> tuple:
        excluded = sum(key not in others[index] for index in remaining)
        return (-excluded, 0 if key.startswith("res:") else 1, len(key), key)

    while remaining and common - set(chosen):
        best = min(common - set(chosen), key=rank)
        if not any(best not in others[index] for index in remaining):
            break
        chosen.append(best)
        remaining = [index for index in remaining if best in others[index]]
        if len(chosen) == 3:
            break
    if not chosen and common:
        chosen.append(min(common, key=rank))
    return [members[0][key] for key in chosen], len(remaining)


# --- facts ------------------------------------------------------------------------------------
#
# A fact is one observable condition of one observation, from the role-normalized variant items
# (rows, transients and typed echoes already removed). Facts drive three things: variant
# descriptions, parameterized expectations, and the preconditions that separate outcomes.


def _facts(package: str, typed: list[str], nodes: list[dict], inputs: set[str]) -> dict[str, dict]:
    """Label -> fact for one observation. Labels read as review text ("terms unchecked")."""
    facts: dict[str, dict] = {}
    for resource, _, value, _, flags, error, _, *rest in variant_items(package, _owned(nodes, package), typed):
        short = _short(resource) if resource and resource.startswith(f"{package}:id/") else None
        if rest:
            if short:
                facts[f"{short} {rest[0]}"] = {"kind": "list", "resource": short, "value": rest[0], "rank": 2}
            continue
        if short and error:
            facts[f"{short} shows error {error!r}"] = {"kind": "error", "resource": short, "value": error, "rank": 1}
        if short and "CHECKABLE" in flags:
            state = "CHECKED" in flags
            facts[f"{short} {'checked' if state else 'unchecked'}"] = {
                "kind": "checked", "resource": short, "value": state, "rank": 1}
        if short and "ENABLED" not in flags:
            facts[f"{short} disabled"] = {"kind": "enabled", "resource": short, "value": False, "rank": 1}
        if not isinstance(value, str) or value == "<typed>" or (value and _glyph(value)):
            continue
        if short in inputs:
            label = f"{short} is empty" if value == "" else f"{short} = {value!r}"
            facts[label] = {"kind": "input", "resource": short, "value": value, "rank": 0}
        elif short and "CHECKABLE" not in flags:
            facts[f"{short} = {value!r}"] = {"kind": "text", "resource": short, "value": value, "rank": 3}
        elif not short and value.strip():
            facts[f"text {value!r} visible"] = {"kind": "visible", "resource": None, "value": value, "rank": 4}
    return facts


def _separate(members: list[set[str]], others: list[set[str]], rank: Mapping[str, int]) -> tuple[list[str], int]:
    """Greedy conjunction common to every member that excludes the most non-members.

    Returns up to three labels and how many non-member observations still satisfy all of them
    (0 = the conjunction separates in this evidence; it is still only observed, not causal).
    """
    common = set.intersection(*members) if members else set()
    remaining = list(others)
    chosen: list[str] = []
    while remaining and common - set(chosen) and len(chosen) < 3:
        best = min(common - set(chosen),
                   key=lambda label: (-sum(label not in other for other in remaining), rank.get(label, 9), label))
        if all(best in other for other in remaining):
            break
        chosen.append(best)
        remaining = [other for other in remaining if best in other]
    return chosen, len(remaining)


def _alternatives(members: list[set[str]], others: list[set[str]], rank: Mapping[str, int]) -> list[str]:
    """Single facts that each separate on their own: the evidence cannot say which one is the cause."""
    if not members or not others:
        return []
    common = set.intersection(*members)
    return sorted((label for label in common if not any(label in other for other in others)),
                  key=lambda label: (rank.get(label, 9), label))


def _either(members: list[set[str]], others: list[set[str]], rank: Mapping[str, int]) -> list[str]:
    """A greedy "A or B" cover: facts absent from every non-member that together cover the members.

    Proposed only when no conjunction separates (different members had different causes, e.g.
    rejected once for an empty name and once for unchecked terms). Empty when no cover of at
    most three facts exists.
    """
    candidates = {label for facts in members for label in facts if not any(label in other for other in others)}
    uncovered = list(range(len(members)))
    chosen: list[str] = []
    while uncovered and len(chosen) < 3:
        best = min(candidates - set(chosen), default=None,
                   key=lambda label: (-sum(label in members[index] for index in uncovered), rank.get(label, 9), label))
        if best is None or not any(best in members[index] for index in uncovered):
            return []
        chosen.append(best)
        uncovered = [index for index in uncovered if best not in members[index]]
    return chosen if not uncovered and len(chosen) > 1 else []


def _negate(label: str) -> str | None:
    """The opposite of a fact the test controls, or None when it has no single opposite."""
    for positive, negative in ((" is not empty", " is empty"), (" unchecked", " checked")):
        if label.endswith(positive):
            return label[: -len(positive)] + negative
        if label.endswith(negative):
            return label[: -len(negative)] + positive
    return None


def _untested(members: list[set[str]], others: list[set[str]], chosen: list[str],
              fact_of: Mapping[str, dict]) -> list[str]:
    """Controlled facts the evidence cannot rule in or out next to a separating ``chosen``.

    A fact every member had, which some other outcome lacked, but only ever together with a
    failed ``chosen`` (Loop's Save: the name was empty only while the target was empty too).
    Whether it matters on its own was never tried; the draft keeps ``chosen`` and asks.
    """
    common = set.intersection(*members) if members else set()
    result = []
    for label in sorted(common - set(chosen)):
        if fact_of.get(label, {}).get("kind") not in ("input", "checked"):
            continue
        failed = [other for other in others if label not in other]
        if failed and not any(set(chosen) <= other for other in failed):
            general = _generalize(label, fact_of, failed)
            if _negate(general) is not None:
                result.append(general)
    return result


def _expectations(observations: list[dict[str, dict]]) -> list[dict]:
    """Screen-level checks for what varies across one screen's observations.

    * A resource whose text takes two or more values is data: ``expect_<res>(value)``.
    * A checkable or enablable control whose state varies: ``expect_<res>_checked(checked)``.
    * A text that is present in some observations only: ``expect_<res>_<words>()`` and, when it
      can be absent, ``expect_no_<res>()``.
    * A list that was empty in some observations and had rows in others: ``expect_<res>_empty(empty)``.
    * A field error (``TextView.setError``) present in some observations only: ``expect_<res>_error()``.
    Inputs the test sets itself are never expectations; constant facts belong to ``verify()``.
    """
    seen: dict[tuple[str, str | None], dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for facts in observations:
        for fact in facts.values():
            if fact["kind"] == "input":
                continue
            seen[(fact["kind"], fact["resource"])][json.dumps(fact["value"])].append(facts)
    result: list[dict] = []
    total = len(observations)
    for (kind, resource), values in sorted(seen.items(), key=lambda item: (item[0][0], item[0][1] or "")):
        decoded = sorted(json.loads(value) for value in values)
        absent = sum(len(holders) for holders in values.values()) < total
        if kind == "list":
            if len(decoded) > 1:
                result.append({"kind": kind, "resource": resource, "examples": decoded})
        elif kind == "checked":
            if len(decoded) > 1:  # Constant state is part of the screen, not a variant.
                result.append({"kind": kind, "resource": resource, "examples": decoded})
        elif kind == "enabled":
            if absent:  # Only "disabled" is a fact; disabled somewhere and not everywhere.
                result.append({"kind": kind, "resource": resource, "examples": [False, True]})
        elif kind == "error":
            if absent:  # An error shown on every observation is the screen itself, not a check.
                result.append({"kind": kind, "resource": resource, "examples": decoded})
        elif kind == "visible":
            if absent:
                result.append({"kind": "visible", "resource": None, "examples": decoded})
        else:
            nonempty = [value for value in decoded if value != ""]
            if len(nonempty) > 1:
                result.append({"kind": "value", "resource": resource, "examples": nonempty})
            elif nonempty and (absent or "" in decoded):
                result.append({"kind": "shown", "resource": resource, "examples": nonempty, "absent": absent})
    # A text whose presence varies is listed once per value; collapse texts into one check each.
    expanded = []
    for expectation in result:
        if expectation["kind"] == "visible":
            expanded += [{"kind": "visible", "resource": None, "examples": [value]} for value in expectation["examples"]]
        else:
            expanded.append(expectation)
    return expanded


def _variants(observations: dict[str, dict[str, dict]], variants: dict[str, list[str]]) -> list[dict]:
    """Variant descriptions for review: the facts that tell each variant apart from the rest."""
    groups = {key: [set(observations[o]) for o in members] for key, members in variants.items()}
    rank = {label: fact["rank"] for facts in observations.values() for label, fact in facts.items()}
    result = []
    for key, members in sorted(variants.items()):
        others = [facts for other, sets in groups.items() if other != key for facts in sets]
        labels, ambiguous = _separate(groups[key], others, rank) if len(variants) > 1 else ([], 0)
        result.append({"id": key, "observations": members, "facts": labels, "unseparated": ambiguous})
    return result


# --- model ------------------------------------------------------------------------------------


def build(run: Path, review: Mapping | None = None, verification: Mapping | None = None) -> dict:
    """Build the `tap-robots/1` draft document from ``run/graph.db`` (or graph.json).

    ``verification`` is a `tap-robots-verification/1` replay result; it marks methods by their
    evidence attempts, so review renames keep their status.
    """
    from .store import GraphStore

    review = review or {}
    if review and review.get("format") != REVIEW_FORMAT:
        raise ValueError("unsupported review file")
    if (run / "graph.db").is_file():
        with GraphStore(run / "graph.db") as store:
            doc = store.document()
    else:
        doc = json.loads((run / "graph.json").read_text(encoding="utf-8"))
    package = doc["context"]["package"]
    typed = sorted({action["scenario"]["value"] for action in doc["actions"].values()
                    if action["verb"] == "fill" and isinstance(action.get("scenario", {}).get("value"), str)
                    and action["status"] == "attempted" and action["scenario"]["value"]})
    typed_set = set(typed)
    snapshots: dict[str, list[dict]] = {}
    state_of: dict[str, str] = {}
    for state_id, state in doc["states"].items():
        for observation in state["observations"]:
            nodes = _snapshot(run, doc["observations"][observation]["evidence"])
            if nodes is not None and _owned(nodes, package):
                snapshots[observation] = nodes
                state_of[observation] = state_id
    if not snapshots:
        raise ValueError("no retained owned-package snapshots in this run")
    clusters = cluster_evidence({observation: node_evidence(package, _owned(nodes, package), typed)
                                 for observation, nodes in snapshots.items()})
    groups = sorted({members for members in clusters.values()})
    screen_of_state: dict[str, int] = {}
    for index, members in enumerate(groups):
        for observation in members:
            screen_of_state.setdefault(state_of[observation], index)
    landmarks = {observation: _landmark_items(nodes, package, typed_set) for observation, nodes in snapshots.items()}
    inputs = {short for nodes in snapshots.values() for node in _owned(nodes, package)
              if editable(node) and (short := _short(node.get("resource")))}
    facts = {observation: _facts(package, typed, nodes, inputs) for observation, nodes in snapshots.items()}

    names, sources = _screen_names(groups, snapshots, package, typed_set)
    rows: dict[str, set[str]] = defaultdict(set)
    for nodes in snapshots.values():
        for short, classes in _row_classes(nodes, package).items():
            rows[short] |= classes
    screens = []
    for index, members in enumerate(groups):
        member_items = [landmarks[observation] for observation in members]
        others = [landmarks[observation] for observation in snapshots if observation not in members]
        predicates, undistinguished = _landmark(member_items, others)
        variants: dict[str, list[str]] = defaultdict(list)
        for observation in members:
            key = hashlib.sha256(json.dumps(variant_items(package, _owned(snapshots[observation], package), typed),
                                            separators=(",", ":")).encode()).hexdigest()[:12]
            variants[key].append(observation)
        expectations = _expectations([facts[o] for o in members])
        for expectation in expectations:
            if expectation["kind"] == "list":
                expectation["rows"] = sorted(rows.get(expectation["resource"], ()))
        texts = list(dict.fromkeys(_titles(snapshots[members[0]], package, typed_set)
                                   + _options(snapshots[members[0]], package, typed_set, 6)))[:6]
        screens.append({"id": names[index], "name": names[index], "name_source": sources[index],
                        "texts": texts, "members": list(members),
                        "states": sorted({state_of[observation] for observation in members}),
                        "landmark": [{"kind": kind, "value": value} for kind, value in predicates],
                        "landmark_undistinguished_observations": undistinguished,
                        "variants": _variants({o: facts[o] for o in members}, variants),
                        "expectations": expectations, "methods": [], "unexplored": []})
    _methods(doc, package, typed_set, screens, screen_of_state, facts)
    for screen in screens:
        _name_expectations(screen)
        screen["workflows"] = _workflows(screen)
    _apply_review(screens, review)
    _apply_verification(screens, verification)
    return {"format": FORMAT, "package": package, "status": _status(screens),
            "context": {key: doc["context"][key] for key in sorted(doc["context"]) if key in ("package", "apk_sha256", "policy")},
            "typed_values": typed, "screens": screens, "review": _review_items(screens)}



def _screen_names(groups: list[tuple[str, ...]], snapshots: dict, package: str,
                  typed: set[str]) -> tuple[list[str], list[str]]:
    """`<Title>Robot`, from the first short static text every member shares.

    Screens sharing a title get the first shared text unique to them ("Create habit" +
    "Frequency"); title-less dialogs and menus are named after their first two options.
    Always a proposal: the review file renames robots. The second list says where each name
    came from (``title``, ``options`` or ``fallback``); only a title is likely to be right.
    """
    titles = []
    for members in groups:
        lists = [_titles(snapshots[observation], package, typed) for observation in members]
        common = [value for value in lists[0] if all(value in other for other in lists[1:])]
        options = _options(snapshots[members[0]], package, typed)
        titles.append((common, options))
    names: list[str] = []
    sources: list[str] = []
    for index, (common, options) in enumerate(titles):
        sources.append("title" if common else "options" if options else "fallback")
        if common:
            base = common[0]
            clash = [other for other, (texts, _) in enumerate(titles) if other != index and texts and texts[0] == base]
            if clash:
                others = {value for other in clash for value in titles[other][0]}
                extra = next((value for value in common[1:] if value not in others), None)
                base = f"{base} {extra}" if extra else base
        elif options:
            base = " ".join(options)
        else:
            base = f"screen {index + 1}"
        name = pascal(base) + "Robot"
        unique, counter = name, 2
        while unique in names:
            unique, counter = f"{name[:-5]}{counter}Robot", counter + 1
        names.append(unique)
    return names, sources

_GENERIC = re.compile(r"^(button\d*|text\d*|title|icon|content|custom|summary|label|android:.*)$")


def _control_name(target: Mapping, typed: set[str], package: str) -> str:
    """Name from the app's resource ID when it is meaningful, else the visible label."""
    selector = json.dumps(target.get("selector") or {})
    resources = re.findall(r'"resource": \{"name": "([^"]+)"(?:, "package(?:_name)?": "([^"]+)")?', selector)
    label = target.get("label") or ""
    if label.startswith(f"{package}:id/"):
        label = _short(label) or ""
    for name, owner in resources:
        if (not owner or owner == package) and not _GENERIC.match(name):
            return _resource_words(name)
    return snake(label if label and label not in typed else "control")


def _resource_words(name: str) -> str:
    words = re.sub(r"_?(input|edit_?text|edit|field|picker|radio_?button|button)$", "", name, flags=re.I)
    words = re.sub(r"^(button|btn|input|edit|action)(_|(?=[A-Z]))", "", words)
    return snake(words or name)


def _methods(doc: dict, package: str, typed: set[str], screens: list[dict], screen_of_state: dict[str, int],
             facts: dict[str, dict[str, dict]]) -> None:
    """One method per (screen, control, observed destination screen).

    A control with one destination keeps its base name. A control whose destination depended on
    state gets a method per outcome (``_rejected`` when it stayed, ``_to_<screen>`` otherwise),
    each with the observed source conditions that separated the outcomes — a precondition for a
    human to confirm, preferring inputs the test sets, then control state, then other text.
    """
    outcomes: dict[tuple[int, str], dict] = {}
    for attempt_id, attempt in sorted(doc["attempts"].items()):
        if attempt.get("status") != "succeeded" or not attempt.get("destination"):
            continue
        action = doc["actions"][attempt["action"]]
        source = screen_of_state.get(action["source"])
        destination = screen_of_state.get(attempt["destination"])
        if source is None or destination is None:
            continue
        target = action.get("target") or {}
        row = target.get("provenance") == "list-row-text/1" or (
            target.get("label") in typed and action["verb"] == "tap")
        parameters = {target["label"]: "label"} if row and target.get("label") else {}
        rendered = render_selector(target.get("selector"), package, parameters)
        key = (source, json.dumps([action["verb"], rendered if row else target.get("selector")], sort_keys=True))
        entry = outcomes.setdefault(key, {"verb": action["verb"], "target": target, "selector": rendered,
                                          "row": row, "values": set(), "destinations": defaultdict(list),
                                          "sources": defaultdict(list), "selectors": []})
        entry["selectors"].append(target.get("selector"))
        if action["verb"] == "fill":
            entry["values"].add(action.get("scenario", {}).get("value", ""))
        entry["destinations"][destination].append(attempt_id)
        if attempt.get("before") in facts:
            # A field error is what a rejection left behind (Loop keeps it after the field is
            # fixed), not a condition a test sets up: never a precondition.
            entry["sources"][destination].append({label for label, fact in facts[attempt["before"]].items()
                                                  if fact["kind"] != "error"})
    fact_of = {label: fact for observation in facts.values() for label, fact in observation.items()}
    rank = {label: fact["rank"] for label, fact in fact_of.items()}
    covered: dict[int, list] = defaultdict(list)
    for (source, _), entry in sorted(outcomes.items(), key=lambda item: item[0]):
        screen = screens[source]
        covered[source] += [[entry["verb"], selector] for selector in entry["selectors"]]
        if entry["row"]:
            base = "open_row"
        elif entry["verb"] == "fill":
            base = "enter_" + _control_name(entry["target"], typed, package)
        else:
            base = "tap_" + _control_name(entry["target"], typed, package)
        several = len(entry["destinations"]) > 1
        for destination, attempts in sorted(entry["destinations"].items()):
            when, unseparated, alternatives, either, untested = [], 0, [], [], []
            if several:
                others = [observed for other, sources in entry["destinations"].items() if other != destination
                          for observed in entry["sources"][other]]
                members = entry["sources"][destination]
                if members:
                    when, unseparated = _separate(members, others, rank)
                    alternatives = _alternatives(members, others, rank)
                    either = _either(members, others, rank) if unseparated else []
                    untested = _untested(members, others, when, fact_of) if when and not unseparated else []
                    when, alternatives, either = ([_generalize(label, fact_of, others) for label in labels]
                                                  for labels in (when, alternatives, either))
                else:
                    unseparated = len(others)
            if not several:
                name = base
            elif destination == source:
                name = f"{base}_rejected"
            else:
                name = f"{base}_to_{snake(screens[destination]['id'].removesuffix('Robot'), 'screen')}"
            existing = {method["name"] for method in screen["methods"]}
            unique, counter = name, 2
            while unique in existing:
                unique, counter = f"{name}_{counter}", counter + 1
            screen["methods"].append({
                "name": unique, "verb": entry["verb"], "label": entry["target"].get("label") or "",
                "selector": entry["selector"],
                "parameter": "label" if entry["row"] else ("value" if entry["verb"] == "fill" else None),
                "examples": sorted(entry["values"]), "provenance": entry["target"].get("provenance"),
                "returns": screens[destination]["id"], "evidence": attempts,
                "outcome_dependent": several, "when": when, "when_mode": "all", "when_unseparated": unseparated,
                "alternatives": alternatives if len(alternatives) > 1 else [], "either": either,
                "untested": untested, "widen": []})
        _widen(screen["methods"][-len(entry["destinations"]):] if several else [])
    for action_id, action in sorted(doc["actions"].items()):
        source = screen_of_state.get(action["source"])
        if source is None or action["verb"] not in ("tap", "fill"):
            continue
        target = action.get("target") or {}
        label = target.get("label") or ""
        if [action["verb"], target.get("selector")] in covered[source] or label in typed:
            continue  # Covered, or a typed value echoed back (data, not a control).
        entry = {"label": _short(label) if label.startswith(f"{package}:id/") else label,
                 "verb": action["verb"], "status": action["status"],
                 "blocked_reason": target.get("blocked_reason"), "provenance": target.get("provenance"),
                 "selector": target.get("selector")}
        if entry not in screens[source]["unexplored"]:
            screens[source]["unexplored"].append(entry)


def _generalize(label: str, fact_of: Mapping[str, dict], others: list[set[str]]) -> str:
    """``name = 'Ada'`` reads ``name is not empty`` when every other outcome had it empty."""
    fact = fact_of.get(label)
    if fact is None or fact["kind"] != "input" or fact["value"] == "":
        return label
    empty = f"{fact['resource']} is empty"
    return f"{fact['resource']} is not empty" if others and all(empty in other for other in others) else label


def _widen(methods: list[dict]) -> None:
    """Preconditions to offer beyond the observed one when facts went untested (``_untested``).

    The method gets ``all(when + fact)``; with two outcomes, its sibling gets the complement,
    ``any(not when, not fact)``. The draft keeps the observed condition: only a reviewer, or a
    run that tries the combination, can say which is right.
    """
    for method in methods:
        if not method["untested"]:
            continue
        extra = [[fact] for fact in method["untested"]] + ([method["untested"]] if len(method["untested"]) > 1 else [])
        method["widen"] += [{"mode": "all", "facts": method["when"] + facts} for facts in extra]
        if len(methods) == 2:
            sibling = methods[0] if methods[1] is method else methods[1]
            for facts in extra:
                negated = [_negate(label) for label in method["when"] + facts]
                if all(negated):
                    sibling["widen"].append({"mode": "any", "facts": negated})


def precondition_choices(method: Mapping) -> dict[str, tuple[str, dict]]:
    """The answers ``--ask`` offers for an outcome-dependent method: key -> (label, condition)."""
    choices = {"y": ("confirm: " + (" and ".join(method["when"]) or "no condition"),
                     {"mode": "all", "facts": list(method["when"])})}
    for index, label in enumerate(method["alternatives"], 1):
        choices[str(index)] = (f"only: {label}", {"mode": "all", "facts": [label]})
    if method["either"]:
        choices["e"] = ("either: " + " or ".join(method["either"]), {"mode": "any", "facts": list(method["either"])})
    for index, condition in enumerate(method.get("widen", []), 1):
        joiner = " and " if condition["mode"] == "all" else " or "
        choices[f"w{index}"] = (("all: " if condition["mode"] == "all" else "any: ") + joiner.join(condition["facts"]),
                                condition)
    seen, unique = set(), {}
    for key, (label, condition) in choices.items():
        facts = frozenset(condition["facts"])
        identity = (condition["mode"] if len(facts) > 1 else "all", facts)
        if identity not in seen:
            seen.add(identity)
            unique[key] = (label, condition)
    return unique


def _workflows(screen: dict) -> list[dict]:
    """A proposed workflow method: fill the screen's inputs, then the control that leaves it.

    The forward control is the one whose observed precondition needs an input (``name is not
    empty``), else the only control that leaves the screen. Emitted only once accepted in the
    review: a workflow is a test-design decision, not evidence.
    """
    fills = [method for method in screen["methods"]
             if method["verb"] == "fill" and method["returns"] == screen["id"] and not method["outcome_dependent"]]
    leaving = [method for method in screen["methods"] if method["verb"] == "tap" and method["returns"] != screen["id"]]
    needing = [method for method in leaving if any(label.endswith(" is not empty") for label in method["when"])]
    forward = needing if len(needing) == 1 else leaving if len(leaving) == 1 else []
    if not fills or not forward:
        return []
    parameters = []
    for method in fills:
        parameter, counter = method["name"].removeprefix("enter_"), 2
        while parameter in parameters or parameter == "self":
            parameter, counter = f"{method['name'].removeprefix('enter_')}_{counter}", counter + 1
        parameters.append(parameter)
    taken = {method["name"] for method in screen["methods"]}
    name = f"fill_and_{forward[0]['name']}"
    return [] if name in taken else [{
        "name": name, "steps": [method["name"] for method in fills] + [forward[0]["name"]],
        "parameters": parameters, "returns": forward[0]["returns"], "accepted": False}]


def _name_expectations(screen: dict) -> None:
    taken = {method["name"] for method in screen["methods"]} | {"verify"}
    for expectation in screen["expectations"]:
        resource = _resource_words(expectation["resource"]) if expectation["resource"] else ""
        kind = expectation["kind"]
        if kind == "value":
            name = f"expect_{resource}"
        elif kind in ("checked", "enabled"):
            name = f"expect_{resource}_{kind}"
        elif kind == "list":
            name = f"expect_{resource}_empty"
        elif kind == "error":
            name = f"expect_{resource}_error"
        elif kind == "shown":
            name = f"expect_{resource}_{snake(expectation['examples'][0], 'text')}"
        else:
            name = f"expect_{snake(expectation['examples'][0], 'text')}"
        unique, counter = name, 2
        while unique in taken:
            unique, counter = f"{name}_{counter}", counter + 1
        taken.add(unique)
        expectation["name"] = unique
        if kind == "shown" and expectation["absent"]:
            absent = f"expect_no_{resource}"
            expectation["absent_name"] = absent if absent not in taken else f"{absent}_{counter}"
            taken.add(expectation["absent_name"])


def _apply_review(screens: list[dict], review: Mapping) -> None:
    names = review.get("screens", {})
    renamed = {screen["id"]: names.get(screen["id"], {}).get("name", screen["name"]) for screen in screens}
    for screen in screens:
        decision = names.get(screen["id"], {})
        screen["name"] = renamed[screen["id"]]
        screen["reviewed_name"] = "name" in decision
        method_names = decision.get("methods", {})
        dropped = set(decision.get("drop", []))
        screen["methods"] = [method for method in screen["methods"] if method["name"] not in dropped]
        screen["expectations"] = [item for item in screen["expectations"] if item["name"] not in dropped]
        for item in screen["expectations"]:
            if item.get("absent_name") in dropped:
                del item["absent_name"]
        for method in screen["methods"]:
            method["name"] = method_names.get(method["name"], method["name"])
            method["returns"] = renamed.get(method["returns"], method["returns"])
        promoted = set(decision.get("expectations", []))
        for expectation in screen["expectations"]:
            expectation["promoted"] = expectation["name"] in promoted
            expectation["name"] = method_names.get(expectation["name"], expectation["name"])
        confirmed = set(decision.get("confirmed", []))
        preconditions = decision.get("preconditions", {})
        for method in screen["methods"]:
            method["confirmed"] = method["name"] in confirmed
            chosen = preconditions.get(method["name"])
            if chosen:
                method["when"], method["when_mode"] = list(chosen["facts"]), chosen.get("mode", "all")
                method["confirmed"] = True
        accepted = decision.get("workflows", {})
        kept = []
        for workflow in screen.get("workflows", []):
            if accepted.get(workflow["name"]) is False or any(step in dropped for step in workflow["steps"]):
                continue
            workflow["accepted"] = accepted.get(workflow["name"]) is True
            workflow["steps"] = [method_names.get(step, step) for step in workflow["steps"]]
            workflow["returns"] = renamed.get(workflow["returns"], workflow["returns"])
            workflow["id"], workflow["name"] = workflow["name"], method_names.get(workflow["name"], workflow["name"])
            kept.append(workflow)
        screen["workflows"] = kept


def _apply_verification(screens: list[dict], verification: Mapping | None) -> None:
    replays = {}
    if verification:
        if verification.get("format") != "tap-robots-verification/1":
            raise ValueError("unsupported verification file")
        replays = {tuple(entry["evidence"]): entry for entry in verification["methods"].values()}
    for screen in screens:
        for method in screen["methods"]:
            entry = replays.get(tuple(method["evidence"]))
            method["replay"] = entry["status"] if entry else "not run"
            if entry and entry.get("error"):
                method["replay_error"] = entry["error"]


def _status(screens: list[dict]) -> str:
    methods = [method for screen in screens for method in screen["methods"]]
    verified = sum(method["replay"] == "verified" for method in methods)
    if not verified:
        return "unverified draft"
    return f"draft; {verified} of {len(methods)} methods verified by replay"


def _review_items(screens: list[dict]) -> list[dict]:
    """What a human must decide, most consequential first. Nothing here is auto-resolved."""
    items = []
    for screen in screens:
        if screen["landmark_undistinguished_observations"] or not screen["landmark"]:
            items.append({"screen": screen["id"], "kind": "landmark",
                          "detail": f"verify() cannot tell this screen apart from "
                                    f"{screen['landmark_undistinguished_observations']} other observation(s)"})
        for method in screen["methods"]:
            if method["replay"] in ("failed", "unplanned"):
                items.append({"screen": screen["id"], "kind": "replay-failed", "method": method["name"],
                              "detail": method.get("replay_error", method["replay"])})
            if method["outcome_dependent"] and not method["confirmed"]:
                when = " and ".join(method["when"]) or "no common source condition"
                if method["when_unseparated"]:
                    when += f" (also true before {method['when_unseparated']} attempt(s) with another outcome)"
                if method["either"]:
                    when += "; perhaps either " + " or ".join(method["either"])
                if method["alternatives"]:
                    when += "; each alone separates: " + ", ".join(method["alternatives"]) + " (which is the cause?)"
                for fact in method["untested"]:
                    when += f"; never tried with {' and '.join(method['when'])} but {_negate(fact)} (does {fact} matter?)"
                items.append({"screen": screen["id"], "kind": "outcome-depends", "method": method["name"],
                              "detail": f"→ {method['returns']} observed when {when}; confirm the precondition"})
            if method["selector"] is None:
                items.append({"screen": screen["id"], "kind": "selector", "method": method["name"],
                              "detail": "no index-free selector; needs a stable resource ID or label"})
            elif method["provenance"] in ("list-row-text/1", "ancestor-text/1"):
                items.append({"screen": screen["id"], "kind": "inferred-target", "method": method["name"],
                              "detail": "row/ancestor target inferred from a label, not a clickable node"})
        if not screen["reviewed_name"]:
            if screen["name_source"] == "title":
                items.append({"screen": screen["id"], "kind": "name", "detail": "generated robot/method names"})
            else:
                items.append({"screen": screen["id"], "kind": "dialog-name",
                              "detail": "no title; named from " + (f"its options {screen['texts']}" if screen["texts"]
                                                                  else "its position") + ": name it"})
        for expectation in screen["expectations"]:
            if expectation["kind"] == "list" and not expectation["promoted"]:
                items.append({"screen": screen["id"], "kind": "list-check", "method": expectation["name"],
                              "detail": f"{expectation['resource']} was empty in some observations and had rows "
                                        "in others: should tests check it?"})
        for workflow in screen["workflows"]:
            if not workflow["accepted"]:
                items.append({"screen": screen["id"], "kind": "workflow", "method": workflow["name"],
                              "detail": f"add {workflow['name']}({', '.join(workflow['parameters'])}) = "
                                        + " → ".join(workflow["steps"]) + "?"})
        explorable = [entry for entry in screen["unexplored"] if not entry["blocked_reason"]]
        if explorable:
            items.append({"screen": screen["id"], "kind": "frontier",
                          "detail": f"{len(explorable)} addressable control(s) never exercised: " +
                                    ", ".join(sorted({entry['label'] for entry in explorable})[:6])})
    order = {"landmark": 0, "replay-failed": 0.5, "outcome-depends": 1, "dialog-name": 2, "selector": 3, "inferred-target": 4,
             "workflow": 5, "list-check": 6, "frontier": 7, "name": 8}
    return sorted(items, key=lambda item: (order[item["kind"]], item["screen"], item.get("method", "")))


# --- emission ---------------------------------------------------------------------------------


def _landmark_source(predicate: Mapping) -> str:
    function = "res" if predicate["kind"] == "res" else "text"
    return f"{function}({_quote(predicate['value'])})"


def _docstring(lines: list[str], indent: str) -> list[str]:
    """A safe docstring from evidence text (labels may hold quotes or backslashes)."""
    safe = [_quote(line)[1:-1] for line in lines]  # Escaped quotes, backslashes and newlines.
    if len(safe) == 1:
        return [f'{indent}"""{safe[0]}"""']
    return [f'{indent}"""{safe[0]}'] + [f"{indent}{line}" for line in safe[1:]] + [f'{indent}"""']


def _examples(values: list) -> str:
    return ", ".join(repr(value) for value in values[:4]) + (", …" if len(values) > 4 else "")


def _expectation_source(expectation: Mapping, robot: str) -> list[str]:
    status = "Reviewed" if expectation["promoted"] else "OBSERVED, not a business assertion"
    kind = expectation["kind"]
    target = f"res({_quote(expectation['resource'])})" if expectation["resource"] else ""
    if kind == "value":
        return [f"    def {expectation['name']}(self, value: str) -> {robot}:",
                *_docstring([f"{status}. Observed values: {_examples(expectation['examples'])}."], "        "),
                f"        self.app.wait({target}, self.timeout).text_equals(value)", "        return self"]
    if kind in ("checked", "enabled"):
        positive, negative = ("checked", "unchecked") if kind == "checked" else ("enabled", "disabled")
        return [f"    def {expectation['name']}(self, {kind}: bool = True) -> {robot}:",
                *_docstring([f"{status}. Observed both states."], "        "),
                f"        wait = self.app.wait({target}, self.timeout)",
                f"        wait.{positive}() if {kind} else wait.{negative}()", "        return self"]
    if kind == "list":
        rows = [f"class_name({_quote(row)})" for row in expectation.get("rows", [])]
        if not rows:
            return [f"    def {expectation['name']}(self, empty: bool = True) -> {robot}:",
                    '        raise NotImplementedError("REVIEW: no observed row class for this list")']
        row = rows[0] if len(rows) == 1 else f"any_of({', '.join(rows)})"
        return [f"    def {expectation['name']}(self, empty: bool = True) -> {robot}:",
                *_docstring([f"{status}. Observed empty and with rows."], "        "),
                f"        rows = self.app.wait({row}.has_parent({target}), self.timeout)",
                "        rows.gone() if empty else rows.visible()", "        return self"]
    if kind == "error":
        # Snapshots capture the error, but no element API reads it: the screen snapshot is
        # diagnostic, not a test assertion. The check is named here for review.
        return [f"    def {expectation['name']}(self, error: str = {_quote(expectation['examples'][0])}) -> {robot}:",
                *_docstring([f"{status}. Observed errors: {_examples(expectation['examples'])}."], "        "),
                '        raise NotImplementedError("REVIEW: tap_e2e cannot assert a field error on an element yet")']
    if kind == "shown":
        lines = [f"    def {expectation['name']}(self) -> {robot}:",
                 *_docstring([f"{status}. Present in some observations only."], "        "),
                 f"        self.app.wait({target}, self.timeout).text_equals({_quote(expectation['examples'][0])})",
                 "        return self"]
        if expectation.get("absent_name"):
            lines += ["", f"    def {expectation['absent_name']}(self) -> {robot}:",
                      *_docstring([f"{status}. Absent in some observations."], "        "),
                      f"        self.app.wait({target}, self.timeout).gone()", "        return self"]
        return lines
    return [f"    def {expectation['name']}(self) -> {robot}:",
            *_docstring([f"{status}. Present in some observations only."], "        "),
            f"        self.app.wait(text({_quote(expectation['examples'][0])}), self.timeout).visible()",
            "        return self"]


def python(model: Mapping) -> str:
    """Emit the draft as a Python module on the public `tap_e2e` API."""
    lines = [
        f'"""UNVERIFIED robot draft for {model["package"]} ({FORMAT}).',
        "",
        "Generated from one exploration run. Names, landmarks and expectations are proposals:",
        "review them (see REVIEW.md), run the robots, and only then rely on them in tests.",
        '"""',
        "",
        "from __future__ import annotations",
        "",
        "from tap_e2e import App, any_of, class_name, desc, hint, res, res_id, text  # noqa: F401",
        "",
        "",
        "class Robot:",
        '    """Base: every robot holds the app and checks its screen on arrival."""',
        "",
        "    def __init__(self, app: App, timeout: float = 10.0):",
        "        self.app, self.timeout = app, timeout",
        "",
        "    def verify(self) -> Robot:",
        "        return self",
    ]
    for screen in model["screens"]:
        robot = screen["name"]
        lines += ["", "", f"class {robot}(Robot):"]
        doc = [f"{len(screen['variants'])} observed variant(s) over {len(screen['members'])} observation(s)."]
        if screen["landmark_undistinguished_observations"]:
            doc.append(f"REVIEW: verify() does not exclude {screen['landmark_undistinguished_observations']} "
                       "other observation(s).")
        explorable = sorted({entry["label"] for entry in screen["unexplored"] if not entry["blocked_reason"]})
        if explorable:
            doc.append("Unexplored controls: " + ", ".join(explorable[:8]) + ".")
        lines += _docstring(doc, "    ")
        lines += ["", f"    def verify(self) -> {robot}:"]
        for predicate in screen["landmark"]:
            lines.append(f"        self.app.wait({_landmark_source(predicate)}, self.timeout).one()")
        lines.append("        return self")
        for method in screen["methods"]:
            returns = method["returns"]
            parameter = method["parameter"]
            signature = f"self, {parameter}: str" if parameter else "self"
            lines += ["", f"    def {method['name']}({signature}) -> {returns}:"]
            summary = f"{method['verb']} {method['label']!r}" if method["label"] else method["verb"]
            if method["examples"]:
                summary += f"; observed values: {_examples(method['examples'])}"
            docstring = [f"{summary}. Evidence: {', '.join(method['evidence'][:3])}."]
            if method["replay"] == "verified":
                docstring.append("Verified by replay from a restored device snapshot.")
            elif method["replay"] in ("failed", "unplanned"):
                docstring.append(f"REVIEW: replay {method['replay']}: {method.get('replay_error', '')}")
            if method["outcome_dependent"]:
                joiner = " or " if method["when_mode"] == "any" else " and "
                docstring.append(("Precondition" if method["confirmed"] else "REVIEW: observed only when") + ": " +
                                 (joiner.join(method["when"]) or "no common source condition") + ".")
            lines += _docstring(docstring, "        ")
            if method["selector"] is None:
                lines.append('        raise NotImplementedError("REVIEW: no index-free selector for this control")')
                continue
            call = "tap()" if method["verb"] == "tap" else "set_text(value)"
            lines.append(f"        self.app.element({method['selector']}).{call}")
            if returns == robot:
                lines.append("        return self.verify()")
            else:
                lines.append(f"        return {returns}(self.app, self.timeout).verify()")
        for workflow in screen["workflows"]:
            if not workflow["accepted"]:
                continue
            parameters = "".join(f", {parameter}: str" for parameter in workflow["parameters"])
            lines += ["", f"    def {workflow['name']}(self{parameters}) -> {workflow['returns']}:",
                      *_docstring(["Workflow (reviewed): " + ", then ".join(workflow["steps"]) + "."], "        ")]
            calls = [f"{step}({parameter})" for step, parameter in zip(workflow["steps"], workflow["parameters"])]
            lines.append("        return self." + ".".join([*calls, f"{workflow['steps'][-1]}()"]))
        for expectation in screen["expectations"]:
            lines += ["", *_expectation_source(expectation, robot)]
    return "\n".join(lines) + "\n"


def report(model: Mapping) -> str:
    """Markdown checklist of the review queue."""
    lines = [f"# Robot draft review — {model['package']}", "",
             f"{len(model['screens'])} robots, {sum(len(s['methods']) for s in model['screens'])} methods, "
             f"{sum(len(s['expectations']) for s in model['screens'])} expectations. "
             f"Status: **{model['status']}**. Record decisions in `review.json` and regenerate.", ""]
    for item in model["review"]:
        method = f" `{item['method']}`" if item.get("method") else ""
        lines.append(f"- [ ] **{item['kind']}** `{item['screen']}`{method}: {item['detail']}")
    lines += ["", "## Variants", ""]
    for screen in model["screens"]:
        for variant in screen["variants"]:
            if variant["facts"]:
                extra = f" (also matches {variant['unseparated']} other)" if variant["unseparated"] else ""
                lines.append(f"- `{screen['id']}` {variant['id']}: {' and '.join(variant['facts'])}{extra}")
    example = {}
    if model["screens"]:
        screen = model["screens"][0]
        example = {screen["id"]: {"name": "BetterRobotName", "methods": {"tap_x": "open_x"}, "drop": [],
                                  "confirmed": [], "expectations": []}}
    lines += ["", "## review.json", "",
              "`name` renames a robot, `methods` renames methods and expectations, `drop` removes them, "
              "`confirmed` accepts an outcome method's precondition, `preconditions` replaces one "
              "(`{\"mode\": \"all\" | \"any\", \"facts\": [...]}`), `expectations` promotes an observed "
              "check to a reviewed one, and `workflows` accepts (true) or declines (false) a proposed "
              "workflow method. `tap-explorer robots --ask` asks these questions and writes the file.",
              "", "```json",
              json.dumps({"format": REVIEW_FORMAT, "screens": example}, indent=2), "```", ""]
    return "\n".join(lines)


_ASKED = ("outcome-depends", "dialog-name", "workflow", "list-check", "name")


def ask(model: Mapping, review: Mapping | None, read=input, show=print) -> dict:
    """Walk the review queue as questions and return the updated `tap-robots-review/1` decisions.

    Asks only what a person must decide (preconditions, names, workflows, list checks); the
    other review items are shown, not asked. ``s`` (or an empty answer where nothing is
    proposed) skips a question so it comes back next time, ``q`` stops. Never decides anything
    itself: an answer is required for every recorded decision.
    """
    result = json.loads(json.dumps(review or {"format": REVIEW_FORMAT, "screens": {}}))
    result.setdefault("format", REVIEW_FORMAT)
    decisions = result.setdefault("screens", {})
    screens = {screen["id"]: screen for screen in model["screens"]}

    def reply(prompt: str) -> str:
        try:
            return read(prompt).strip()
        except EOFError:
            return "q"

    for item in model["review"]:
        screen = screens[item["screen"]]
        decision = decisions.setdefault(screen["id"], {})
        renames = decision.get("methods", {})

        def original(name: str) -> str:
            return next((old for old, new in renames.items() if new == name), name)

        def add(key: str, value: str) -> None:
            if value not in decision.setdefault(key, []):
                decision[key].append(value)

        if item["kind"] not in _ASKED:
            show(f"[{item['kind']}] {screen['name']}{' ' + item['method'] if item.get('method') else ''}: "
                 f"{item['detail']}")
            continue
        show("")
        if item["kind"] in ("name", "dialog-name"):
            texts = f" (texts: {', '.join(screen['texts'])})" if screen["texts"] else ""
            answer = reply(f"{screen['name']}{texts}\n  robot name [Enter keeps it, s skips, q quits]: ")
            if answer == "q":
                break
            if answer == "s":
                continue
            name = pascal(answer.removesuffix("Robot")) + "Robot" if answer else screen["name"]
            if name in {other["name"] for other in model["screens"] if other is not screen}:
                show(f"  {name} is taken; skipped")
                continue
            decision["name"] = name
        elif item["kind"] == "outcome-depends":
            method = next(method for method in screen["methods"] if method["name"] == item["method"])
            choices = precondition_choices(method)
            show(f"{screen['name']}.{method['name']} → {method['returns']}: {item['detail']}")
            for key, (label, _) in choices.items():
                show(f"  {key}) {label}")
            answer = reply("  d) drop the method  s) skip  q) quit\n  precondition: ")
            if answer == "q":
                break
            if answer == "d":
                add("drop", original(method["name"]))
            elif answer in choices:
                decision.setdefault("preconditions", {})[method["name"]] = choices[answer][1]
        elif item["kind"] == "workflow":
            workflow = next(workflow for workflow in screen["workflows"] if workflow["name"] == item["method"])
            answer = reply(f"{screen['name']}: {item['detail']}\n  y) add  n) never  s) skip  q) quit: ")
            if answer == "q":
                break
            if answer in ("y", "n"):
                decision.setdefault("workflows", {})[workflow["id"]] = answer == "y"
        elif item["kind"] == "list-check":
            answer = reply(f"{screen['name']}: {item['detail']}\n  y) check it  n) drop it  s) skip  q) quit: ")
            if answer == "q":
                break
            if answer == "y":
                add("expectations", original(item["method"]))
            elif answer == "n":
                add("drop", original(item["method"]))
    for screen_id in [key for key, value in decisions.items() if not value]:
        del decisions[screen_id]
    return result


def write(run: Path, out: Path, review_path: Path | None = None, verification_path: Path | None = None) -> dict:
    """Write robots.json, robots.py and REVIEW.md to a new or existing output directory."""
    review = json.loads(review_path.read_text(encoding="utf-8")) if review_path else None
    verification = json.loads(verification_path.read_text(encoding="utf-8")) if verification_path else None
    model = build(run, review, verification)
    out.mkdir(parents=True, exist_ok=True)
    (out / "robots.json").write_text(json.dumps(model, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    (out / "robots.py").write_text(python(model), encoding="utf-8")
    (out / "REVIEW.md").write_text(report(model), encoding="utf-8")
    return {"robots": len(model["screens"]), "methods": sum(len(s["methods"]) for s in model["screens"]),
            "expectations": sum(len(s["expectations"]) for s in model["screens"]),
            "review_items": len(model["review"]), "status": model["status"], "out": str(out)}
