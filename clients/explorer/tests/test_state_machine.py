"""Adversarial, oracle-scored SDK stress tests. Fake gRPC, not Android or paid AI.

The explorer gets only snapshots/controls. The oracle alone knows business state. Approval
is a test-only blanket policy for this disposable machine, never an unfamiliar-app policy.
"""
import json

from tap_e2e import proto as pb
from tap_e2e import res
from tap_explorer.live import PilotStopped
from tap_explorer.session import DiscoverySession
from tap_explorer.store import GraphStore

PACKAGE = "machine.test"


class Wizard:
    """Branching wizard with retained edits, conditional validation and nested explanation."""

    def __init__(self):
        self.page = "home"
        self.name = ""
        self.pro = False
        self.accept = False
        self.error = ""
        self.mutations = []
        self.oracle = set()
        self.frames = {}

    def key(self):
        # Only business distinctions actually observable/relevant on this page.
        if self.page in ("home", "about"):
            return (self.page,)
        return (self.page, self.name, self.pro, self.accept, self.error)

    def snapshot(self):
        result = pb.ScreenSnapshotResponse(snapshot_id=len(self.frames) + 1)
        def node(resource, text, *, button=False, field=False, checked=False):
            flags = [pb.FLAG_ENABLED]
            if button or field:
                flags.append(pb.FLAG_CLICKABLE)
            if resource in ("tier", "terms"):
                flags.append(pb.FLAG_CHECKABLE)
                if checked:
                    flags.append(pb.FLAG_CHECKED)
            result.nodes.add(ref=f"e{len(result.nodes)+1}", window_package=PACKAGE,
                depth=1, resource_name=f"{PACKAGE}:id/{resource}", text=text,
                class_name="android.widget.EditText" if field else "android.widget.Button" if button else "android.widget.TextView",
                interactive=button or field, flags=flags,
                **({"selector": res(resource).to_proto()} if button or field else {}))
        node("title", {"home": "Local requests", "about": "About requests", "identity": "Your name",
            "options": "Choose a plan", "explain": "Plan details", "review": "Review request", "receipt": "Request saved"}[self.page])
        if self.page == "home":
            node("wizard", "New request", button=True)
            node("about", "About", button=True)
        elif self.page == "about":
            node("close", "Close", button=True)
        elif self.page == "identity":
            node("name", self.name, field=True)
            node("validation", self.error)
            # Retained edits are visible even while editing the name.
            node("summary", f"Plan: {'Pro' if self.pro else 'Basic'}; terms: {self.accept}")
            node("next", "Continue", button=True)
            node("home", "Cancel request", button=True)
        elif self.page in ("options", "explain"):
            node("summary", f"Name: {self.name}")
            node("tier", "Pro plan", button=True, checked=self.pro)
            node("terms", "Accept fictional terms", button=True, checked=self.accept)
            node("validation", self.error)
            if self.page == "options":
                node("next", "Review", button=True)
                node("explain", "Plan details", button=True)
                node("edit", "Edit name", button=True)
                node("home", "Cancel request", button=True)
            else:
                node("close", "Close details", button=True)
        else:
            node("summary", f"Name: {self.name}; plan: {'Pro' if self.pro else 'Basic'}; terms: {self.accept}")
            if self.page == "review":
                node("confirm", "Save fictional request", button=True)
                node("edit", "Edit name", button=True)
            node("home", "Return home", button=True)
        self.oracle.add(self.key())
        self.frames[result.snapshot_id] = self.key()
        return result

    def execute(self, command):
        op = command.WhichOneof("op")
        if op not in ("tap", "set_text"):
            return None
        operation = getattr(command, op)
        def resource(node):
            if node.HasField("resource"):
                return node.resource.name
            return next((resource(child) for child in node.all_of.nodes if resource(child)), None)
        target = resource(operation.selector.node)
        self.mutations.append((op, target))
        if op == "set_text":
            self.name = operation.text
            self.error = ""
        elif target == "wizard":
            self.page, self.name, self.pro, self.accept, self.error = "identity", "", False, False, ""
        elif target == "home":
            self.page, self.name, self.pro, self.accept, self.error = "home", "", False, False, ""
        elif target == "about":
            self.page = "about"
        elif target == "close":
            self.page = "home" if self.page == "about" else "options"
        elif target == "next":
            if self.page == "identity":
                if not self.name:
                    self.error = "A name is required."
                else:
                    self.page, self.error = "options", ""
            elif self.pro and not self.accept:
                self.error = "Pro requires accepting the fictional terms."
            else:
                self.page, self.error = "review", ""
        elif target == "tier":
            self.pro, self.error = not self.pro, ""
        elif target == "terms":
            self.accept, self.error = not self.accept, ""
        elif target == "edit":
            self.page, self.error = "identity", ""
        elif target == "explain":
            self.page = "explain"
        elif target == "confirm":
            self.page = "receipt"
        # Tapping a text field is a real self-loop, not a fill or a useful branch.
        return None


def test_generic_explorer_branching_wizard_without_screen_catalog(fake, pilot_device, tmp_path):
    machine = Wizard()
    fake.devices.snapshot = machine.snapshot()
    def responder(command):
        result = machine.execute(command)
        fake.devices.snapshot = machine.snapshot()
        return result
    fake.devices.responder = responder
    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=500, max_depth=100)
        session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
        session.observe()
        values = ("", "Ada")
        stop = "frontier_resolved"
        for _ in range(500):
            doc = graph.document()
            available = {id: action for id, action in doc["actions"].items()
                         if action["status"] == "blocked" and not action["target"].get("blocked_reason")
                         and action["verb"] in ("tap", "fill")
                         and not (action["verb"] == "fill" and all(any(
                             other.startswith(id + "/value-") and scenario["scenario"].get("value") == value
                             for other, scenario in doc["actions"].items()) for value in values))}
            if not available:
                break
            local = sorted(id for id, action in available.items() if action["source"] == session.current)
            if not local:
                reachable = []
                for source in sorted({action["source"] for action in available.values()}):
                    try:
                        route = session.route(source)
                    except PilotStopped as error:
                        assert "no observed route" in str(error)
                        continue
                    reachable.append((len(route), source))
                if not reachable:
                    stop = "unreachable_frontier"
                    break
                session.navigate(min(reachable)[1])
                continue
            candidate = local[0]
            # Two finite fictional scenarios. No labels or state IDs guide traversal.
            value = None
            if available[candidate]["verb"] == "fill":
                value = next(value for value in values if not any(
                    other.startswith(candidate + "/value-") and scenario["scenario"].get("value") == value
                    for other, scenario in doc["actions"].items()))
            session.approve(candidate, reason="test-only safe local machine", value=value)
            actual = graph.next_action()["action"]
            session.step(actual)
        else:
            stop = "iteration_budget"
        doc = graph.document()
        mappings = {}
        inverse = {}
        for id, state in doc["states"].items():
            for observation in state["observations"]:
                key = machine.frames[doc["observations"][observation]["evidence"]["after_snapshot_id"]]
                mappings.setdefault(id, set()).add(key)
                inverse.setdefault(key, set()).add(id)
        report = {"kind": "fake-grpc-oracle", "stop": stop, "states": len(doc["states"]),
                  "oracle_states_seen": len(machine.oracle), "attempts": len(doc["attempts"]),
                  "distinct_edges": len({(e["source"], e["action"], e["destination"]) for e in graph.transitions()}),
                  "false_ui_merges": sum(len(keys) > 1 for keys in mappings.values()),
                  "false_ui_splits": sum(len(ids) > 1 for ids in inverse.values()),
                  "finite_fill_values": list(values),
                  "pages_seen": sorted({key[0] for key in machine.oracle})}
        (tmp_path / "report.json").write_text(json.dumps(report, indent=2))
        print(json.dumps(report, sort_keys=True))
        assert stop == "frontier_resolved"
        assert len(machine.mutations) == len(doc["attempts"])
        assert report["false_ui_merges"] == 0
        assert report["false_ui_splits"] == 0
        assert report["states"] == 30
        assert report["pages_seen"] == ["about", "explain", "home", "identity", "options", "receipt", "review"]
        assert all(a["status"] == "succeeded" for a in doc["attempts"].values())
