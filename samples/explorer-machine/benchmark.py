"""Operator-invoked, fixture-only unseeded discovery benchmark; never a general safety policy.

Only this no-permission, fictional APK is authorized by the policy. There is no state catalog
or expected transition table in the explorer. Ordinary snapshots discover every candidate.
Requires an explicitly running daemon and fresh output. An existing fixture requires the
operator's explicit reviewed-reuse flag; no install/overwrite then occurs. No AI calls.
"""
import argparse
import hashlib
import json
import time
import uuid
from pathlib import Path

from google.protobuf.json_format import ParseDict
from tap_e2e import TapClient
from tap_e2e import proto as pb
from tap_explorer.live import PilotStopped
from tap_explorer.session import DiscoverySession
from tap_explorer.store import GraphStore

PACKAGE = "io.github.noamcohen48.tap.explorer.machine"
RESOURCES = {"wizard", "about", "close", "name", "next", "home", "tier", "terms", "edit", "explain", "confirm"}
VALUES = ("", "Ada")


def resource_names(node):
    if node.HasField("resource"):
        return [node.resource.name]
    return [name for child in node.all_of.nodes for name in resource_names(child)]


def reviewed(action):
    if action["target"].get("blocked_reason") or action["verb"] not in ("tap", "fill"):
        return False
    selector = ParseDict(action["target"]["selector"], pb.Selector())
    names = resource_names(selector.node)
    return len(names) == 1 and names[0].split(":id/")[-1] in RESOURCES


def walk(session, *, business_controls_only=False):
    """Bounded local-first traversal, then shortest observed route to remaining reviewed frontier."""
    session.observe()
    for _ in range(600):
        doc = session.store.document()
        used = {}
        for id, action in doc["actions"].items():
            if "/value-" in id:
                used.setdefault(id.rsplit("/value-", 1)[0], set()).add(action["scenario"]["value"])
        fill_targets = {(action["source"], json.dumps(action["target"].get("selector"), sort_keys=True))
                        for action in doc["actions"].values() if action["verb"] == "fill"}
        # Explicit fixture review: focus-only EditText taps are outside this business-flow
        # experiment. They remain retained proposals; no cursor/window signature is hidden.
        available = {id: action for id, action in doc["actions"].items()
                     if action["status"] == "blocked" and reviewed(action)
                     and not (business_controls_only and action["verb"] == "tap" and
                              (action["source"], json.dumps(action["target"].get("selector"), sort_keys=True)) in fill_targets)
                     and not (action["verb"] == "fill" and set(VALUES) <= used.get(id, set()))}
        if not available:
            return "reviewed_frontier_resolved"
        local = sorted(id for id, action in available.items() if action["source"] == session.current)
        if not local:
            paths = []
            for source in sorted({action["source"] for action in available.values()}):
                try:
                    paths.append((len(session.route(source)), source))
                except PilotStopped as error:
                    if "no observed route" not in str(error):
                        raise
            if not paths:
                return "unreachable_reviewed_frontier"
            session.navigate(min(paths)[1])
            continue
        candidate = local[0]
        value = next((value for value in VALUES if value not in used.get(candidate, set())), None) if available[candidate]["verb"] == "fill" else None
        session.approve(candidate, reason="operator: no-permission fictional machine control; fixture policy/1", value=value)
        session.step(session.store.next_action()["action"])
    return "iteration_budget"


def audit(store, events):
    """Check ordered native input against intent records, explicit package scope and log integrity."""
    if events.dropped or any(not event.ok for event in events.events):
        raise PilotStopped("failed/evicted event log cannot pass")
    actual = []
    for event in events.events:
        if event.serial != store.document()["context"]["serial"]:
            raise PilotStopped("another device in log")
        if event.command is None:
            if not event.app or event.app.get("package_name") != PACKAGE or event.app.get("operation") not in ("install", "cold_launch", "force_stop"):
                raise PilotStopped("unexpected lifecycle operation")
            continue
        command = ParseDict(event.command, pb.Command())
        op = command.WhichOneof("op")
        if op not in ("tap", "set_text"):
            if op not in ("wait_visible", "wait_screen_stable"):
                raise PilotStopped(f"unexpected command: {op}")
            continue
        mutation = getattr(command, op)
        names = resource_names(mutation.selector.node)
        nodes = list(mutation.selector.node.all_of.nodes)
        packages = [node.match.value for node in nodes if node.HasField("match")
                    and node.match.property == pb.PROPERTY_PACKAGE_NAME and node.match.mode == pb.MATCH_EXACT]
        if (mutation.selector.WhichOneof("pick") is not None or not packages
                or any(package != PACKAGE for package in packages) or len(names) != 1
                or len(nodes) != len(packages) + 1):
            raise PilotStopped("unscoped/index mutation")
        actual.append((op, names[0].split(":id/")[-1], command.set_text.text if op == "set_text" else None))
    expected = []
    for _, attempt in sorted(store.document()["attempts"].items()):
        action = store.document()["actions"][attempt["action"]]
        names = resource_names(ParseDict(action["target"]["selector"], pb.Selector()).node)
        expected.append(("set_text" if action["verb"] == "fill" else "tap", names[0].split(":id/")[-1], action["scenario"].get("value")))
    if actual != expected:
        raise PilotStopped("native input differs from persisted attempts")
    return {"trace_matches_attempts": True, "mutations": len(actual), "dropped": events.dropped}


def run(serial, apk, output, *, reuse_reviewed_fixture=False, business_controls_only=False, max_elapsed=900):
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    report = {"kind": "native-fixture-unseeded", "status": "failed", "serial": serial, "package": PACKAGE,
              "apk_sha256": digest, "finite_values": list(VALUES), "max_actions": 400, "max_depth": 100,
              "business_controls_only": business_controls_only, "max_elapsed_seconds": max_elapsed}
    with GraphStore(output / "graph.db") as store:
        store.initialize({"serial": serial, "package": PACKAGE, "apk_sha256": digest,
                          "policy": "reviewed-fictional-machine/1"}, max_actions=400, max_depth=100)
        try:
            with TapClient.create() as client, client.connect(f"explorer-machine-{uuid.uuid4().hex}") as connection:
                device = connection.attach_device(serial, wait_for_device=0)
                app = device.app(PACKAGE)
                try:
                    installed = app.is_installed()
                    if installed and not reuse_reviewed_fixture:
                        raise PilotStopped("existing fixture requires explicit reviewed reuse; no overwrite/clear-data/uninstall")
                    if not installed and reuse_reviewed_fixture:
                        raise PilotStopped("reviewed reuse requires the already installed fixture")
                    report["reviewed_fixture_reused"] = installed
                    report["device"] = json.loads(device.info().bytes)
                    if not installed:
                        app.install(apk)
                    app.cold_launch(".MainActivity")
                    session = DiscoverySession(device, store, output / "observations", package=PACKAGE, max_elapsed=max_elapsed)
                    report["stop"] = walk(session, business_controls_only=business_controls_only)
                    report["conflicts"] = session.behavioral_conflicts()
                    doc = store.document()
                    report.update(states=len(doc["states"]), attempts=len(doc["attempts"]),
                                  observations=len(doc["observations"]),
                                  distinct_edges=len({(e["source"], e["action"], e["destination"]) for e in store.transitions()}))
                    if report["stop"] != "reviewed_frontier_resolved":
                        raise PilotStopped(report["stop"])
                    app.force_stop()
                    report["status"] = "passed"
                finally:
                    events = connection.event_log()
                    (output / "events.json").write_text(json.dumps({"dropped": events.dropped, "events": [e.to_dict() for e in events.events]}, indent=2))
                    if report["status"] == "passed":
                        report["audit"] = audit(store, events)
        except BaseException as error:
            report.update(status="failed", error=f"{type(error).__name__}: {error}")
            raise
        finally:
            doc = store.document()
            report.update(states=len(doc["states"]), attempts=len(doc["attempts"]), observations=len(doc["observations"]),
                          outcomes={status: sum(a["status"] == status for a in doc["attempts"].values())
                                    for status in ("succeeded", "failed_before_input", "indeterminate", "intent")})
            report["elapsed_seconds"] = round(time.monotonic() - started, 3)
            (output / "graph.json").write_text(json.dumps(store.document(), indent=2))
            (output / "report.json").write_text(json.dumps(report, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--reuse-reviewed-fixture", action="store_true", help="explicitly cold-launch the installed fictional fixture; never resume an old graph")
    parser.add_argument("--business-controls-only", action="store_true", help="reviewed fixture policy: exclude text-field focus taps, retain fill scenarios")
    parser.add_argument("--max-elapsed", type=int, default=900, help="finite experiment time budget, 1..1800 seconds")
    args = parser.parse_args()
    if not 1 <= args.max_elapsed <= 1800:
        parser.error("max elapsed must be 1..1800 seconds")
    print(json.dumps(run(args.serial, args.apk, args.out, reuse_reviewed_fixture=args.reuse_reviewed_fixture,
                         business_controls_only=args.business_controls_only, max_elapsed=args.max_elapsed), indent=2))
