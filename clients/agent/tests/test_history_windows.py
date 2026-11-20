"""Time windows select independently retained evidence, not just periods with agent steps."""
from __future__ import annotations

import datetime
import json
from collections.abc import Iterable

import pytest
from tap_e2e import _gen as pb

from tap_agent import AgentError, cli


def iso(at: float) -> str:
    return datetime.datetime.fromtimestamp(at, datetime.timezone.utc).isoformat()


def death(cursor: int, at: float, *, anr: bool = False) -> pb.LogEntry:
    return pb.LogEntry(cursor=cursor, host_time_ms=int(at * 1000), host_monotonic_ns=9_007_199_254_740_993,
        death=pb.RecordedAppDeath(attached_device_id="old-attachment", serial="phone", death=pb.AppDeath(
            sequence=cursor, kind=pb.AppDeath.KIND_ANR if anr else pb.AppDeath.KIND_CRASH,
            package_name="com.shop", process_name="com.shop:worker", pid=42, at_epoch_ms=123,
            exception_class="ExampleException", message="redacted diagnostic", stack_trace="at Cart.pay(Cart.kt:42)\nat Main.run(Main.kt:1)")))


def seed(fake, monkeypatch, *, owner: str = "original", event_entries: Iterable[pb.LogEntry] = (),
         log_entries: Iterable[pb.LogEntry] = ()) -> dict[str, list[pb.LogEntry]]:
    fake.device_log.archives = [
        pb.RetainedLog(client_connection_id=owner, attached_device_id="original-events", events=True, closed=True),
        pb.RetainedLog(client_connection_id=owner, attached_device_id="old-attachment", serial="phone", closed=True),
    ]
    fake.device_log.clock_id = "original-clock"
    entries = {"original-events": list(event_entries), "old-attachment": list(log_entries)}
    monkeypatch.setattr(fake.device_log, "_entries_for", lambda archive_id, _: entries[archive_id])
    return entries


def test_time_bounds_retrieve_logs_without_any_local_steps_or_live_attachment(fake, agent, monkeypatch):
    lines = [pb.LogEntry(cursor=i, host_time_ms=i * 1000, line=pb.LogLine(tag="App", message=str(i))) for i in (9, 10, 15, 20)]
    seed(fake, monkeypatch, log_entries=lines, event_entries=[death(1, 15)])
    result = json.loads(agent.history(start=iso(10), until=iso(20), logs=True, as_json=True))
    assert not result["steps"] and not fake.connections.live
    assert [e["line"]["message"] for e in result["log"]["phone"]["entries"]] == ["10", "15"]
    assert result["deaths"][0]["kind"] == "crash"
    assert result["deaths"][0]["clock_id"] == "original-clock"
    assert result["deaths"][0]["host_monotonic_ns"] == "9007199254740993"
    assert result["deaths"][0]["attached_device_id"] == "old-attachment"
    assert "CRASH com.shop" in agent.history(start=iso(10), until=iso(20))


def test_last_reads_background_calls_logs_and_anr_without_steps(fake, agent, monkeypatch):
    now = datetime.datetime.now(datetime.timezone.utc).timestamp()
    event = pb.LogEntry(cursor=3, host_time_ms=int((now - 5) * 1000), event=pb.LoggedEvent(
        seq=1, at_epoch_ms=int((now - 60) * 1000), duration_ms=55_000, serial="phone",
        device=pb.DeviceCall(operation="background_operation")))
    seed(fake, monkeypatch, event_entries=[death(1, now - 60), death(2, now - 5, anr=True), event],
        log_entries=[pb.LogEntry(cursor=1, host_time_ms=int((now - 4) * 1000), line=pb.LogLine(message="background"))])
    args = cli.parser().parse_args(["history", "--last", "30s", "--logs", "--json"])
    result = json.loads(cli.run(args, agent))
    assert not result["steps"]
    assert [d["kind"] for d in result["deaths"]] == ["anr"]
    assert result["events"][0]["device"]["operation"] == "background_operation"
    assert result["log"]["phone"]["entries"][0]["line"]["message"] == "background"
    assert 29.9 < result["range"]["until_time"] - result["range"]["from_time"] < 30.1


@pytest.mark.parametrize("kwargs", [{}, {"logs": True}, {"details": True}])
def test_crash_and_anr_are_explicit_in_all_history_text_modes(fake, agent, monkeypatch, kwargs):
    seed(fake, monkeypatch, event_entries=[death(1, 10), death(2, 20, anr=True)])
    text = agent.history(**kwargs)
    assert "CRASH com.shop" in text and "ANR com.shop" in text
    assert ("Cart.kt:42" in text) == kwargs.get("details", False)
    document = json.loads(agent.history(as_json=True, **kwargs))
    assert [d["kind"] for d in document["deaths"]] == ["crash", "anr"]
    assert "Cart.kt:42" in document["deaths"][0]["stack_trace"]


@pytest.mark.parametrize("last", ["0s", "-1s", "bad", "1e999s", "999999999999999999999999999h"])
def test_last_rejects_invalid_durations_before_device_activity(agent, last):
    with pytest.raises(AgentError) as error:
        agent.history(last=last)
    assert error.value.exit_code == 2


@pytest.mark.parametrize("bound", [{"start": 1}, {"until": iso(20)}])
def test_last_cannot_be_combined_with_other_bounds(agent, bound):
    with pytest.raises(AgentError, match="cannot be combined"):
        agent.history(last="30s", **bound)


def test_step_limit_does_not_shrink_window_evidence_or_claim_complete_intent(fake, agent, monkeypatch):
    agent.attach("phone")
    owner = next(iter(fake.connections.live))
    agent.tap("id=one")
    agent.tap("id=two")
    now = datetime.datetime.now(datetime.timezone.utc).timestamp()
    seed(fake, monkeypatch, owner=owner, event_entries=[death(1, now - 20), death(2, now - 1, anr=True)],
        log_entries=[pb.LogEntry(cursor=1, host_time_ms=int((now - 20) * 1000), line=pb.LogLine(message="before steps"))])
    result = json.loads(agent.history(last="30s", limit=1, logs=True, as_json=True))
    assert len(result["steps"]) == 1 and result["steps_truncated"] and not result["complete"]
    assert len(result["deaths"]) == 2
    assert result["log"]["phone"]["entries"][0]["line"]["message"] == "before steps"


def test_record_export_retains_independent_deaths_without_logs_or_intent(fake, agent, monkeypatch):
    agent.attach("phone")
    owner = next(iter(fake.connections.live))
    entries = seed(fake, monkeypatch, owner=owner)
    agent.record("start", "incident")
    entries["original-events"].extend([death(1, 10), death(2, 20, anr=True)])
    agent.record("stop", "incident")
    agent.release()
    for kwargs in ({}, {"logs": True}, {"details": True}):
        text = agent.record("export", "incident", **kwargs)
        assert "CRASH com.shop" in text and "ANR com.shop" in text
        result = json.loads(agent.record("export", "incident", as_json=True, **kwargs))
        assert [d["kind"] for d in result["deaths"]] == ["crash", "anr"]
