"""Unified reading, attachment-scoped controls and references to explicit captures."""
from __future__ import annotations

import datetime
import json
import uuid

import pytest
from mcp import Client
from tap_e2e import Device, TapClient
from tap_e2e import _gen as pb
from tap_e2e.logs import DeviceLog

from tap_agent import Agent, AgentError, cli
from tap_agent.mcp_server import create_server
from tap_agent.records import RecordError

from .conftest import TOKEN


def view(agent, **kwargs):
    return json.loads(agent.history(as_json=True, **kwargs))


def archives(fake, monkeypatch):
    owner = str(uuid.uuid4())
    ids = {serial: str(uuid.uuid4()) for serial in ("first", "second")}
    fake.device_log.archives = [pb.RetainedLog(client_connection_id=owner, attached_device_id=identity,
        serial=serial, closed=True) for serial, identity in ids.items()]
    fake.device_log.archives.append(pb.RetainedLog(client_connection_id=owner, attached_device_id=owner, events=True, closed=True))
    entries = {identity: [] for identity in (*ids.values(), owner)}
    monkeypatch.setattr(fake.device_log, "_entries_for", lambda identity, _: entries[identity])
    return owner, ids, entries


def line(cursor, at, message):
    return pb.LogEntry(cursor=cursor, host_time_ms=at, line=pb.LogLine(message=message, tag="App"))


def test_device_selection_precedes_intent_pagination(fake, agent):
    agent.attach("first")
    agent.attach("second")
    agent.tap("id=one", "first")
    agent.tap("id=two", "second")
    agent.tap("id=three", "second")
    saved = view(agent, devices=["first"], limit=1)
    assert [row["serial"] for row in saved["steps"]] == ["first"]
    assert saved["older"] is None
    args = cli.parser().parse_args(["history", "--device", "first", "--device", "second", "--json"])
    assert len(json.loads(cli.run(args, agent))["steps"]) == 3


def test_device_filter_keeps_only_selected_calls_and_deaths_but_never_hides_gaps(fake, agent, monkeypatch):
    owner, ids, entries = archives(fake, monkeypatch)
    entries[ids["first"]] = [line(1, 1000, "first")]
    entries[ids["second"]] = [line(1, 1000, "second")]
    entries[owner] = [pb.LogEntry(cursor=1, host_time_ms=1000, event=pb.LoggedEvent(seq=1, serial="first", device=pb.DeviceCall(operation="inspect"))),
        pb.LogEntry(cursor=2, host_time_ms=1000, event=pb.LoggedEvent(seq=2, serial="second", device=pb.DeviceCall(operation="inspect"))),
        pb.LogEntry(cursor=3, host_time_ms=1000, death=pb.RecordedAppDeath(serial="second", attached_device_id=ids["second"], death=pb.AppDeath(sequence=1))),
        pb.LogEntry(cursor=4, host_time_ms=1000, gap="lost connection evidence")]
    saved = view(agent, start="1970-01-01T00:00:00Z", devices=["second"], logs=True)
    assert set(saved["log"]) == {"second"}
    assert [e["serial"] for e in saved["events"]] == ["second"]
    assert [e["serial"] for e in saved["deaths"]] == ["second"]
    assert saved["event_capture"][0]["gaps"][0]["gap"] == "lost connection evidence"
    assert not fake.connections.live


def test_opaque_after_preserves_other_devices_at_the_same_receipt_time(fake, agent, monkeypatch):
    _, ids, entries = archives(fake, monkeypatch)
    entries[ids["first"]] = [line(1, 1000, "before"), line(2, 2000, "anchor"), line(3, 2000, "after")]
    entries[ids["second"]] = [line(1, 2000, "same time other device")]
    saved = view(agent, after=f"{ids['first']}:2")
    assert [e["line"]["message"] for e in saved["log"]["first"]["entries"]] == ["after"]
    assert saved["log"]["second"]["entries"][0]["line"]["message"] == "same time other device"
    assert f"{ids['first']}:3" in agent.history(after=f"{ids['first']}:2")
    with pytest.raises(AgentError, match="no longer retained"):
        agent.history(after=f"{ids['first']}:99")
    with pytest.raises(AgentError, match="ARCHIVE_UUID"):
        agent.history(after="2")


def test_tail_reports_omissions_without_dropping_gaps_or_deaths(fake, agent, monkeypatch):
    owner, ids, entries = archives(fake, monkeypatch)
    entries[ids["first"]] = [pb.LogEntry(cursor=1, host_time_ms=1000, gap="log gap"), line(2, 2000, "old")]
    entries[ids["second"]] = [line(1, 3000, "new")]
    entries[owner] = [pb.LogEntry(cursor=1, host_time_ms=1000, death=pb.RecordedAppDeath(serial="first", death=pb.AppDeath(sequence=1)))]
    saved = view(agent, tail=1, grep="new", actions=False)
    assert saved["log_omitted"] == 1  # gap is retained outside the requested tail.
    assert saved["log"]["first"]["entries"][0]["gap"] == "log gap"
    assert len(saved["deaths"]) == 1
    requests = [r for r in fake.device_log.requests if isinstance(r, pb.ReadLogRequest)]
    assert all(not r.message_regex and not r.exclude_actions for r in requests if r.attached_device_id == owner)
    assert all(r.message_regex == "new" and r.exclude_actions for r in requests if r.attached_device_id != owner)


def test_historical_package_ownership_is_never_taken_from_another_attachment(fake, agent, monkeypatch):
    archives(fake, monkeypatch)
    with pytest.raises(AgentError, match="original live attachment") as refused:
        agent.history(packages=["com.shop"])
    assert refused.value.exit_code == 2
    assert not fake.connections.live


def test_last_action_uses_latest_per_device_and_needs_retained_actions(fake, agent, monkeypatch):
    _, ids, entries = archives(fake, monkeypatch)
    with pytest.raises(AgentError, match="no retained action"):
        agent.history(last_action=True)
    entries[ids["first"]] = [pb.LogEntry(cursor=1, host_time_ms=1000, action=pb.LogAction(operation="tap")), line(2, 2000, "observed")]
    entries[ids["second"]] = [pb.LogEntry(cursor=1, host_time_ms=3000, action=pb.LogAction(operation="launch"))]
    assert view(agent, last_action=True)["range"]["from_time"] == 1
    assert view(agent, last_action=True, devices=["second"])["range"]["from_time"] == 3


def test_controls_apply_to_selected_held_attachments_without_creating_sessions(fake, agent):
    agent.attach("first")
    agent.attach("second")
    before = set(fake.connections.live)
    selected = json.loads(agent.history("mark", "flow", devices=["second"], as_json=True))
    assert [d["serial"] for d in selected["devices"]] == ["second"]
    all_devices = json.loads(agent.history("mark", "flow", as_json=True))
    assert [d["serial"] for d in all_devices["devices"]] == ["first", "second"]
    assert len(agent.records.marker_anchor("flow")) == 2
    assert len(agent.records.marker_anchor("flow", ("first",))) == 1
    agent.history("redact", "private-value", devices=["second"])
    assert fake.device_log.requests[-1].attached_device_id == "attached-second"
    before_requests = len(fake.device_log.requests)
    with pytest.raises(AgentError, match="not attached"):
        agent.history("mark", "x", devices=["first", "missing"])
    assert len(fake.device_log.requests) == before_requests
    assert set(fake.connections.live) == before


def test_control_reports_prior_acknowledgements_on_partial_failure(fake, agent, monkeypatch):
    agent.attach("first")
    agent.attach("second")
    original = DeviceLog.mark
    def mark(log, name):
        if log._device.serial == "second":
            raise RecordError("unavailable")
        return original(log, name)
    monkeypatch.setattr(DeviceLog, "mark", mark)
    with pytest.raises(AgentError, match="acknowledged devices first"):
        agent.history("mark", "flow")
    assert agent.records.marker_anchor("flow")[0]["serial"] == "first"


def test_acknowledged_marker_survives_index_failure_in_the_error_report(fake, agent, monkeypatch):
    agent.attach("first")
    def unavailable(*args, **kwargs):
        raise RecordError("index unavailable")
    monkeypatch.setattr(agent.records, "mark", unavailable)
    with pytest.raises(AgentError, match="acknowledged devices first"):
        agent.history("mark", "flow")
    assert sum(isinstance(r, pb.MarkLogRequest) for r in fake.device_log.requests) == 1


def test_capture_references_survive_restart_and_clean_does_not_delete_files(fake, agent, tmp_path):
    agent.attach("first")
    agent.record("start", "incident")
    directory = tmp_path / "capture"
    agent.capture(out=directory)
    agent.record("stop", "incident")
    saved = json.loads(agent.record("export", "incident", as_json=True))
    assert saved["artifacts"][0]["scope"] == "state_captured_at_request_time"
    assert view(agent, devices=["second"])["artifacts"] == []
    agent.release()
    restarted = Agent("other", client=TapClient.create(fake.address, TOKEN), out_dir=agent.out_dir)
    try:
        assert view(restarted)["artifacts"][0]["directory"] == str(directory)
    finally:
        restarted.close()
    agent.record("clean", "incident")
    assert directory.is_dir() and list(directory.iterdir())


def test_capture_index_failure_keeps_saved_files_and_never_repeats_capture(fake, agent, tmp_path, monkeypatch):
    agent.attach("first")
    calls = []
    original = Device.capture
    def capture(device, **kwargs):
        calls.append(device.serial)
        return original(device, **kwargs)
    def unavailable(*args, **kwargs):
        raise RecordError("private exception must not be indexed")
    monkeypatch.setattr(Device, "capture", capture)
    monkeypatch.setattr(agent.records, "artifact", unavailable)
    directory = tmp_path / "bundle"
    text = agent.capture(out=directory)
    assert calls == ["first"] and list(directory.iterdir())
    assert "files were saved" in text and "do not repeat" in text
    assert "private exception" not in text


def test_artifact_time_bounds_are_exclusive_and_never_open_files(fake, agent, tmp_path):
    agent.attach("first")
    directory = tmp_path / "bundle"
    agent.capture(out=directory)
    artifact = view(agent)["artifacts"][0]
    instant = datetime.datetime.fromisoformat(artifact["at"].replace("Z", "+00:00"))
    for path in directory.iterdir():
        path.unlink()
    assert view(agent, start=instant.isoformat())["artifacts"]
    assert not view(agent, until=instant.isoformat())["artifacts"]
    assert "CAPTURE reference" in agent.history()


@pytest.mark.anyio
async def test_mcp_and_cli_share_controls_filters_and_global_history(fake, agent):
    agent.attach("first")
    server = create_server(lambda _: agent)
    async with Client(server, mode="legacy") as client:
        tools = (await client.list_tools()).tools
        assert "log" not in {tool.name for tool in tools}
        result = await client.call_tool("history", {"action": "mark", "value": "mcp", "devices": ["first"], "as_json": True})
        assert not result.is_error
        result = await client.call_tool("history", {"devices": ["first"], "as_json": True})
        assert not result.is_error
        args = cli.parser().parse_args(["history", "read", "-d", "first", "--json"])
        assert json.loads(result.content[0].text) == json.loads(cli.run(args, agent))
    with pytest.raises(SystemExit):
        cli.parser().parse_args(["log"])


@pytest.mark.parametrize("step", [True, 0, -1, 2**63, "9" * 5000])
def test_invalid_global_step_values_are_bounded_before_catalog_lookup(agent, step):
    with pytest.raises(AgentError, match="positive 64-bit"):
        agent.history(step=step)


def test_bookmarks_are_visible_without_any_application_steps(fake, agent):
    agent.attach("first")
    agent.history("mark", "before-any-input")
    saved = view(agent)
    assert not saved["steps"]
    assert saved["markers"][0]["name"] == "before-any-input"
    assert saved["markers"][0]["attached_device_id"] == "attached-first"
    assert "marker before-any-input" in agent.history()
    assert not view(agent, devices=["second"])["markers"]


def test_step_logs_use_original_action_span_even_when_delivered_late(fake, agent):
    agent.attach("first")
    agent.tap("id=one")
    step = view(agent)["steps"][0]
    owner = next(iter(fake.connections.live))
    fake.device_log.archives.append(pb.RetainedLog(client_connection_id=owner,
        attached_device_id="attached-first", serial="first"))
    late = int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000) + 5000
    fake.device_log.entries = [pb.LogEntry(cursor=1, host_time_ms=late, action=pb.LogAction(operation="tap", step=step["id"])),
        line(2, late, "delayed observation"),
        pb.LogEntry(cursor=3, host_time_ms=late, action=pb.LogAction(operation="tap", step=step["id"], finished=True))]
    saved = view(agent, step=step["id"], logs=True)
    assert len(saved["log"]["first"]["entries"]) == 3
    assert saved["range"]["log_scope"] == "original_step_action_span"
    assert next(r for r in reversed(fake.device_log.requests) if getattr(r, "attached_device_id", "") == "attached-first").step == step["id"]


def test_partial_capture_indexes_failure_types_not_exception_text(fake, agent, tmp_path, monkeypatch):
    agent.attach("first")
    original = Device.capture
    def partial(device):
        captured = original(device)
        captured.screenshot = None
        captured.failures["screenshot"] = OSError("private unclassified exception")
        return captured
    monkeypatch.setattr(Device, "capture", partial)
    agent.capture(out=tmp_path / "partial")
    saved = view(agent)
    assert saved["artifacts"][0]["failures"] == {"screenshot": "OSError"}
    assert "private unclassified" not in json.dumps(saved)
    assert "unavailable parts: screenshot" in agent.history()


def test_capture_refuses_an_existing_directory_before_collecting(fake, agent, tmp_path, monkeypatch):
    agent.attach("first")
    def must_not_collect(device):
        raise AssertionError("capture was repeated")
    monkeypatch.setattr(Device, "capture", must_not_collect)
    with pytest.raises(AgentError):
        agent.capture(out=tmp_path)


def test_legacy_marker_without_original_receipt_identity_is_explicit(fake, agent):
    agent.attach("first")
    record = agent.records.ensure(agent.session, next(iter(fake.connections.live)))
    agent.records.mark(record, "old", "first", 1)
    with pytest.raises(AgentError, match="legacy marker lacks"):
        agent.history(since_marker="old")


def test_log_read_failure_does_not_erase_independent_crashes(fake, agent, monkeypatch):
    import grpc
    agent.attach("first")
    owner = next(iter(fake.connections.live))
    fake.device_log.archives.append(pb.RetainedLog(client_connection_id=owner, attached_device_id="attached-first", serial="first"))
    original_entries = fake.device_log._entries_for
    monkeypatch.setattr(fake.device_log, "_entries_for", lambda identity, connection: [pb.LogEntry(cursor=1,
        death=pb.RecordedAppDeath(serial="first", attached_device_id="attached-first", death=pb.AppDeath(sequence=1, kind=pb.AppDeath.KIND_CRASH,
            package_name="com.shop")))] if identity == owner else original_entries(identity, connection))
    original_check = fake.device_log._check
    def check(request, context):
        original_check(request, context)
        if request.attached_device_id == "attached-first":
            context.abort(grpc.StatusCode.UNAVAILABLE, "private unclassified diagnostic")
    monkeypatch.setattr(fake.device_log, "_check", check)
    saved = view(agent, grep="anything")
    assert [death["kind"] for death in saved["deaths"]] == ["crash"]
    assert not saved["complete"] and "incomplete" in saved["log"]["first"]["capture_error"]
    assert "private unclassified" not in json.dumps(saved)
