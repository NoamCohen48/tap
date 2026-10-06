"""Partial observability is a measured limitation, not magically solved by screen hashing."""
import pytest
from tap_e2e import proto as pb
from tap_e2e import res
from tap_explorer.live import PilotStopped
from tap_explorer.session import DiscoverySession
from tap_explorer.store import GraphStore

PACKAGE = "alias.test"


class HiddenGate:
    def __init__(self):
        self.armed = False
        self.page = "home"
        self.mutations = []

    def snapshot(self):
        response = pb.ScreenSnapshotResponse(snapshot_id=1)
        response.nodes.add(ref="title", window_package=PACKAGE, resource_name=f"{PACKAGE}:id/title",
                           class_name="android.widget.TextView", text=self.page, flags=[pb.FLAG_ENABLED])
        for resource in ("open", "arm") if self.page == "home" else ("home",):
            response.nodes.add(ref=resource, window_package=PACKAGE, resource_name=f"{PACKAGE}:id/{resource}",
                class_name="android.widget.Button", text=resource, flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE],
                interactive=True, selector=res(resource).to_proto())
        return response

    def respond(self, command):
        if command.WhichOneof("op") != "tap":
            return None
        def resource(node):
            if node.HasField("resource"):
                return node.resource.name
            return next((resource(child) for child in node.all_of.nodes if resource(child)), None)
        target = resource(command.tap.selector.node)
        self.mutations.append(target)
        if target == "arm":
            self.armed = True  # Deliberately no visible change at all.
        elif target == "open":
            self.page = "alternate" if self.armed else "standard"
        else:
            self.page = "home"
        return None


def execute(session, label, *, trial=False):
    candidates = [(id, action) for id, action in session.store.document()["actions"].items()
                  if action["source"] == session.current and action["target"]["label"] == label]
    assert len(candidates) == 1
    id, action = candidates[0]
    if action["status"] == "blocked":
        session.approve(id, reason="test-only hidden-state diagnostic")
    return session.step(id, trial=trial)


def create(fake, pilot_device, tmp_path):
    machine = HiddenGate()
    fake.devices.snapshot = machine.snapshot()
    def respond(command):
        result = machine.respond(command)
        fake.devices.snapshot = machine.snapshot()
        return result
    fake.devices.responder = respond
    graph = GraphStore(tmp_path / "graph.db")
    graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=20)
    session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
    return machine, graph, session


def test_unknown_hidden_history_is_not_identifiable_before_input_and_stops_on_divergence(fake, pilot_device, tmp_path):
    machine, graph, session = create(fake, pilot_device, tmp_path)
    with graph:
        home = session.observe()["state"]
        standard = execute(session, "open")["state"]
        execute(session, "home")
        execute(session, "arm")
        assert session.current == home  # Known limitation: same visible features, different hidden state.
        assert not session.behavioral_conflicts()  # No divergent outcome observed yet.
        with pytest.raises(PilotStopped, match="diverged"):
            session.navigate(standard)
        assert machine.mutations == ["open", "home", "arm", "open"]
        assert len(session.behavioral_conflicts()) == 1
        assert len(graph.document()["states"]) == 3  # alternate was captured, not mislabeled standard.
        assert session.halted == "observed route diverged"
        with pytest.raises(PilotStopped):
            execute(session, "home")
        assert len(machine.mutations) == 4  # No retry or next input after recognizing the mismatch.


def test_known_conflicting_outcomes_are_not_used_as_deterministic_routes(fake, pilot_device, tmp_path):
    machine, graph, session = create(fake, pilot_device, tmp_path)
    with graph:
        home = session.observe()["state"]
        standard = execute(session, "open")["state"]
        execute(session, "home")
        execute(session, "arm")
        alternate = execute(session, "open", trial=True)["state"]
        execute(session, "home")
        assert session.current == home
        conflict, = session.behavioral_conflicts()
        assert conflict["source"] == home
        assert set(conflict["destinations"]) == {standard, alternate}
        assert len(conflict["attempts"]) == 2
        before = len(machine.mutations)
        with pytest.raises(PilotStopped, match="single-outcome"):
            session.navigate(standard)
        with pytest.raises(PilotStopped, match="single-outcome"):
            session.navigate(alternate)
        assert len(machine.mutations) == before
        assert len(graph.transitions()) == 5  # All evidence, including the self-loop, survives.
