"""Fresh, human-reviewed unseeded exploration over public Tap APIs.

A session discovers candidates but never trusts them automatically. An approval (a person's,
or an autonomy policy's on their behalf) authorizes one finite scenario. Native exact-one
checks and fresh retained features precede every input; the Back key has no target, so its
check is the fresh source state alone. There is no secret value, coordinate action or transport
retry; a runtime-permission dialog an input opens is answered with one fixed choice
(``permission``) and recorded on the attempt, never explored. A reset is an explicit episode boundary
(:meth:`DiscoverySession.reset`), never an inferred route.
"""

from __future__ import annotations

import hashlib
import json
import math
import time
from collections import deque
from collections.abc import Callable
from pathlib import Path

from google.protobuf.json_format import MessageToDict, ParseDict
from tap_e2e import CommandError, Device, Direction, PermissionChoice, Selector, WaitTimeoutError
from tap_e2e import proto as pb

from .discovery import SignaturePolicy, discover
from .live import SAFE_FAILURES, PilotStopped, _node
from .store import INPUT_VERBS, GraphStore

ROUTE_DIVERGED = "observed route diverged"
# The first of these a dialog offers answers it: deny keeps the app on its no-permission path.
PERMISSION_ANSWERS = {
    "deny": (PermissionChoice.DENY, PermissionChoice.DENY_AND_DONT_ASK_AGAIN),
    "allow": (PermissionChoice.ALLOW_FOREGROUND_ONLY, PermissionChoice.ALLOW, PermissionChoice.ALLOW_ALL,
              PermissionChoice.KEEP_FOREGROUND_ONLY, PermissionChoice.ALLOW_ONE_TIME),
}


class DiscoverySession:
    """Single-thread-owned fresh run; the caller owns the connection and app bootstrap.

    Observations derive anonymous state IDs, not AI names. The operator approves candidates
    independently of AI risk guesses. Matching UI features do not prove backend restoration.
    """

    def __init__(self, device: Device, store: GraphStore, artifacts: Path, *, package: str,
                 policy: SignaturePolicy = SignaturePolicy(), timeout: float = 10,
                 max_elapsed: float = 900, resume: bool = False, captures: int = 1,
                 confirm_after: float = 0.0, permission: str | None = None):
        """``resume`` continues a graph whose previous executor has stopped; the caller must
        then establish the starting condition with :meth:`reset` before any input.

        ``permission`` (``deny`` or ``allow``) answers a runtime-permission dialog that hides
        the app after an input (a camera screen asking on open); the attempt's destination is
        then the screen after the answer, and the attempt lists it under ``permissions``.
        ``None`` stops at the dialog like at any foreign window.

        ``captures`` > 1 re-observes a screen that changed during capture (late rendering)
        instead of stopping; ``confirm_after`` > 0 also requires one more snapshot that long
        after the capture to agree, so a screen that keeps rendering is not split in two.
        """
        doc = store.document()
        if doc["context"].get("serial") != device.serial or doc["context"].get("package") != package:
            raise ValueError("device/package context mismatch")
        if not resume and any(doc[key] for key in ("observations", "states", "actions", "attempts")):
            raise ValueError("fresh graph required; pass resume=True to continue a stopped run")
        if not math.isfinite(timeout) or not math.isfinite(max_elapsed) or timeout <= 0 or max_elapsed <= 0:
            raise ValueError("finite positive time bounds required")
        if captures < 1 or not math.isfinite(confirm_after) or confirm_after < 0:
            raise ValueError("captures must be positive and confirm_after finite and nonnegative")
        if permission is not None and permission not in PERMISSION_ANSWERS:
            raise ValueError(f"permission must be one of {sorted(PERMISSION_ANSWERS)}")
        self.permission = permission
        self.answered: list[str] = []
        self.device, self.store, self.artifacts = device, store, artifacts
        self.package, self.policy, self.timeout = package, policy, timeout
        self.captures, self.confirm_after = captures, confirm_after
        self.resumed = resume and bool(doc["observations"])
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
        capture = 1
        settle_error, started, before, shot, after, finished, signature, moving, features = self._capture()
        # Retry a capture that moved or did not settle; not one that timed out settling with none
        # of the app's nodes on screen (it left the app): that wait would repeat in full.
        while ((moving or signature is None or settle_error) and capture < self.captures
               and not (signature is None and settle_error)):
            capture += 1
            settle_error, started, before, shot, after, finished, signature, moving, features = self._capture()
        observation = f"obs-{len(self.store.view()['observations']) + 1:06d}"
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
        # Retained features live beside the snapshot: the graph is rewritten on every change,
        # so per-observation node lists inline would make a long run quadratic.
        retained = self.artifacts / f"{observation}.features.json"
        with retained.open("x", encoding="utf-8") as output:
            json.dump(features, output, sort_keys=True)
        self.store.add_observation(observation, {"screenshot": str(image), "snapshot": str(snapshot),
            "before_snapshot_id": before.snapshot_id, "after_snapshot_id": after.snapshot_id,
            "capture_order": "snapshot,screenshot,snapshot", "moving": moving,
            "features_file": str(retained), "settle_error": settle_error, "captures": capture,
            "started_ns": started, "finished_ns": finished})
        self.observation = observation
        if moving or signature is None or settle_error:
            # A few dialogs in a row at most (one per permission the screen asks for).
            if len(self.answered) < 3 and self._answer_permission():
                return self.observe(depth, _after_known_success=_after_known_success)
            raise PilotStopped("drift/foreign focus/unrecognized target; raw evidence retained, no input")
        state = "s-" + signature.rsplit(":", 1)[1][:32]
        doc = self.store.view()
        if state in doc["states"]:
            if doc["states"][state]["signature"] != signature:
                raise PilotStopped("state ID collision")
            self.store.attach_observation(state, observation)
        else:
            self.store.add_state(state, observation, signature=signature, depth=depth)
        fresh = discover(after, self.package, state)
        known = doc["actions"]
        added = [(candidate.id, state, candidate.verb, candidate.target(), None, 0)
                 for candidate in fresh if candidate.id not in known]
        if added:
            self.store.add_actions(added)
        self.current = state
        self.fresh = fresh
        return {"state": state, "observation": observation,
                "candidates": [candidate.id for candidate in fresh]}

    def _capture(self) -> tuple:
        """One settle + snapshot/screenshot/snapshot capture and its identity checks."""
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
        try:
            signature = self.policy.signature(after, self.package)
            moving = self.policy.signature(before, self.package) != signature
            features = self.policy.features(after, self.package)
            if not moving and self.confirm_after:
                time.sleep(self.confirm_after)
                moving = self.policy.signature(self.device.screen_snapshot(), self.package) != signature
        except ValueError:
            signature, moving, features = None, True, None
        return settle_error, started, before, shot, after, finished, signature, moving, features

    def _answer_permission(self) -> bool:
        """Answer the runtime-permission dialog on screen, if any, with the session's choice."""
        if self.permission is None:
            return False
        try:
            prompt = self.device.await_permission_prompt(timeout=1.0)
        except WaitTimeoutError:
            return False
        choice = next((choice for choice in PERMISSION_ANSWERS[self.permission] if choice in prompt.choices), None)
        if choice is None:
            return False
        self.device.choose_permission(choice)
        self.answered.append(choice.value)
        return True

    def approve(self, candidate_id: str, *, reason: str, value: str | None = None) -> None:
        """Approve a supported candidate/scenario explicitly, never based on an AI score.

        Fill values are non-sensitive literals. They are retained in the graph and daemon log;
        credentials are not supported. A distinct finite fill scenario gets a distinct ID.
        """
        self._check()
        action = self.store.view()["actions"].get(candidate_id)
        if not action or action["verb"] not in INPUT_VERBS or action["target"].get("blocked_reason"):
            raise PilotStopped("unsupported/unaddressable candidate cannot be approved")
        if not reason.strip():
            raise ValueError("explicit operator reason required")
        if action["verb"] == "fill":
            if not isinstance(value, str) or len(value) > 200:
                raise ValueError("finite non-sensitive fill value required")
            # Values are scenarios, never silently rewrite an existing candidate/attempt.
            scenario_id = candidate_id + "/value-" + hashlib.sha256(value.encode()).hexdigest()[:16]
            if scenario_id not in self.store.view()["actions"]:
                self.store.add_action(scenario_id, action["source"], "fill", target=action["target"], scenario={"value": value})
            self.store.approve_action(scenario_id, reason)
        else:
            if value is not None:
                raise ValueError(f"{action['verb']} does not accept a fill value")
            self.store.approve_action(candidate_id, reason)

    def withdraw(self, candidate_id: str) -> None:
        """Withdraw an approval before its input; no device access, so a halted run allows it."""
        if candidate_id not in self.store.view()["actions"]:
            raise ValueError("unknown candidate")
        self.store.withdraw_action(candidate_id)

    def step(self, candidate_id: str, *, trial: bool = False, expected: str | None = None) -> dict:
        """Execute exactly one approved action once, persisting intent before transmission."""
        self._check()
        doc = self.store.view()
        action = doc["actions"].get(candidate_id)
        if not action or action["status"] not in (("attempted",) if trial else ("pending",)):
            raise PilotStopped("candidate has no applicable explicit approval")
        if action["verb"] not in INPUT_VERBS or action["target"].get("blocked_reason"):
            raise PilotStopped("unsupported target")
        depth = doc["states"][action["source"]]["depth"]
        before = self.observe(depth)
        if before["state"] != action["source"]:
            raise PilotStopped("source changed before input")
        # Re-derive candidates from this fresh snapshot, never resolve a stale ref or index.
        # Derived (row/ancestor) selectors match no node's own selector, so compare proposals.
        fresh = [candidate for candidate in self.fresh if candidate.verb == action["verb"]
                 and candidate.selector == action["target"].get("selector")]
        if action["verb"] == "submit" and not fresh:
            before = self._focus(action)
            fresh = [candidate for candidate in self.fresh if candidate.verb == "submit"
                     and candidate.selector == action["target"].get("selector")]
            if not fresh:
                raise PilotStopped("focusing the field did not offer its keyboard action")
            doc = self.store.view()
        if len(fresh) != 1 or fresh[0].blocked_reason:
            raise PilotStopped("fresh target is not unique/addressable")
        selector = None
        if action["verb"] != "back":
            selector_data = action["target"].get("selector")
            if not isinstance(selector_data, dict):
                raise PilotStopped("no native selector")
            selector = Selector.from_proto(ParseDict(selector_data, pb.Selector()))
            if selector.to_proto().WhichOneof("pick") is not None:
                raise PilotStopped("index/pick selector forbidden")
            self.app.wait(selector, timeout=self.timeout).one()
        attempt = f"attempt-{len(doc['attempts']) + 1:06d}"  # Observing adds no attempts.
        if self.store.next_action()["reason"] == "action_budget":
            # Nothing was sent; a route cut short by the budget stops here, not mid-trial.
            raise PilotStopped("action budget exhausted")
        if trial:
            self.store.begin_trial(attempt, candidate_id, before["observation"], approval="new route trial from rechecked source")
        else:
            self.store.begin_attempt(attempt, candidate_id, before["observation"])
        try:
            if selector is None:
                self.device.press_back()
            elif action["verb"] == "tap":
                self.app.element(selector).tap()
            elif action["verb"] == "submit":
                self.app.element(selector).ime_action()
            elif action["verb"] == "long_tap":
                self.app.element(selector).long_tap()
            elif action["verb"] == "scroll":
                self.app.element(selector).scroll(Direction(action["target"].get("direction") or "DOWN"))
            else:
                value = action["scenario"].get("value")
                if not isinstance(value, str):
                    raise ValueError("fill scenario lacks approved value")
                self.app.element(selector).set_text(value)
        except Exception as error:
            safe = isinstance(error, CommandError) and error.code in SAFE_FAILURES
            self.store.finish_attempt(attempt, "failed_before_input" if safe else "indeterminate",
                                      note=f"{type(error).__name__}: {error}"[:500])
            if not safe:
                # Nothing more on this device until a reset; a safe rejection sent no input.
                self.halted = "uncertain outcome; no further input before a reset"
            raise
        self.answered = []
        try:
            after = self.observe(depth + 1, _after_known_success=True)
        except Exception as error:
            self.store.finish_attempt(attempt, "succeeded", note=f"post-observation failed: {type(error).__name__}",
                                      permissions=self.answered)
            self.halted = "known command success without verified destination"
            raise
        self.store.finish_attempt(attempt, "succeeded", after=after["observation"], destination=after["state"],
                                  permissions=self.answered)
        if expected and after["state"] != expected:
            self.halted = ROUTE_DIVERGED
            raise PilotStopped(self.halted)
        return {"attempt": attempt, **after}

    def _focus(self, action: dict) -> dict:
        """Give a keyboard action's field input focus again with the state's own field tap.

        The action is offered only while the field has focus, which navigation loses. The tap
        runs as a checked trial of a recorded tap that stayed on this state; never a first try.
        """
        tap = self._focus_tap(self.store.view(), action)
        if tap is None:
            raise PilotStopped("the field has no input focus and no recorded tap that keeps this screen")
        self.step(tap, trial=True, expected=action["source"])
        return {"state": self.current, "observation": self.observation}

    @staticmethod
    def _focus_tap(doc: dict, action: dict) -> str | None:
        """The recorded tap on a keyboard action's field that stayed on the action's state."""
        selector = action["target"].get("selector")
        for id, tap in sorted(doc["actions"].items()):
            if (tap["source"] == action["source"] and tap["verb"] == "tap" and tap["status"] == "attempted"
                    and tap["target"].get("selector") == selector and any(
                        attempt["action"] == id and attempt["status"] == "succeeded"
                        and attempt["destination"] == action["source"] for attempt in doc["attempts"].values())):
                return id
        return None

    def continue_after_divergence(self) -> bool:
        """Lift the halt a diverging route left; ``True`` when there was one to lift.

        The diverging input succeeded and its destination was observed, so nothing is
        uncertain: only the route's prediction failed, and the edge, now with two outcomes, is
        no longer routable. An unattended run carries on from the observed state instead of
        resetting; a person's workbench keeps the halt, since they approved the route.
        """
        if self.halted != ROUTE_DIVERGED or self.current is None:
            return False
        self.halted = None
        return True

    def reset(self, reset: Callable[[], Device | None], *, method: str, reason: str) -> dict:
        """Return the device to the run's starting condition and continue in the same graph.

        ``reset`` must restore the starting condition completely (an emulator snapshot, or the
        app's data cleared and the app cold-launched): the store then stops treating earlier
        indeterminate outcomes as live, and nothing earlier is replayed. An unfinished intent
        is recovered as indeterminate first, so call this only when no input is in flight.
        A reset that had to reattach (a snapshot load ends the old attachment) returns the new
        :class:`Device` for the same serial; the session uses it from then on.
        Returns the first observation after the reset (or raises :class:`PilotStopped`).
        """
        if time.monotonic() >= self.deadline:
            raise PilotStopped("elapsed-time budget exhausted")
        self.store.recover_interrupted()
        self.current = None
        device = reset()
        if device is not None:
            if device.serial != self.device.serial:
                raise ValueError("a reset must reattach the same device")
            self.device, self.app = device, device.app(self.package)
        self.store.add_episode(method=method, reason=reason)
        self.halted = None
        return self.observe(0)

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
        edges = self.routable_edges()
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
        raise PilotStopped("no observed route using single-outcome edges; conflicting outcomes and resets are not routing proof")

    def routable_edges(self) -> list[dict]:
        """The observed edges :meth:`route` may use: single-outcome, and a keyboard action only
        where a recorded tap can focus its field again (arriving by a route gives no focus)."""
        conflicts = {(item["source"], item["action"]) for item in self.behavioral_conflicts()}
        doc = self.store.view()
        return [edge for edge in self.store.transitions() if (edge["source"], edge["action"]) not in conflicts
                and (doc["actions"][edge["action"]]["verb"] != "submit"
                     or self._focus_tap(doc, doc["actions"][edge["action"]]) is not None)]

    def navigate(self, destination: str) -> None:
        """Invoke every observed route edge as a new trial with checked expected destination."""
        for edge in self.route(destination):
            self.step(edge["action"], trial=True, expected=edge["destination"])

    def history(self, destination: str) -> list[dict] | None:
        """The shortest recorded run of inputs from an episode's start to ``destination``.

        Where hidden state makes the graph's edges conflict, the exact inputs that once led
        from a reset to a state are the evidence left: replayed from the same starting
        condition, a deterministic app takes the same path. Only histories of succeeded inputs
        whose actions were never uncertain qualify; inputs rejected before input are skipped.
        Each step is ``{"attempt", "action", "source", "destination"}``; ``None`` when no
        episode reached ``destination``.
        """
        doc = self.store.view()
        ordered = sorted(doc["attempts"].items())
        starts = [episode["after_attempts"] for episode in doc["episodes"]]
        best: list[dict] | None = None
        for index, (_, attempt) in enumerate(ordered):
            if attempt["status"] != "succeeded" or attempt["destination"] != destination:
                continue
            start = max((value for value in starts if value <= index), default=None)
            if start is None:
                continue
            steps: list[dict] | None = []
            for key, earlier in ordered[start:index + 1]:
                if earlier["status"] == "failed_before_input":
                    continue
                action = doc["actions"][earlier["action"]]
                if earlier["status"] != "succeeded" or earlier["destination"] is None or action["status"] != "attempted":
                    steps = None
                    break
                steps.append({"attempt": key, "action": earlier["action"], "source": action["source"],
                              "destination": earlier["destination"]})
            if steps and (best is None or len(steps) < len(best)):
                best = steps
        return best

    def replay(self, steps: list[dict]) -> None:
        """Invoke a recorded :meth:`history` as new trials, each checked against its recorded
        destination; a divergence halts the session like a diverging route. Call it only right
        after :meth:`reset`, from the history's first source."""
        if not steps or self.current != steps[0]["source"]:
            raise PilotStopped("a history replays only from its first source")
        for step in steps:
            self.step(step["action"], trial=True, expected=step["destination"])
