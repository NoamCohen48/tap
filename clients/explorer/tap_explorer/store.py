"""Transactional, offline graph storage. This module never executes device commands."""

from __future__ import annotations

import copy
import json
import sqlite3
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from pathlib import Path
from types import TracebackType

FORMAT = "tap-exploration/2"
# /1 had no episodes; it reads as /2 with none (nothing was ever reset in such a run).
_FORMAT_1 = "tap-exploration/1"
# Unsupported snapshot capabilities are retained as blocked diagnostics, not commands.
VERBS = {"tap", "long_tap", "fill", "submit", "scroll", "back", "unsupported"}
# The verbs a session can execute; ``unsupported`` marks an interactive node with none of them.
INPUT_VERBS = ("tap", "fill", "submit", "long_tap", "scroll", "back")
OUTCOMES = {"succeeded", "failed_before_input", "indeterminate"}


def _json(value: object) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False)


def _integer(value: object, name: str) -> None:
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer")


def _text(value: object, name: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be nonempty text")


def _upgrade(doc: object) -> object:
    """A ``tap-exploration/1`` document as /2: the same graph with no episodes."""
    if isinstance(doc, dict) and doc.get("format") == _FORMAT_1 and "episodes" not in doc:
        return {**doc, "format": FORMAT, "episodes": []}
    return doc


def _validate(doc: dict) -> str:
    """Validate both exported graphs and every committed update; returns the JSON encoding."""
    if not isinstance(doc, dict) or set(doc) != {
        "format", "context", "budgets", "observations", "states", "actions", "attempts", "episodes"
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
        if set(attempt) - {"permissions"} != {"action", "before", "status", "after", "destination", "note"}:
            raise ValueError("invalid attempt fields")
        if "permissions" in attempt and (not isinstance(attempt["permissions"], list) or not all(
                isinstance(choice, str) for choice in attempt["permissions"])):
            raise ValueError("attempt permissions must be a list of choices")
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
    if not isinstance(doc["episodes"], list):
        raise ValueError("episodes must be a list")
    previous = 0
    for episode in doc["episodes"]:
        if not isinstance(episode, dict) or set(episode) != {"after_attempts", "method", "reason"}:
            raise ValueError("invalid episode fields")
        _integer(episode["after_attempts"], "after_attempts")
        _text(episode["method"], "reset method")
        _text(episode["reason"], "reset reason")
        if not previous <= episode["after_attempts"] <= len(doc["attempts"]):
            raise ValueError("episodes must follow the attempts in order")
        previous = episode["after_attempts"]
    if active and doc["episodes"] and sorted(doc["attempts"]).index(
            next(key for key, a in doc["attempts"].items() if a["status"] == "intent")) < previous:
        raise ValueError("a reset cannot precede an unfinished intent")
    for action_id, action in doc["actions"].items():
        statuses = by_action.get(action_id, [])
        expected = "uncertain" if "indeterminate" in statuses else "attempted" if statuses else None
        if "intent" in statuses:
            expected = "attempted" if "indeterminate" not in statuses else "uncertain"
        if expected is not None and action["status"] != expected:
            raise ValueError("action status disagrees with attempts")
        if expected is None and action["status"] not in ("blocked", "pending"):
            raise ValueError("action status requires an attempt")
    return _json(doc)  # Rejects NaN and non-JSON values before persistence.


def retained_features(evidence: dict, run: Path | None = None) -> dict | None:
    """An observation's retained features: inline in older runs, else its ``features_file``.

    ``run`` relocates the file to ``run/observations/`` when the run directory was moved.
    """
    if "features" in evidence:
        return evidence["features"]
    name = evidence.get("features_file")
    if not isinstance(name, str):
        return None
    for path in (Path(name), *((run / "observations" / Path(name).name,) if run is not None else ())):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
    return None


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
        # One parsed copy for this class's own read-only queries; never handed out.
        self._parsed: tuple[tuple[int, int], dict] | None = None
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
            # The parsed copy is edited in place: a failed change drops it below, so the next
            # read parses the committed document again.
            doc = self._view()
            yield doc
            encoded = _validate(doc)
            self._db.execute("UPDATE graph SET document=? WHERE id=1", (encoded,))
            # Read the cache key while BEGIN IMMEDIATE still excludes external writers.
            # Sampling it after COMMIT could wrongly bless an intervening external write.
            key = self._document_key()
            self._db.execute("COMMIT")
            self._cached_document = (key, encoded)
            self._parsed = (key, doc)
        except BaseException:
            self._cached_document = self._parsed = None
            if self._db.in_transaction:  # An interrupt can land after COMMIT: nothing to undo then.
                self._db.execute("ROLLBACK")
            raise

    def initialize(self, context: dict, *, max_actions: int = 100, max_depth: int = 10) -> None:
        """Create a run with explicit context and finite action/depth budgets.

        Context should describe app/build, account class, and starting condition without secrets.
        Existing runs cannot be overwritten.
        """
        doc = {"format": FORMAT, "context": context, "budgets": {"actions": max_actions, "depth": max_depth},
               "observations": {}, "states": {}, "actions": {}, "attempts": {}, "episodes": []}
        self.import_document(doc)

    def import_document(self, doc: dict) -> None:
        """Import a validated graph into an empty store, retaining unfinished/uncertain attempts.

        Artifact references are metadata only; this does not copy screenshots or read their paths.
        A ``tap-exploration/1`` document is imported as /2 with no episodes.
        """
        doc = _upgrade(doc)  # type: ignore[assignment]
        _validate(doc)
        try:
            self._db.execute("INSERT INTO graph VALUES (1, ?)", (_json(doc),))
        except sqlite3.IntegrityError as error:
            raise ValueError("store already initialized") from error

    def _document_key(self) -> tuple[int, int]:
        return self._db.execute("PRAGMA data_version").fetchone()[0], self._db.total_changes

    def view(self) -> dict:
        """The current document, shared and parsed once per version: read it, never mutate it.

        Cheaper than :meth:`document` for a caller that only reads (no copy is parsed). The
        store's own changes edit it in place, so do not hold it across a change.
        """
        return self._view()

    def _view(self) -> dict:
        """The current document, shared and parsed once per version: read it, never mutate it."""
        key = self._document_key()
        if self._parsed is None or self._parsed[0] != key:
            self._parsed = (key, self.document())
        return self._parsed[1]

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
            doc = _upgrade(json.loads(row[0]))
        except (TypeError, json.JSONDecodeError) as error:
            raise ValueError("invalid stored graph JSON") from error
        _validate(doc)  # type: ignore[arg-type]
        encoded = _json(doc)
        self._cached_document = (key, encoded)
        return json.loads(encoded)

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
        self.add_actions([(action_id, source, verb, target, scenario, priority)])

    def add_actions(self, actions: Sequence[tuple[str, str, str, dict | None, dict | None, int]]) -> None:
        """Add several blocked candidates in one transaction: ``(id, source, verb, target, scenario, priority)``."""
        with self._change() as doc:
            for action_id, source, verb, target, scenario, priority in actions:
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
        # An indeterminate outcome blocks input until a verified reset wiped what it may have
        # done; an unfinished intent always blocks (recover it first).
        since = doc["episodes"][-1]["after_attempts"] if doc["episodes"] else 0
        ordered = [doc["attempts"][key] for key in sorted(doc["attempts"])]
        if (any(a["status"] == "intent" for a in ordered)
                or any(a["status"] == "indeterminate" for a in ordered[since:])):
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
        return self._decision(self._view())

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
                       destination: str | None = None, note: str = "", permissions: list[str] | tuple = ()) -> None:
        """Complete an intent once. Success may lack a recognized destination. ``permissions``:
        the runtime-permission dialogs answered after the input, in order (their choices).

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
            if permissions:
                attempt["permissions"] = list(permissions)
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

    def add_episode(self, *, method: str, reason: str) -> None:
        """Record that the device was reset to the run's starting condition after the attempts so far.

        Call it only after the reset succeeded (an emulator snapshot restore, or the app's data
        cleared and the app cold-launched). Indeterminate outcomes before it no longer block
        input, because the reset discarded whatever they did; they stay indeterminate and their
        actions stay uncertain, so none is ever replayed. An unfinished intent must be recovered
        first. Episodes never change observations, states or transitions.
        """
        _text(method, "reset method")
        _text(reason, "reset reason")
        with self._change() as doc:
            if any(a["status"] == "intent" for a in doc["attempts"].values()):
                raise ValueError("recover the unfinished intent before recording a reset")
            doc["episodes"].append({"after_attempts": len(doc["attempts"]), "method": method, "reason": reason})

    def extend_budgets(self, *, actions: int | None = None, depth: int | None = None) -> None:
        """Raise a run's budgets (a resumed run may get more); lowering one is refused."""
        with self._change() as doc:
            for name, value in (("actions", actions), ("depth", depth)):
                if value is None:
                    continue
                _integer(value, f"{name} budget")
                if value < doc["budgets"][name]:
                    raise ValueError(f"the {name} budget can only be raised")
                doc["budgets"][name] = value

    def transitions(self) -> list[dict]:
        """Return evidence-backed observed edges, including repeated destinations and self-loops.

        Uncertain attempts are excluded. Multiple outcomes can be recorded through separately
        approved new trials; completed candidates are never auto-requeued.
        """
        doc = self._view()
        return [{"attempt": key, "source": doc["actions"][a["action"]]["source"],
                 "action": a["action"], "destination": a["destination"]}
                for key, a in sorted(doc["attempts"].items())
                if a["status"] == "succeeded" and a["destination"] is not None]
