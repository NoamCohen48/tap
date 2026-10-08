"""Replay robot drafts on a device restored from an emulator snapshot; only a replay verifies.

For every generated method, plan a route from the run's start state to the exact state its
evidence attempt started in, using only observed single-outcome transitions (as the explorer's
own routing does), and express that route as robot method calls. Each method then runs in a
fresh device state: the caller's ``reset`` restores a snapshot before the plan starts. A method
is ``verified`` only when every step (each ending in the target robot's ``verify()``) passed.

A replay proves the generated code reproduces the observed path on this build and snapshot. It
does not prove a precondition is the cause of an outcome, nor that the path is a business rule.
"""

from __future__ import annotations

import importlib.util
import json
import sys
import time
from collections import defaultdict, deque
from collections.abc import Callable, Mapping
from pathlib import Path

FORMAT = "tap-robots-verification/1"


def _document(run: Path) -> dict:
    from .store import GraphStore

    if (run / "graph.db").is_file():
        with GraphStore(run / "graph.db") as store:
            return store.document()
    return json.loads((run / "graph.json").read_text(encoding="utf-8"))


def plans(run: Path, model: Mapping) -> dict:
    """``{"start": robot, "methods": {"Robot.method": plan-or-None}}``; a plan is a list of steps.

    A step is ``{"robot", "method", "argument", "attempt"}``. ``None`` means no observed
    single-outcome route reaches any of the method's evidence sources.
    """
    doc = _document(run)
    step_of: dict[str, dict] = {}
    for screen in model["screens"]:
        for method in screen["methods"]:
            for attempt in method["evidence"]:
                action = doc["actions"][doc["attempts"][attempt]["action"]]
                argument = None
                if method["parameter"] == "value":
                    argument = action.get("scenario", {}).get("value", "")
                elif method["parameter"] == "label":
                    argument = (action.get("target") or {}).get("label")
                step_of[attempt] = {"robot": screen["name"], "method": method["name"], "argument": argument,
                                    "attempt": attempt}
    edges = [{"attempt": key, "source": doc["actions"][attempt["action"]]["source"], "action": attempt["action"],
              "destination": attempt["destination"]}
             for key, attempt in sorted(doc["attempts"].items())
             if attempt.get("status") == "succeeded" and attempt.get("destination")]
    outcomes: dict[tuple[str, str], set[str]] = defaultdict(set)
    for edge in edges:
        outcomes[(edge["source"], edge["action"])].add(edge["destination"])
    # Known divergent outcomes are not routing proof; nor is an attempt no method covers.
    routable = [edge for edge in edges if len(outcomes[(edge["source"], edge["action"])]) == 1
                and edge["attempt"] in step_of]
    state_of = {observation: state for state, value in doc["states"].items() for observation in value["observations"]}
    start_observation = min(state_of)
    start = state_of[start_observation]
    routes: dict[str, list[dict]] = {start: []}
    queue = deque([start])
    while queue:
        state = queue.popleft()
        for edge in routable:
            if edge["source"] == state and edge["destination"] not in routes:
                routes[edge["destination"]] = routes[state] + [step_of[edge["attempt"]]]
                queue.append(edge["destination"])
    start_robot = next((screen["name"] for screen in model["screens"] if start_observation in screen["members"]), None)
    result: dict[str, list[dict] | None] = {}
    for screen in model["screens"]:
        for method in screen["methods"]:
            candidates = [routes[doc["actions"][doc["attempts"][attempt]["action"]]["source"]] + [step_of[attempt]]
                          for attempt in method["evidence"]
                          if doc["actions"][doc["attempts"][attempt]["action"]]["source"] in routes]
            result[f"{screen['name']}.{method['name']}"] = min(candidates, key=len) if candidates else None
    return {"start": start_robot, "methods": result}


def _module(path: Path):
    spec = importlib.util.spec_from_file_location("tap_robot_draft", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules.pop("tap_robot_draft", None)
    spec.loader.exec_module(module)
    return module


def replay(module, app, start: str, plan: list[dict], timeout: float) -> None:
    """Run one plan from the start robot; raises on the first failing step."""
    robot = getattr(module, start)(app, timeout).verify()
    for step in plan:
        if type(robot).__name__ != step["robot"]:
            raise AssertionError(f"expected {step['robot']} before {step['method']}, at {type(robot).__name__}")
        method = getattr(robot, step["method"])
        robot = method(step["argument"]) if step["argument"] is not None else method()


def verify(run: Path, out: Path, *, open_app: Callable[[], object], reset: Callable[[], None],
           close: Callable[[], None] = lambda: None, timeout: float = 10.0, only: set[str] | None = None) -> dict:
    """Replay every planned method from a fresh reset; write ``out/verification.json``.

    ``open_app`` returns a launched ``tap_e2e.App`` after ``reset``; ``close`` releases it.
    """
    model = json.loads((out / "robots.json").read_text(encoding="utf-8"))
    module = _module(out / "robots.py")
    planned = plans(run, model)
    evidence = {f"{screen['name']}.{method['name']}": method["evidence"]
                for screen in model["screens"] for method in screen["methods"]}
    results = {}
    for key, plan in sorted(planned["methods"].items()):
        if only is not None and key not in only:
            continue
        if plan is None or planned["start"] is None:
            results[key] = {"status": "unplanned", "steps": [], "error": "no observed single-outcome route"}
            continue
        started = time.monotonic()
        reset()
        try:
            replay(module, open_app(), planned["start"], plan, timeout)
            results[key] = {"status": "verified", "steps": [f"{s['robot']}.{s['method']}" for s in plan]}
        except Exception as error:  # Any failure leaves the method unverified, with the reason.
            results[key] = {"status": "failed", "steps": [f"{s['robot']}.{s['method']}" for s in plan],
                            "error": f"{type(error).__name__}: {error}"[:500]}
        finally:
            close()
        results[key]["seconds"] = round(time.monotonic() - started, 1)
    for key, value in results.items():
        value["evidence"] = evidence[key]
    document = {"format": FORMAT, "package": model["package"], "start": planned["start"], "methods": results}
    (out / "verification.json").write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return document


def emulator_snapshot_reset(serial: str, snapshot: str, adb: str = "adb", timeout: float = 120.0) -> Callable[[], None]:
    """Restore ``snapshot`` on an emulator; refuses anything that is not an emulator serial."""
    import subprocess

    if not serial.startswith("emulator-"):
        raise ValueError("snapshot replay only resets emulators; a physical device is never reset")

    def reset() -> None:
        loaded = subprocess.run([adb, "-s", serial, "emu", "avd", "snapshot", "load", snapshot],
                                capture_output=True, text=True, timeout=timeout)
        if loaded.returncode != 0 or "OK" not in loaded.stdout:
            raise RuntimeError(f"snapshot load failed: {loaded.stdout.strip()} {loaded.stderr.strip()}")
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            booted = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                    capture_output=True, text=True, timeout=30)
            if booted.stdout.strip() == "1":
                return
            time.sleep(1)
        raise RuntimeError("emulator did not come back after the snapshot load")

    return reset
