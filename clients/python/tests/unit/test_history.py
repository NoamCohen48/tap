"""``client.history``: typed reads of the server's kept history and history recordings."""
import datetime
import json

import pytest

from tap_e2e import HistoryIssueKind, HistoryPage, ServerError, TapClient, text
from tap_e2e import _gen as pb

from .conftest import TOKEN


def line(cursor: int, message: str, *, at: int, priority=pb.LOG_PRIORITY_INFO, tag="App") -> pb.LogEntry:
    return pb.LogEntry(cursor=cursor, host_time_ms=at, line=pb.LogLine(priority=priority, tag=tag, message=message, pid=7))


@pytest.fixture
def client(fake):
    with TapClient.create(fake.address, TOKEN) as tap:
        yield tap


def test_a_read_after_release_has_typed_calls_deaths_and_log_lines(fake, client):
    connection = client.connect("script")
    device = connection.attach_device("emulator-5554")
    device.app("com.shop").launch()
    fake.history.logs["attached-emulator-5554"] = [line(1, "starting", at=1_790_000_000_001)]  # before the call
    fake.history.death(connection.id, "emulator-5554", sequence=1, kind=pb.AppDeath.KIND_CRASH,
        package_name="com.shop", pid=42, exception_class="java.lang.IllegalStateException", message="Out of stock",
        stack_trace="at Shop.pay\nat Shop.main")
    connection.close()

    page = client.history.read(connections=[connection.id], logs=True)

    kinds = [entry.kind for entry in page]
    assert kinds == ["line", "call", "death"]
    call = page.entries[1].call
    assert call is not None and call.app == {"operation": "launch", "package_name": "com.shop"}
    death = page.entries[2].death
    assert death is not None and death.package_name == "com.shop" and death.serial == "emulator-5554"
    assert page.entries[2].attached_device_id == "attached-emulator-5554"
    assert {s.id: s.is_device_log for s in page.sources} == {connection.id: False, "attached-emulator-5554": True}
    assert "CRASH com.shop (pid 42): java.lang.IllegalStateException: Out of stock" in page.text
    assert "at Shop.pay" not in page.text and "| at Shop.pay" in page.render_text(details=True)
    document = json.loads(page.to_json())
    assert document["format"] == "tap-history/2"
    assert [e["kind"] for e in document["entries"]] == kinds
    assert document["entries"][2]["death"]["stack_trace"] == "at Shop.pay\nat Shop.main"
    assert page.bytes == page.to_json().encode() and page.media_type == "application/json"


def test_read_sends_bounds_filters_and_order_to_the_server(fake, client):
    connection = client.connect("script")
    connection.attach_device("emulator-5554")
    fake.history.logs["attached-emulator-5554"] = [pb.LogEntry(cursor=1, host_time_ms=1_790_000_000_000, marker="checkout")]
    page = client.history.read(serials=["emulator-5554"], since_marker="checkout", until=1_790_000_000.5, step="s-1",
        logs=True, min_priority="warn", tags=["Pay"], grep="declined", actions=False, newest_first=True, limit=10)
    assert isinstance(page, HistoryPage)
    request = fake.history.requests[-1]
    assert request.selection.serials == ["emulator-5554"]
    assert request.from_marker == "checkout" and request.until_time_ms == 1_790_000_000_500
    assert request.step == "s-1" and request.include_logs and request.newest_first and request.limit == 10
    assert request.log_filter.min_priority == pb.LOG_PRIORITY_WARN and request.log_filter.tags == ["Pay"]
    assert request.log_filter.message_regex == "declined" and request.log_filter.exclude_actions
    client.history.read(since="2026-11-21T12:00:00Z", until=datetime.datetime(2026, 11, 21, 12, 0, 30, tzinfo=datetime.timezone.utc))
    request = fake.history.requests[-1]
    assert (request.from_time_ms, request.until_time_ms) == (1_795_262_400_000, 1_795_262_430_000)


def test_a_time_bound_is_never_taken_for_a_marker_name(fake, client):
    with pytest.raises(ValueError, match="since_marker"):
        client.history.read(since="checkout")
    with pytest.raises(ValueError, match="time zone"):
        client.history.read(until="2026-11-21T12:00:00")
    with pytest.raises(ValueError, match="not both"):
        client.history.read(since=1.0, since_marker="checkout")
    assert not fake.history.requests


def test_a_marker_bound_starts_at_each_device_s_newest_marker(fake, client):
    connection = client.connect("script")
    connection.attach_device("emulator-5554")
    fake.history.logs["attached-emulator-5554"] = [
        pb.LogEntry(cursor=1, host_time_ms=1_790_000_000_000, marker="checkout"),
        line(2, "first try", at=1_790_000_000_001),
        pb.LogEntry(cursor=3, host_time_ms=1_790_000_000_002, marker="checkout"),
        line(4, "paid", at=1_790_000_000_003)]
    page = client.history.read(since_marker="checkout", logs=True)
    assert [e.marker or e.line.message for e in page if e.marker or e.line] == ["checkout", "paid"]


def test_log_filters_without_logs_are_refused_before_any_call(fake, client):
    with pytest.raises(ValueError, match="logs=True"):
        client.history.read(tags=["Pay"])
    with pytest.raises(ValueError, match="limit"):
        client.history.read(limit=0)
    assert not fake.history.requests


def test_pages_continue_with_the_cursor_alone(fake, client):
    connection = client.connect("script")
    connection.attach_device("emulator-5554")
    fake.history.logs["attached-emulator-5554"] = [line(i, f"line {i}", at=1_790_000_000_000 + i) for i in range(1, 6)]

    pages = list(client.history.pages(sources=["attached-emulator-5554"], logs=True, limit=2))

    assert [[e.line.message for e in p if e.line] for p in pages] == [["line 1", "line 2"], ["line 3", "line 4"], ["line 5"]]
    assert pages[-1].next_cursor is None and pages[-1].next_page() is None
    continuation = fake.history.requests[1]
    assert continuation.HasField("cursor") and not continuation.include_logs and not continuation.HasField("selection")


def test_a_missing_marker_is_a_server_error(fake, client):
    with pytest.raises(ServerError):
        client.history.read(since_marker="never-written")


def test_issues_are_typed_and_shown_as_warnings(fake, client):
    fake.history.issues = [pb.HistoryIssue(source_id="s", kind=pb.HISTORY_ISSUE_KIND_EXPIRED, message="older entries expired", missing_entries=3)]
    page = client.history.read()
    assert page.issues[0].kind is HistoryIssueKind.EXPIRED and page.issues[0].missing_entries == 3
    assert "WARNING: expired: older entries expired" in page.text


def test_recordings_are_per_connection_and_follow_later_attachments(fake, client):
    first, second = client.connect("one"), client.connect("two")
    first.attach_device("emulator-5554")
    one = client.history.start_recording(first, "checkout")
    two = client.history.start_recording(second, "checkout")  # the same name on another connection
    assert one.active and one.id != two.id
    first.attach_device("85e49002")

    stopped = client.history.stop_recording(one)

    assert not stopped.active and stopped.stopped_at is not None
    assert {span.source_id for span in stopped.spans} >= {first.id, "attached-emulator-5554", "attached-85e49002"}
    assert [r.name for r in client.history.recordings(connections=[first])] == ["checkout"]
    with pytest.raises(ServerError):
        client.history.delete_recording(two)  # still recording
    client.history.delete_recording(stopped)
    assert [r.id for r in client.history.recordings()] == [two.id]
    read = client.history.read(recording=two)
    assert fake.history.requests[-1].selection.recording_id == two.id and not read.entries


def test_text_hides_control_characters_from_the_terminal(fake, client):
    connection = client.connect("script")
    connection.attach_device("emulator-5554")
    fake.history.logs["attached-emulator-5554"] = [line(1, "\x1b[31mred", at=1_790_000_000_001)]
    assert "\\u001b[31mred" in client.history.read(logs=True).text


def test_calls_read_as_their_operation_and_target(fake, client):
    connection = client.connect("script")
    device = connection.attach_device("emulator-5554")
    device.app("com.shop").launch()
    device.app("com.shop").element(text("Pay")).exists()
    page = client.history.read(connections=[connection.id])
    assert [e.summary for e in page if e.call] == ["call app launch com.shop → ok", 'call exists text("Pay") & app("com.shop") → ok']
