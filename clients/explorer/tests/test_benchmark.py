"""Identity hypotheses are offline evidence scoring, not execution or human authority."""

import copy
import itertools
import json
import random
import subprocess
import sys
from pathlib import Path

import pytest
from tap_explorer.benchmark import (
    BASELINES,
    CORPUS_FORMAT,
    _metrics,
    compare,
    predict,
    score,
    validate_corpus,
)
from tap_explorer.cli import main

CORPUS = Path(__file__).parents[1] / "benchmarks" / "corpus-v2.json"
HELD_OUT = Path(__file__).parents[1] / "benchmarks" / "corpus-held-out-wizard.json"


def small():
    features = {"version": "test/1", "package": "example.app", "nodes": [
        {"package": "example.app", "resource": "example.app:id/title", "class": "TextView", "text": "A"}]}
    node = {**features["nodes"][0], "description": None, "hint": None, "flags": [],
            "depth": 0, "bounds": [0, 0, 100, 100], "selector": {"node": {"resource": {"name": "title"}}}}
    case = {"id": "a", "source": "test", "observation": "obs-a", "features": features,
            "snapshot": {"snapshot_id": 1, "rotation": 0, "nodes": [node]},
            "snapshot_sha256": "0" * 64, "removed_foreign_nodes": 0,
            "labels": {"screen": "screen", "variant": "base", "provenance": "analyst-provisional", "note": "test only"}}
    return {"format": CORPUS_FORMAT, "sources": {"test": {"package": "example.app", "description": "test",
                                                           "label_scope": "presentation only", "typed_values": []}}, "cases": [case]}


def test_saved_corpus_exposes_screen_and_variant_failures():
    corpus = json.loads(CORPUS.read_text())
    original = copy.deepcopy(corpus)
    result = compare(corpus)
    assert corpus == original
    assert len(corpus["cases"]) == 114
    exact = result["exact"]["sources"]
    assert exact["wizard"]["screens"]["truth_clusters"] == 7
    assert exact["wizard"]["screens"]["predicted_clusters"] == 30
    assert exact["wizard"]["variants"]["truth_clusters"] == 30
    assert exact["wizard"]["variants"]["false_merge_pairs"] == 0
    assert exact["wizard"]["variants"]["false_split_pairs"] == 0
    assert exact["loop"]["variants"]["false_merge_pairs"] == 4  # Required-name error icon missing.
    skeleton = result["skeleton"]["sources"]
    assert skeleton["loop"]["screens"]["truth_clusters"] == skeleton["loop"]["screens"]["predicted_clusters"] == 8
    assert skeleton["loop"]["screens"]["false_merge_pairs"] == 4  # Two generic, semantically different menus.
    assert skeleton["loop"]["screens"]["false_split_pairs"] == 18  # List + creation toast.
    assert skeleton["wizard"]["variants"]["false_merge_pairs"] == 368  # Coarse routing would erase conditions.
    assert result["skeleton-exact"]["sources"]["wizard"]["variants"]["false_merge_pairs"] == 0
    assert result["exact"]["overall"]["label_provenance"] == {"analyst-provisional": 54, "fixture-oracle": 60}
    assert all("no execution" in r["authority"] for r in result.values())
    assert skeleton["loop"]["screens"]["merged_truth_cluster_pairs"] == 1
    assert skeleton["loop"]["screens"]["impure_predicted_clusters"] == 1
    assert skeleton["loop"]["screens"]["split_truth_clusters"] == 1
    assert result["skeleton-exact"]["screen_merge_gate"]["passed"] is False
    similarity = result["similarity"]
    for source in ("wizard", "loop"):
        assert similarity["sources"][source]["screens"]["false_merge_pairs"] == 0
        assert similarity["sources"][source]["screens"]["false_split_pairs"] == 0
    assert similarity["screen_merge_gate"]["passed"] is True
    assert similarity["sources"]["loop"]["variants"]["false_merge_pairs"] == 4
    assert all(case["snapshot"]["nodes"][0]["selector"] for case in corpus["cases"])
    assert all(case["removed_foreign_nodes"] > 0 for case in corpus["cases"])


def test_held_out_wizard_runs_are_grouped_without_errors_by_roles():
    corpus = json.loads(HELD_OUT.read_text())
    assert len(corpus["cases"]) == 127
    assert {case["labels"]["provenance"] for case in corpus["cases"]} == {"fixture-oracle"}
    in_sample = json.loads(CORPUS.read_text())
    assert not {c["snapshot_sha256"] for c in corpus["cases"]} & {c["snapshot_sha256"] for c in in_sample["cases"]}
    roles = score(corpus, "similarity-roles")
    assert roles["screen_merge_gate"]["passed"] is True
    for source in roles["sources"].values():
        for level in ("screens", "variants"):
            assert (source[level]["false_merge_pairs"], source[level]["false_split_pairs"]) == (0, 0)
    # The status-bar icon that split run 3 under tap-state-signature/1 (fixed in /2, run 4).
    exact = score(corpus, "exact")["sources"]
    assert exact["held-out-wizard-3"]["variants"]["false_split_pairs"] == 6
    assert exact["held-out-wizard-4"]["variants"]["false_split_pairs"] == 0


@pytest.mark.parametrize("seed", range(10))
def test_counter_metrics_equal_independent_pair_enumeration(seed):
    randomizer = random.Random(seed)
    rows = [(str(i), str(randomizer.randrange(5)), str(randomizer.randrange(4))) for i in range(25)]
    merge = split = same_truth = same_prediction = 0
    for left, right in itertools.combinations(rows, 2):
        t, p = left[1] == right[1], left[2] == right[2]
        same_truth += t
        same_prediction += p
        merge += p and not t
        split += t and not p
    actual = _metrics(rows)
    assert actual["false_merge_pairs"] == merge
    assert actual["false_split_pairs"] == split
    assert actual["same_truth_pairs"] == same_truth
    assert actual["same_prediction_pairs"] == same_prediction


def test_unknown_variant_is_excluded_not_inferred():
    corpus = small()
    corpus["cases"][0]["labels"]["variant"] = None
    result = score(corpus)["overall"]
    assert result["unlabeled_variants"] == 1
    assert result["variants"]["labeled_cases"] == 0
    assert result["variants"]["pair_precision"] is None
    assert result["variants"]["pair_recall"] is None


def test_predictions_do_not_use_labels_and_scopes_do_not_cross_apps():
    corpus = small()
    features = corpus["cases"][0]["features"]
    before = [predict(features, p) for p in BASELINES]
    corpus["cases"][0]["labels"]["screen"] = "renamed by human"
    assert before == [predict(features, p) for p in BASELINES]
    other = copy.deepcopy(features)
    other["package"] = "other.app"
    assert all(predict(features, p) != predict(other, p) for p in BASELINES)


def test_skeleton_discards_order_and_multiplicity_but_exact_does_not():
    features = small()["cases"][0]["features"]
    repeated = copy.deepcopy(features)
    repeated["nodes"] *= 2
    assert predict(features, "skeleton") == predict(repeated, "skeleton")
    assert predict(features, "exact") != predict(repeated, "exact")
    assert predict(features, "skeleton-exact")[0] == predict(repeated, "skeleton-exact")[0]
    assert predict(features, "skeleton-exact")[1] != predict(repeated, "skeleton-exact")[1]


@pytest.mark.parametrize("mutation", [
    lambda c: c.update(format="future"),
    lambda c: c["cases"].append(copy.deepcopy(c["cases"][0])),
    lambda c: c["cases"][0].update(source="unknown"),
    lambda c: c["cases"][0]["features"].update(package="other.app"),
    lambda c: c["cases"][0]["features"]["nodes"][0].update(package="foreign.app"),
    lambda c: c["cases"][0]["features"]["nodes"][0].update(resource=123),
    lambda c: c["cases"][0]["labels"].update(provenance="model-approved"),
    lambda c: c["cases"][0]["labels"].update(provenance=[]),
    lambda c: c["cases"][0]["labels"].update(screen=""),
    lambda c: c["cases"][0]["features"].update(text=float("nan")),
    lambda c: c["sources"]["test"].update(typed_values=[3]),
    lambda c: c["cases"][0]["snapshot"]["nodes"][0].update(package="foreign.app"),
    lambda c: c["cases"][0]["snapshot"].update(snapshot_id=True),
    lambda c: c["cases"][0]["snapshot"]["nodes"][0].update(bounds=[1, 2, 3]),
    lambda c: c["cases"][0]["snapshot"]["nodes"][0].update(selector="unsafe"),
    lambda c: c["cases"][0]["snapshot"]["nodes"][0].update(editable="true"),
    lambda c: c["cases"][0].update(snapshot_sha256="unpinned"),
])
def test_malformed_corpus_is_rejected(mutation):
    corpus = small()
    mutation(corpus)
    with pytest.raises(ValueError):
        validate_corpus(corpus)


def test_unknown_policy_is_rejected():
    with pytest.raises(ValueError, match="policy"):
        score(small(), "promote-to-execution")


def test_cli_scores_without_database_and_rejects_db(tmp_path, capsys):
    corpus = tmp_path / "corpus.json"
    corpus.write_text(json.dumps(small()))
    assert main(["benchmark", "--corpus", str(corpus), "--policy", "exact"]) == 0
    assert json.loads(capsys.readouterr().out)["policy"] == "exact"
    db = tmp_path / "must-not-exist.db"
    with pytest.raises(SystemExit) as failure:
        main(["--db", str(db), "benchmark", "--corpus", str(corpus)])
    assert failure.value.code == 2
    assert not db.exists()


@pytest.mark.parametrize("policy,expected", [("similarity", 0), ("skeleton-exact", 1)])
def test_cli_screen_merge_gate_has_failing_exit_code(policy, expected, capsys):
    assert main(["benchmark", "--corpus", str(CORPUS), "--policy", policy,
                 "--require-no-screen-merges"]) == expected
    report = json.loads(capsys.readouterr().out)
    assert report["screen_merge_gate"]["passed"] == (expected == 0)


def test_cli_invalid_corpus_reports_error(tmp_path, capsys):
    path = tmp_path / "bad.json"
    path.write_text('{"format":"future"}')
    with pytest.raises(SystemExit) as failure:
        main(["benchmark", "--corpus", str(path)])
    assert failure.value.code == 1
    assert "tap-explorer benchmark:" in capsys.readouterr().err


def test_benchmark_runs_without_sdk_or_site_packages():
    source = Path(__file__).parents[1]
    script = """
import json,sys
sys.path.insert(0,sys.argv[1])
from tap_explorer.benchmark import score
with open(sys.argv[2]) as source:
    report=score(json.load(source))
assert 'tap_e2e' not in sys.modules
assert 'openai' not in sys.modules
assert 'grpc' not in sys.modules
print(report['overall']['screens']['labeled_cases'])
"""
    result = subprocess.run([sys.executable, "-S", "-c", script, str(source), str(CORPUS)],
                            check=True, capture_output=True, text=True, timeout=30)
    assert result.stdout.strip() == "114"


def similarity_cases(specs):
    corpus = small()
    base = corpus["cases"][0]
    corpus["cases"] = []
    for id, tokens, truth in specs:
        case = copy.deepcopy(base)
        case.update(id=id, observation=f"obs-{id}")
        case["labels"]["screen"] = truth
        case["snapshot"]["nodes"] = [{**base["snapshot"]["nodes"][0], "text": token} for token in tokens]
        case["features"]["nodes"] = copy.deepcopy(case["snapshot"]["nodes"])
        corpus["cases"].append(case)
    return corpus


def test_complete_link_rejects_transitive_chain():
    from tap_explorer.similarity import cluster

    corpus = similarity_cases([("a", ["a", "b"], "left"),
                               ("b", ["a", "b", "c"], "left"),
                               ("c", ["b", "c"], "right")])
    # AB and BC pass .5 text overlap; AC does not. Single linkage would merge all three.
    groups = cluster(corpus)
    assert groups["a"] == groups["b"]
    assert groups["a"] != groups["c"]
    assert len(set(groups.values())) == 2


@pytest.mark.parametrize("seed", range(10))
def test_complete_link_order_and_ties_are_deterministic(seed):
    from tap_explorer.similarity import cluster

    corpus = similarity_cases([("a", ["a", "b"], "left"),
                               ("b", ["a", "b", "c"], "left"),
                               ("c", ["b", "c"], "right"),
                               ("d", ["unrelated"], "other")])
    expected = cluster(corpus)
    random.Random(seed).shuffle(corpus["cases"])
    assert cluster(corpus) == expected
    # The scorer never uses labels to resolve the tie or select a cluster.
    for case in corpus["cases"]:
        case["labels"].update(screen="all renamed", variant="all renamed")
    assert cluster(corpus) == expected


def test_similarity_uses_raw_evidence_and_preserves_literal_variants():
    corpus = similarity_cases([("a", ["Your name", ""], "identity"),
                               ("b", ["Your name", "Ada"], "identity")])
    for case in corpus["cases"]:
        case["snapshot"]["nodes"][1].update(resource="example.app:id/name", **{"class": "android.widget.EditText"})
        case["features"]["nodes"] = copy.deepcopy(case["snapshot"]["nodes"])
        case["labels"]["variant"] = case["snapshot"]["nodes"][1]["text"] or "empty"
    original = copy.deepcopy(corpus)
    report = score(corpus, "similarity")
    assert report["overall"]["screens"]["predicted_clusters"] == 1
    assert report["overall"]["variants"]["predicted_clusters"] == 2
    assert corpus == original


def test_screen_merge_gate_is_not_offset_by_splits():
    corpus = similarity_cases([("a", ["Shared"], "left"), ("b", ["Shared"], "right"),
                               ("c", ["Unrelated one"], "split"), ("d", ["Unrelated two"], "split")])
    report = score(corpus, "similarity")
    assert report["overall"]["screens"]["merged_truth_cluster_pairs"] == 1
    assert report["overall"]["screens"]["split_truth_clusters"] == 1
    assert report["screen_merge_gate"]["passed"] is False


def test_global_typed_literal_exclusion_is_not_general_robot_accuracy():
    corpus = similarity_cases([("a", ["Settings", "Delete"], "delete"),
                               ("b", ["Settings", "Clear"], "clear")])
    assert score(corpus, "similarity")["screen_merge_gate"]["passed"] is True
    # Deliberate counterexample: data literals may also be meaningful static control text.
    # This candidate's exclusion can hide the distinction; human/held-out promotion must fail.
    corpus["sources"]["test"]["typed_values"] = ["Delete", "Clear"]
    assert score(corpus, "similarity")["screen_merge_gate"]["passed"] is False


def test_cluster_metrics_are_unique_truth_pairs_not_observation_frequency():
    rows = [("a", "left", "merged-one"), ("b", "right", "merged-one"),
            ("c", "left", "merged-two"), ("d", "right", "merged-two")]
    metrics = _metrics(rows)
    assert metrics["false_merge_pairs"] == 2
    assert metrics["merged_truth_cluster_pairs"] == 1
    assert metrics["impure_predicted_clusters"] == 2
    assert metrics["split_truth_clusters"] == 2



def _reference_complete_link(items, structure_threshold=0.9, text_threshold=0.5):
    """The original O(n^4) scan, kept only to prove the fast clustering is equivalent."""
    from tap_explorer.similarity import quality

    ids = sorted(items)
    pair = {(a, b): quality(items[a], items[b], structure_threshold=structure_threshold,
                            text_threshold=text_threshold) for a, b in itertools.combinations(ids, 2)}
    groups = [(id,) for id in ids]
    while True:
        best = None
        for left, right in itertools.combinations(sorted(groups), 2):
            weakest = min(pair[tuple(sorted((a, b)))] for a in left for b in right)
            if weakest >= 1 and (best is None or (-weakest, left, right) < best):
                best = (-weakest, left, right)
        if best is None:
            return {id: group for group in groups for id in group}
        groups.remove(best[1])
        groups.remove(best[2])
        groups.append(tuple(sorted(best[1] + best[2])))


@pytest.mark.parametrize("seed", range(40))
def test_fast_complete_link_equals_reference_scan(seed):
    from tap_explorer.similarity import cluster_evidence

    rng = random.Random(seed)
    vocabulary = [f"t{i}" for i in range(8)]
    slots = [(f"r{i}", "TextView") for i in range(12)]
    items = {}
    for index in range(rng.randint(2, 18)):
        # Small vocabularies force duplicates, chains and exact-score ties.
        items[f"c{index:02d}"] = (rng.choice(["p", "p", "q"]),
                                  frozenset(rng.sample(slots, rng.randint(9, 12))),
                                  frozenset(rng.sample(vocabulary, rng.randint(1, 4))))
    assert cluster_evidence(items) == _reference_complete_link(items)


def test_clustering_scales_to_live_graph_sizes():
    import time

    from tap_explorer.similarity import cluster_evidence

    rng = random.Random(7)
    items = {}
    for index in range(1200):
        screen = index % 40
        slots = frozenset((f"s{screen}-{i}", "View") for i in range(20)) | {(f"v{rng.randint(0, 3)}", "View")}
        items[f"o{index:05d}"] = ("p", slots, frozenset({f"title{screen}", f"value{rng.randint(0, 30)}"}))
    started = time.monotonic()
    groups = cluster_evidence(items)
    assert time.monotonic() - started < 20
    assert len(set(groups.values())) >= 40


def _n(depth, cls, resource=None, text=None, flags=("ENABLED",), **extra):
    return {"package": "p", "depth": depth, "class": cls, "resource": resource, "text": text,
            "description": None, "hint": extra.pop("hint", None), "flags": list(flags), **extra}


def test_variant_roles_collapse_rows_snackbars_and_typed_echo_only():
    from tap_explorer.similarity import variant_items

    def screen(rows, snackbar=False, error=None, name=""):
        nodes = [_n(0, "FrameLayout"), _n(1, "TextView", "p:id/title", "Habits"),
                 _n(1, "EditText", "p:id/name", name or "e.g. Run", hint="e.g. Run")]
        if error:
            nodes.append(_n(1, "TextView", "p:id/error", error))
        nodes.append(_n(1, "androidx.recyclerview.widget.RecyclerView"))
        nodes += [node for row in rows for node in (_n(2, "FrameLayout"), _n(3, "TextView", text=row))]
        if snackbar:
            nodes += [_n(1, "FrameLayout"), _n(2, "LinearLayout"), _n(3, "TextView", "p:id/snackbar_text", "Saved")]
        return variant_items("p", nodes, ["Read", "Run"])

    one = screen(["Read"])
    assert screen(["Read", "Write"]) == one == screen(["Read"], snackbar=True)
    assert screen([]) != one  # empty vs nonempty list is a condition
    assert screen(["Read"], error="Name can't be blank") != one  # validation text is kept
    assert screen(["Read"], name="Run") != one  # an input binding stays literal
    assert screen(["Read"], name="e.g. Run") == one  # hint text reads as empty


def test_variant_roles_keep_flags_and_captured_error_metadata():
    from tap_explorer.similarity import variant_items

    base = [_n(0, "EditText", "p:id/name", "")]
    flagged = [_n(0, "EditText", "p:id/name", "", error="Required", content_invalid=True)]
    checked = [_n(0, "EditText", "p:id/name", "", flags=("ENABLED", "CHECKED"))]
    assert len({json.dumps(variant_items("p", nodes)) for nodes in (base, flagged, checked)}) == 3


def test_snackbar_wrapper_climb_stops_at_substantive_content():
    from tap_explorer.similarity import variant_items

    nodes = [_n(0, "FrameLayout"), _n(1, "TextView", "p:id/title", "Form"),
             _n(1, "TextView", "p:id/snackbar_text", "Saved")]
    # The parent holds real content: only the Snackbar text itself is dropped.
    assert sorted(str(item[0]) for item in variant_items("p", nodes)) == ["None", "p:id/title"]
