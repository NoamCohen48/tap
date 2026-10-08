"""Short native business-oracle checks of the operator-reviewed fictional fixture.

These checks are seeded assertions, NOT the explorer's recognition/traversal policy. They run
fresh explicit cold launches, preserve every intent/evidence pair, and never retry a mutation.
Requires this reviewed APK installed and an explicitly running daemon. No AI or other apps.
"""
import argparse
import json
from pathlib import Path

from benchmark import PACKAGE, audit, resource_names
from google.protobuf.json_format import ParseDict
from tap_e2e import TapClient
from tap_e2e import proto as pb
from tap_explorer.session import DiscoverySession
from tap_explorer.store import GraphStore


def run(serial, output):
    output.mkdir(parents=True, exist_ok=False)
    report = {"kind": "seeded-native-business-oracle", "status": "failed", "assertions": []}
    with GraphStore(output / "graph.db") as store:
        store.initialize({"serial": serial, "package": PACKAGE, "policy": "approved-business-oracle/1"}, max_actions=50, max_depth=50)
        try:
            with TapClient.create() as client, client.connect("explorer-machine-business-oracle") as connection:
                device = connection.attach_device(serial, wait_for_device=0)
                app = device.app(PACKAGE)
                if not app.is_installed():
                    raise RuntimeError("install the operator-reviewed fixture explicitly first")
                app.cold_launch(".MainActivity")
                session = DiscoverySession(device, store, output / "observations", package=PACKAGE, max_elapsed=300)
                try:
                    session.observe()
                    def step(resource, *, value=None):
                        verb = "fill" if value is not None else "tap"
                        candidates = [(id, action) for id, action in store.document()["actions"].items()
                                      if action["source"] == session.current and action["verb"] == verb
                                      and "/value-" not in id
                                      and resource_names(ParseDict(action["target"]["selector"], pb.Selector()).node) == [resource]]
                        if len(candidates) != 1:
                            raise AssertionError("one fresh fixture candidate required")
                        id, action = candidates[0]
                        scenario = next(((key, item) for key, item in store.document()["actions"].items()
                                         if key.startswith(id + "/value-") and item["scenario"].get("value") == value), None) if verb == "fill" else (id, action)
                        if scenario and scenario[1]["status"] == "attempted":
                            session.step(scenario[0], trial=True)
                        else:
                            session.approve(id, reason="operator: fresh fictional business-oracle invocation", value=value)
                            session.step(store.next_action()["action"])
                    def field(resource):
                        evidence = store.document()["observations"][session.observation]["evidence"]
                        nodes = [n for n in evidence["features"]["nodes"] if n["resource"] == f"{PACKAGE}:id/{resource}"]
                        if len(nodes) != 1:
                            raise AssertionError("one oracle field required")
                        return nodes[0]
                    def verify(name, condition):
                        if not condition:
                            raise AssertionError(name)
                        report["assertions"].append(name)
                    step("wizard")
                    step("next")
                    verify("empty name refused", field("validation")["text"] == "A name is required.")
                    step("name", value="Ada")
                    verify("name correction clears error", not field("validation")["text"])
                    step("next")
                    step("tier")
                    step("next")
                    verify("Pro without terms refused", field("validation")["text"] == "Pro requires accepting the fictional terms.")
                    step("terms")
                    verify("accepting terms clears error", not field("validation")["text"])
                    verify("terms checked", "CHECKED" in field("terms")["flags"])
                    step("next")
                    verify("Pro review reflects choices", field("summary")["text"] == "Name: Ada; plan: Pro; terms: true")
                    step("edit")
                    verify("edit retains name and plan", field("name")["text"] == "Ada" and field("summary")["text"] == "Plan: Pro; terms: true")
                    step("name", value="")
                    step("next")
                    verify("cleared retained name refused", field("validation")["text"] == "A name is required.")
                    step("name", value="Ada")
                    step("next")
                    step("next")
                    step("confirm")
                    verify("receipt reflects retained choices", field("title")["text"] == "Request saved" and field("summary")["text"] == "Name: Ada; plan: Pro; terms: true")
                    step("home")
                    step("wizard")
                    step("name", value="Ada")
                    step("next")
                    verify("new request resets plan and terms", "CHECKED" not in field("tier")["flags"] and "CHECKED" not in field("terms")["flags"])
                    step("next")
                    verify("Basic needs no terms", field("title")["text"] == "Review request" and field("summary")["text"] == "Name: Ada; plan: Basic; terms: false")
                    step("home")
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
            (output / "graph.json").write_text(json.dumps(store.document(), indent=2))
            (output / "report.json").write_text(json.dumps(report, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(run(args.serial, args.out), indent=2))
