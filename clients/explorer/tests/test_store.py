"""Offline graph invariants, durable attempts, and conservative recovery; no device or AI."""

import copy
import math
import subprocess
import sys

import pytest

from tap_explorer import GraphStore


@pytest.fixture
def store(tmp_path):
    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"app": "fixture", "build": "test", "starting_condition": "Home"})
        for name in ("home", "login", "error"):
            graph.add_observation(name, {"snapshot": f"{name}.json", "screenshot": f"{name}.png"})
            graph.add_state(name, name, signature=name, depth=0 if name == "home" else 1)
        yield graph


def candidate(graph, name="open", source="home", *, approve=True, priority=0):
    graph.add_action(name, source, "tap", target={"resource": "profile"}, priority=priority)
    if approve:
        graph.approve_action(name, "operator: fixture navigation")


def test_unknown_risk_is_blocked(store):
    candidate(store, approve=False)
    assert store.next_action() == {"action": None, "reason": "frontier_resolved"}
    with pytest.raises(ValueError):
        store.begin_attempt("a", "open", "home")
    assert store.document()["actions"]["open"]["status"] == "blocked"


def test_frontier_order_is_independent_of_insertion(store, tmp_path):
    for name in ("z", "b", "a"):
        candidate(store, name)
    candidate(store, "deep", "login")
    candidate(store, "priority", priority=1)
    doc = store.document()
    for collection in ("states", "actions", "observations"):
        doc[collection] = dict(reversed(list(doc[collection].items())))
    with GraphStore(tmp_path / "import.db") as restored:
        restored.import_document(doc)
        assert restored.next_action() == store.next_action() == {"action": "a", "reason": "eligible"}


def test_intent_gates_other_attempts_and_does_not_auto_recover(store, tmp_path):
    candidate(store)
    candidate(store, "second")
    store.begin_attempt("try", "open", "home")
    assert store.next_action()["reason"] == "recovery_required"
    with pytest.raises(ValueError):
        store.begin_attempt("other", "second", "home")
    with GraphStore(tmp_path / "graph.db") as reader:
        assert reader.document()["attempts"]["try"]["status"] == "intent"
        assert reader.recover_interrupted() == 1
        assert reader.recover_interrupted() == 0
    assert store.next_action()["reason"] == "recovery_required"
    assert store.document()["actions"]["open"]["status"] == "uncertain"
    with pytest.raises(ValueError):
        store.begin_trial("replay", "open", "home", approval="operator")


def test_reopen_retains_progress(tmp_path):
    path = tmp_path / "run.db"
    with GraphStore(path) as graph:
        graph.initialize({})
        graph.add_observation("o", {})
        graph.add_state("s", "o", signature="s", depth=0)
        candidate(graph, source="s")
        graph.begin_attempt("try", "open", "o")
    with GraphStore(path) as graph:
        assert graph.next_action()["reason"] == "recovery_required"
        graph.recover_interrupted()
    with GraphStore(path) as graph:
        assert graph.document()["attempts"]["try"]["status"] == "indeterminate"


def test_atomic_begin_with_two_connections(store, tmp_path):
    candidate(store)
    with GraphStore(tmp_path / "graph.db") as second:
        store.begin_attempt("one", "open", "home")
        with pytest.raises(ValueError):
            second.begin_attempt("two", "open", "home")
        assert list(second.document()["attempts"]) == ["one"]


def test_multiple_destinations_and_self_loop_preserve_evidence(store):
    candidate(store)
    store.begin_attempt("first", "open", "home")
    store.finish_attempt("first", "succeeded", after="login", destination="login")
    store.begin_trial("second", "open", "home", approval="new invocation after reset")
    store.finish_attempt("second", "succeeded", after="error", destination="error")
    store.begin_trial("third", "open", "home", approval="new invocation after reset")
    store.finish_attempt("third", "succeeded", after="home", destination="home")
    assert [edge["destination"] for edge in store.transitions()] == ["login", "error", "home"]
    assert len(store.document()["attempts"]) == 3
    assert "new invocation" in store.document()["attempts"]["second"]["note"]
    assert store.next_action()["reason"] == "frontier_resolved"


def test_success_without_recognized_destination_is_not_an_edge(store):
    candidate(store)
    store.begin_attempt("try", "open", "home")
    store.finish_attempt("try", "succeeded", after="home")
    assert store.transitions() == []
    assert store.document()["attempts"]["try"]["status"] == "succeeded"


def test_indeterminate_outcome_is_not_retried_or_verified(store):
    candidate(store)
    store.begin_attempt("try", "open", "home")
    store.finish_attempt("try", "indeterminate", after="login", destination="login")
    assert store.transitions() == []
    assert store.next_action()["reason"] == "recovery_required"
    with pytest.raises(ValueError):
        store.finish_attempt("try", "succeeded", after="login", destination="login")


def test_before_and_after_require_state_membership_and_rollback(store):
    candidate(store)
    before = store.document()
    with pytest.raises(ValueError):
        store.begin_attempt("try", "open", "login")
    assert store.document() == before
    store.begin_attempt("try", "open", "home")
    intent = store.document()
    with pytest.raises(ValueError):
        store.finish_attempt("try", "succeeded", after="home", destination="login")
    assert store.document() == intent


def test_failure_before_input_needs_explicit_new_trial(store):
    candidate(store)
    store.begin_attempt("try", "open", "home")
    with pytest.raises(ValueError):
        store.finish_attempt("try", "failed_before_input", after="login", destination="login")
    store.finish_attempt("try", "failed_before_input", note="AMBIGUOUS")
    assert store.next_action()["action"] is None
    store.begin_trial("new", "open", "home", approval="operator corrected prerequisite")
    assert store.document()["attempts"]["try"]["note"] == "AMBIGUOUS"


def test_completed_attempt_is_immutable(store):
    candidate(store)
    store.begin_attempt("try", "open", "home")
    store.finish_attempt("try", "succeeded")
    before = store.document()
    with pytest.raises(ValueError):
        store.finish_attempt("try", "failed_before_input")
    with pytest.raises(ValueError):
        store.begin_trial("try", "open", "home", approval="operator")
    assert store.document() == before


@pytest.mark.parametrize("field,value", [("actions", 0), ("depth", 0)])
def test_budgets_stop_selection(store, tmp_path, field, value):
    candidate(store, source="login")
    doc = store.document()
    doc["budgets"][field] = value
    with GraphStore(tmp_path / "limited.db") as limited:
        limited.import_document(doc)
        expected = "action_budget" if field == "actions" else "depth_budget"
        assert limited.next_action()["reason"] == expected
        with pytest.raises(ValueError):
            limited.begin_attempt("try", "open", "login")


def test_attempts_count_against_budget_even_if_rejected(store, tmp_path):
    candidate(store)
    doc = store.document()
    doc["budgets"]["actions"] = 1
    with GraphStore(tmp_path / "limited.db") as limited:
        limited.import_document(doc)
        limited.begin_attempt("one", "open", "home")
        limited.finish_attempt("one", "failed_before_input")
        assert limited.next_action()["reason"] == "action_budget"
        with pytest.raises(ValueError):
            limited.begin_trial("two", "open", "home", approval="operator")


def test_equal_signatures_are_not_implicitly_merged(store):
    store.add_state("other", "login", signature="home", depth=2)
    assert len(store.document()["states"]) == 4


def test_observations_are_detached_and_cannot_be_overwritten(store):
    evidence = {"labels": ["Home"]}
    store.add_observation("new", evidence)
    evidence["labels"].append("changed")
    assert store.document()["observations"]["new"]["evidence"] == {"labels": ["Home"]}
    with pytest.raises(ValueError):
        store.add_observation("new", {})
    exported = store.document()
    exported["observations"].clear()
    assert "new" in store.document()["observations"]


def test_export_import_roundtrip_retains_uncertainty(store, tmp_path):
    candidate(store)
    store.begin_attempt("try", "open", "home")
    store.recover_interrupted()
    with GraphStore(tmp_path / "restored.db") as restored:
        restored.import_document(store.document())
        assert restored.document() == store.document()
        assert restored.next_action()["reason"] == "recovery_required"
        with pytest.raises(ValueError):
            restored.import_document(store.document())


@pytest.mark.parametrize("mutate", [
    lambda d: d.update(format="future/2"),
    lambda d: d.update(unknown=True),
    lambda d: d.update(context=[]),
    lambda d: d["budgets"].update(actions=-1),
    lambda d: d["budgets"].update(actions=True),
    lambda d: d["budgets"].update(depth=1.5),
    lambda d: d["observations"]["home"].update(evidence={"nan": math.nan}),
    lambda d: d["states"]["home"].update(observations=["missing"]),
    lambda d: d["states"]["home"].update(depth=-1),
    lambda d: d["actions"]["open"].update(source="missing"),
    lambda d: d["actions"]["open"].update(verb="coordinate_tap"),
    lambda d: d["actions"]["open"].update(approval=None),
    lambda d: d["actions"]["open"].update(status="attempted"),
    lambda d: d["actions"]["open"].update(scenario=[]),
])
def test_malformed_import_is_rejected_atomically(store, tmp_path, mutate):
    candidate(store)
    doc = copy.deepcopy(store.document())
    mutate(doc)
    with GraphStore(tmp_path / "invalid.db") as target:
        with pytest.raises(ValueError):
            target.import_document(doc)
        target.initialize({})  # Failed import did not insert a partial graph.


def test_attach_observation_requires_existing_evidence(store):
    store.add_observation("extra", {"warning": "moving"})
    store.attach_observation("home", "extra")
    assert store.document()["states"]["home"]["observations"] == ["home", "extra"]
    before = store.document()
    with pytest.raises(ValueError):
        store.attach_observation("home", "missing")
    assert store.document() == before


def test_wrong_scheduler_choice_is_rejected(store):
    candidate(store, "a")
    candidate(store, "b")
    with pytest.raises(ValueError):
        store.begin_attempt("try", "b", "home")
    assert not store.document()["attempts"]


@pytest.mark.parametrize("finish", [False, True])
def test_process_exit_without_cleanup_preserves_commit(tmp_path, finish):
    path = tmp_path / "crash.db"
    script = """
import os
import sys
from tap_explorer import GraphStore
store = GraphStore(sys.argv[1])
store.initialize({})
store.add_observation("o", {})
store.add_state("s", "o", signature="s", depth=0)
store.add_action("a", "s", "back")
store.approve_action("a", "operator")
store.begin_attempt("try", "a", "o")
if sys.argv[2] == "yes":
    store.finish_attempt("try", "succeeded", after="o", destination="s")
os._exit(7)
"""
    result = subprocess.run([sys.executable, "-c", script, str(path), "yes" if finish else "no"],
                            capture_output=True, text=True, timeout=30, check=False)
    assert result.returncode == 7, result.stderr
    with GraphStore(path) as restored:
        expected = "succeeded" if finish else "intent"
        assert restored.document()["attempts"]["try"]["status"] == expected
        assert restored.next_action()["reason"] == ("frontier_resolved" if finish else "recovery_required")
