"""Scoring robot drafts against a hand-written answer key; offline, on test_robots' fixture run."""

import copy
import json
from pathlib import Path

import pytest

from tap_explorer.cli import main
from tap_explorer.score import evaluate, regressions, score, summary, validate_key
from tap_explorer.robots import build
from tap_explorer.verify import _document

from test_robots import _run as _robots_run


def _run(tmp_path):
    """test_robots' run, with the observation features a live run stores in its graph."""
    run = _robots_run(tmp_path)
    graph = json.loads((run / "graph.json").read_text())
    for value in graph["observations"].values():
        value["evidence"]["features"] = json.loads(Path(value["evidence"]["snapshot"]).read_text())
    (run / "graph.json").write_text(json.dumps(graph))
    return run

KEY = {
    "format": "tap-robots-key/1", "package": "p", "provenance": "fixture-source",
    "screens": [
        {"id": "home", "name": "Requests", "recognize": [{"text": "Requests"}],
         "methods": [{"verb": "tap", "control": "start", "to": "form"}],
         "checks": [{"resource": "requests", "kind": "list"}]},
        {"id": "form", "name": "YourName", "recognize": [{"text": "Your name"}], "workflow": True,
         "methods": [{"verb": "fill", "control": "name", "to": "form"},
                     {"verb": "tap", "control": "next", "to": "form",
                      "when": {"mode": "all", "facts": ["name is empty"]}},
                     {"verb": "tap", "control": "next", "to": "done",
                      "when": {"mode": "all", "facts": ["name is not empty"]}},
                     {"verb": "tap", "control": "help", "to": "form", "optional": True}],
         "checks": [{"resource": "summary", "kind": "value"}, {"resource": "terms", "kind": "checked"},
                    {"resource": "validation", "kind": "shown"}]},
        {"id": "done", "name": "Saved", "recognize": [{"text": "Saved"}],
         "methods": [{"verb": "tap", "control": "home", "to": "home"}], "checks": []},
    ],
}


def test_a_draft_that_matches_its_key_scores_full_marks_without_counting_names(tmp_path):
    run = _run(tmp_path)
    result = score(build(run), _document(run), KEY)

    assert (result["screens"]["robots"], result["screens"]["split"], result["screens"]["merged"]) == (3, [], [])
    assert result["landmarks"]["exact"] == 3
    assert (result["methods"]["recall"], result["methods"]["precision"]) == (1.0, 1.0)
    assert result["preconditions"]["right"] == 2
    assert (result["checks"]["recall"], result["checks"]["precision"]) == (1.0, 1.0)


def test_methods_on_a_screen_the_key_leaves_out_are_reported_not_scored(tmp_path):
    """A key that covers only some screens cannot judge a method that starts or ends elsewhere;
    a method between two keyed screens that the key does not list is still wrong."""
    run = _run(tmp_path)
    key = copy.deepcopy(KEY)
    key["screens"] = [screen for screen in key["screens"] if screen["id"] != "done"]
    form = key["screens"][1]
    form["methods"] = [method for method in form["methods"] if method["to"] != "done"]
    key["screens"][0]["methods"] = []
    result = score(build(run), _document(run), key)["methods"]

    assert sorted(tuple(row["as"]) for row in result["outside"]) == [
        ("?", "tap", "home", "home"), ("form", "tap", "next", "?")]
    assert [tuple(row["as"]) for row in result["extra"]] == [("home", "tap", "start", "form")]
    assert result["precision"] == round(result["drafted"] / (result["drafted"] + 1), 3)


def test_checks_on_a_screen_the_key_leaves_out_or_on_visible_text_are_sorted_and_reported(tmp_path):
    run = _run(tmp_path)
    model = build(run)
    form = next(robot for robot in model["screens"] if any(item["kind"] == "value" for item in robot["expectations"]))
    form["expectations"] += [{"kind": "visible", "resource": None, "examples": ["Saved draft"]},
                             {"kind": "shown", "resource": "banner", "examples": ["Draft"]}]
    key = copy.deepcopy(KEY)
    key["screens"] = [screen for screen in key["screens"] if screen["id"] != "home"]
    for screen in key["screens"]:
        screen["methods"] = [method for method in screen["methods"] if method["to"] != "home"]
    result = score(model, _document(run), key)["checks"]

    assert result["extra"] == [["form", None, "visible"], ["form", "banner", "shown"]]
    assert result["outside"] and all(row[0] == "?" for row in result["outside"])
    assert all(row[0] != "?" for row in result["extra"])


def test_untested_fact_is_offered_to_the_reviewer_and_answered_right(tmp_path):
    """Terms were only ever unchecked while the name was empty: the draft cannot know that the
    key needs both, keeps the observed condition, and offers the combination."""
    key = copy.deepcopy(KEY)
    forms = key["screens"][1]["methods"]
    forms[1]["when"] = {"mode": "any", "facts": ["name is empty", "terms unchecked"]}
    forms[2]["when"] = {"mode": "all", "facts": ["name is not empty", "terms checked"]}
    report = evaluate(_run(tmp_path), key)

    assert (report["draft"]["preconditions"]["right"], report["draft"]["preconditions"]["offered"]) == (0, 2)
    assert report["answered"]["preconditions"]["right"] == 2
    assert report["effort"]["unanswerable"] == 0
    assert "preconditions" in summary(report)


def test_a_condition_the_draft_never_offers_stays_wrong_and_unanswerable(tmp_path):
    key = copy.deepcopy(KEY)
    key["screens"][1]["methods"][2]["when"] = {"mode": "all", "facts": ["terms unchecked"]}
    result = evaluate(_run(tmp_path), key)

    assert (result["draft"]["preconditions"]["wrong"], result["answered"]["preconditions"]["wrong"]) == (1, 1)
    assert result["effort"]["unanswerable"] == 1



def test_each_copy_of_a_method_on_a_split_screen_is_judged(tmp_path):
    """A form split into two robots (one per tab) drafts Continue on each; a copy whose evidence
    hid the deciding field guesses another condition, and is wrong whichever copy came last."""
    run = _run(tmp_path)
    model = build(run)
    form = next(robot for robot in model["screens"] if robot["name"] == "YourNameRobot")
    twin = copy.deepcopy(form)
    twin.update(id=form["id"] + "-tab", name="YourName2Robot")
    for method in twin["methods"]:
        if method["name"] == "tap_next_to_saved":
            method.update(when=["terms checked"], alternatives=[], either=[], widen=[])
    for position in (0, 1):
        ordered = copy.deepcopy(model)
        ordered["screens"].insert(ordered["screens"].index(form) + position, copy.deepcopy(twin))
        result = score(ordered, _document(run), KEY)["preconditions"]

        assert (result["keyed"], result["right"], result["wrong"]) == (4, 3, 1)
        assert {row["robot"] for row in result["detail"] if row["verdict"] == "wrong"} == {"YourName2Robot"}

@pytest.mark.parametrize("mutate", [
    lambda key: key.update(format="other"),
    lambda key: key.update(provenance="guessed"),
    lambda key: key["screens"][0]["methods"][0].update(to="nowhere"),
    lambda key: key["screens"][1]["checks"][0].update(kind="colour"),
    lambda key: key["screens"][1]["methods"][1].update(when={"mode": "some", "facts": ["x"]}),
])
def test_malformed_keys_are_refused(mutate):
    key = copy.deepcopy(KEY)
    mutate(key)
    with pytest.raises(ValueError):
        validate_key(key)


def test_cli_prints_the_table_or_the_report(tmp_path, capsys):
    (tmp_path / "run").mkdir()
    run = _run(tmp_path / "run")
    key = tmp_path / "key.json"
    key.write_text(json.dumps(KEY))
    assert main(["robots-score", "--run", str(run), "--key", str(key)]) == 0
    assert "methods: precision" in capsys.readouterr().out
    assert main(["robots-score", "--run", str(run), "--key", str(key), "--json"]) == 0
    assert json.loads(capsys.readouterr().out)["format"] == "tap-robots-score/1"


def test_features_in_sidecar_files_score_like_inline_ones(tmp_path):
    """Live runs keep features beside the snapshot (``features_file``), not in the graph."""
    (tmp_path / "inline").mkdir()
    (tmp_path / "sidecar").mkdir()
    inline = _run(tmp_path / "inline")
    run = _robots_run(tmp_path / "sidecar")
    graph = json.loads((run / "graph.json").read_text())
    for obs, value in graph["observations"].items():
        sidecar = run / f"{obs}.features.json"
        sidecar.write_text(Path(value["evidence"]["snapshot"]).read_text())
        value["evidence"]["features_file"] = str(sidecar)
    (run / "graph.json").write_text(json.dumps(graph))
    assert evaluate(run, KEY)["draft"] == evaluate(inline, KEY)["draft"]


def test_baseline_gate_flags_only_worse_numbers(tmp_path, capsys):
    (tmp_path / "run").mkdir()
    run = _run(tmp_path / "run")
    key = tmp_path / "key.json"
    key.write_text(json.dumps(KEY))
    report = evaluate(run, KEY)
    assert regressions(report, report) == []

    better = copy.deepcopy(report)
    better["draft"]["preconditions"]["right"] += 1
    right = report["draft"]["preconditions"]["right"]
    assert regressions(report, better) == [f"draft preconditions.right: {right + 1} -> {right}"]
    assert regressions(better, report) == []
    merged = copy.deepcopy(report)
    merged["draft"]["screens"]["merged"] = [["form", "done"]]  # lists are compared by length
    assert regressions(merged, report) == ["draft screens.merged: 0 -> 1"]

    baseline = tmp_path / "baseline.json"
    baseline.write_text(json.dumps(report))
    argv = ["robots-score", "--run", str(run), "--key", str(key), "--baseline", str(baseline)]
    assert main(argv) == 0
    capsys.readouterr()
    baseline.write_text(json.dumps(better))
    assert main(argv) == 1
    assert "regression: draft preconditions.right" in capsys.readouterr().err

    for mutate in ({"format": "other"}, {"package": "q"}):
        with pytest.raises(ValueError):
            regressions(report, {**report, **mutate})
    baseline.write_text(json.dumps({**report, "package": "q"}))
    with pytest.raises(SystemExit) as stopped:
        main(argv)
    assert stopped.value.code == 1


def test_history_facts_are_scored_only_by_a_key_that_names_them(tmp_path):
    """The Notes key leaves "a title already taken" unkeyed: a draft that drafts it is judged on
    the rest of its condition, and the history fact is counted. A key that names one compares it."""
    run = _run(tmp_path)
    model = build(run)
    method = next(method for robot in model["screens"] for method in robot["methods"]
                  if method["when"] == ["name is not empty"])
    method["when"] = ["name is not empty", "name is a new value"]
    result = score(model, _document(run), KEY)["preconditions"]
    assert (result["right"], result["wrong"], result["history"]) == (2, 0, 1)

    key = copy.deepcopy(KEY)
    key["screens"][1]["methods"][2]["when"]["facts"] = ["name is not empty", "name is a new value"]
    method["when"] = ["name is not empty"]
    result = score(model, _document(run), key)["preconditions"]
    assert (result["right"], result["wrong"], result["history"]) == (1, 1, 0)
