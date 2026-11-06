"""Named high-level intent across CLI/MCP invocations, without replaying SDK internals."""
# pyright: reportMissingImports=false
from __future__ import annotations

import contextlib
import inspect
import json
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


def test_interrupted_step_requires_explicit_stop_before_more_recorded_input(fake, agent, monkeypatch):
    agent.attach("first")
    agent.record("start", "interrupted")
    def interrupt(*args, **kwargs):
        raise KeyboardInterrupt
    monkeypatch.setattr(agent, "_element", interrupt)
    with pytest.raises(KeyboardInterrupt):
        agent.tap("id=save")
    with pytest.raises(AgentError, match="unfinished step"):
        agent.key("back")
    assert not fake.devices.commands
    agent.record("stop")
    assert not json.loads(agent.export())["complete"]


def test_settle_timeouts_are_explicit_without_recording_internal_calls(fake, agent):
    agent.attach("first")
    agent.record("start", "settle-outcomes")
    def respond(command):
        if command.WhichOneof("op") == "device_info":
            return pb.CommandResult(device_info=pb.DeviceInfo(current_package="com.example"))
        if command.WhichOneof("op") == "wait_screen_stable":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_WAIT_TIMEOUT))
    fake.devices.responder = respond
    agent.tap("id=save", settle=True)
    agent.settle()
    agent.record("stop")
    steps = json.loads(agent.export())["steps"]
    assert [step["outcome"]["status"] for step in steps] == ["post_settle_timeout", "wait_timeout"]


@pytest.mark.parametrize("flag", ["false", 1, None])
def test_clean_requires_a_real_boolean_flag(agent, flag):
    with pytest.raises(AgentError, match="boolean"):
        agent.record("clean", all_records=flag)
    with pytest.raises(RecordError, match="boolean"):
        agent.records.clean(None, agent.session, all_records=flag)


def _statuses(agent) -> list[str]:
    return [step["outcome"]["status"] for step in json.loads(agent.export())["steps"]]


@pytest.mark.parametrize("code", ["INDETERMINATE", "STALE_DURING_COMMAND", "ACTION_REJECTED", "AUT_CRASHED", "INTERNAL"])
def test_uncertain_mutation_outcomes_remain_unknown_and_are_not_replayed(fake, agent, code):
    agent.attach("first")
    agent.record("start", "uncertain")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=getattr(pb, "ERR_" + code)))
    with pytest.raises(AgentError):
        agent.tap("id=save")
    assert len(fake.devices.commands) == 1
    with pytest.raises(AgentError, match="unfinished step"):
        agent.key("back")
    assert len(fake.devices.commands) == 1
    agent.record("stop")
    document = json.loads(agent.export())
    assert not document["complete"] and document["steps"][0]["outcome"]["status"] == "unknown"


@pytest.mark.parametrize("code", ["NOT_FOUND", "AMBIGUOUS", "NOT_INTERACTABLE", "STALE_BEFORE_INPUT", "TRANSPORT_LOST", "DEADLINE_EXCEEDED"])
def test_a_command_refused_before_input_is_failed_and_recording_continues(fake, agent, code):
    """The protocol contract marks these codes "may have mutated: no"."""
    agent.attach("first")
    agent.record("start", "refused")
    fake.devices.responder = lambda command: pb.CommandResult(error=pb.Error(code=getattr(pb, "ERR_" + code)))
    with pytest.raises(AgentError):
        agent.tap("id=save")
    fake.devices.responder = None
    agent.key("back")
    agent.record("stop")
    assert _statuses(agent) == ["failed", "succeeded"]
    assert json.loads(agent.export())["complete"]


def test_arguments_and_lookups_refused_before_any_input_are_not_failed_actions(fake, agent):
    agent.attach("first")
    agent.record("start", "rejected")
    with pytest.raises(AgentError, match="count"):
        agent.tap("id=save", count=0)
    with pytest.raises(AgentError, match="not attached"):
        agent.key("back", device="other")
    agent.screen(" STATE ")  # a query, whatever its spelling: not recorded
    agent.record("stop")
    assert _statuses(agent) == ["rejected_before_input", "rejected_before_input"]
    assert [command.WhichOneof("op") for command in fake.devices.commands] == ["device_info"]


def test_a_failure_after_input_was_sent_is_unknown_even_with_a_harmless_code(fake, agent):
    agent.attach("first")
    agent.record("start", "after-input")

    def type_fails(command):  # the tap of tap-then-type lands, then typing is refused
        if command.WhichOneof("op") == "type_text":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_NOT_FOUND))
        return None
    fake.devices.responder = type_fails
    with pytest.raises(AgentError):
        agent.type("hello", target="id=input", await_focus=False)
    agent.record("stop")

    agent.record("start", "read-after-input")

    def read_fails(command):  # the rotation lands, the read-back that follows fails
        if command.WhichOneof("op") == "device_info":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_TRANSPORT_LOST))
        return None
    fake.devices.responder = read_fails
    with pytest.raises(AgentError):
        agent.rotate("landscape")
    agent.record("stop")
    for name in ("after-input", "read-after-input"):
        assert [step["outcome"]["status"] for step in agent.records.export(name, agent.session)["steps"]] == ["unknown"]


@pytest.mark.parametrize(("reason", "status"), [
    (FailureReason.UNSUPPORTED_API, "failed"),
    (FailureReason.INVALID_ARGUMENT, "failed"),
    (FailureReason.DEVICE_SETTING, "unknown"),
    (FailureReason.UNSPECIFIED, "unknown"),
])
def test_server_refusals_are_failed_only_for_reasons_that_precede_any_change(fake, agent, monkeypatch, reason, status):
    agent.attach("first")
    agent.record("start", "server")

    def refuse(self, enabled):
        raise ServerError("FAILED_PRECONDITION", "refused", reason)
    monkeypatch.setattr(Device, "set_dark_mode", refuse)
    with pytest.raises(AgentError):
        agent.condition("dark-mode", "on")
    agent.record("stop")
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
    for index, (name, (args, kwargs)) in enumerate(calls):
        agent.record("start", f"cover-{index}")
        seen.clear()
        with contextlib.suppress(AgentError):
            getattr(agent, name)(*args, **kwargs)
        agent.record("stop")
        assert seen and seen[0][0] == "action" and seen[0][1] >= 1, (name, args, kwargs, seen)
        covered.add(name)
    assert covered == ACTIONS | CONDITIONAL


def test_named_steps_resolve_refs_ignore_queries_and_survive_release(fake, agent):
    agent.attach("first")
    agent.record("start", "checkout")
    fake.devices.refs["e1"] = res("save").to_proto()
    agent.tap("@e1")
    agent.snapshot()
    agent.exists("id=save")
    agent.count("id=save")
    agent.wait("id=save", state="count", value=0, timeout=.1)
    current = json.loads(agent.export("checkout"))
    assert not current["complete"] and current["state"] == "recording"
    assert [s["action"] for s in current["steps"]] == ["tap", "wait"]
    assert [s["kind"] for s in current["steps"]] == ["action", "wait"]
    assert all(s["serial"] == "first" for s in current["steps"])
    assert current["steps"][0]["targets"]["target"]["node"]["resource"]["name"] == "save"
    assert "@e1" not in json.dumps(current)
    agent.release()
    archived = json.loads(agent.export())
    assert archived["complete"] and archived["stop_reason"] == "released"
    with pytest.raises(AgentError, match="no session"):
        agent.history()


def test_separate_agent_instances_share_named_recordings_and_offline_export(fake, agent, client, monkeypatch, tmp_path):
    agent.attach("first")
    agent.record("start", "shared")
    other = Agent("agent", client=client, out_dir=agent.out_dir)
    other.tap("id=first")
    agent.key("back")
    other.record("stop")
    assert len(json.loads(agent.export("shared"))["steps"]) == 2
    offline = Agent("agent", out_dir=agent.out_dir)
    monkeypatch.setattr(TapClient, "create", lambda *a, **kw: pytest.fail("local archive controls must not contact daemon"))
    path = tmp_path / "export.json"
    assert str(path) in offline.export("shared", path)
    assert json.loads(path.read_text())["complete"]
    assert "shared" in offline.record("list")
    with pytest.raises(AgentError):
        offline.export("shared", path)  # exclusive, never overwrite
    offline.record("clean", "shared")
    assert offline.record("list") == "no step recordings"


def test_sensitive_inputs_selectors_outputs_and_exception_text_are_not_persisted(fake, agent, monkeypatch):
    agent.attach("first")
    agent.record("start", "private")
    secret = "private-" + uuid.uuid4().hex
    agent.fill("text=" + secret, secret, sensitive=True)
    def fail(*args, **kwargs):
        raise AgentError("failure containing " + secret)
    monkeypatch.setattr(agent, "_element", fail)
    with pytest.raises(AgentError):
        agent.fill("id=input", secret, sensitive=True)
    agent.record("stop")
    document = json.loads(agent.export())
    assert document["complete"]
    assert document["steps"][0]["targets"]["target"] == {"redacted": True}
    assert document["steps"][1]["outcome"] == {"status": "rejected_before_input", "error_type": "AgentError"}
    assert secret not in json.dumps(document)
    assert all(secret.encode() not in path.read_bytes() for path in agent.records.directory.iterdir())


def test_post_input_recording_failure_warns_without_replaying_or_losing_result(fake, agent, monkeypatch):
    agent.attach("first")
    agent.record("start", "incomplete")
    def fail(*args, **kwargs):
        raise RecordError("disk full")
    monkeypatch.setattr(agent.records, "finish", fail)
    result = agent.tap("id=save")
    assert result.startswith("tapped") and "do not repeat" in result
    assert len(fake.devices.commands) == 1
    with pytest.raises(AgentError, match="unfinished step"):
        agent.tap("id=other")
    assert len(fake.devices.commands) == 1
    agent.record("stop")
    assert not json.loads(agent.export())["complete"]


def test_pre_input_recording_failure_prevents_input(fake, agent, monkeypatch):
    agent.attach("first")
    agent.record("start", "preflight")
    def fail(*args, **kwargs):
        raise RecordError("disk full")
    monkeypatch.setattr(agent.records, "begin", fail)
    with pytest.raises(AgentError, match="disk full"):
        agent.tap("id=save")
    assert not fake.devices.commands


def test_actual_selected_device_cannot_switch_recording_connection(fake, agent, client, monkeypatch):
    agent.attach("first")
    agent.record("start", "bound")
    original = agent._held()
    other = client.connect("other", hold=600)
    other.attach_device("second")
    replacement = next(entry for entry in client.connections() if entry.id == other.id)
    entries = iter([original, replacement])
    monkeypatch.setattr(agent, "_held", lambda: next(entries))
    with pytest.raises(AgentError, match="connection changed"):
        agent.tap("id=save")
    assert not fake.devices.commands


def test_cli_named_controls_and_new_history_name(fake, agent, tmp_path):
    agent.attach("first")
    def run(arguments):
        return cli.run(cli.parser().parse_args(arguments), agent)
    run(["record", "start", "cli-record"])
    run(["tap", "id=save"])
    run(["record", "stop"])
    assert json.loads(run(["export", "cli-record"]))["steps"][0]["action"] == "tap"
    assert json.loads(run(["history"]))["format"] == "tap-events/1"
    path = tmp_path / "record.json"
    run(["export", "cli-record", "-o", str(path)])
    assert json.loads(path.read_text())["name"] == "cli-record"
    run(["record", "clean", "--all"])
    assert run(["record", "list"]) == "no step recordings"


@pytest.mark.anyio
async def test_mcp_and_cli_share_step_recording_state(fake, agent):
    agent.attach("first")
    server = create_server(lambda _: agent, default_session="agent")
    async with Client(server, mode="legacy") as client:
        result = await client.call_tool("record", {"action": "start", "name": "mixed"})
        assert not result.is_error
        cli.run(cli.parser().parse_args(["tap", "id=save"]), agent)
        result = await client.call_tool("record", {"action": "stop"})
        assert not result.is_error
        result = await client.call_tool("export", {"name": "mixed"})
        assert not result.is_error
        content = result.content[0]
        assert isinstance(content, TextContent)
        document = json.loads(content.text)
        assert document["complete"] and document["steps"][0]["action"] == "tap"
        result = await client.call_tool("history", {})
        content = result.content[0]
        assert isinstance(content, TextContent)
        assert not result.is_error and json.loads(content.text)["format"] == "tap-events/1"


@pytest.mark.anyio
async def test_mcp_tools_that_write_local_files_are_not_marked_read_only(agent):
    server = create_server(lambda _: agent, default_session="agent")
    async with Client(server, mode="legacy") as client:
        tools = (await client.list_tools()).tools
    writers = {tool.name for tool in tools if {"path", "out"} & set(tool.input_schema.get("properties", {}))}
    assert {"history", "export", "logs", "screenshot", "capture"} <= writers
    for tool in tools:
        if tool.name in writers:
            assert tool.annotations is None or not tool.annotations.read_only_hint, tool.name
