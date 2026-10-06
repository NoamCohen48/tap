"""Transactional, offline graph storage. This module never executes device commands."""

from __future__ import annotations

import copy
import json
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from types import TracebackType

FORMAT = "tap-exploration/1"
# Unsupported snapshot capabilities are retained as blocked diagnostics, not commands.
VERBS = {"tap", "long_tap", "fill", "scroll", "back", "unsupported"}
OUTCOMES = {"succeeded", "failed_before_input", "indeterminate"}


def _json(value: object) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False)


def _integer(value: object, name: str) -> None:
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer")


def _text(value: object, name: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be nonempty text")


def _validate(doc: dict) -> None:
    """Validate both exported graphs and every committed update."""
    if not isinstance(doc, dict) or set(doc) != {
        "format", "context", "budgets", "observations", "states", "actions", "attempts"
    } or doc["format"] != FORMAT:
        raise ValueError("unsupported exploration document")
    if not isinstance(doc["context"], dict):
        raise ValueError("context must be an object")
    if not isinstance(doc["budgets"], dict) or set(doc["budgets"]) != {"actions", "depth"}:
        raise ValueError("budgets must contain actions and depth")
    for name, value in doc["budgets"].items():
        _integer(value, name)
    for name in ("observations", "states", "actions", "attempts"):
        if not isinstance(doc[name], dict):
            raise ValueError(f"{name} must be an object")
        for key, value in doc[name].items():
            _text(key, "entity ID")
            if not isinstance(value, dict):
                raise ValueError("entity must be an object")
    for observation in doc["observations"].values():
        if set(observation) != {"evidence"} or not isinstance(observation["evidence"], dict):
            raise ValueError("observation must contain an evidence object")
    for state in doc["states"].values():
        if set(state) != {"observations", "signature", "depth"}:
            raise ValueError("invalid state fields")
        _text(state["signature"], "signature")
        _integer(state["depth"], "depth")
        if not isinstance(state["observations"], list) or not state["observations"]:
            raise ValueError("state needs observations")
        if any(not isinstance(o, str) or o not in doc["observations"] for o in state["observations"]):
            raise ValueError("unknown state observation")
    for action in doc["actions"].values():
        if set(action) != {"source", "verb", "target", "scenario", "priority", "approval", "status"}:
            raise ValueError("invalid action fields")
        if not isinstance(action["source"], str) or action["source"] not in doc["states"]:
            raise ValueError("unknown action source")
        if not isinstance(action["verb"], str) or action["verb"] not in VERBS:
            raise ValueError("unsupported action verb")
        if not isinstance(action["target"], dict) or not isinstance(action["scenario"], dict):
            raise ValueError("target and scenario must be objects")
        _integer(action["priority"], "priority")
        if action["approval"] is not None:
            _text(action["approval"], "approval")
        if not isinstance(action["status"], str) or action["status"] not in ("pending", "blocked", "attempted", "uncertain"):
            raise ValueError("invalid action status")
        if action["status"] != "blocked" and action["approval"] is None:
            raise ValueError("unapproved action must be blocked")
    active = 0
    by_action: dict[str, list[str]] = {}
    for attempt in doc["attempts"].values():
        if set(attempt) != {"action", "before", "status", "after", "destination", "note"}:
            raise ValueError("invalid attempt fields")
        action_id = attempt["action"]
        if not isinstance(action_id, str) or action_id not in doc["actions"]:
            raise ValueError("unknown attempt action")
        source = doc["states"][doc["actions"][action_id]["source"]]
        if attempt["before"] not in source["observations"]:
            raise ValueError("before observation must belong to action source")
        status = attempt["status"]
        if not isinstance(status, str) or status not in OUTCOMES | {"intent"}:
            raise ValueError("invalid attempt status")
        by_action.setdefault(action_id, []).append(status)
        active += status == "intent"
        if not isinstance(attempt["note"], str):
            raise ValueError("attempt note must be text")
        after, destination = attempt["after"], attempt["destination"]
        if after is not None and (not isinstance(after, str) or after not in doc["observations"]):
            raise ValueError("unknown after observation")
        if destination is not None:
            if not isinstance(destination, str) or destination not in doc["states"]:
                raise ValueError("unknown destination")
            if after not in doc["states"][destination]["observations"]:
                raise ValueError("after observation must belong to destination")
        if status == "intent" and (after is not None or destination is not None):
            raise ValueError("unfinished intent cannot have a destination")
        if status == "failed_before_input" and destination is not None:
            raise ValueError("rejected input cannot establish a transition")
    if active > 1:
        raise ValueError("only one unfinished intent allowed")
    for action_id, action in doc["actions"].items():
        statuses = by_action.get(action_id, [])
        expected = "uncertain" if "indeterminate" in statuses else "attempted" if statuses else None
        if "intent" in statuses:
            expected = "attempted" if "indeterminate" not in statuses else "uncertain"
        if expected is not None and action["status"] != expected:
            raise ValueError("action status disagrees with attempts")
        if expected is None and action["status"] not in ("blocked", "pending"):
            raise ValueError("action status requires an attempt")
    _json(doc)  # Reject NaN and non-JSON values before persistence.


class GraphStore:
    """One offline run in a SQLite file, serialized through write transactions.

    Opening a store never recovers intents automatically. Call :meth:`recover_interrupted`
    only after ensuring the previous executor has stopped. No method retries device input.
    IDs are supplied by the caller; observations and completed attempts are immutable.
    """

    def __init__(self, path: str | Path):
        self._db = sqlite3.connect(path, isolation_level=None)
        # Cache immutable, already validated JSON, never an externally mutable dict.
        # data_version detects other connections; total_changes detects this one's writes.
        self._cached_document: tuple[tuple[int, int], str] | None = None
        try:
            self._db.execute("CREATE TABLE IF NOT EXISTS graph (id INTEGER PRIMARY KEY CHECK(id=1), document TEXT NOT NULL)")
        except BaseException:
            self._db.close()
            raise

    def close(self) -> None:
        """Close the local connection; unfinished intents remain persisted."""
        self._db.close()

    def __enter__(self) -> GraphStore:
        return self

    def __exit__(self, exc_type: type[BaseException] | None, exc_value: BaseException | None,
                 traceback: TracebackType | None) -> None:
        self.close()

    @contextmanager
    def _change(self) -> Iterator[dict]:
        self._db.execute("BEGIN IMMEDIATE")
        try:
            doc = self.document()
            yield doc
            _validate(doc)
            encoded = _json(doc)
            self._db.execute("UPDATE graph SET document=? WHERE id=1", (encoded,))
            # Read the cache key while BEGIN IMMEDIATE still excludes external writers.
            # Sampling it after COMMIT could wrongly bless an intervening external write.
            key = self._document_key()
            self._db.execute("COMMIT")
            self._cached_document = (key, encoded)
        except BaseException:
            self._db.execute("ROLLBACK")
            raise

    def initialize(self, context: dict, *, max_actions: int = 100, max_depth: int = 10) -> None:
        """Create a run with explicit context and finite action/depth budgets.

        Context should describe app/build, account class, and starting condition without secrets.
        Existing runs cannot be overwritten.
        """
        doc = {"format": FORMAT, "context": context, "budgets": {"actions": max_actions, "depth": max_depth},
               "observations": {}, "states": {}, "actions": {}, "attempts": {}}
        self.import_document(doc)

    def import_document(self, doc: dict) -> None:
        """Import a validated graph into an empty store, retaining unfinished/uncertain attempts.

        Artifact references are metadata only; this does not copy screenshots or read their paths.
        """
        _validate(doc)
        try:
            self._db.execute("INSERT INTO graph VALUES (1, ?)", (_json(doc),))
        except sqlite3.IntegrityError as error:
            raise ValueError("store already initialized") from error

    def _document_key(self) -> tuple[int, int]:
        return self._db.execute("PRAGMA data_version").fetchone()[0], self._db.total_changes

    def document(self) -> dict:
        """Return a detached, JSON-ready export of the entire run (not automatically redacted).

        Unchanged validated JSON is reused; every returned dict remains independent. Commits
        from other connections invalidate the cache, preserving the durable intent gate.
        """
        key = self._document_key()
        if self._cached_document is not None and self._cached_document[0] == key:
            return json.loads(self._cached_document[1])
        row = self._db.execute("SELECT document FROM graph WHERE id=1").fetchone()
        if row is None:
            raise ValueError("store is not initialized")
        try:
            doc = json.loads(row[0])
        except (TypeError, json.JSONDecodeError) as error:
            raise ValueError("invalid stored graph JSON") from error
        _validate(doc)
        self._cached_document = (key, row[0])
        return doc

    @staticmethod
    def _insert(doc: dict, collection: str, key: str, value: dict) -> None:
        if key in doc[collection]:
            raise ValueError(f"duplicate {collection} ID: {key}")
        doc[collection][key] = copy.deepcopy(value)

    def add_observation(self, observation_id: str, evidence: dict) -> None:
        """Persist immutable evidence metadata; callers own artifact capture and privacy."""
        with self._change() as doc:
            self._insert(doc, "observations", observation_id, {"evidence": evidence})

    def add_state(self, state_id: str, observation_id: str, *, signature: str, depth: int) -> None:
        """Explicitly classify an observation; equal signatures never merge states implicitly."""
        with self._change() as doc:
            self._insert(doc, "states", state_id, {"observations": [observation_id], "signature": signature, "depth": depth})

    def attach_observation(self, state_id: str, observation_id: str) -> None:
        """Explicitly associate another observation with a known state."""
        with self._change() as doc:
            state = doc["states"][state_id]
            if observation_id in state["observations"]:
                raise ValueError("observation already attached")
            state["observations"].append(observation_id)

    def add_action(self, action_id: str, source: str, verb: str, *, target: dict | None = None,
                   scenario: dict | None = None, priority: int = 0) -> None:
        """Add a blocked candidate. Targets/scenarios are opaque offline metadata, not executable commands."""
        with self._change() as doc:
            self._insert(doc, "actions", action_id, {"source": source, "verb": verb,
                "target": {} if target is None else target,
                "scenario": {} if scenario is None else scenario, "priority": priority,
                "approval": None, "status": "blocked"})

    def approve_action(self, action_id: str, approval: str) -> None:
        """Make a never-attempted candidate eligible, recording explicit operator approval."""
        with self._change() as doc:
            action = doc["actions"][action_id]
            if action["status"] != "blocked":
                raise ValueError("only a blocked candidate can be approved")
            action.update(approval=approval, status="pending")

    def withdraw_action(self, action_id: str) -> None:
        """Return an approved, never-attempted candidate to blocked; nothing else changes.

        An approval made against a screen that has since changed would otherwise stay first
        in the scheduler's order and hold every later step.
        """
        with self._change() as doc:
            action = doc["actions"][action_id]
            if action["status"] != "pending":
                raise ValueError("only an approved, unattempted candidate can be withdrawn")
            action.update(approval=None, status="blocked")

    @staticmethod
    def _decision(doc: dict) -> dict:
        attempts = doc["attempts"].values()
        if any(a["status"] in ("intent", "indeterminate") for a in attempts):
            return {"action": None, "reason": "recovery_required"}
        if len(doc["attempts"]) >= doc["budgets"]["actions"]:
            return {"action": None, "reason": "action_budget"}
        pending = [(key, a) for key, a in doc["actions"].items() if a["status"] == "pending"]
        eligible = [(key, a) for key, a in pending
                    if doc["states"][a["source"]]["depth"] <= doc["budgets"]["depth"]]
        if eligible:
            key, _ = min(eligible, key=lambda item: (item[1]["priority"],
                doc["states"][item[1]["source"]]["depth"], item[1]["source"], item[0]))
            return {"action": key, "reason": "eligible"}
        return {"action": None, "reason": "depth_budget" if pending else "frontier_resolved"}

    def next_action(self) -> dict:
        """Return deterministic priority/depth/source/ID selection, or an explicit stop reason.

        Resolved frontier means no eligible pending candidates, not complete app coverage.
        """
        return self._decision(self.document())

    def begin_attempt(self, attempt_id: str, action_id: str, before: str) -> None:
        """Persist intent before any input. Atomically enforce ordering, budget, and recovery gates."""
        with self._change() as doc:
            decision = self._decision(doc)
            if decision["action"] != action_id:
                raise ValueError(f"action not selected: {decision['reason']}")
            self._insert(doc, "attempts", attempt_id, {"action": action_id, "before": before,
                "status": "intent", "after": None, "destination": None, "note": ""})
            doc["actions"][action_id]["status"] = "attempted"

    def begin_trial(self, attempt_id: str, action_id: str, before: str, *, approval: str) -> None:
        """Record a separately approved new trial of a completed action, never a transport retry.

        Caller must re-establish and check source preconditions. Interrupted/indeterminate
        attempts block trials too. Failed-before-input candidates also need this explicit approval.
        """
        _text(approval, "trial approval")
        with self._change() as doc:
            decision = self._decision(doc)
            if decision["reason"] in ("recovery_required", "action_budget"):
                raise ValueError(f"trial blocked: {decision['reason']}")
            action = doc["actions"][action_id]
            if action["status"] != "attempted":
                raise ValueError("trial requires a completed action")
            if doc["states"][action["source"]]["depth"] > doc["budgets"]["depth"]:
                raise ValueError("trial blocked: depth_budget")
            self._insert(doc, "attempts", attempt_id, {"action": action_id, "before": before,
                "status": "intent", "after": None, "destination": None,
                "note": f"new trial approved: {approval}"})

    def finish_attempt(self, attempt_id: str, outcome: str, *, after: str | None = None,
                       destination: str | None = None, note: str = "") -> None:
        """Complete an intent once. Success may lack a recognized destination.

        A destination records an observed outcome, never route verification or business correctness.
        """
        if outcome not in OUTCOMES:
            raise ValueError("unsupported outcome")
        with self._change() as doc:
            attempt = doc["attempts"][attempt_id]
            if attempt["status"] != "intent":
                raise ValueError("attempt is already completed")
            previous_note = attempt["note"]
            attempt.update(status=outcome, after=after, destination=destination,
                           note=f"{previous_note}\n{note}".strip())
            doc["actions"][attempt["action"]]["status"] = "uncertain" if outcome == "indeterminate" else "attempted"

    def recover_interrupted(self) -> int:
        """Mark orphaned intents indeterminate after the prior executor has been stopped.

        Does not requeue actions or reconcile their effects. Recovery blocks subsequent attempts.
        """
        count = 0
        with self._change() as doc:
            for attempt in doc["attempts"].values():
                if attempt["status"] == "intent":
                    attempt.update(status="indeterminate", note=(attempt["note"] +
                        "\nexecutor stopped without a persisted outcome").strip())
                    doc["actions"][attempt["action"]]["status"] = "uncertain"
                    count += 1
        return count

    def transitions(self) -> list[dict]:
        """Return evidence-backed observed edges, including repeated destinations and self-loops.

        Uncertain attempts are excluded. Multiple outcomes can be recorded through separately
        approved new trials; completed candidates are never auto-requeued.
        """
        doc = self.document()
        return [{"attempt": key, "source": doc["actions"][a["action"]]["source"],
                 "action": a["action"], "destination": a["destination"]}
                for key, a in sorted(doc["attempts"].items())
                if a["status"] == "succeeded" and a["destination"] is not None]
