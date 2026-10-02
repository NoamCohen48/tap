"""Install and explore only Tap's disposable sample AUT, on an explicitly named device."""

from __future__ import annotations

import hashlib
import json
import time
import uuid
from pathlib import Path

from google.protobuf.json_format import ParseDict
from tap_e2e import EventLog, TapClient
from tap_e2e import proto as pb

from .live import ActionRule, BoundedExplorer, PilotStopped, ScreenRule
from .store import GraphStore

PACKAGE = "io.github.noamcohen48.tap.explorer.sample"
STATES = ("home", "about", "profile-empty", "profile-error", "profile-ready", "welcome",
          "preferences-off", "preferences-on")
_CHECKS = {
    "profile-error": (("validation", "Please enter a name."),),
    "profile-ready": (("name_input", "Ada"),),
    "welcome": (("greeting", "Hello, Ada!"),),
    "preferences-off": (("option_value", "Option: off"),),
    "preferences-on": (("option_value", "Option: on"),),
}
SCREENS = tuple(ScreenRule(state, state, priority=10 if state == "about" else 0,
                          checks=_CHECKS.get(state, ())) for state in STATES)
ACTIONS = (
    ActionRule("about", "home", "open_about"),
    ActionRule("profile", "home", "open_profile"),
    ActionRule("preferences", "home", "open_preferences"),
    ActionRule("return-home", "about", "close_about", priority=0),
    *(ActionRule("return-home", state, "home", priority=0) for state in STATES
      if state not in ("home", "about")),
    *(ActionRule("fill-name", state, "name_input", verb="fill", value="Ada")
      for state in ("profile-empty", "profile-error")),
    *(ActionRule("submit", state, "submit") for state in ("profile-empty", "profile-error", "profile-ready")),
    *(ActionRule("toggle", state, "toggle_option") for state in ("preferences-off", "preferences-on")),
)


def check_sample(store: GraphStore, result: dict) -> None:
    """Check this known fixture's discovery, branching, self-loop, and success accounting.

    This is the test oracle for the controlled sample, not an oracle for arbitrary apps.
    """
    doc = store.document()
    if result["stop_reason"] != "frontier_resolved" or result["blocked"]:
        raise PilotStopped(f"sample exploration incomplete: {result}")
    if set(doc["states"]) != set(STATES) or set(doc["actions"]) != {rule.key for rule in ACTIONS}:
        raise PilotStopped("sample did not discover every configured state/action")
    if any(a["status"] != "succeeded" or a["destination"] is None for a in doc["attempts"].values()):
        raise PilotStopped("sample has an unsuccessful/unclassified attempt")
    destinations = {(e["source"], e["action"], e["destination"]) for e in store.transitions()}
    expected = {
        ("home", "home/about", "about"),
        ("home", "home/profile", "profile-empty"),
        ("home", "home/preferences", "preferences-off"),
        ("about", "about/return-home", "home"),
        ("profile-empty", "profile-empty/submit", "profile-error"),
        ("profile-error", "profile-error/submit", "profile-error"),
        ("profile-empty", "profile-empty/fill-name", "profile-ready"),
        ("profile-error", "profile-error/fill-name", "profile-ready"),
        ("profile-ready", "profile-ready/submit", "welcome"),
        ("preferences-off", "preferences-off/toggle", "preferences-on"),
        ("preferences-on", "preferences-on/toggle", "preferences-off"),
        *((state, f"{state}/return-home", "home") for state in STATES
          if state not in ("home", "about")),
    }
    if destinations != expected:
        raise PilotStopped(f"unexpected sample transitions: missing={expected - destinations}, extra={destinations - expected}")
    # More attempts than candidates proves that checked new route invocations actually ran.
    if len(doc["attempts"]) <= len(doc["actions"]):
        raise PilotStopped("sample did not exercise route replay")


def audit_events(store: GraphStore, events: EventLog) -> dict:
    """Compare the actual daemon input trace to persisted attempts, including route trials.

    Parses the contract rather than assuming camelCase/snake_case JSON spelling. Requires an
    unevicted, successful log and package-bound resource selectors; no hidden extra input.
    """
    if events.dropped or any(not event.ok for event in events.events):
        raise PilotStopped("cannot audit an evicted/failed command log")
    actual = []
    bootstrap_calls = 0
    doc = store.document()
    for event in events.events:
        if event.serial != doc["context"]["serial"]:
            raise PilotStopped("event log contains another device")
        if event.command is None:
            if (event.app is None or event.app.get("package_name") != PACKAGE
                    or event.app.get("operation") not in ("install", "cold_launch", "force_stop")):
                raise PilotStopped("unapproved lifecycle call in sample log")
            bootstrap_calls += 1
            continue
        command = ParseDict(event.command, pb.Command())
        operation = command.WhichOneof("op")
        if operation not in ("tap", "set_text"):
            if operation not in ("wait_visible", "wait_screen_stable"):
                raise PilotStopped(f"unapproved command in sample log: {operation}")
            continue
        mutation = command.tap if operation == "tap" else command.set_text
        nodes = list(mutation.selector.node.all_of.nodes)
        resources = [node.resource.name for node in nodes if node.HasField("resource")]
        packages = [node.match.value for node in nodes if node.HasField("match")
                    and node.match.property == pb.PROPERTY_PACKAGE_NAME and node.match.mode == pb.MATCH_EXACT]
        if (mutation.selector.WhichOneof("pick") is not None or len(nodes) != 2
                or len(resources) != 1 or packages != [PACKAGE]):
            raise PilotStopped("mutation does not target exactly the sample's approved resource/package")
        value = command.set_text.text if operation == "set_text" else None
        actual.append((operation, resources[0], value))
    expected = []
    for _, attempt in sorted(doc["attempts"].items()):
        action = doc["actions"][attempt["action"]]
        expected.append(("set_text" if action["verb"] == "fill" else "tap", action["target"]["resource"],
                         action["scenario"].get("value")))
    if actual != expected:
        raise PilotStopped("actual daemon mutation trace differs from persisted attempts")
    return {"mutations": len(actual), "taps": sum(op == "tap" for op, _, _ in actual),
            "fills": sum(op == "set_text" for op, _, _ in actual), "bootstrap_calls": bootstrap_calls,
            "dropped": events.dropped,
            "trace_matches_attempts": True}


def run_sample(serial: str, apk: Path, output: Path, *, max_actions: int = 100) -> dict:
    """Install/cold-launch the no-permission sample, walk its finite policy, and save evidence.

    Refuses to reuse an output directory. Reuses an explicitly running daemon; never starts or
    stops it. Acquires the named device without waiting/stealing and releases it on exit.
    Only a clean run force-stops the sample. No uninstall, clear-data, permissions, or reboot.
    """
    apk_bytes = apk.read_bytes()  # Fail before acquiring a device if the APK does not exist.
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    report: dict = {"serial": serial, "package": PACKAGE, "apk_sha256": hashlib.sha256(apk_bytes).hexdigest(),
                    "output": str(output.resolve()), "status": "failed"}
    with GraphStore(output / "graph.db") as store:
        store.initialize({"serial": serial, "package": PACKAGE, "apk_sha256": report["apk_sha256"],
                          "policy": "tap-explorer-sample/1", "starting_condition": "cold launch → home"},
                         max_actions=max_actions, max_depth=10)
        try:
            with TapClient.create() as client, client.connect(f"explorer-sample-{uuid.uuid4().hex}") as connection:
                device = connection.attach_device(serial, wait_for_device=0)
                app = device.app(PACKAGE)
                try:
                    report["device"] = json.loads(device.info().bytes)
                    app.install(apk)
                    app.cold_launch(".MainActivity")
                    explorer = BoundedExplorer(device, store, output / "observations", package=PACKAGE,
                                               screens=SCREENS, actions=ACTIONS, initial="home")
                    result = explorer.run()
                    check_sample(store, result)
                    report.update(result)
                    app.force_stop()
                    report["status"] = "passed"
                finally:
                    # Persist before Disconnect: the daemon's bounded log ends with the connection.
                    events = connection.event_log()
                    with (output / "events.json").open("w", encoding="utf-8") as log:
                        json.dump({"dropped": events.dropped, "events": [e.to_dict() for e in events.events]},
                                  log, indent=2, sort_keys=True)
                    if report["status"] == "passed":
                        report["command_audit"] = audit_events(store, events)
            return report
        except BaseException as error:
            report["status"] = "failed"
            report["error"] = f"{type(error).__name__}: {error}"
            raise
        finally:
            report["elapsed_seconds"] = round(time.monotonic() - started, 3)
            with (output / "graph.json").open("w", encoding="utf-8") as graph:
                json.dump(store.document(), graph, indent=2, sort_keys=True)
            with (output / "report.json").open("w", encoding="utf-8") as summary:
                json.dump(report, summary, indent=2, sort_keys=True)
