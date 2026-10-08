"""Scoring robot drafts against a hand-written answer key; offline, on test_robots' fixture run."""

import copy
import json
from pathlib import Path

import pytest

from tap_explorer.cli import main
from tap_explorer.score import evaluate, score, summary, validate_key
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
