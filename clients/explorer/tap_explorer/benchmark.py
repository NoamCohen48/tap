"""Offline screen/variant policy scoring. Never changes graphs or executes device input.

Corpus labels describe reviewed presentation/observable conditions, not Markov identity.
Analyst labels remain provisional; metrics against them are not human validation or app coverage.
This module deliberately depends only on the standard library (no SDK, model or database).
"""

from __future__ import annotations

import hashlib
import json
from collections import Counter, defaultdict
from itertools import combinations

from .similarity import STRUCTURE_THRESHOLD, TEXT_THRESHOLD, cluster, variant_key

CORPUS_FORMAT = "tap-discovery-corpus/2"
REPORT_FORMAT = "tap-discovery-score/2"
BASELINES = ("exact", "skeleton", "skeleton-exact")
POLICIES = (*BASELINES, "similarity", "similarity-roles")
PROVENANCE = {"fixture-oracle", "analyst-provisional", "human-reviewed"}


def _canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def _hash(value: object) -> str:
    return hashlib.sha256(_canonical(value).encode()).hexdigest()


def _string(value: object, label: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")


def validate_corpus(corpus: dict) -> None:
    """Validate an evidence-only corpus, including label provenance and package boundaries.

    Unknown variant labels are null, never guessed from the policy under evaluation. Features
    must be finite JSON, with owned node arrays; original retained features stay unchanged.
    """
    if not isinstance(corpus, dict) or set(corpus) != {"format", "sources", "cases"} or corpus["format"] != CORPUS_FORMAT:
        raise ValueError("unsupported discovery corpus")
    _canonical(corpus)  # Reject NaN, infinity and non-JSON inputs even for direct callers.
    sources, cases = corpus["sources"], corpus["cases"]
    if not isinstance(sources, dict) or not sources or not isinstance(cases, list) or not cases:
        raise ValueError("corpus needs sources and cases")
    for key, source in sources.items():
        _string(key, "source ID")
        if not isinstance(source, dict):
            raise ValueError("invalid source")
        for required in ("package", "description", "label_scope"):
            _string(source.get(required), required)
        values = source.get("typed_values")
        if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
            raise ValueError("recorded typed_values must be strings")
    seen = set()
    for case in cases:
        if not isinstance(case, dict) or set(case) != {"id", "source", "observation", "features", "labels", "snapshot", "snapshot_sha256", "removed_foreign_nodes"}:
            raise ValueError("invalid corpus case")
        _string(case["id"], "case ID")
        _string(case["observation"], "observation ID")
        _string(case["source"], "case source")
        if case["id"] in seen or case["source"] not in sources:
            raise ValueError("duplicate case or unknown source")
        seen.add(case["id"])
        labels, features = case["labels"], case["features"]
        if not isinstance(labels, dict) or set(labels) != {"screen", "variant", "provenance", "note"}:
            raise ValueError("invalid labels")
        _string(labels["screen"], "screen label")
        _string(labels["note"], "label note")
        if labels["variant"] is not None:
            _string(labels["variant"], "variant label")
        if not isinstance(labels["provenance"], str) or labels["provenance"] not in PROVENANCE:
            raise ValueError("unrecognized label provenance")
        if not isinstance(features, dict) or features.get("package") != sources[case["source"]]["package"]:
            raise ValueError("feature package does not match source")
        _string(features.get("version"), "feature version")
        nodes = features.get("nodes")
        if not isinstance(nodes, list) or not nodes:
            raise ValueError("owned feature nodes required")
        for node in nodes:
            if not isinstance(node, dict) or node.get("package") != features["package"]:
                raise ValueError("foreign node in owned features")
            for slot in ("resource", "class"):
                if slot not in node or (node[slot] is not None and not isinstance(node[slot], str)):
                    raise ValueError("resource/class must be string or null")
        snapshot = case["snapshot"]
        if not isinstance(snapshot, dict) or set(snapshot) != {"snapshot_id", "rotation", "nodes"}:
            raise ValueError("invalid owned snapshot")
        if type(snapshot["snapshot_id"]) is not int or not 0 < snapshot["snapshot_id"] < 2**64:
            raise ValueError("invalid snapshot ID")
        if type(snapshot["rotation"]) is not int or snapshot["rotation"] not in range(4):
            raise ValueError("invalid snapshot rotation")
        digest = case["snapshot_sha256"]
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("invalid original snapshot digest")
        if type(case["removed_foreign_nodes"]) is not int or case["removed_foreign_nodes"] < 0:
            raise ValueError("invalid snapshot privacy accounting")
        if not isinstance(snapshot["nodes"], list) or not snapshot["nodes"]:
            raise ValueError("owned raw snapshot nodes required")
        for node in snapshot["nodes"]:
            if not isinstance(node, dict) or node.get("package") != features["package"]:
                raise ValueError("foreign node in owned snapshot")
            for slot in ("resource", "class", "text", "description", "hint"):
                if slot not in node or (node[slot] is not None and not isinstance(node[slot], str)):
                    raise ValueError("invalid snapshot string property")
            if not isinstance(node.get("flags"), list) or any(not isinstance(flag, str) for flag in node["flags"]):
                raise ValueError("invalid snapshot flags")
            if type(node.get("depth")) is not int or node["depth"] < 0:
                raise ValueError("invalid snapshot depth")
            bounds = node.get("bounds")
            if not isinstance(bounds, list) or len(bounds) != 4 or any(type(v) is not int for v in bounds):
                raise ValueError("invalid snapshot bounds")
            if "selector" not in node or (node["selector"] is not None and not isinstance(node["selector"], dict)):
                raise ValueError("invalid snapshot selector")
            for flag in ("editable", "showing_hint", "content_invalid"):
                if flag in node and node[flag] is not None and type(node[flag]) is not bool:
                    raise ValueError("invalid snapshot boolean property")
            if node.get("error") is not None and not isinstance(node["error"], str):
                raise ValueError("invalid snapshot error")


def predict(features: dict, policy: str) -> tuple[str, str]:
    """Return hypothesis keys for screen/variant scoring, never execution-state IDs.

    Skeleton deliberately discards multiplicity/order/text. It is a baseline known to merge
    different generic menus and split Snackbar-bearing screens. Exact reproduces retained-feature
    identity; skeleton-exact changes only screen grouping, retaining exact variant evidence.
    """
    if policy not in BASELINES:
        raise ValueError("single-observation prediction requires a baseline policy")
    exact = _hash(features)
    skeleton = _hash({"package": features["package"], "slots": sorted({
        _canonical([node["resource"], node["class"]]) for node in features["nodes"]})})
    return (exact, exact) if policy == "exact" else (skeleton, skeleton if policy == "skeleton" else exact)


def _pairs(count: int) -> int:
    return count * (count - 1) // 2


def _metrics(rows: list[tuple[str, str, str]]) -> dict:
    # rows = case ID, truth key, prediction key. Counter algebra avoids quadratic pair scans.
    truth = Counter(t for _, t, _ in rows)
    predictions = Counter(p for _, _, p in rows)
    joint = Counter((t, p) for _, t, p in rows)
    same_truth = sum(_pairs(count) for count in truth.values())
    same_prediction = sum(_pairs(count) for count in predictions.values())
    same_both = sum(_pairs(count) for count in joint.values())
    total = _pairs(len(rows))
    merged: dict[str, list[tuple[str, str]]] = defaultdict(list)
    split: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for id, t, p in rows:
        merged[p].append((id, t))
        split[t].append((id, p))
    merged_truth_pairs = {pair for members in merged.values()
                          for pair in combinations(sorted({t for _, t in members}), 2)}
    return {"labeled_cases": len(rows), "truth_clusters": len(truth), "predicted_clusters": len(predictions),
            "merged_truth_cluster_pairs": len(merged_truth_pairs),
            "impure_predicted_clusters": sum(len({t for _, t in members}) > 1 for members in merged.values()),
            "split_truth_clusters": sum(len({p for _, p in members}) > 1 for members in split.values()),
            "pairs": total, "same_truth_pairs": same_truth, "same_prediction_pairs": same_prediction,
            "false_merge_pairs": same_prediction - same_both, "false_split_pairs": same_truth - same_both,
            "pair_precision": same_both / same_prediction if same_prediction else None,
            "pair_recall": same_both / same_truth if same_truth else None,
            "merge_examples": [{"case_ids": [id for id, _ in members[:8]],
                                "truth_labels": sorted({t for _, t in members})[:8]}
                               for _, members in sorted(merged.items()) if len({t for _, t in members}) > 1][:3],
            "split_examples": [{"truth_label": t, "case_ids": [id for id, _ in members[:8]],
                                "predicted_groups": len({p for _, p in members})}
                               for t, members in sorted(split.items()) if len({p for _, p in members}) > 1][:3]}


def score(corpus: dict, policy: str = "exact") -> dict:
    """Score screen families and scoped variants against explicitly supplied labels.

    Pair counts are dataset-dependent, not a percentage of app correctness/coverage. Missing
    variant labels are excluded and counted. Reports disclose provisional-label counts.
    """
    validate_corpus(corpus)
    if policy not in POLICIES:
        raise ValueError("unknown benchmark policy")
    if policy in ("similarity", "similarity-roles"):
        screens = cluster(corpus)

        def variant(case: dict) -> str:
            if policy == "similarity":
                return _hash(case["features"])
            source = corpus["sources"][case["source"]]
            return variant_key(source["package"], case["snapshot"]["nodes"], source["typed_values"])

        predictions = {case["id"]: (screens[case["id"]], variant(case)) for case in corpus["cases"]}
    else:
        predictions = {case["id"]: predict(case["features"], policy) for case in corpus["cases"]}

    def section(cases: list[dict]) -> dict:
        screens, variants = [], []
        for case in cases:
            labels = case["labels"]
            package = corpus["sources"][case["source"]]["package"]
            screen, variant = predictions[case["id"]]
            screens.append((case["id"], _canonical([package, labels["screen"]]), screen))
            if labels["variant"] is not None:
                variants.append((case["id"], _canonical([package, labels["screen"], labels["variant"]]),
                                 _canonical([screen, variant])))
        return {"screens": _metrics(screens), "variants": _metrics(variants),
                "unlabeled_variants": len(cases) - len(variants),
                "label_provenance": dict(sorted(Counter(case["labels"]["provenance"] for case in cases).items()))}

    overall = section(corpus["cases"])
    return {"format": REPORT_FORMAT, "policy": policy, "corpus_sha256": _hash(corpus),
            "screen_merge_gate": {"passed": overall["screens"]["merged_truth_cluster_pairs"] == 0,
                                  "scope": "supplied labels only; provisional labels and no held-out validation"},
            "settings": ({"structure_threshold": STRUCTURE_THRESHOLD, "text_threshold": TEXT_THRESHOLD,
                          "clustering": "complete-link; deterministic strongest weakest-pair merge"}
                         if policy.startswith("similarity") else {}),
            "authority": "offline hypotheses only; no execution identity or approval changes",
            "overall": overall,
            "sources": {key: section([case for case in corpus["cases"] if case["source"] == key])
                        for key in sorted(corpus["sources"])}}


def compare(corpus: dict) -> dict:
    """Compare fixed baseline policies on identical evidence and label coverage."""
    return {policy: score(corpus, policy) for policy in POLICIES}
