"""Automatic intent fidelity and log diagnostics, without replay or legacy export commands."""
# pyright: reportMissingImports=false
from __future__ import annotations

import contextlib
import inspect
import json
import pathlib
import uuid

import pytest
from tap_e2e import Device, FailureReason, ServerError, TapClient, res
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.records import RecordError
from tap_agent.step_capture import ACTIONS, CONDITIONAL, EXCLUDED, WAITS


def document(agent):
    return json.loads(agent.history(as_json=True))


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
    saved = document(agent)
    assert not saved["complete"]
    assert [step["outcome"]["status"] for step in saved["steps"]] == ["interrupted", "succeeded"]


def test_a_session_records_actions_and_waits_not_inspections_and_resolves_refs(fake, agent):
    assert agent.history() == "no retained activity"
    agent.attach("first")
    assert agent.history() == "no retained activity"
    fake.devices.refs["e1"] = res("save").to_proto()
    agent.tap("@e1")
    agent.snapshot()
    agent.exists("id=save")
    agent.count("id=save")
    agent.wait("id=save", state="count", value=0, timeout=.1)
    saved = document(agent)
    assert not saved["complete"]
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
    assert document(agent)["complete"]
    assert "tap" in agent.history() and "wait" in agent.history()


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
        agent.history(step=first["id"], until=999999)


def test_markers_bound_history_and_are_stored_with_original_attachment(fake, agent):
    agent.attach("first")
    agent.tap("id=pay")
    agent.history("mark", "checkout")
    agent.history("mark", "paid")
    assert list(agent.records.marker_names().values()) == [["checkout@1", "paid@2"]]
    markers = agent.records.marker_anchor("checkout")
    assert markers[0]["attached_device_id"] == "attached-first"
    saved = json.loads(agent.history(since_marker="checkout", until_marker="paid", as_json=True))
    assert saved["range"]["from_time"] == 0
    assert saved["range"]["until_time"] == .001
    args = cli.parser().parse_args(["history", "--since-marker", "checkout", "--until-marker", "paid", "--json"])
    assert json.loads(cli.run(args, agent)) == saved
    with pytest.raises(AgentError, match="own end"):
        agent.history(step="1", until_marker="paid")


def test_history_intent_limit_does_not_limit_logs_and_driver_lines_remain_opt_in(fake, agent):
    agent.attach("first")
    owner = next(iter(fake.connections.live))
    fake.device_log.archives.append(pb.RetainedLog(client_connection_id=owner, attached_device_id="attached-first", serial="first"))
    agent.tap("id=save")
    fake.device_log.entries = [pb.LogEntry(cursor=1, marker="flow"),
        pb.LogEntry(cursor=2, line=pb.LogLine(packages=["io.github.noamcohen48.tap.driver"], message="Retrieving nodes")),
        *(pb.LogEntry(cursor=c, line=pb.LogLine(packages=["com.shop"], message=f"line {c}")) for c in range(3, 8))]
    assert "Retrieving nodes" not in agent.history(logs=True, limit=1)
    assert "Retrieving nodes" in agent.history(driver_lines=True, limit=1)
    saved = json.loads(agent.history(logs=True, limit=1, as_json=True))
    assert len(saved["log"]["first"]["entries"]) == 6
    tail = json.loads(agent.history(tail=2, as_json=True))
    assert [e["cursor"] for e in tail["log"]["first"]["entries"]] == [6, 7]
    assert tail["log_omitted"] == 4


def test_marker_names_remain_data_and_missing_markers_are_explicit(fake, agent):
    agent.attach("first")
    assert "'with space'" in agent.history("mark", "with space")
    assert agent.records.marker_anchor("with space")[0]["name"] == "with space"
    with pytest.raises(AgentError, match="no retained marker"):
        agent.history(since_marker="never")


def test_an_until_marker_before_the_start_is_not_an_invented_open_range(fake, agent):
    agent.attach("first")
    record = agent.records.ensure(agent.session, next(iter(fake.connections.live)))
    agent.records.mark(record, "early", "first", 1, batch_id="early", attached_device_id="attached-first", at="2026-01-01T00:00:00.000Z")
    agent.records.mark(record, "late", "first", 2, batch_id="late", attached_device_id="attached-first", at="2026-01-02T00:00:00.000Z")
    with pytest.raises(AgentError, match="must not precede"):
        agent.history(since_marker="late", until_marker="early")


def test_capture_indexes_private_files_without_a_second_evidence_command(fake, agent, tmp_path):
    agent.attach("first")
    directory = tmp_path / "capture"
    paths = agent.capture(out=directory).splitlines()
    assert paths and all(pathlib.Path(path).stat().st_mode & 0o777 == 0o600 for path in paths)
    saved = document(agent)["artifacts"]
    assert len(saved) == 1 and saved[0]["serial"] == "first"
    assert saved[0]["directory"] == str(directory)
    assert "current state requested" in agent.history()
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
    assert _statuses(agent) == ["unknown", "succeeded"] and not document(agent)["complete"]


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
    assert _statuses(agent) == ["failed", "succeeded"] and saved["complete"]
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
    assert saved["complete"]
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
    assert not saved["complete"] and saved["steps"][0]["outcome"]["status"] == "running"
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
    assert not saved["complete"] and saved["events_unavailable"]
    assert "tap" in offline.history() and "WARNING" in offline.history()
