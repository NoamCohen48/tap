"""The agent's own steps: what it records of each command, and how `history` shows them."""
# pyright: reportMissingImports=false
from __future__ import annotations

import contextlib
import inspect
import json
import pathlib
import uuid

import pytest
from tap_e2e import Device, FailureReason, ServerError, TapClient, TapError, res
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.records import RecordError
from tap_agent.targets import UsageError
from tap_agent.step_capture import ACTIONS, CONDITIONAL, EXCLUDED, WAITS


def document(agent):
    return json.loads(agent.history(as_json=True))


NOTHING = "nothing happened in this range (or none of it is kept)"


def _statuses(agent):
    return [step["outcome"]["status"] for step in document(agent)["steps"]]


def test_every_public_agent_method_has_an_explicit_recording_policy():
    actual = {name for name, member in inspect.getmembers(Agent) if not name.startswith("_") and inspect.isroutine(member)}
    assert actual == ACTIONS | WAITS | CONDITIONAL | EXCLUDED
    assert not (ACTIONS & WAITS or ACTIONS & CONDITIONAL or EXCLUDED & (ACTIONS | WAITS | CONDITIONAL))
    assert "export" not in actual


def test_an_interrupted_step_is_kept_and_does_not_block_later_input(fake, agent, monkeypatch):
    agent.attach("first")
    def interrupt(*args, **kwargs):
        raise KeyboardInterrupt
    with monkeypatch.context() as patch:
        patch.setattr(agent, "_element", interrupt)
        with pytest.raises(KeyboardInterrupt):
            agent.tap("id=save")
    agent.key("back")
    assert len(fake.devices.commands) == 1
    agent.release()
    assert _statuses(agent) == ["interrupted", "succeeded"]


def test_a_session_records_actions_and_waits_not_inspections_and_resolves_refs(fake, agent):
    assert agent.history() == NOTHING
    agent.attach("first")
    assert agent.history() == NOTHING
    fake.devices.refs["e1"] = res("save").to_proto()
    agent.tap("@e1")
    agent.snapshot()
    agent.exists("id=save")
    agent.count("id=save")
    agent.wait("id=save", state="count", value=0, timeout=.1)
    saved = document(agent)
    assert saved["format"] == "tap-history/2"
    assert [s["action"] for s in saved["steps"]] == ["tap", "wait"]
    assert [s["kind"] for s in saved["steps"]] == ["action", "wait"]
    assert all(s["serial"] == "first" and s["session"] == "agent" for s in saved["steps"])
    assert saved["steps"][0]["targets"]["target"]["node"]["resource"]["name"] == "save"
    assert "@e1" not in json.dumps(saved)
    ids = [s["id"] for s in saved["steps"]]
    assert len(set(ids)) == 2 and all(len(identifier) == 16 for identifier in ids)
    calls = list(zip(fake.devices.commands, fake.devices.steps, strict=True))
    assert [step for call, step in calls if call.WhichOneof("op") == "tap"] == [ids[0]]
    assert [step for call, step in calls if call.WhichOneof("op") == "exists"] == [None]
    counts = [step for call, step in calls if call.WhichOneof("op") == "count"]
    assert counts[0] is None and counts[1:] and set(counts[1:]) == {ids[1]}
    assert all(step is None for call, step in calls if call.WhichOneof("op") == "read_toast_history")
    agent.release()
    assert _statuses(agent) == ["succeeded", "succeeded"]
    text = agent.history()
    assert "step 1: tap" in text and "step 2: wait" in text


def test_history_selects_one_stable_global_step_by_cursor_or_id(fake, agent):
    agent.attach("first")
    agent.tap("id=cart")
    agent.fill("id=card", "4111")
    first, second = document(agent)["steps"]
    assert json.loads(agent.history(step=str(second["cursor"]), as_json=True))["steps"][0]["id"] == second["id"]
    assert json.loads(agent.history(step=first["id"], as_json=True))["steps"][0]["id"] == first["id"]
    with pytest.raises(AgentError, match="not retained"):
        agent.history(step="999999")
    with pytest.raises(AgentError, match="own end"):
        agent.history(step=first["id"], until="2026-01-01T00:00:00Z")
    request = fake.history.requests[-1]
    assert request.step == first["id"] and list(request.selection.connection_ids) == [first["connection_id"]]


def test_markers_bound_history_and_are_stored_with_original_attachment(fake, agent):
    agent.attach("first")
    agent.tap("id=pay")
    agent.history("mark", "checkout")
    agent.history("mark", "paid")
    assert list(agent.records.marker_names().values()) == [["checkout@1", "paid@2"]]
    markers = agent.records.marker_anchor("checkout")
    assert markers[0]["attached_device_id"] == "attached-first"
    saved = json.loads(agent.history(since_marker="checkout", until_marker="paid", logs=True, as_json=True))
    request = fake.history.requests[-1]
    assert request.from_marker == "checkout" and request.until_marker == "paid"  # the server resolves markers
    assert [e.get("marker") for e in saved["entries"]] == ["checkout", "paid"]
    args = cli.parser().parse_args(["history", "--since-marker", "checkout", "--until-marker", "paid", "--logs", "--json"])
    assert json.loads(cli.run(args, agent))["entries"] == saved["entries"]
    with pytest.raises(AgentError, match="own end"):
        agent.history(step="1", until_marker="paid")


def test_one_limit_pages_the_merged_timeline_and_log_filters_imply_logs(fake, agent):
    agent.attach("first")
    fake.history.logs["attached-first"] = [
        pb.LogEntry(cursor=c, host_time_ms=1_790_000_000_000 + c, line=pb.LogLine(tag="Shop", message=f"line {c}"))
        for c in range(1, 6)]
    newest = json.loads(agent.history(logs=True, limit=2, as_json=True))
    assert [e["line"]["message"] for e in newest["entries"]] == ["line 4", "line 5"]  # the newest, oldest first
    assert newest["next"].startswith("bl:") and fake.history.requests[-1].newest_first
    text = agent.history(logs=True, limit=2)
    assert text.splitlines()[-1] == f"older: --after {newest['next']}"
    older = json.loads(agent.history(after=newest["next"], limit=2, as_json=True))
    assert [e["line"]["message"] for e in older["entries"]] == ["line 2", "line 3"]
    forward = json.loads(agent.history(start="2026-01-01T00:00:00Z", tags=["Shop"], limit=3, as_json=True))
    request = fake.history.requests[-1]
    assert request.include_logs and list(request.log_filter.tags) == ["Shop"] and not request.newest_first
    assert [e["line"]["message"] for e in forward["entries"]] == ["line 1", "line 2", "line 3"] and forward["next"].startswith("fl:")
    agent.history(driver_lines=True)
    assert fake.history.requests[-1].log_filter.include_driver_lines
    with pytest.raises(AgentError, match="--after continues"):
        agent.history(after=newest["next"], tags=["Shop"])


def test_marker_names_remain_data_and_missing_markers_are_explicit(fake, agent):
    agent.attach("first")
    assert "'with space'" in agent.history("mark", "with space")
    assert agent.records.marker_anchor("with space")[0]["name"] == "with space"
    with pytest.raises(AgentError, match="marker"):
        agent.history(since_marker="never")


def test_an_until_marker_before_the_start_is_refused_not_read_as_an_open_range(fake, agent):
    agent.attach("first")
    fake.history.logs["attached-first"] = [pb.LogEntry(cursor=1, host_time_ms=1, marker="early"),
        pb.LogEntry(cursor=2, host_time_ms=2, marker="late")]
    with pytest.raises(AgentError, match="older than the from marker"):
        agent.history(since_marker="late", until_marker="early")


def test_capture_indexes_private_files_without_a_second_evidence_command(fake, agent, tmp_path):
    agent.attach("first")
    directory = tmp_path / "capture"
    paths = agent.capture(out=directory).splitlines()[1:]
    assert paths and all(pathlib.Path(path).stat().st_mode & 0o777 == 0o600 for path in paths)
    saved = document(agent)["artifacts"]
    assert len(saved) == 1 and saved[0]["serial"] == "first"
    assert saved[0]["directory"] == str(directory)
    assert f" saved to {directory}" in agent.history() and "  CAPTURE " in agent.history()
    with pytest.raises(AgentError):
        agent.capture(out=directory)
    assert not hasattr(agent, "log")


def test_settle_timeouts_are_explicit_without_recording_internal_calls(fake, agent):
    agent.attach("first")
    def respond(command):
        if command.WhichOneof("op") == "device_info":
            return pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.example"))
        if command.WhichOneof("op") == "wait_screen_stable":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT))
    fake.devices.responder = respond
    agent.tap("id=save", settle=True)
    agent.settle()
    assert _statuses(agent) == ["post_settle_timeout", "wait_timeout"]


@pytest.mark.parametrize("code", ["INDETERMINATE", "STALE_DURING_COMMAND", "ACTION_REJECTED", "AUT_CRASHED", "INTERNAL"])
def test_uncertain_mutation_outcomes_remain_unknown_and_are_not_replayed(fake, agent, code):
    agent.attach("first")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=getattr(pb, "ERR_" + code)))
    with pytest.raises(AgentError):
        agent.tap("id=save")
    assert len(fake.devices.commands) == 1
    fake.devices.responder = None
    agent.key("back")
    assert len(fake.devices.commands) == 2
    assert _statuses(agent) == ["unknown", "succeeded"]


@pytest.mark.parametrize("code", ["NOT_FOUND", "AMBIGUOUS", "NOT_INTERACTABLE", "STALE_BEFORE_INPUT", "TRANSPORT_LOST", "DEADLINE_EXCEEDED"])
def test_refusal_before_input_is_failed_and_capture_continues(fake, agent, code):
    agent.attach("first")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=getattr(pb, "ERR_" + code)))
    with pytest.raises(AgentError):
        agent.tap("id=save")
    fake.devices.responder = None
    agent.key("back")
    agent.release()
    saved = document(agent)
    assert _statuses(agent) == ["failed", "succeeded"]
    assert saved["steps"][0]["outcome"]["error"] == code


def test_driver_loss_refusals_and_invalid_arguments_are_preserved_before_input(fake, agent):
    import grpc
    agent.attach("first")
    with pytest.raises(AgentError):
        agent.tap("id=save", count=0)
    with pytest.raises(AgentError):
        agent.key("back", device="other")
    fake.devices.execute_failure = (grpc.StatusCode.ABORTED, pb.FAILURE_REASON_SESSION_UNUSABLE, "driver lost")
    with pytest.raises(AgentError):
        agent.key("back")
    with pytest.raises(AgentError):
        agent.wait("text=Done", timeout=.1)
    assert _statuses(agent) == ["rejected_before_input", "rejected_before_input", "failed", "failed"]
    assert not fake.devices.commands


def test_compound_input_and_post_input_read_failures_remain_unknown(fake, agent):
    agent.attach("first")
    def type_fails(command):
        if command.WhichOneof("op") == "type_text":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
    fake.devices.responder = type_fails
    with pytest.raises(AgentError):
        agent.type("hello", target="id=input", await_focus=False)
    def read_fails(command):
        if command.WhichOneof("op") == "device_info":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_TRANSPORT_LOST))
    fake.devices.responder = read_fails
    with pytest.raises(AgentError):
        agent.rotate("landscape")
    assert _statuses(agent) == ["unknown", "unknown"]


@pytest.mark.parametrize(("reason", "status"), [(FailureReason.UNSUPPORTED_API, "failed"),
    (FailureReason.INVALID_ARGUMENT, "failed"), (FailureReason.DEVICE_SETTING, "unknown"), (FailureReason.UNSPECIFIED, "unknown")])
def test_server_refusal_statuses_preserve_mutation_uncertainty(fake, agent, monkeypatch, reason, status):
    agent.attach("first")
    def refuse(self, enabled):
        raise ServerError("FAILED_PRECONDITION", "refused", reason)
    monkeypatch.setattr(Device, "set_dark_mode", refuse)
    with pytest.raises(AgentError):
        agent.condition("dark-mode", "on")
    assert _statuses(agent) == [status]


ACTION_CALLS = [
    ("tap", (("id=save",), {})), ("tap", (("id=save",), {"count": 2})), ("tap", (("id=save",), {"long": True})),
    ("tap", (("id=save",), {"double": True})), ("fill", (("id=input", "x"), {})), ("clear", (("id=input",), {})),
    ("scroll", (("id=list", "down"), {})), ("swipe", (("id=list", "left"), {})), ("fling", (("id=list", "up"), {})),
    ("pinch", (("id=map", "open"), {})), ("submit", (("id=input",), {})), ("progress", (("id=bar", 3), {})),
    ("action", (("id=row", "expand"), {})), ("action", (("id=row", "Archive"), {"custom": True})),
    ("drag", (("id=a", "id=b"), {})), ("scroll_until", (("id=list", "id=row"), {})),
    ("type", (("x",), {})), ("type", (("x",), {"target": "id=input", "await_focus": False})),
    ("key", (("back",), {})), ("panel", (("notifications",), {})), ("panel", (("quick-settings",), {})),
    ("rotate", (("auto",), {})), ("rotate", (("landscape",), {})), ("rotate", (("left",), {})),
    ("screen", (("on",), {})), ("screen", (("off",), {})), ("screen", (("unlock",), {})),
    ("keyboard", (("hide",), {})), ("condition", (("animations", "off"), {})), ("location", ((1.0, 2.0), {})),
    ("clipboard", (("x",), {})), ("notification", (("open",), {"title": "t"})), ("notification", (("dismiss",), {"title": "t"})),
    ("permission", (("allow",), {})),
] + [("app", ((action, "com.example", argument), {})) for action, argument in [
    ("launch", None), ("cold-launch", None), ("foreground", None), ("background", None), ("open-link", "https://e.x"),
    ("stop", None), ("clear", None), ("uninstall", None), ("grant", "p"), ("revoke", "p"), ("locale", "fr"),
]] + [("app_data", (("delete", "com.example", "f"), {}))]


def test_every_recorded_action_marks_input_before_sending(fake, agent, monkeypatch, tmp_path):
    import tap_agent.step_capture as capture
    seen = []
    real = capture.outcome
    def spy(kind, failure, current):
        seen.append((kind, current.inputs))
        return real(kind, failure, current)
    monkeypatch.setattr(capture, "outcome", spy)
    local = tmp_path / "f"
    local.write_bytes(b"x")
    calls = ACTION_CALLS + [("push", ((local, "/sdcard/f"), {})), ("media", ((local,), {})),
        ("app_data", (("write", "com.example", "f", str(local)), {})), ("app", (("install", "com.example", str(local)), {}))]
    agent.attach("first")
    covered = set()
    for name, (args, kwargs) in calls:
        seen.clear()
        with contextlib.suppress(AgentError):
            getattr(agent, name)(*args, **kwargs)
        assert seen and seen[0][0] == "action" and seen[0][1] >= 1, (name, args, seen)
        covered.add(name)
    assert covered == ACTIONS | CONDITIONAL


def test_sensitive_input_selectors_and_exception_text_never_enter_history(fake, agent, monkeypatch):
    agent.attach("first")
    secret = "private-" + uuid.uuid4().hex
    agent.fill("text=" + secret, secret, sensitive=True)
    def fail(*args, **kwargs):
        raise AgentError("failure containing " + secret)
    monkeypatch.setattr(agent, "_element", fail)
    with pytest.raises(AgentError):
        agent.fill("id=input", secret, sensitive=True)
    agent.release()
    saved = document(agent)
    assert saved["steps"][0]["targets"]["target"] == {"redacted": True}
    assert saved["steps"][1]["outcome"] == {"status": "rejected_before_input", "error": "AgentError"}
    assert secret not in json.dumps(saved)
    assert all(secret.encode() not in path.read_bytes() for path in agent.records.directory.iterdir())


def test_storage_failures_do_not_replay_or_replace_the_action_outcome(fake, agent, monkeypatch):
    agent.attach("first")
    def fail(*args, **kwargs):
        raise RecordError("disk full")
    with monkeypatch.context() as patch:
        patch.setattr(agent.records, "finish", fail)
        result = agent.tap("id=save")
    assert result.startswith("tapped") and "do not repeat" in result
    assert len(fake.devices.commands) == 1
    saved = document(agent)
    assert saved["steps"][0]["outcome"]["status"] == "running"
    monkeypatch.setattr(agent.records, "begin", fail)
    with pytest.raises(AgentError, match="disk full"):
        agent.tap("id=again")
    assert len(fake.devices.commands) == 1


def test_separate_process_views_preserve_local_steps_when_daemon_is_unavailable(fake, agent, client, monkeypatch):
    agent.attach("first")
    other = Agent("agent", client=client, out_dir=agent.out_dir)
    other.tap("id=first")
    agent.key("back")
    before = document(agent)
    other.release()
    offline = Agent("agent", out_dir=agent.out_dir)
    def unavailable(*args, **kwargs):
        raise AgentError("daemon unavailable", 3)
    monkeypatch.setattr(TapClient, "create", unavailable)
    saved = document(offline)
    assert saved["steps"] == before["steps"]
    assert [issue["kind"] for issue in saved["issues"]] == ["unavailable"]
    text = offline.history()
    assert "step 1: tap" in text and "WARNING: unavailable: the server's history could not be read" in text


def test_bare_keyboard_and_screen_reads_are_not_recorded_as_input_steps():
    from tap_agent.step_capture import category
    for name in ("keyboard", "screen"):
        assert category(name, {"action": None}) is None
        assert category(name, {"action": " "}) is None
    assert category("keyboard", {"action": "hide"}) == "action"
    assert category("screen", {"action": "unlock"}) == "action"


def test_capture_saves_the_device_history_as_text_and_json(fake, agent, tmp_path):
    agent.attach("first")
    agent.tap("id=pay")
    agent.tap("id=confirm")
    paths = [pathlib.Path(p) for p in agent.capture(out=tmp_path / "bundle").splitlines()]
    names = [p.name for p in paths]
    assert "first.history.txt" in names and "first.history.json" in names
    text = (tmp_path / "bundle" / "first.history.txt").read_text()
    assert 'step 1: tap res("pay") → succeeded' in text and 'step 2: tap res("confirm")' in text
    saved = json.loads((tmp_path / "bundle" / "first.history.json").read_text())
    assert saved["format"] == "tap-history/2" and saved["next"] is None
    assert [s["action"] for s in saved["steps"]] == ["tap", "tap"]
    assert "first.history.json" in document(agent)["artifacts"][-1]["parts"]


def test_capture_history_merges_every_page_once(fake, agent):
    from tap_agent.history import complete
    agent.attach("first")
    for name in ("a", "b", "c"):
        agent.tap(f"id={name}")
    device = agent._device(None)
    whole_text, whole = complete(agent, device.owner_connection.id, "first")
    paged_text, paged = complete(agent, device.owner_connection.id, "first", limit=1)
    assert [s["id"] for s in paged["steps"]] == [s["id"] for s in whole["steps"]]
    assert len(paged["entries"]) == len(whole["entries"]) and paged["next"] is None
    assert paged_text == whole_text


def test_capture_history_bound_and_a_failed_read(fake, agent, tmp_path, monkeypatch):
    agent.attach("first")
    agent.tap("id=pay")
    agent.capture(out=tmp_path / "recent", last="5m")
    assert fake.history.requests[-1].from_time_ms > 0
    with pytest.raises(AgentError, match="not both"):
        agent.capture(out=tmp_path / "x", last="5m", start="2026-11-21T12:00:00Z")
    def lost(*args, **kwargs):
        raise TapError("history read lost")
    monkeypatch.setattr(type(agent.client.history), "read", lost)
    lines = agent.capture(out=tmp_path / "failed").splitlines()
    assert any(line.startswith("history failed: ") and "history read lost" in line for line in lines)
    assert not any(line.endswith(".history.txt") for line in lines)
    assert any(line.endswith(".screenshot.png") for line in lines)


def test_captures_are_named_listed_shown_and_cleaned(fake, agent, tmp_path):
    agent.attach("first")
    assert agent.capture(name="checkout", out=tmp_path / "a").startswith("capture checkout saved to ")
    with pytest.raises(AgentError, match="a capture named 'checkout' exists"):
        agent.capture(name="checkout", out=tmp_path / "b")
    assert not (tmp_path / "b").exists()
    other = agent.capture(out=tmp_path / "c").splitlines()[0].split()[1]  # an unnamed one: its id
    listed = agent.capture("list").splitlines()
    assert [line.split()[1] for line in listed] == [other, "checkout"]  # newest first
    assert "id=" in agent.capture("list", long=True) and "parts=" in agent.capture("list", long=True)
    entries = json.loads(agent.capture("list", as_json=True))
    assert entries[1]["name"] == "checkout" and entries[1]["bytes"] > 0
    shown = agent.capture("show", "checkout")
    assert f"directory {tmp_path / 'a'}" in shown and "first.history.json" in shown
    assert json.loads(agent.capture("show", other, as_json=True))["directory"] == str(tmp_path / "c")
    assert "CAPTURE checkout saved to" in agent.history()

    (tmp_path / "a" / "notes.txt").write_text("mine")
    refused = agent.capture("clean", "checkout")
    assert "not cleaned" in refused and "notes.txt" in refused and (tmp_path / "a" / "first.history.txt").exists()
    (tmp_path / "a" / "notes.txt").unlink()
    assert agent.capture("clean", "checkout") == f"cleaned capture checkout ({tmp_path / 'a'})"
    assert not (tmp_path / "a").exists()
    assert "cleaned " in agent.capture("list").splitlines()[1]
    assert "(cleaned " in agent.history()
    assert agent.capture(name="checkout", out=tmp_path / "d").startswith("capture checkout")  # the name is free again
    assert agent.capture("clean", all_captures=True).count("cleaned capture") == 2
    assert agent.capture("clean", older_than="1h") == "no captures to clean"


@pytest.mark.parametrize("call", [
    lambda a: a.capture("list", "x"), lambda a: a.capture("show"), lambda a: a.capture("clean"),
    lambda a: a.capture("clean", "x", all_captures=True), lambda a: a.capture(long=True),
    lambda a: a.capture("clean", "x", as_json=True), lambda a: a.capture("list", out="o"),
    lambda a: a.capture(name="list"), lambda a: a.capture(name="bad name"),
    lambda a: a.capture("login"), lambda a: a.capture("list", last="1h"), lambda a: a.capture(newer_than="1h"),
    lambda a: a.capture("show", "x", since="2026-11-21T12:00:00Z"), lambda a: a.capture("list", newer_than="1h", since="2026-11-21T12:00:00Z"),
])
def test_capture_management_refuses_mixed_forms(fake, agent, call):
    agent.attach("first")
    with pytest.raises(AgentError):
        call(agent)


def test_capture_cli_names_a_bundle_with_name_and_lists_with_filters_of_its_own(fake, agent, tmp_path):
    agent.attach("first")
    parser = cli.parser()
    out = cli.run(parser.parse_args(["capture", "--name", "login", "-o", str(tmp_path / "x")]), agent)
    assert out.startswith("capture login saved to ")
    assert cli.run(parser.parse_args(["capture", "list"]), agent).split()[1] == "login"
    assert cli.run(parser.parse_args(["capture", "list", "--newer-than", "1h"]), agent).split()[1] == "login"
    assert cli.run(parser.parse_args(["capture", "list", "--since", "2999-01-01T00:00:00Z"]), agent) == "no captures"
    assert "directory " in cli.run(parser.parse_args(["capture", "show", "login"]), agent)
    with pytest.raises(SystemExit):
        parser.parse_args(["capture", "login"])  # a name is --name: the word slot holds list, show or clean
    with pytest.raises(UsageError):
        cli.run(parser.parse_args(["capture", "show", "login", "--name", "x"]), agent)
    with pytest.raises(SystemExit):
        parser.parse_args(["capture", "list", "--newer-than", "1h", "--since", "2026-11-21T12:00:00Z"])


def test_capture_saves_the_snapshot_outline_and_survives_a_failed_one(fake, agent, tmp_path, monkeypatch):
    agent.attach("first")
    fake.devices.snapshot = pb.ScreenSnapshotResponse(nodes=[pb.ScreenNode(
        ref="e3", window_package="com.example", text="Pay", interactive=True, flags=[pb.FLAG_ENABLED, pb.FLAG_CLICKABLE])])
    agent.capture(out=tmp_path / "bundle")
    outline = (tmp_path / "bundle" / "first.snapshot.txt").read_text()
    assert '@e3  "Pay"  accepts=tap' in outline
    assert "first.snapshot.txt" in document(agent)["artifacts"][-1]["parts"]
    def lost(*args, **kwargs):
        raise TapError("snapshot lost")
    monkeypatch.setattr(type(agent._device(None)), "screen_snapshot", lost)
    lines = agent.capture(out=tmp_path / "failed").splitlines()
    assert any(line.startswith("snapshot failed: ") for line in lines)
    assert any(line.endswith(".history.txt") for line in lines)
