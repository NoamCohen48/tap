import pytest

from tap_explorer.robots import precondition_choices
from tap_explorer.rules import apply, consistent, questions


def _model():
    """Catima's Options-tab Save: saved with store and ID filled, rejected without either, and
    once rejected with both filled (its balance did not parse)."""
    def method(name, returns, observed):
        return {"name": name, "verb": "tap", "label": "Save", "returns": returns, "group": "g1",
                "when": [], "when_mode": "all", "alternatives": [], "either": [],
                "observed": [{"attempt": attempt, "facts": sorted(facts)} for attempt, facts in observed]}

    saved = method("tap_save_to_list", "ListRobot", [
        ("attempt-1", {"store is not empty", "id is not empty", "balance = '0'"}),
        ("attempt-2", {"store is not empty", "id is not empty", "balance = '1'"})])
    rejected = method("tap_save_rejected", "OptionsRobot", [
        ("attempt-3", {"store is empty", "id is not empty", "balance = '0'"}),
        ("attempt-4", {"store is not empty", "id is not empty", "balance = 'Grace'"})])
    return {"package": "p", "screens": [{"id": "OptionsRobot", "methods": [saved, rejected]}]}


def test_questions_list_each_outcome_with_its_attempts_and_the_vocabulary():
    document = questions(_model())
    assert document["format"] == "tap-rule-questions/1"
    assert document["vocabulary"] == ["balance = '0'", "balance = '1'", "balance = 'Grace'", "id is not empty",
                                      "store is empty", "store is not empty"]
    [control] = document["controls"]
    assert (control["screen"], control["label"]) == ("OptionsRobot", "Save")
    assert [(o["method"], [a["attempt"] for a in o["attempts"]]) for o in control["outcomes"]] == [
        ("tap_save_to_list", ["attempt-1", "attempt-2"]), ("tap_save_rejected", ["attempt-3", "attempt-4"])]


def test_proposed_rules_are_checked_against_every_attempt_and_only_consistent_ones_offered():
    model = _model()
    refused = apply(model, {"format": "tap-rule-candidates/1", "source": "model-x", "rules": [
        {"screen": "OptionsRobot", "method": "tap_save_to_list", "mode": "all",
         "facts": ["store is not empty", "id is not empty"], "reason": "the required fields"},
        {"screen": "OptionsRobot", "method": "tap_save_rejected", "mode": "any",
         "facts": ["store is empty", "balance = 'Grace'"], "reason": "a balance that does not parse"},
        {"screen": "OptionsRobot", "method": "tap_save_rejected", "mode": "all", "facts": ["note is empty"]},
        {"screen": "OptionsRobot", "method": "tap_missing", "mode": "all", "facts": ["store is empty"]},
        {"screen": "OptionsRobot", "method": "tap_save_rejected", "mode": "all", "facts": ["store is empty"],
         "selector": "#save"}]})
    saved, rejected = model["screens"][0]["methods"]
    # attempt-4 had both fields filled and was rejected: the draft's evidence contradicts it.
    assert saved["proposed"][0]["contradictions"] == ["attempt-4"] and consistent(saved) == []
    assert consistent(rejected) == rejected["proposed"]
    assert precondition_choices(rejected)["p1"] == (
        "any: store is empty or balance = 'Grace' (proposed by model-x: a balance that does not parse)",
        {"mode": "any", "facts": ["store is empty", "balance = 'Grace'"]})
    assert [item["reason"] for item in refused] == [
        "facts no attempt had: note is empty", "no outcome-dependent method by that screen and name",
        "fields are screen, method, mode, facts and an optional reason"]


@pytest.mark.parametrize("document", [{}, {"format": "tap-rule-candidates/1", "rules": []},
                                      {"format": "tap-rule-candidates/1", "source": "x", "rules": {}}])
def test_a_malformed_candidates_document_is_refused(document):
    with pytest.raises(ValueError):
        apply(_model(), document)
