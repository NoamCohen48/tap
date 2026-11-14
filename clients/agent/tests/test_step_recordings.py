"""Every session's step history across CLI/MCP invocations, without replaying SDK internals."""
# pyright: reportMissingImports=false
from __future__ import annotations

import contextlib
import inspect
import json
import pathlib
import uuid

import pytest
from mcp import Client
from mcp_types import TextContent
from tap_e2e import Device, FailureReason, ServerError, TapClient, res
from tap_e2e import _gen as pb

from tap_agent import Agent, AgentError, cli
from tap_agent.mcp_server import create_server
from tap_agent.records import RecordError
from tap_agent.step_capture import ACTIONS, CONDITIONAL, EXCLUDED, WAITS


def test_every_public_agent_method_has_an_explicit_recording_policy():
    actual = {name for name, member in inspect.getmembers(Agent) if not name.startswith("_") and inspect.isroutine(member)}
    assert actual == ACTIONS | WAITS | CONDITIONAL | EXCLUDED
    assert not (ACTIONS & WAITS or ACTIONS & CONDITIONAL or EXCLUDED & (ACTIONS | WAITS | CONDITIONAL))


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
    document = json.loads(agent.export())
    assert not document["complete"]
    assert [step["outcome"]["status"] for step in document["steps"]] == ["interrupted", "succeeded"]


def test_a_session_records_its_steps_from_the_first_one_and_each_runs_as_a_step(fake, agent):
    assert agent.history() == "no step histories"
    agent.attach("first")
    assert agent.history() == "no step histories"  # attaching is not a step
    agent.tap("id=save")
    agent.exists("id=save")  # a query: not a step, and it carries no step id
    agent.key("back")
    document = json.loads(agent.export())
    assert document["state"] == "recording" and document["session"] == "agent"
    ids = [step["id"] for step in document["steps"]]
    assert [step["action"] for step in document["steps"]] == ["tap", "key"]
    assert len(set(ids)) == 2 and all(len(identifier) == 16 for identifier in ids)
    assert fake.devices.steps == [ids[0], None, ids[1]]
    history = agent.history()
    assert history.startswith(f"{document['name']}  live  2 steps  started ") and history.endswith("  session 'agent'")


def test_a_marker_is_written_to_the_device_log_and_the_history_and_cuts_an_export(fake, agent):
    agent.attach("first")
    agent.tap("id=cart")
    assert agent.log("mark", "checkout") == "marked 'checkout' at cursor 1; read from it with --since checkout or --since @1"
    agent.tap("id=pay")
    agent.log("mark", "paid")
    agent.key("back")
    assert [request.name for request in fake.device_log.requests] == ["checkout", "paid"]
    whole = json.loads(agent.export())
    assert [(m["name"], m["after_seq"], m["serial"], m["cursor"]) for m in whole["markers"]] == [("checkout", 1, "first", 1), ("paid", 2, "first", 2)]
    between = json.loads(agent.export(since="checkout", until="paid"))
    assert [step["seq"] for step in between["steps"]] == [2]
    assert between["range"] == {"since": {"name": "checkout", "cursor": 1}, "until": {"name": "paid", "cursor": 2}}
    # A marker by its cursor: exactly that one, whatever its name.
    assert json.loads(agent.export(since="@1", until="@2"))["steps"] == between["steps"]
    with pytest.raises(AgentError, match="no marker at cursor 9"):
        agent.export(since="@9")
    with pytest.raises(AgentError, match="@CURSOR takes a cursor number"):
        agent.export(since="@x")
    with pytest.raises(AgentError, match="never"):
        agent.export(since="never")


def test_an_export_adds_the_device_log_and_the_servers_calls_while_the_session_lives(fake, agent):
    agent.attach("first")
    agent.tap("id=cart")
    agent.log("mark", "checkout")
    agent.tap("id=pay")
    first, second = (step["id"] for step in json.loads(agent.export())["steps"])
    fake.device_log.entries = [
        pb.LogEntry(cursor=1, action=pb.LogAction(operation="tap", request_id=1, step=first)),
        pb.LogEntry(cursor=2, marker="checkout"),
        pb.LogEntry(cursor=3, action=pb.LogAction(operation="tap", request_id=2, step=second)),
        pb.LogEntry(cursor=4, line=pb.LogLine(device_time_ms=1_794_608_507_879, uid=10123, pid=7, priority=pb.LOG_PRIORITY_WARN, tag="Shop", message="paying")),
    ]
    document = json.loads(agent.export(logs=True, calls=True))
    log = document["log"]["first"]["entries"]
    assert [next(key for key in ("line", "action", "marker", "gap") if key in entry) for entry in log] == ["action", "marker", "action", "line"]
    assert log[2]["action"]["step"] == second
    assert log[3]["line"] == {"priority": "W", "tag": "Shop", "message": "paying", "packages": [], "uid": 10123, "pid": 7, "tid": 0, "device_time": 1794608507.879}
    assert [event["step"] for event in document["calls"]["events"]] == [first, second]
    cut = json.loads(agent.export(since="checkout", logs=True, calls=True))
    assert fake.device_log.requests[-1].marker == "checkout"
    assert [step["id"] for step in cut["steps"]] == [second]
    assert [event["step"] for event in cut["calls"]["events"]] == [second]
    agent.release()
    with pytest.raises(AgentError, match="it was released"):
        agent.export(logs=True)
    assert len(json.loads(agent.export())["steps"]) == 2


def test_the_log_names_each_step_and_reads_one_by_number_or_id(fake, agent):
    agent.attach("first")
    agent.tap("id=cart")
    agent.fill("id=card", "4111")
    first, second = (step["id"] for step in json.loads(agent.export())["steps"])
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


def test_the_log_ends_at_a_marker_and_returns_its_entries_as_data(fake, agent):
    agent.attach("first")
    agent.tap("id=pay")
    (step,) = (item["id"] for item in json.loads(agent.export())["steps"])
    line = pb.LogLine(device_time_ms=1_794_608_507_879, uid=10123, pid=7, priority=pb.LOG_PRIORITY_WARN, tag="Shop", message="paying")
    fake.device_log.entries = [
        pb.LogEntry(cursor=1, marker="checkout"),
        pb.LogEntry(cursor=2, action=pb.LogAction(operation="tap", target="res=pay", request_id=1, step=step)),
        pb.LogEntry(cursor=3, line=line),
        pb.LogEntry(cursor=4, marker="paid"),
        pb.LogEntry(cursor=5, line=line),
    ]
    last, cursor = agent.log(since="checkout", until="paid").splitlines()[-2:]
    assert last.endswith(" ── marker paid") and cursor == "cursor 4"
    assert agent.log(since="checkout", until="@3").splitlines()[-1] == "cursor 3"
    request = fake.device_log.requests[-1]
    assert request.marker == "checkout" and not request.HasField("step")
    agent.log(until="paid")  # no start: everything kept, not just the newest entries
    assert fake.device_log.requests[-1].WhichOneof("from") is None
    document = json.loads(agent.log(since="checkout", until="paid", as_json=True))
    assert document["dropped"] == 0 and document["omitted"] == 0 and document["cursor"] == 4
    assert document["steps"] == {step: {"seq": 1, "action": "tap"}}
    assert [next(key for key in ("line", "action", "marker", "gap") if key in entry) for entry in document["entries"]] == ["marker", "action", "line", "marker"]
    assert document["entries"][1]["action"]["step"] == step and document["entries"][2]["line"]["message"] == "paying"
    newest = json.loads(agent.log(as_json=True, limit=2))
    assert [entry["cursor"] for entry in newest["entries"]] == [4, 5] and newest["omitted"] == 3
    assert json.loads(cli.run(cli.parser().parse_args(["log", "--since", "checkout", "--until", "paid", "--json"]), agent)) == document
    with pytest.raises(AgentError, match="no marker 'never'"):
        agent.log(until="never")
    with pytest.raises(AgentError, match="a --step read already ends"):
        agent.log(step="1", until="paid")
    with pytest.raises(AgentError, match="before where the read starts"):
        agent.log(since="@4", until="@2")
    with pytest.raises(AgentError, match="no read options"):
        agent.log("mark", "x", as_json=True)


def test_a_history_whose_session_ended_on_the_server_is_not_shown_as_live(fake, agent):
    agent.attach("first")
    agent.tap("id=save")
    fake.connections.live.clear()  # an idle timeout or a daemon restart: the connection is gone
    assert " stopped (connection ended)  1 step  started " in agent.history()
    document = json.loads(agent.export())
    assert (document["state"], document["stop_reason"]) == ("stopped", "connection ended")
    with pytest.raises(AgentError, match=r"its session ended \(connection ended\)"):
        agent.export(logs=True)


def test_an_export_narrows_its_log_like_log_and_never_overwrites_a_file(fake, agent, tmp_path):
    agent.attach("first")
    agent.tap("id=save")
    fake.device_log.entries = [pb.LogEntry(cursor=1, line=pb.LogLine(uid=10123, priority=pb.LOG_PRIORITY_ERROR, tag="Shop", message="boom", packages=["com.shop"]))]
    document = json.loads(agent.export(logs=True, packages=["com.shop"], priority="error", tags=["Shop"], grep="bo+m"))
    request = fake.device_log.requests[-1]
    assert (list(request.packages), request.min_priority, list(request.tags), request.message_regex) == (["com.shop"], pb.LOG_PRIORITY_ERROR, ["Shop"], "bo+m")
    log = document["log"]["first"]
    assert log["filters"] == {"packages": ["com.shop"], "min_priority": "error", "tags": ["Shop"], "grep": "bo+m"}
    assert log["entries"][0]["line"]["packages"] == ["com.shop"]
    with pytest.raises(AgentError, match="narrow the log of --logs"):
        agent.export(packages=["com.shop"])
    path = tmp_path / "history.json"
    agent.export(out=path)
    with pytest.raises(AgentError) as refused:
        agent.export(out=path)
    assert str(refused.value) == f"local output already exists; choose a new path: {path}"
    assert not list(tmp_path.glob(".*.partial"))


def test_an_until_marker_before_the_start_says_so():
    from types import SimpleNamespace as Entry

    from tap_agent.core import _until

    entries = [Entry(cursor=5, marker=None), Entry(cursor=6, marker="paid"), Entry(cursor=7, marker=None)]
    assert [entry.cursor for entry in _until(entries, "paid", [])] == [5, 6]
    assert [entry.cursor for entry in _until(entries, 6, [])] == [5, 6]
    # The session marked "start" at cursor 2, before this read began at 5.
    with pytest.raises(AgentError, match=r"the --until marker 'start' \(cursor 2\) is before where the read starts"):
        _until(entries, "start", [{"name": "start", "cursor": 2}])
    with pytest.raises(AgentError, match="no marker 'never'"):
        _until(entries, "never", [])


def test_the_driver_log_and_failure_evidence_are_saved_to_private_files(fake, agent, tmp_path):
    agent.attach("first")
    driver = pathlib.Path(agent.log("driver"))
    assert driver.parent == agent.out_dir and driver.suffix == ".txt" and driver.stat().st_mode & 0o777 == 0o600
    evidence = pathlib.Path(agent.log("evidence", out=tmp_path / "evidence.json"))
    assert evidence == tmp_path / "evidence.json" and isinstance(json.loads(evidence.read_text()), dict)
    with pytest.raises(AgentError, match="already exists"):
        agent.log("evidence", out=evidence)
    with pytest.raises(AgentError, match="only --out"):
        agent.log("driver", tail=5)
    with pytest.raises(AgentError, match="--out is only"):
        agent.log(out=tmp_path / "log.txt")
    assert not agent.records.list()  # none of these is a step


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
    steps = json.loads(agent.export())["steps"]
    assert [step["outcome"]["status"] for step in steps] == ["post_settle_timeout", "wait_timeout"]


@pytest.mark.parametrize("flag", ["false", 1, None])
def test_clean_requires_a_real_boolean_flag(agent, flag):
    with pytest.raises(AgentError, match="boolean"):
        agent.history("clean", all_histories=flag)
    with pytest.raises(RecordError, match="boolean"):
        agent.records.clean(None, agent.session, all_records=flag)


def _statuses(agent) -> list[str]:
    return [step["outcome"]["status"] for step in json.loads(agent.export())["steps"]]


@pytest.mark.parametrize("code", ["INDETERMINATE", "STALE_DURING_COMMAND", "ACTION_REJECTED", "AUT_CRASHED", "INTERNAL"])
def test_uncertain_mutation_outcomes_remain_unknown_and_are_not_replayed(fake, agent, code):
    agent.attach("first")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=getattr(pb, "ERR_" + code)))
    with pytest.raises(AgentError):
        agent.tap("id=save")
    assert len(fake.devices.commands) == 1
    fake.devices.responder = None
    agent.key("back")  # the unknown outcome is on record; it does not hold the session up
    assert len(fake.devices.commands) == 2
    assert _statuses(agent) == ["unknown", "succeeded"] and not json.loads(agent.export())["complete"]


@pytest.mark.parametrize("code", ["NOT_FOUND", "AMBIGUOUS", "NOT_INTERACTABLE", "STALE_BEFORE_INPUT", "TRANSPORT_LOST", "DEADLINE_EXCEEDED"])
def test_a_command_refused_before_input_is_failed_and_recording_continues(fake, agent, code):
    """The protocol contract marks these codes "may have mutated: no"."""
    agent.attach("first")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=getattr(pb, "ERR_" + code)))
    with pytest.raises(AgentError):
        agent.tap("id=save")
    fake.devices.responder = None
    agent.key("back")
    agent.release()
    assert _statuses(agent) == ["failed", "succeeded"]
    document = json.loads(agent.export())
    assert document["complete"]
    # The step keeps the driver's code, not the agent's wrapping of it.
    assert document["steps"][0]["outcome"]["error"] == code


def test_a_step_refused_because_the_driver_connection_was_lost_sent_nothing(fake, agent):
    import grpc

    agent.attach("first")
    fake.devices.execute_failure = (grpc.StatusCode.ABORTED, pb.FAILURE_REASON_SESSION_UNUSABLE, "Driver connection on first was lost")
    with pytest.raises(AgentError):
        agent.key("back")
    with pytest.raises(AgentError):
        agent.wait("text=Done", timeout=0.1)
    outcomes = [step["outcome"] for step in json.loads(agent.export())["steps"]]
    assert outcomes == [{"status": "failed", "error": "SESSION_UNUSABLE"}, {"status": "failed", "error": "SESSION_UNUSABLE"}]


def test_arguments_and_lookups_refused_before_any_input_are_not_failed_actions(fake, agent):
    agent.attach("first")
    with pytest.raises(AgentError, match="count"):
        agent.tap("id=save", count=0)
    with pytest.raises(AgentError, match="not attached"):
        agent.key("back", device="other")
    agent.screen(" STATE ")  # a query, whatever its spelling: not recorded
    assert _statuses(agent) == ["rejected_before_input", "rejected_before_input"]
    assert [command.WhichOneof("op") for command in fake.devices.commands] == ["device_info"]


def test_a_failure_after_input_was_sent_is_unknown_even_with_a_harmless_code(fake, agent):
    agent.attach("first")

    def type_fails(command):  # the tap of tap-then-type lands, then typing is refused
        if command.WhichOneof("op") == "type_text":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
        return None
    fake.devices.responder = type_fails
    with pytest.raises(AgentError):
        agent.type("hello", target="id=input", await_focus=False)


    def read_fails(command):  # the rotation lands, the read-back that follows fails
        if command.WhichOneof("op") == "device_info":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_TRANSPORT_LOST))
        return None
    fake.devices.responder = read_fails
    with pytest.raises(AgentError):
        agent.rotate("landscape")
    assert _statuses(agent) == ["unknown", "unknown"]


@pytest.mark.parametrize(("reason", "status"), [
    (FailureReason.UNSUPPORTED_API, "failed"),
    (FailureReason.INVALID_ARGUMENT, "failed"),
    (FailureReason.DEVICE_SETTING, "unknown"),
    (FailureReason.UNSPECIFIED, "unknown"),
])
def test_server_refusals_are_failed_only_for_reasons_that_precede_any_change(fake, agent, monkeypatch, reason, status):
    agent.attach("first")

    def refuse(self, enabled):
        raise ServerError("FAILED_PRECONDITION", "refused", reason)
    monkeypatch.setattr(Device, "set_dark_mode", refuse)
    with pytest.raises(AgentError):
        agent.condition("dark-mode", "on")
    assert _statuses(agent) == [status]


# One invocation per recorded action shape. Each must enter `sending()` before its device input,
# or a failure there would be recorded as rejected before input.
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


def test_every_recorded_action_marks_its_input(fake, agent, monkeypatch, tmp_path):
    import tap_agent.step_capture as step_capture

    seen: list[tuple[str, int]] = []
    real = step_capture.outcome

    def spy(kind, failure, capture):
        seen.append((kind, capture.inputs))
        return real(kind, failure, capture)
    monkeypatch.setattr(step_capture, "outcome", spy)
    local = tmp_path / "f"
    local.write_bytes(b"x")
    calls = ACTION_CALLS + [("push", ((local, "/sdcard/f"), {})), ("media", ((local,), {})),
                            ("app_data", (("write", "com.example", "f", str(local)), {})),
                            ("app", (("install", "com.example", str(local)), {}))]
    agent.attach("first")
    covered = set()
    for name, (args, kwargs) in calls:
        seen.clear()
        with contextlib.suppress(AgentError):
            getattr(agent, name)(*args, **kwargs)
        assert seen and seen[0][0] == "action" and seen[0][1] >= 1, (name, args, kwargs, seen)
        covered.add(name)
    assert covered == ACTIONS | CONDITIONAL


def test_steps_resolve_refs_ignore_queries_and_survive_release(fake, agent):
    agent.attach("first")
    fake.devices.refs["e1"] = res("save").to_proto()
    agent.tap("@e1")
    agent.snapshot()
    agent.exists("id=save")
    agent.count("id=save")
    agent.wait("id=save", state="count", value=0, timeout=.1)
    current = json.loads(agent.export())
    assert not current["complete"] and current["state"] == "recording"
    assert [s["action"] for s in current["steps"]] == ["tap", "wait"]
    assert [s["kind"] for s in current["steps"]] == ["action", "wait"]
    assert all(s["serial"] == "first" for s in current["steps"])
    assert current["steps"][0]["targets"]["target"]["node"]["resource"]["name"] == "save"
    assert "@e1" not in json.dumps(current)
    agent.release()
    archived = json.loads(agent.export())
    assert archived["complete"] and archived["stop_reason"] == "released"
    assert agent.history().startswith(f"{archived['name']}  stopped (released)  2 steps  started ")


def test_separate_agent_instances_share_a_history_and_export_it_offline(fake, agent, client, monkeypatch, tmp_path):
    agent.attach("first")
    other = Agent("agent", client=client, out_dir=agent.out_dir)
    other.tap("id=first")
    agent.key("back")
    shared = json.loads(agent.export())["name"]
    assert len(json.loads(agent.export(shared))["steps"]) == 2
    other.release()
    offline = Agent("agent", out_dir=agent.out_dir)
    monkeypatch.setattr(TapClient, "create", lambda *a, **kw: pytest.fail("local archive controls must not contact daemon"))
    path = tmp_path / "export.json"
    assert str(path) in offline.export(shared, path)
    assert json.loads(path.read_text())["complete"]
    assert shared in offline.history()
    with pytest.raises(AgentError):
        offline.export(shared, path)  # exclusive, never overwrite
    offline.history("clean", shared)
    assert offline.history() == "no step histories"


def test_sensitive_inputs_selectors_outputs_and_exception_text_are_not_persisted(fake, agent, monkeypatch):
    agent.attach("first")
    secret = "private-" + uuid.uuid4().hex
    agent.fill("text=" + secret, secret, sensitive=True)
    def fail(*args, **kwargs):
        raise AgentError("failure containing " + secret)
    monkeypatch.setattr(agent, "_element", fail)
    with pytest.raises(AgentError):
        agent.fill("id=input", secret, sensitive=True)
    agent.release()
    document = json.loads(agent.export())
    assert document["complete"]
    assert document["steps"][0]["targets"]["target"] == {"redacted": True}
    assert document["steps"][1]["outcome"] == {"status": "rejected_before_input", "error": "AgentError"}
    assert secret not in json.dumps(document)
    assert all(secret.encode() not in path.read_bytes() for path in agent.records.directory.iterdir())


def test_post_input_recording_failure_warns_without_replaying_or_losing_result(fake, agent, monkeypatch):
    agent.attach("first")
    def fail(*args, **kwargs):
        raise RecordError("disk full")
    monkeypatch.setattr(agent.records, "finish", fail)
    result = agent.tap("id=save")
    assert result.startswith("tapped") and "do not repeat" in result
    assert len(fake.devices.commands) == 1
    document = json.loads(agent.export())
    assert not document["complete"] and document["steps"][0]["outcome"]["status"] == "running"


def test_pre_input_recording_failure_prevents_input(fake, agent, monkeypatch):
    agent.attach("first")
    def fail(*args, **kwargs):
        raise RecordError("disk full")
    monkeypatch.setattr(agent.records, "begin", fail)
    with pytest.raises(AgentError, match="disk full"):
        agent.tap("id=save")
    assert not fake.devices.commands


def test_a_new_connection_of_the_session_starts_the_next_history(fake, agent):
    agent.attach("first")
    agent.tap("id=save")
    first = json.loads(agent.export())["name"]
    agent._connection().close()  # the hold ended without this agent's release
    agent.attach("first")
    agent.key("back")
    second = json.loads(agent.export())
    assert second["name"] != first and [step["action"] for step in second["steps"]] == ["key"]
    stopped = json.loads(agent.export(first))
    assert stopped["stop_reason"] == "connection ended" and [step["action"] for step in stopped["steps"]] == ["tap"]


def test_cli_exports_cuts_and_cleans_the_history(fake, agent, tmp_path):
    agent.attach("first")
    def run(arguments):
        return cli.run(cli.parser().parse_args(arguments), agent)
    run(["tap", "id=save"])
    run(["log", "mark", "saved"])
    run(["key", "back"])
    assert [step["action"] for step in json.loads(run(["export"]))["steps"]] == ["tap", "key"]
    assert [step["action"] for step in json.loads(run(["export", "--since", "saved"]))["steps"]] == ["key"]
    document = json.loads(run(["export", "--until", "saved", "--calls", "--logs"]))
    assert [event["step"] for event in document["calls"]["events"]] == [document["steps"][0]["id"]]
    assert [entry["marker"] for entry in document["log"]["first"]["entries"]] == ["saved"]
    run(["log", "--step", "2"])
    assert fake.device_log.requests[-1].step == json.loads(run(["export"]))["steps"][1]["id"]
    assert pathlib.Path(run(["log", "driver", "-o", str(tmp_path / "driver.txt")])) == tmp_path / "driver.txt"
    name = json.loads(run(["export"]))["name"]
    path = tmp_path / "record.json"
    assert "(2 steps)" in run(["export", name, "-o", str(path)])
    assert json.loads(path.read_text())["name"] == name
    assert run(["history"]).startswith(f"{name}  live  2 steps  started ")
    with pytest.raises(AgentError, match="live session"):
        run(["history", "clean", name])
    run(["release"])
    assert run(["history", "clean", "--all"]) == "cleaned 1 step history"
    assert run(["history", "list"]) == "no step histories"


@pytest.mark.anyio
async def test_mcp_and_cli_share_the_step_history(fake, agent):
    agent.attach("first")
    server = create_server(lambda _: agent, default_session="agent")
    async with Client(server, mode="legacy") as client:
        cli.run(cli.parser().parse_args(["tap", "id=save"]), agent)
        result = await client.call_tool("log", {"action": "mark", "value": "saved"})
        assert not result.is_error
        result = await client.call_tool("press_key", {"key": "back"})
        assert not result.is_error
        result = await client.call_tool("export", {"since": "saved", "calls": True})
        assert not result.is_error
        content = result.content[0]
        assert isinstance(content, TextContent)
        document = json.loads(content.text)
        assert [step["action"] for step in document["steps"]] == ["key"]
        assert [event["step"] for event in document["calls"]["events"]] == [document["steps"][0]["id"]]
        result = await client.call_tool("history", {})
        content = result.content[0]
        assert isinstance(content, TextContent)
        assert not result.is_error and "live  2 steps" in content.text


@pytest.mark.anyio
async def test_mcp_tools_that_write_local_files_are_not_marked_read_only(agent):
    server = create_server(lambda _: agent, default_session="agent")
    async with Client(server, mode="legacy") as client:
        tools = (await client.list_tools()).tools
    writers = {tool.name for tool in tools if {"path", "out"} & set(tool.input_schema.get("properties", {}))}
    assert {"export", "log", "screenshot", "capture"} <= writers
    for tool in tools:
        if tool.name in writers:
            assert tool.annotations is None or not tool.annotations.read_only_hint, tool.name
