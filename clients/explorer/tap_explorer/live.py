"""Bounded, explicitly configured device pilot. Not an AI or general whole-app crawler."""

from __future__ import annotations

import json
import re
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path

from google.protobuf.json_format import MessageToDict
from tap_e2e import (
    CommandError,
    Device,
    ErrorCode,
    NodeFlag,
    ScreenNode,
    ScreenSnapshot,
    res,
    text,
)

from .store import GraphStore


@dataclass(frozen=True)
class ScreenRule:
    """An operator-defined state landmark, checked with ordinary Tap selectors.

    Higher priority wins when an approved modal and its underlying page are both visible.
    This is configured recognition, not automatic inference of unknown pages.
    """

    name: str
    marker: str
    priority: int = 0
    resource: str = "state"
    checks: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class ActionRule:
    """An explicitly approved finite tap/fill scenario on one configured screen.

    Values are stored verbatim: use only non-sensitive sample data. No password fields,
    index picks, coordinates, arbitrary verbs, or external-package actions are supported.
    """

    name: str
    source: str
    resource: str
    verb: str = "tap"
    value: str | None = None
    priority: int = 100

    @property
    def key(self) -> str:
        """Stable state-qualified candidate ID, independent of snapshot refs."""
        return f"{self.source}/{self.name}"


class PilotStopped(RuntimeError):
    """The pilot cannot continue safely; inspect retained observations/attempts."""


def _resource(node: ScreenNode, package: str) -> str | None:
    if node.window_package != package or not node.resource_name:
        return None
    prefix = f"{package}:id/"
    if node.resource_name.startswith(prefix):
        return node.resource_name[len(prefix):]
    return node.resource_name if ":" not in node.resource_name and "/" not in node.resource_name else None


def _node(node: ScreenNode) -> dict:
    return {"ref": node.ref, "depth": node.depth, "package": node.window_package,
            "resource": node.resource_name, "class": node.class_name, "text": node.text,
            "description": node.content_description, "hint": node.hint,
            "bounds": [node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom],
            "flags": sorted(flag.value for flag in node.flags), "password": node.password,
            "interactive": node.interactive, "by_index": node.by_index,
            "selector": MessageToDict(node.selector.to_proto()) if node.selector is not None else None}


class BoundedExplorer:
    """Explore approved candidates using snapshots and already-observed routes.

    Uses a fresh graph only; resuming/reconciling an old device run is not implemented.
    Every route step is a separately approved new trial, with native pre/post landmark checks.
    No return edge is inferred from Back, and no accepted mutation is retransmitted.
    The caller owns the connection, app bootstrap, policy, and artifact privacy.
    """

    def __init__(self, device: Device, store: GraphStore, artifacts: Path, *, package: str,
                 screens: tuple[ScreenRule, ...], actions: tuple[ActionRule, ...],
                 initial: str, timeout: float = 10.0):
        self.device, self.store, self.artifacts = device, store, artifacts
        self.package, self.screens, self.actions = package, screens, actions
        self.initial, self.timeout = initial, timeout
        self.app = device.app(package)
        self._rules = {action.key: action for action in actions}
        self._screens = {screen.name: screen for screen in screens}
        self._current: str | None = None
        self._started = False
        if len(self._rules) != len(actions) or len(self._screens) != len(screens):
            raise ValueError("duplicate policy rule")
        if initial not in self._screens or not package or timeout <= 0:
            raise ValueError("invalid starting condition")
        for screen in screens:
            if not screen.name or "/" in screen.name or not screen.marker or screen.priority < 0:
                raise ValueError("invalid screen rule")
            self._validate_resource(screen.resource)
            for resource, _ in screen.checks:
                self._validate_resource(resource)
        for action in actions:
            self._validate_resource(action.resource)
            if (action.source not in self._screens or not action.name or "/" in action.name
                    or action.priority < 0 or action.verb not in ("tap", "fill")
                    or (action.verb == "fill" and not isinstance(action.value, str))
                    or (action.verb == "tap" and action.value is not None)):
                raise ValueError("invalid action rule")
        doc = store.document()
        if doc["context"].get("package") != package or doc["context"].get("serial") != device.serial:
            raise ValueError("graph context does not match device/package")
        if any(doc[name] for name in ("observations", "states", "actions", "attempts")):
            raise ValueError("live pilot requires an empty graph; no automatic resume")
        artifacts.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _validate_resource(resource: str) -> None:
        if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", resource):
            raise ValueError("policy requires a bare resource name")

    def _recognize(self, snapshot: ScreenSnapshot) -> str:
        matches = [screen for screen in self.screens if sum(
            _resource(node, self.package) == screen.resource and node.text == screen.marker
            for node in snapshot.nodes) == 1]
        if not matches:
            raise PilotStopped("unknown screen; retained raw evidence, no guessed action")
        highest = max(screen.priority for screen in matches)
        selected = [screen for screen in matches if screen.priority == highest]
        if len(selected) != 1:
            raise PilotStopped("ambiguous screen landmarks")
        return selected[0].name

    def _fingerprint(self, snapshot: ScreenSnapshot) -> list[dict]:
        nodes = [_node(node) for node in snapshot.nodes if node.window_package == self.package]
        for node in nodes:
            node.pop("ref")
            node.pop("selector")
        return nodes

    def _observe(self, depth: int) -> tuple[str, str, ScreenSnapshot]:
        # Explicit tree settle, never sync-sdk/await_idle. Paired calls are not an atomic frame.
        self.app.await_settled(stable_for=0.2, timeout=self.timeout)
        started = time.time_ns()
        before = self.device.screen_snapshot(selector_candidates=True)
        shot = self.device.screenshot()
        after = self.device.screen_snapshot(selector_candidates=True)
        finished = time.time_ns()
        moving = before.rotation != after.rotation or self._fingerprint(before) != self._fingerprint(after)
        observation = f"obs-{len(self.store.document()['observations']) + 1:06d}"
        image_path = self.artifacts / f"{observation}.png"
        snapshot_path = self.artifacts / f"{observation}.json"
        # Refuse to overwrite evidence from another run or an interrupted capture.
        with image_path.open("xb") as image:
            image.write(shot.bytes)
        payload = {"snapshot_id": after.snapshot_id, "rotation": after.rotation,
                   "nodes": [_node(node) for node in after.nodes]}
        with snapshot_path.open("x", encoding="utf-8") as output:
            json.dump(payload, output, indent=2, sort_keys=True, ensure_ascii=False)
        self.store.add_observation(observation, {"screenshot": str(image_path),
            "snapshot": str(snapshot_path), "started_ns": started, "finished_ns": finished,
            "before_snapshot_id": before.snapshot_id, "after_snapshot_id": after.snapshot_id,
            "capture_order": "snapshot,screenshot,snapshot", "moving": moving})
        if moving:
            raise PilotStopped("snapshot changed around screenshot; no action on a drifting frame")
        state = self._recognize(after)
        rule = self._screens[state]
        # Exploration snapshots propose identity; normal native selectors check it.
        self.app.wait(res(rule.resource) & text(rule.marker), timeout=self.timeout).one()
        for resource, value in rule.checks:
            self.app.wait(res(resource) & text(value), timeout=self.timeout).one()
        doc = self.store.document()
        if state not in doc["states"]:
            self.store.add_state(state, observation, signature=f"configured-landmark/1:{state}", depth=depth)
        else:
            self.store.attach_observation(state, observation)
        self._discover(state, after)
        self._current = state
        return state, observation, after

    def _addressable(self, rule: ActionRule, snapshot: ScreenSnapshot) -> bool:
        nodes = [node for node in snapshot.nodes if _resource(node, self.package) == rule.resource]
        if len(nodes) != 1:
            return False
        node = nodes[0]
        if node.selector is None or node.by_index or node.password or NodeFlag.ENABLED not in node.flags:
            return False
        return (NodeFlag.CLICKABLE in node.flags if rule.verb == "tap"
                else bool(node.class_name and node.class_name.endswith("EditText")))

    def _discover(self, state: str, snapshot: ScreenSnapshot) -> None:
        for rule in self.actions:
            if rule.source != state:
                continue
            doc = self.store.document()
            if rule.key not in doc["actions"]:
                self.store.add_action(rule.key, state, rule.verb, target={"resource": rule.resource},
                    scenario={"value": rule.value} if rule.verb == "fill" else {}, priority=rule.priority)
            if (self.store.document()["actions"][rule.key]["status"] == "blocked"
                    and self._addressable(rule, snapshot)):
                self.store.approve_action(rule.key, "operator-defined bounded pilot policy")

    def _step(self, key: str, *, trial: bool = False, expected: str | None = None) -> None:
        rule = self._rules[key]
        depth = self.store.document()["states"][rule.source]["depth"]
        state, before, snapshot = self._observe(depth)
        if state != rule.source or not self._addressable(rule, snapshot):
            raise PilotStopped("route/source or action precondition changed before input")
        attempt = f"attempt-{len(self.store.document()['attempts']) + 1:06d}"
        if trial:
            self.store.begin_trial(attempt, key, before, approval="new route invocation from checked source")
        else:
            self.store.begin_attempt(attempt, key, before)
        try:
            element = self.app.element(res(rule.resource))
            if rule.verb == "tap":
                element.tap()
            else:
                if rule.value is None:
                    raise ValueError("fill requires approved value")
                element.set_text(rule.value)
        except CommandError as error:
            safe = error.code in (ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS)
            self.store.finish_attempt(attempt, "failed_before_input" if safe else "indeterminate", note=str(error))
            raise  # Never resend a mutation, even when rejected before input.
        except Exception as error:
            self.store.finish_attempt(attempt, "indeterminate", note=str(error))
            raise
        try:
            destination, after, _ = self._observe(depth + 1)
        except Exception as error:
            # Known command success is not the same as a recognized/verified destination.
            self.store.finish_attempt(attempt, "succeeded", note=f"post-observation failed: {error}")
            raise
        self.store.finish_attempt(attempt, "succeeded", after=after, destination=destination)
        if expected is not None and destination != expected:
            raise PilotStopped(f"route diverged: expected {expected}, observed {destination}")

    def _route(self, source: str, target: str) -> list[dict]:
        edges = self.store.transitions()
        queue = deque([(source, [])])
        seen = {source}
        while queue:
            state, path = queue.popleft()
            if state == target:
                return path
            for edge in sorted(edges, key=lambda e: (e["action"], e["destination"], e["attempt"])):
                if edge["source"] == state and edge["destination"] not in seen:
                    seen.add(edge["destination"])
                    queue.append((edge["destination"], path + [edge]))
        raise PilotStopped(f"no observed route from {source} to {target}; no guessed Back/reset")

    def run(self) -> dict:
        """Walk the eligible frontier with checked observed routes; return counts and stop reason.

        This reports only configured candidates. It never claims whole-app coverage or infers
        unseen return routes. Failures stop immediately with evidence left in the store.
        """
        if self._started:
            raise PilotStopped("pilot already started; automatic resume/retry is not supported")
        self._started = True
        state, _, _ = self._observe(0)
        if state != self.initial:
            raise PilotStopped(f"wrong starting condition: expected {self.initial}, observed {state}")
        while True:
            decision = self.store.next_action()
            key = decision["action"]
            if key is None:
                doc = self.store.document()
                return {"states": len(doc["states"]), "candidates": len(doc["actions"]),
                    "attempts": len(doc["attempts"]), "transitions": len(self.store.transitions()),
                    "blocked": sum(a["status"] == "blocked" for a in doc["actions"].values()),
                    "stop_reason": decision["reason"], "current_state": self._current,
                    "coverage": "operator-configured candidates only"}
            source = self._rules[key].source
            if self._current is None:
                raise PilotStopped("no current observation")
            for edge in self._route(self._current, source):
                if self.store.next_action()["reason"] in ("action_budget", "recovery_required"):
                    break
                self._step(edge["action"], trial=True, expected=edge["destination"])
            # A route may have exhausted the budget. Do not send the frontier mutation afterward.
            if self.store.next_action()["action"] != key:
                continue
            self._step(key)
