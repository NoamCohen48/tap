"""Exercise the live bridge over real gRPC to the SDK's fake daemon (no device)."""

import pytest
from tap_e2e import CommandError, ErrorCode, res
from tap_e2e import proto as pb

from tap_explorer.live import ActionRule, BoundedExplorer, PilotStopped, ScreenRule
from tap_explorer.store import GraphStore

PACKAGE = "sample.app"
SCREENS = (ScreenRule("home", "home"), ScreenRule("detail", "detail"))
ACTIONS = (ActionRule("a-open", "home", "open"), ActionRule("b-loop", "home", "self"),
           ActionRule("return", "detail", "home", priority=0), ActionRule("extra", "detail", "extra"))


def snapshot(state):
    resources = ("open", "self") if state == "home" else ("home", "extra")
    result = pb.ScreenSnapshotResponse(snapshot_id=1)
    result.nodes.add(ref="e1", window_package=PACKAGE, resource_name=f"{PACKAGE}:id/state",
                     class_name="android.widget.TextView", text=state, flags=[pb.FLAG_ENABLED])
    for index, resource in enumerate(resources, 2):
        result.nodes.add(ref=f"e{index}", window_package=PACKAGE, resource_name=f"{PACKAGE}:id/{resource}",
            class_name="android.widget.Button", text=resource, interactive=True,
            flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE], selector=res(resource).to_proto())
    return result


def resource(node):
    if node.HasField("resource"):
        return node.resource.name
    return next((resource(child) for child in node.all_of.nodes if resource(child)), None)


def script(fake, *, diverge=False, error=None):
    fake.devices.snapshot = snapshot("home")
    taps = []

    def respond(command):
        if command.WhichOneof("op") != "tap":
            return None
        target = resource(command.tap.selector.node)
        taps.append(target)
        if error is not None:
            return pb.CommandResult(error=pb.Error(code=error))
        current = fake.devices.snapshot.nodes[0].text
        if target == "open":
            state = "home" if diverge and taps.count("open") > 1 else "detail"
        elif target == "home":
            state = "home"
        else:
            state = current
        fake.devices.snapshot = snapshot(state)
        return None

    fake.devices.responder = respond
    return taps


def explorer(device, tmp_path, *, actions=ACTIONS, max_actions=100):
    graph = GraphStore(tmp_path / "graph.db")
    graph.initialize({"serial": "fake", "package": PACKAGE}, max_actions=max_actions)
    pilot = BoundedExplorer(device, graph, tmp_path / "evidence", package=PACKAGE,
                            screens=SCREENS, actions=actions, initial="home")
    return graph, pilot


def test_walk_discovers_graph_and_replays_checked_route(fake, pilot_device, tmp_path):
    taps = script(fake)
    graph, pilot = explorer(pilot_device, tmp_path)
    with graph:
        result = pilot.run()
        assert result == {"states": 2, "candidates": 4, "attempts": 5, "transitions": 5,
                          "blocked": 0, "stop_reason": "frontier_resolved", "current_state": "detail",
                          "coverage": "operator-configured candidates only"}
        assert taps == ["open", "home", "self", "open", "extra"]
        assert len(list((tmp_path / "evidence").glob("*.png"))) == len(graph.document()["observations"])
        assert any(e["source"] == e["destination"] for e in graph.transitions())
        assert any("new route invocation" in a["note"] for a in graph.document()["attempts"].values())
        for observation in graph.document()["observations"].values():
            assert observation["evidence"]["moving"] is False


def test_route_divergence_stops_before_next_mutation(fake, pilot_device, tmp_path):
    taps = script(fake, diverge=True)
    graph, pilot = explorer(pilot_device, tmp_path)
    with graph, pytest.raises(PilotStopped, match="route diverged"):
        pilot.run()
        pytest.fail("must not finish")
    with GraphStore(tmp_path / "graph.db") as saved:
        assert taps == ["open", "home", "self", "open"]
        assert saved.document()["actions"]["detail/extra"]["status"] == "pending"
        assert saved.transitions()[-1]["destination"] == "home"  # Actual outcome, not expected detail.


@pytest.mark.parametrize("code,status", [(pb.ERR_AMBIGUOUS, "failed_before_input"),
                                        (pb.ERR_INDETERMINATE, "indeterminate")])
def test_mutation_failure_is_never_replayed(fake, pilot_device, tmp_path, code, status):
    taps = script(fake, error=code)
    graph, pilot = explorer(pilot_device, tmp_path)
    with graph:
        with pytest.raises(CommandError) as failure:
            pilot.run()
        assert failure.value.code in (ErrorCode.AMBIGUOUS, ErrorCode.INDETERMINATE)
        assert taps == ["open"]
        assert list(graph.document()["attempts"].values())[0]["status"] == status
        assert not graph.transitions()
        if status == "indeterminate":
            assert graph.next_action()["reason"] == "recovery_required"


def test_budget_exhausted_by_route_does_not_send_frontier_action(fake, pilot_device, tmp_path):
    taps = script(fake)
    graph, pilot = explorer(pilot_device, tmp_path, max_actions=4)
    with graph:
        result = pilot.run()
        assert result["stop_reason"] == "action_budget"
        assert result["attempts"] == 4
        assert taps == ["open", "home", "self", "open"]
        assert graph.document()["actions"]["detail/extra"]["status"] == "pending"


def test_unapproved_controls_are_not_enumerated_or_touched(fake, pilot_device, tmp_path):
    taps = script(fake)
    # Only self is allowed; the visible open control is not automatically trusted.
    graph, pilot = explorer(pilot_device, tmp_path, actions=(ActionRule("loop", "home", "self"),))
    with graph:
        assert pilot.run()["candidates"] == 1
        assert taps == ["self"]


def test_duplicate_resource_stays_blocked_before_input(fake, pilot_device, tmp_path):
    taps = script(fake)
    duplicate = fake.devices.snapshot.nodes.add()
    duplicate.CopyFrom(fake.devices.snapshot.nodes[1])
    duplicate.ref = "e99"
    graph, pilot = explorer(pilot_device, tmp_path, actions=(ACTIONS[0],))
    with graph:
        assert pilot.run()["blocked"] == 1
        assert graph.document()["actions"]["home/a-open"]["status"] == "blocked"
        assert not taps


def test_capture_drift_stops_before_input(fake, pilot_device, tmp_path, monkeypatch):
    taps = script(fake)
    original = pilot_device.screen_snapshot
    calls = 0

    def moving(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 2:
            fake.devices.snapshot = snapshot("detail")
        return original(*args, **kwargs)

    monkeypatch.setattr(pilot_device, "screen_snapshot", moving)
    graph, pilot = explorer(pilot_device, tmp_path)
    with graph:
        with pytest.raises(PilotStopped, match="drifting frame"):
            pilot.run()
        assert not taps
        assert next(iter(graph.document()["observations"].values()))["evidence"]["moving"] is True
        assert not graph.document()["attempts"]


def test_success_with_unknown_post_screen_is_not_a_verified_edge(fake, pilot_device, tmp_path):
    taps = script(fake)
    original = fake.devices.responder

    def unknown(command):
        result = original(command)
        if command.WhichOneof("op") == "tap":
            fake.devices.snapshot = snapshot("unknown")
        return result

    fake.devices.responder = unknown
    graph, pilot = explorer(pilot_device, tmp_path)
    with graph:
        with pytest.raises(PilotStopped, match="unknown screen"):
            pilot.run()
        assert taps == ["open"]
        attempt = next(iter(graph.document()["attempts"].values()))
        assert attempt["status"] == "succeeded" and attempt["destination"] is None
        assert not graph.transitions()


def test_modal_priority_disambiguates_underlying_marker(fake, pilot_device, tmp_path):
    script(fake)
    fake.devices.snapshot.nodes.add(ref="e90", window_package=PACKAGE, resource_name=f"{PACKAGE}:id/state",
        class_name="android.widget.TextView", text="about", flags=[pb.FLAG_ENABLED])
    with GraphStore(tmp_path / "graph.db") as graph:
        graph.initialize({"serial": "fake", "package": PACKAGE})
        pilot = BoundedExplorer(pilot_device, graph, tmp_path / "evidence", package=PACKAGE,
            screens=(ScreenRule("home", "home"), ScreenRule("about", "about", priority=10)),
            actions=(), initial="about")
        assert pilot.run()["current_state"] == "about"


def test_existing_graph_is_not_automatically_resumed(fake, pilot_device, tmp_path):
    script(fake)
    graph, pilot = explorer(pilot_device, tmp_path)
    with graph:
        pilot.run()
        with pytest.raises(ValueError, match="empty graph"):
            BoundedExplorer(pilot_device, graph, tmp_path / "evidence", package=PACKAGE,
                            screens=SCREENS, actions=ACTIONS, initial="home")
        with pytest.raises(PilotStopped, match="already started"):
            pilot.run()


def test_approved_fill_uses_set_text_not_a_coordinate_tap(fake, pilot_device, tmp_path):
    fake.devices.snapshot = snapshot("home")
    field = fake.devices.snapshot.nodes.add(ref="e40", window_package=PACKAGE,
        resource_name=f"{PACKAGE}:id/name", class_name="android.widget.EditText",
        flags=[pb.FLAG_ENABLED], interactive=True, selector=res("name").to_proto())
    sent = []

    def fill(command):
        if command.WhichOneof("op") == "set_text":
            sent.append(command.set_text.text)
            field.text = command.set_text.text
        return None

    fake.devices.responder = fill
    graph, pilot = explorer(pilot_device, tmp_path,
        actions=(ActionRule("fill", "home", "name", verb="fill", value="Ada"),))
    with graph:
        assert pilot.run()["attempts"] == 1
        assert sent == ["Ada"]
        assert graph.transitions()[0]["source"] == graph.transitions()[0]["destination"] == "home"


@pytest.mark.parametrize("unsafe", ["password", "by_index", "disabled"])
def test_unsafe_target_quality_is_blocked(fake, pilot_device, tmp_path, unsafe):
    taps = script(fake)
    target = fake.devices.snapshot.nodes[1]
    if unsafe == "disabled":
        target.flags[:] = [pb.FLAG_CLICKABLE]
    else:
        setattr(target, unsafe, True)
    graph, pilot = explorer(pilot_device, tmp_path, actions=(ACTIONS[0],))
    with graph:
        assert pilot.run()["blocked"] == 1
        assert not taps
