"""Post-script SDK exports preserve actual calls, uncertainty, gaps and original identities."""
import json

from tap_e2e import HistoryExport, TapClient
from tap_e2e import _gen as pb

from .conftest import TOKEN


def event(cursor=1, *, unknown=False):
    return pb.LogEntry(cursor=cursor, host_time_ms=1000 + cursor, event=pb.LoggedEvent(seq=cursor,
        at_epoch_ms=1000 + cursor, duration_ms=20, serial="device", clock_id="original-clock", attached_device_id="original-attachment",
        started_monotonic_ns=9_007_199_254_740_993, finished_monotonic_ns=9_007_199_254_740_994,
        device=pb.DeviceCall(operation="set_dark_mode", enabled=True), indeterminate=unknown))


def archive(fake, connection_id="original"):
    fake.device_log.clock_id = "original-clock"
    fake.device_log.archives = [pb.RetainedLog(client_connection_id=connection_id,
        attached_device_id="original-events", events=True, closed=True, clock_id="original-clock")]


def test_history_uses_original_ids_without_creating_a_connection(fake):
    archive(fake)
    fake.device_log.entries = [event()]
    with TapClient.create(fake.address, TOKEN) as client:
        result = client.log_recordings.history("original")
        assert result.data["complete"]
        saved = result.data["events"][0]
        assert saved["device"] == {"operation": "set_dark_mode", "enabled": True}
        assert saved["started_monotonic_ns"] == "9007199254740993"
        assert saved["clock_id"] == "original-clock"
        assert saved["attached_device_id"] == "original-attachment"
        assert "set_dark_mode" in result.text
        assert json.loads(result.to_json()) == result.data
        assert result.data["event_capture"][0]["clock_id"] == "original-clock"
        assert not client.log_recordings.archives("original")  # Device-log discovery excludes event streams.
        assert all(r.client_connection_id == "original" for r in fake.device_log.requests)


def test_receipt_time_bounds_also_apply_to_matching_step_ids_and_deaths(fake):
    archive(fake)
    fake.device_log.entries = [event(i) for i in (1, 2, 3)]
    for entry in fake.device_log.entries:
        entry.host_time_ms = entry.cursor * 1000
        entry.event.step = "same-step"
    fake.device_log.entries.append(pb.LogEntry(cursor=4, host_time_ms=2500,
        death=pb.RecordedAppDeath(attached_device_id="attachment", serial="device", death=pb.AppDeath(
            sequence=1, kind=pb.AppDeath.KIND_ANR, package_name="com.shop", message="diagnostic", stack_trace="Frame"))))
    with TapClient.create(fake.address, TOKEN) as client:
        result = client.log_recordings.history("original", step_ids=["same-step"], from_time=2, until_time=3)
        assert [e["seq"] for e in result.data["events"]] == [2]
        assert [d["kind"] for d in result.data["deaths"]] == ["anr"]
        assert "ANR com.shop" in result.text
        assert "Frame" in result.to_json() and "Frame" in result.render_text(details=True)
        assert not client.log_recordings.history("original", step_ids=[]).data["events"]
        assert client.log_recordings.history("original", step_ids=[]).data["deaths"]


def test_unrepresentable_device_clock_does_not_discard_host_timed_death(fake):
    archive(fake)
    fake.device_log.entries = [pb.LogEntry(cursor=1, host_time_ms=2000,
        death=pb.RecordedAppDeath(attached_device_id="attachment", serial="device", death=pb.AppDeath(
            sequence=1, kind=pb.AppDeath.KIND_CRASH, at_epoch_ms=2**63 - 1)))]
    with TapClient.create(fake.address, TOKEN) as client:
        death = client.log_recordings.history("original").to_dict()["deaths"][0]
        assert death["device_time_ms"] == str(2**63 - 1)
        assert death["device_time_unavailable"] and death["at"] == "1970-01-01T00:00:02.000Z"


def test_named_export_does_not_stop_and_works_after_release(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        connection = client.connect("script")
        archive(fake, connection.id)
        client.log_recordings.start(connection, "flow")
        fake.device_log.entries = [event()]
        live = client.log_recordings.export("flow")
        assert not live.data["complete"]
        assert not fake.device_log.recordings["flow"].HasField("stopped_at_ms")
        client.log_recordings.stop("flow")
        assert client.log_recordings.export("flow").data["complete"]  # The stopped range is final while its owner remains live.
        connection.close()
        fake.device_log.entries.append(event(2))
        saved = client.log_recordings.export("flow")
        assert saved.data["complete"]
        assert [e["seq"] for e in saved.data["events"]] == [1]


def test_sdk_export_cannot_claim_missing_agent_intent_is_complete(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        connection = client.connect("agent")
        archive(fake, connection.id)
        client.log_recordings.start(connection, "agent-flow", agent_steps_expected=True)
        fake.device_log.entries = [event()]
        client.log_recordings.stop("agent-flow")
        saved = client.log_recordings.export("agent-flow")
        assert saved.data["evidence_complete"]
        assert not saved.data["complete"]
        assert saved.data["step_history_unavailable"] and "WARNING" in saved.text
        connection.close()


def test_indeterminate_and_missing_evidence_cannot_become_success(fake):
    archive(fake)
    fake.device_log.entries = [event(unknown=True), pb.LogEntry(cursor=2, gap="capture interrupted")]
    fake.device_log.dropped = 3
    fake.device_log.capture_error = "storage full"
    with TapClient.create(fake.address, TOKEN) as client:
        saved = client.log_recordings.history("original")
        assert not saved.data["complete"]
        assert not saved.data["events"][0]["ok"]
        assert saved.data["events"][0]["indeterminate"]
        assert saved.data["event_capture"][0]["dropped"] == 3
        assert "unknown" in saved.text and "storage full" in saved.text and "capture interrupted" in saved.text
        missing = client.log_recordings.history("missing")
        assert not missing.data["complete"] and "events_unavailable" in missing.data
        assert "WARNING" in missing.text


def test_recording_reported_death_is_not_duplicated_without_per_step_connection_id():
    saved = HistoryExport({"connection_id": "owner", "deaths": [{"connection_id": "owner", "attached_device_id": "attachment",
        "serial": "phone", "sequence": 1, "kind": "crash", "package_name": "com.shop", "at": "2026-11-20T00:00:00Z"}],
        "steps": [{"id": "step", "app_deaths": [{"serial": "phone", "sequence": 1, "kind": "crash", "expected": True}]}]})
    assert len(saved.to_dict()["deaths"]) == 1
    assert saved.to_dict()["deaths"][0]["reported_by_step"] == "step"
    assert saved.to_dict()["deaths"][0]["expected"]
    assert saved.text.count("CRASH com.shop") == 1


def test_text_uses_resolved_targets_avoids_duplicate_calls_and_escapes_terminal_controls():
    saved = HistoryExport({"steps": [{"seq": 1, "id": "step", "action": "tap", "serial": "device",
        "parameters": {"target": {"selector_ref": "target"}}, "targets": {"target": {"node": {"resource": {"name": "login"}}}},
        "outcome": {"status": "unknown", "error": "INDETERMINATE"}}],
        "events": [{"step": "step", "command": {"hidden_duplicate": True}}],
        "log": {"device": {"entries": [{"line": {"message": "\x1b[31muntrusted", "tag": "App"}}]}}})
    assert "login" in saved.text and "unknown" in saved.text
    assert "hidden_duplicate" not in saved.text and "\x1b" not in saved.text
    assert "\\u001b" in saved.text
    copied = saved.to_dict()
    copied["steps"].clear()
    assert len(saved.data["steps"]) == 1
