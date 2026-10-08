"""Fresh, human-reviewed unseeded exploration over public Tap APIs.

A session discovers candidates but never trusts them automatically. Human approval authorizes
one finite scenario. Native exact-one checks and fresh retained features precede every input.
No reset/Back edge, secret value, coordinate action, permission flow or transport retry exists.
"""

from __future__ import annotations

import hashlib
import json
import math
import time
from collections import deque
from pathlib import Path

from google.protobuf.json_format import MessageToDict, ParseDict
from tap_e2e import CommandError, Device, ErrorCode, Selector
from tap_e2e import proto as pb

from .discovery import SignaturePolicy, discover
from .live import PilotStopped, _node
from .store import GraphStore


class DiscoverySession:
    """Single-thread-owned fresh run; the caller owns the connection and app bootstrap.

    Observations derive anonymous state IDs, not AI names. The operator approves candidates
    independently of AI risk guesses. Matching UI features do not prove backend restoration.
    """

    def __init__(self, device: Device, store: GraphStore, artifacts: Path, *, package: str,
                 policy: SignaturePolicy = SignaturePolicy(), timeout: float = 10,
                 max_elapsed: float = 900):
        doc = store.document()
        if doc["context"].get("serial") != device.serial or doc["context"].get("package") != package:
            raise ValueError("device/package context mismatch")
        if any(doc[key] for key in ("observations", "states", "actions", "attempts")):
            raise ValueError("fresh graph required; automatic resume is not supported")
        if not math.isfinite(timeout) or not math.isfinite(max_elapsed) or timeout <= 0 or max_elapsed <= 0:
            raise ValueError("finite positive time bounds required")
        self.device, self.store, self.artifacts = device, store, artifacts
        self.package, self.policy, self.timeout = package, policy, timeout
        self.app = device.app(package)
        self.deadline = time.monotonic() + max_elapsed
        self.current: str | None = None
        self.observation: str | None = None
        self.halted: str | None = None
        self.fresh: tuple = ()
        artifacts.mkdir(parents=True, exist_ok=True)

    def _check(self, *, after_known_success: bool = False) -> None:
        if self.halted:
            raise PilotStopped(self.halted)
        if time.monotonic() >= self.deadline:
            raise PilotStopped("elapsed-time budget exhausted")
        if not after_known_success and self.store.next_action()["reason"] == "recovery_required":
            raise PilotStopped("uncertain/unfinished intent; no further input")

    def observe(self, depth: int = 0, *, _after_known_success: bool = False) -> dict:
        """Retain a checked snapshot/PNG pair and discover blocked candidates without input."""
        self._check(after_known_success=_after_known_success)
        self.current = None
        settle_error = None
        try:
            self.app.await_settled(stable_for=0.2, timeout=self.timeout)
        except Exception as error:
            # Permission/external focus is still inspectable, not an excuse for blind input.
            settle_error = type(error).__name__
        started = time.time_ns()
        before = self.device.screen_snapshot(selector_candidates=True)
        shot = self.device.screenshot()
        after = self.device.screen_snapshot(selector_candidates=True)
        finished = time.time_ns()
        observation = f"obs-{len(self.store.document()['observations']) + 1:06d}"
        image = self.artifacts / f"{observation}.png"
        snapshot = self.artifacts / f"{observation}.json"
        with image.open("xb") as output:
            output.write(shot.bytes)
        nodes = []
        for node in after.nodes:
            row = _node(node)
            row["selector"] = (MessageToDict(node.selector.to_proto(), preserving_proto_field_name=True)
                               if node.selector else None)
            nodes.append(row)
        with snapshot.open("x", encoding="utf-8") as output:
            json.dump({"snapshot_id": after.snapshot_id, "rotation": after.rotation,
                       "nodes": nodes}, output, indent=2, sort_keys=True)
        try:
            signature = self.policy.signature(after, self.package)
            moving = self.policy.signature(before, self.package) != signature
            features = self.policy.features(after, self.package)
        except ValueError:
            signature, moving, features = None, True, None
        self.store.add_observation(observation, {"screenshot": str(image), "snapshot": str(snapshot),
            "before_snapshot_id": before.snapshot_id, "after_snapshot_id": after.snapshot_id,
            "capture_order": "snapshot,screenshot,snapshot", "moving": moving,
            "features": features, "settle_error": settle_error,
            "started_ns": started, "finished_ns": finished})
        self.observation = observation
        if moving or signature is None or settle_error:
            raise PilotStopped("drift/foreign focus/unrecognized target; raw evidence retained, no input")
        state = "s-" + signature.rsplit(":", 1)[1][:32]
        doc = self.store.document()
        if state in doc["states"]:
            if doc["states"][state]["signature"] != signature:
                raise PilotStopped("state ID collision")
            self.store.attach_observation(state, observation)
        else:
            self.store.add_state(state, observation, signature=signature, depth=depth)
        fresh = discover(after, self.package, state)
        for candidate in fresh:
            if candidate.id not in self.store.document()["actions"]:
                self.store.add_action(candidate.id, state, candidate.verb, target=candidate.target())
        self.current = state
        self.fresh = fresh
        return {"state": state, "observation": observation,
                "candidates": [candidate.id for candidate in fresh]}

    def approve(self, candidate_id: str, *, reason: str, value: str | None = None) -> None:
        """Approve a supported candidate/scenario explicitly, never based on an AI score.

        Fill values are non-sensitive literals. They are retained in the graph and daemon log;
        credentials are not supported. A distinct finite fill scenario gets a distinct ID.
        """
        self._check()
        action = self.store.document()["actions"].get(candidate_id)
        if not action or action["verb"] not in ("tap", "fill") or action["target"].get("blocked_reason"):
            raise PilotStopped("unsupported/unaddressable candidate cannot be approved")
        if not reason.strip():
            raise ValueError("explicit operator reason required")
        if action["verb"] == "fill":
            if not isinstance(value, str) or len(value) > 200:
                raise ValueError("finite non-sensitive fill value required")
            # Values are scenarios, never silently rewrite an existing candidate/attempt.
            scenario_id = candidate_id + "/value-" + hashlib.sha256(value.encode()).hexdigest()[:16]
            if scenario_id not in self.store.document()["actions"]:
                self.store.add_action(scenario_id, action["source"], "fill", target=action["target"], scenario={"value": value})
            self.store.approve_action(scenario_id, reason)
        else:
            if value is not None:
                raise ValueError("tap does not accept a fill value")
            self.store.approve_action(candidate_id, reason)

    def withdraw(self, candidate_id: str) -> None:
        """Withdraw an approval before its input; no device access, so a halted run allows it."""
        if candidate_id not in self.store.document()["actions"]:
            raise ValueError("unknown candidate")
        self.store.withdraw_action(candidate_id)

    def step(self, candidate_id: str, *, trial: bool = False, expected: str | None = None) -> dict:
        """Execute exactly one approved action once, persisting intent before transmission."""
        self._check()
        action = self.store.document()["actions"].get(candidate_id)
        if not action or action["status"] not in (("attempted",) if trial else ("pending",)):
            raise PilotStopped("candidate has no applicable explicit approval")
        if action["verb"] not in ("tap", "fill") or action["target"].get("blocked_reason"):
            raise PilotStopped("unsupported target")
        depth = self.store.document()["states"][action["source"]]["depth"]
        before = self.observe(depth)
        if before["state"] != action["source"]:
            raise PilotStopped("source changed before input")
        # Re-derive candidates from this fresh snapshot, never resolve a stale ref or index.
        # Derived (row/ancestor) selectors match no node's own selector, so compare proposals.
        fresh = [candidate for candidate in self.fresh if candidate.verb == action["verb"]
                 and candidate.selector == action["target"].get("selector")]
        if len(fresh) != 1 or fresh[0].blocked_reason:
            raise PilotStopped("fresh target is not unique/addressable")
        selector_data = action["target"].get("selector")
        if not isinstance(selector_data, dict):
            raise PilotStopped("no native selector")
        selector = Selector.from_proto(ParseDict(selector_data, pb.Selector()))
        if selector.to_proto().WhichOneof("pick") is not None:
            raise PilotStopped("index/pick selector forbidden")
        self.app.wait(selector, timeout=self.timeout).one()
        attempt = f"attempt-{len(self.store.document()['attempts']) + 1:06d}"
        if trial:
            self.store.begin_trial(attempt, candidate_id, before["observation"], approval="new route trial from rechecked source")
        else:
            self.store.begin_attempt(attempt, candidate_id, before["observation"])
        try:
            element = self.app.element(selector)
            if action["verb"] == "tap":
                element.tap()
            else:
                value = action["scenario"].get("value")
                if not isinstance(value, str):
                    raise ValueError("fill scenario lacks approved value")
                element.set_text(value)
        except Exception as error:
            safe = isinstance(error, CommandError) and error.code in (ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS)
            self.store.finish_attempt(attempt, "failed_before_input" if safe else "indeterminate", note=type(error).__name__)
            self.halted = "action failed; no automatic continuation/retry"
            raise
        try:
            after = self.observe(depth + 1, _after_known_success=True)
        except Exception as error:
            self.store.finish_attempt(attempt, "succeeded", note=f"post-observation failed: {type(error).__name__}")
            self.halted = "known command success without verified destination"
            raise
        self.store.finish_attempt(attempt, "succeeded", after=after["observation"], destination=after["state"])
        if expected and after["state"] != expected:
            self.halted = "observed route diverged"
            raise PilotStopped(self.halted)
        return {"attempt": attempt, **after}

    def behavioral_conflicts(self) -> list[dict]:
        """Report source/action pairs with multiple recognized outcomes, not hidden-state guesses.

        Visible-feature equality is not a Markov-state guarantee. Divergent outcomes may arise
        from history, backend state or external events; they are not deterministic route proof.
        Completed evidence remains intact and is never silently split or relabeled by this check.
        """
        groups: dict[tuple[str, str], dict] = {}
        for edge in self.store.transitions():
            key = (edge["source"], edge["action"])
            group = groups.setdefault(key, {"source": key[0], "action": key[1], "destinations": set(), "attempts": []})
            group["destinations"].add(edge["destination"])
            group["attempts"].append(edge["attempt"])
        return [{**group, "destinations": sorted(group["destinations"]), "attempts": sorted(group["attempts"])}
                for _, group in sorted(groups.items()) if len(group["destinations"]) > 1]

    def route(self, destination: str) -> list[dict]:
        """Plan observed single-outcome edges only; never assume UI equality proves hidden state.

        Unknown divergence still requires a fresh checked trial and stops on mismatch. Known
        conflicting outcomes are excluded BEFORE input, rather than choosing one by sort order.
        """
        if self.current is None:
            raise PilotStopped("no current verified observation")
        conflicts = {(item["source"], item["action"]) for item in self.behavioral_conflicts()}
        edges = [edge for edge in self.store.transitions() if (edge["source"], edge["action"]) not in conflicts]
        queue = deque([(self.current, [])])
        seen = {self.current}
        while queue:
            state, path = queue.popleft()
            if state == destination:
                return path
            for edge in sorted(edges, key=lambda item: (item["action"], item["destination"], item["attempt"])):
                if edge["source"] == state and edge["destination"] not in seen:
                    seen.add(edge["destination"])
                    queue.append((edge["destination"], path + [edge]))
        raise PilotStopped("no observed route using single-outcome edges; conflicting outcomes/reset/Back are not routing proof")

    def navigate(self, destination: str) -> None:
        """Invoke every observed route edge as a new trial with checked expected destination."""
        for edge in self.route(destination):
            self.step(edge["action"], trial=True, expected=edge["destination"])
