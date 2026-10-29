"""Fresh unseeded session over fake gRPC; explicit approvals, no physical device/network AI."""

import pytest
from tap_e2e import CommandError, res
from tap_e2e import proto as pb

from tap_explorer.live import PilotStopped
from tap_explorer.session import DiscoverySession
from tap_explorer.store import GraphStore

PACKAGE = "unknown.app"


def frame(title, button):
    result = pb.ScreenSnapshotResponse(snapshot_id=1)
    result.nodes.add(ref="e1", window_package=PACKAGE, resource_name=f"{PACKAGE}:id/title",
                     class_name="android.widget.TextView", text=title, flags=[pb.FLAG_ENABLED])
    result.nodes.add(ref="e2", window_package=PACKAGE, resource_name=f"{PACKAGE}:id/{button}",
                     class_name="android.widget.Button", text=button, interactive=True,
                     flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE], selector=res(button).to_proto())
    return result


def resource(node):
    if node.HasField("resource"):
        return node.resource.name
    return next((resource(child) for child in node.all_of.nodes if resource(child)), None)


def setup(fake, pilot_device, tmp_path, *, error=None):
    fake.devices.snapshot = frame("Library", "settings")
    taps = []

    def responder(command):
        if command.WhichOneof("op") != "tap":
            return None
        target = resource(command.tap.selector.node)
        taps.append(target)
        if error:
            return pb.CommandResult(error=pb.Error(code=error))
        fake.devices.snapshot = frame("Preferences", "home") if target == "settings" else frame("Library", "settings")
        return None

    fake.devices.responder = responder
    graph = GraphStore(tmp_path / "graph.db")
    graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=20)
    session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
    return session, graph, taps


def test_unseeded_two_states_checked_routes_no_automatic_approval(fake, pilot_device, tmp_path):
    session, graph, taps = setup(fake, pilot_device, tmp_path)
    with graph:
        first = session.observe()
        assert graph.next_action()["action"] is None
        key = first["candidates"][-1]
        with pytest.raises(PilotStopped, match="approval"):
            session.step(key)
        assert taps == []
        session.approve(key, reason="operator: controlled settings navigation")
        second = session.step(key)
        assert first["state"] != second["state"]
        home = second["candidates"][-1]
        session.approve(home, reason="operator: return control")
        session.step(home)
        assert session.current == first["state"]
        session.navigate(second["state"])
        assert taps == ["settings", "home", "settings"]
        assert len(graph.document()["states"]) == 2
        assert len(graph.document()["attempts"]) == 3
        assert all(a["status"] == "succeeded" for a in graph.document()["attempts"].values())


def test_manual_source_change_is_checked_before_input(fake, pilot_device, tmp_path):
    session, graph, taps = setup(fake, pilot_device, tmp_path)
    with graph:
        initial = session.observe()
        key = initial["candidates"][-1]
        session.approve(key, reason="operator")
        fake.devices.snapshot = frame("Preferences", "home")
        with pytest.raises(PilotStopped, match="source changed"):
            session.step(key)
        assert not taps and not graph.document()["attempts"]


def test_stale_approval_is_withdrawn_so_the_current_screen_can_step(fake, pilot_device, tmp_path):
    session, graph, taps = setup(fake, pilot_device, tmp_path)
    with graph:
        stale = session.observe()["candidates"][-1]
        session.approve(stale, reason="operator")
        fake.devices.snapshot = frame("Preferences", "home")  # the screen changed before input
        current = session.observe()["candidates"][-1]
        session.approve(current, reason="operator")
        with pytest.raises(ValueError, match="not selected"):
            session.step(current)  # the stale approval is still first in the scheduler's order
        session.withdraw(stale)
        assert graph.document()["actions"][stale] | {"approval": None, "status": "blocked"} == graph.document()["actions"][stale]
        session.step(current)
        assert taps == ["home"]
        with pytest.raises(ValueError, match="withdrawn"):
            session.withdraw(current)  # attempted: its approval is evidence now


@pytest.mark.parametrize("error", [pb.ERR_NOT_FOUND, pb.ERR_AMBIGUOUS, pb.ERR_INDETERMINATE])
def test_failed_input_stops_without_retry(fake, pilot_device, tmp_path, error):
    session, graph, taps = setup(fake, pilot_device, tmp_path, error=error)
    with graph:
        key = session.observe()["candidates"][-1]
        session.approve(key, reason="operator")
        with pytest.raises(CommandError):
            session.step(key)
        with pytest.raises(PilotStopped):
            session.step(key)
        assert taps == ["settings"]
        status = next(iter(graph.document()["attempts"].values()))["status"]
        assert status == ("indeterminate" if error == pb.ERR_INDETERMINATE else "failed_before_input")


def test_old_graph_cannot_be_resumed(fake, pilot_device, tmp_path):
    session, graph, _ = setup(fake, pilot_device, tmp_path)
    with graph:
        session.observe()
        with pytest.raises(ValueError, match="fresh"):
            DiscoverySession(pilot_device, graph, tmp_path / "other", package=PACKAGE)


def test_no_inferred_back_or_reset(fake, pilot_device, tmp_path):
    session, graph, taps = setup(fake, pilot_device, tmp_path)
    with graph:
        session.observe()
        with pytest.raises(PilotStopped, match="no observed route"):
            session.navigate("imagined")
        assert taps == []


def test_approved_list_row_text_tap_executes_with_its_own_selector(fake, pilot_device, tmp_path):
    from tap_e2e import text

    def habits():
        result = pb.ScreenSnapshotResponse(snapshot_id=1)
        result.nodes.add(ref="e1", depth=0, window_package=PACKAGE, class_name="androidx.recyclerview.widget.RecyclerView",
                         flags=[pb.FLAG_ENABLED, pb.FLAG_SCROLLABLE], interactive=True)
        result.nodes.add(ref="e2", depth=1, window_package=PACKAGE, class_name="android.widget.FrameLayout",
                         flags=[pb.FLAG_ENABLED])
        result.nodes.add(ref="e3", depth=2, window_package=PACKAGE, class_name="android.widget.TextView",
                         text="Read", flags=[pb.FLAG_ENABLED], selector=text("Read").to_proto())
        return result

    fake.devices.snapshot = habits()
    sent = []

    def responder(command):
        if command.WhichOneof("op") == "tap":
            sent.append(command.tap.selector)
            fake.devices.snapshot = frame("Read details", "back")
        return None

    fake.devices.responder = responder
    graph = GraphStore(tmp_path / "graph.db")
    graph.initialize({"package": PACKAGE, "serial": "fake"}, max_actions=20)
    session = DiscoverySession(pilot_device, graph, tmp_path / "observations", package=PACKAGE)
    with graph:
        first = session.observe()
        row = [key for key in first["candidates"]
               if graph.document()["actions"][key]["target"]["provenance"] == "list-row-text/1"
               and graph.document()["actions"][key]["verb"] == "tap"]
        assert len(row) == 1
        session.approve(row[0], reason="operator: open the first row")
        after = session.step(row[0])
        assert after["state"] != first["state"]
        assert len(sent) == 1 and "Read" in str(sent[0])


def test_a_screen_without_the_app_that_never_settles_is_captured_once(fake, pilot_device, tmp_path):
    session, graph, _ = setup(fake, pilot_device, tmp_path)
    foreign = pb.ScreenSnapshotResponse(snapshot_id=1)
    foreign.nodes.add(ref="e1", window_package="com.android.launcher", class_name="android.widget.FrameLayout",
                      flags=[pb.FLAG_ENABLED])
    fake.devices.snapshot = foreign
    waits = []

    def responder(command):
        if command.WhichOneof("op") == "wait_screen_stable":
            waits.append(command)
            return pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT))
        return None

    fake.devices.responder = responder
    session.captures = 3
    with graph, pytest.raises(PilotStopped):
        session.observe()
    assert len(waits) == 1  # The app left: retrying would wait the full settle timeout again.


@pytest.mark.parametrize("permission", ["deny", None])
def test_a_permission_dialog_an_input_opens_is_answered_and_recorded(fake, pilot_device, tmp_path, permission):
    session, graph, _ = setup(fake, pilot_device, tmp_path)
    session.permission = permission
    dialog = pb.ScreenSnapshotResponse(snapshot_id=1)
    dialog.nodes.add(ref="e1", window_package="com.android.permissioncontroller",
                     class_name="android.widget.FrameLayout", flags=[pb.FLAG_ENABLED])
    sent = []

    def responder(command):
        op = command.WhichOneof("op")
        if op == "tap":
            fake.devices.snapshot = dialog  # Opening the camera screen asks first.
        elif op == "wait_permission_prompt":
            if fake.devices.snapshot is not dialog:
                return pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT))
            return pb.CommandResult(permission_prompt=pb.PermissionPrompt(
                package_name="com.android.permissioncontroller",
                choices=[pb.PERMISSION_ALLOW_FOREGROUND_ONLY, pb.PERMISSION_ALLOW_ONE_TIME, pb.PERMISSION_DENY]))
        elif op == "choose_permission":
            sent.append(command.choose_permission.choice)
            fake.devices.snapshot = frame("Scan", "more_options")
            return pb.CommandResult(done=pb.Done())
        return None

    fake.devices.responder = responder
    with graph:
        first = session.observe()
        key = first["candidates"][-1]
        session.approve(key, reason="operator: open the scanner")
        if permission is None:
            with pytest.raises(PilotStopped, match="foreign"):
                session.step(key)
            assert sent == []
            return
        after = session.step(key)
        attempt = graph.document()["attempts"]["attempt-000001"]
    assert sent == [pb.PERMISSION_DENY]
    assert attempt["permissions"] == ["DENY"] and attempt["destination"] == after["state"] != first["state"]
    assert session.current == after["state"]
