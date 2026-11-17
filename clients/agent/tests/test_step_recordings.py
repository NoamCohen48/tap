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
from tap_agent.core import EXIT_USAGE
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


def test_the_log_names_each_step_and_reads_one_by_number_or_id(fake, agent):
    agent.attach("first")
    agent.tap("id=cart")
    agent.fill("id=card", "4111")
    first, second = (step["id"] for step in document(agent)["steps"])
    fake.device_log.entries = [
        pb.LogEntry(cursor=1, action=pb.LogAction(operation="tap", target="res=cart", request_id=1, step=first)),
        pb.LogEntry(cursor=2, action=pb.LogAction(operation="tap", finished=True, outcome="ok", duration_ms=5, request_id=1, step=first)),
        pb.LogEntry(cursor=3, action=pb.LogAction(operation="set_text", target="res=card", request_id=2, step=second)),
        pb.LogEntry(cursor=4, action=pb.LogAction(operation="launch", target="com.other")),
    ]
    lines = agent.log().splitlines()
    assert len(lines) == 7 and lines[-1] == "cursor 4"
    assert lines[0] == f"── step 1 tap  [{first}]" and lines[3] == f"── step 2 fill  [{second}]"
    agent.log(step="2")
    assert fake.device_log.requests[-1].step == second
    agent.log(step=first)
    assert fake.device_log.requests[-1].step == first
    with pytest.raises(AgentError, match="no step '9'"):
        agent.log(step="9")
    with pytest.raises(AgentError, match="only one of"):
        agent.log(step="1", tail=5)


def test_markers_still_bound_device_log_reads_and_are_stored_with_intent(fake, agent):
    agent.attach("first")
    agent.tap("id=pay")
    step = document(agent)["steps"][0]["id"]
    agent.log("mark", "checkout")
    agent.log("mark", "paid")
    marks = agent.records.marker_names()
    assert list(marks.values()) == [["checkout@1", "paid@2"]]
    line = pb.LogLine(uid=10123, priority=pb.LOG_PRIORITY_WARN, tag="Shop", message="paying")
    fake.device_log.entries = [pb.LogEntry(cursor=1, marker="checkout"),
        pb.LogEntry(cursor=2, action=pb.LogAction(operation="tap", step=step)),
        pb.LogEntry(cursor=3, line=line), pb.LogEntry(cursor=4, marker="paid"), pb.LogEntry(cursor=5, line=line)]
    saved = json.loads(agent.log(since="checkout", until="paid", as_json=True))
    assert saved["cursor"] == 4 and saved["dropped"] == 0
    assert saved["steps"] == {step: {"seq": 1, "action": "tap"}}
    assert [entry["kind"] for entry in saved["entries"]] == ["marker", "action", "line", "marker"]
    assert json.loads(cli.run(cli.parser().parse_args(["log", "--since", "checkout", "--until", "paid", "--json"]), agent)) == saved
    assert agent.log(since="@1").splitlines()[0].endswith(" ── marker checkout")
    with pytest.raises(AgentError, match="no marker at cursor 3"):
        agent.log(since="@3")
    with pytest.raises(AgentError, match="before where the read starts"):
        agent.log(since="@4", until="@1")
    with pytest.raises(AgentError, match="a --step read already ends"):
        agent.log(step="1", until="paid")
    with pytest.raises(AgentError, match="no read options"):
        agent.log("mark", "x", as_json=True)


def test_log_pagination_keeps_end_and_filters_and_driver_lines_remain_opt_in(fake, agent):
    agent.attach("first")
    agent.tap("id=save")
    fake.device_log.entries = [pb.LogEntry(cursor=1, marker="flow"),
        pb.LogEntry(cursor=2, line=pb.LogLine(uid=10247, priority=pb.LOG_PRIORITY_DEBUG, tag="UiDevice", packages=["io.github.noamcohen48.tap.driver"], message="Retrieving nodes")),
        *(pb.LogEntry(cursor=c, line=pb.LogLine(uid=10123, priority=pb.LOG_PRIORITY_INFO, tag="Shop", packages=["com.shop"], message=f"line {c}")) for c in range(3, 8)),
        pb.LogEntry(cursor=8, marker="done")]
    lines = agent.log(since="flow", until="done", packages=["com.shop"], grep="line .", limit=3).splitlines()
    assert lines[-2:] == ["(4 more entries after this; read on with: log --after 4 --until @8 --package com.shop --grep 'line .')", "cursor 4"]
    assert not any("Retrieving nodes" in line for line in lines)
    page = json.loads(agent.log(since="flow", limit=2, as_json=True))
    assert [(entry["cursor"], entry["kind"]) for entry in page["entries"]] == [(1, "marker"), (3, "line")]
    assert page["next"] == "--after 3"
    assert [entry["cursor"] for entry in json.loads(agent.log(since="flow", limit=2, as_json=True, driver_lines=True))["entries"]] == [1, 2]


def test_marker_names_are_quoted_and_unknown_markers_are_usage_errors(fake, agent):
    agent.attach("first")
    assert agent.log("mark", "with space") == "marked 'with space' at cursor 1; read from it with --since 'with space' or --since @1"
    with pytest.raises(AgentError) as refused:
        agent.log(since="never")
    assert refused.value.exit_code == EXIT_USAGE
    assert "no marker 'never' in this device's log" in str(refused.value)


def test_an_until_marker_before_the_start_says_so():
    from types import SimpleNamespace as Entry

    from tap_agent.core import _until
    entries = [Entry(cursor=5, marker=None), Entry(cursor=6, marker="paid"), Entry(cursor=7, marker=None)]
    assert [entry.cursor for entry in _until(entries, "paid", [])] == [5, 6]
    assert [entry.cursor for entry in _until(entries, 6, [])] == [5, 6]
    with pytest.raises(AgentError, match="before where the read starts"):
        _until(entries, "start", [{"name": "start", "cursor": 2}])
    with pytest.raises(AgentError, match="no marker 'never'"):
        _until(entries, "never", [])


def test_driver_log_and_failure_evidence_keep_exclusive_private_files(fake, agent, tmp_path):
    agent.attach("first")
    driver = pathlib.Path(agent.log("driver"))
    assert driver.parent == agent.out_dir and driver.suffix == ".txt" and driver.stat().st_mode & 0o777 == 0o600
    evidence = pathlib.Path(agent.log("evidence", out=tmp_path / "evidence.json"))
    assert isinstance(json.loads(evidence.read_text()), dict)
    with pytest.raises(AgentError, match="already exists"):
        agent.log("evidence", out=evidence)
    with pytest.raises(AgentError, match="only --out"):
        agent.log("driver", tail=5)
    assert not agent.records.list()


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
