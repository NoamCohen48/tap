"""The device log API over a real gRPC channel: requests, decoding and the live stream."""
import threading

import pytest  # pyright: ignore[reportMissingImports]  # Installed in the test venv.

from tap_e2e import FailureReason, LogEntry, LogPriority, ServerError, TapClient, current_step, step
from tap_e2e import _gen as pb

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("logs") as connection:
            yield connection.attach_device("emulator-5554")


def line(cursor: int, message: str, priority=pb.LOG_PRIORITY_INFO, uid: int | None = 10123) -> pb.LogEntry:
    return pb.LogEntry(
        cursor=cursor,
        host_time_ms=1_794_608_507_900 + cursor,
        line=pb.LogLine(
            device_time_ms=1_794_608_507_879, uid=uid, pid=2346, tid=2357, priority=priority, tag="Shop", message=message
        ),
    )


def test_read_decodes_every_kind_of_entry_and_renders_it_as_text(fake, device):
    fake.device_log.dropped = 7
    fake.device_log.entries = [
        pb.LogEntry(cursor=1, marker="checkout"),
        pb.LogEntry(cursor=2, action=pb.LogAction(operation="tap", target='text="Pay"', request_id=42)),
        line(3, "paying", pb.LOG_PRIORITY_WARN),
        pb.LogEntry(
            cursor=4,
            action=pb.LogAction(operation="tap", finished=True, outcome="ok", duration_ms=243, request_id=42),
        ),
        pb.LogEntry(cursor=5, gap="logcat ended"),
        line(6, "no uid", uid=None),
    ]

    entries = device.logs.read()

    assert entries.dropped == 7
    assert [e.cursor for e in entries] == [1, 2, 3, 4, 5, 6]
    assert entries[0].marker == "checkout" and entries[0].line is None
    started, finished = entries[1].action, entries[3].action
    assert (started.operation, started.target, started.finished, started.outcome) == ("tap", 'text="Pay"', False, None)
    assert (finished.finished, finished.outcome, finished.duration, finished.request_id) == (True, "ok", 0.243, 42)
    warning = entries[2].line
    assert (warning.priority, warning.uid, warning.pid, warning.tag) == (LogPriority.WARN, 10123, 2346, "Shop")
    assert warning.device_time == pytest.approx(1_794_608_507.879)
    assert entries[2].at == pytest.approx(1_794_608_507.903)
    assert entries[5].line.uid is None
    assert entries.messages == ["paying", "no uid"]
    assert entries.text.splitlines() == [
        "── marker checkout",
        '── tap  text="Pay"',
        "1794608507.879 10123  2346  2357 W Shop: paying",
        "── tap  → ok  243 ms",
        "── gap logcat ended",
        "1794608507.879     ?  2346  2357 I Shop: no uid",
    ]


def test_read_sends_the_starting_point_and_the_filters(fake, device):
    fake.device_log.entries = [pb.LogEntry(cursor=1, marker="checkout")]
    device.logs.read(
        since="checkout", packages=["com.shop"], min_priority="warn", tags=["Shop"], grep="pa.d", actions=False
    )
    device.logs.read(since=12)
    device.logs.read(since=LogEntry(cursor=30, at=0.0))
    device.logs.read(tail=5, min_priority=LogPriority.ERROR)
    device.logs.read(since_last_action=True)
    device.logs.read()

    owner = dict(
        client_connection_id=device.owner_connection.id, attached_device_id=device.attached_device_id
    )
    assert fake.device_log.requests == [
        pb.ReadLogRequest(
            marker="checkout",
            packages=["com.shop"],
            min_priority=pb.LOG_PRIORITY_WARN,
            tags=["Shop"],
            message_regex="pa.d",
            exclude_actions=True,
            **owner,
        ),
        pb.ReadLogRequest(after_cursor=12, **owner),
        pb.ReadLogRequest(after_cursor=30, **owner),
        pb.ReadLogRequest(tail=5, min_priority=pb.LOG_PRIORITY_ERROR, **owner),
        pb.ReadLogRequest(last_action=True, **owner),
        pb.ReadLogRequest(**owner),
    ]
    assert fake.device_log.requests[-1].WhichOneof("from") is None


def test_bad_arguments_are_refused_before_any_call(fake, device):
    with pytest.raises(ValueError, match="only one of"):
        device.logs.read(since="a", tail=3)
    with pytest.raises(TypeError, match="lists of strings"):
        device.logs.read(packages="com.shop")
    with pytest.raises(ValueError, match="unknown log priority"):
        device.logs.read(min_priority="loud")
    with pytest.raises(TypeError, match="marker name"):
        device.logs.read(since=1.5)
    assert fake.device_log.requests == []


def test_an_unknown_marker_and_a_capture_that_is_off_carry_their_reason(fake, device):
    with pytest.raises(ServerError) as unknown:
        device.logs.read(since="never")
    assert unknown.value.reason is FailureReason.UNKNOWN_LOG_MARKER

    fake.device_log.capture_off = True
    with pytest.raises(ServerError) as off:
        device.logs.mark("x")
    assert off.value.reason is FailureReason.DAEMON_PRECONDITION


def test_mark_returns_the_cursor_and_redact_sends_the_patterns(fake, device):
    assert device.logs.mark("checkout") == 1
    assert device.logs.mark("paid") == 2
    device.logs.redact(r"session=(\w+)", "4111[0-9]{12}")

    assert [r.name for r in fake.device_log.requests[:2]] == ["checkout", "paid"]
    assert list(fake.device_log.requests[2].patterns) == [r"session=(\w+)", "4111[0-9]{12}"]


def test_follow_yields_the_backlog_then_live_entries_until_closed(fake, device):
    fake.device_log.entries = [line(1, "old"), line(2, "older")]
    fake.device_log.dropped = 3
    seen: list[str] = []
    ended = threading.Event()

    with device.logs.follow(tags=["Shop"]) as log:

        def read() -> None:
            for entry in log:
                seen.append(entry.line.message)
                if entry.line.message == "live":
                    log.close()
            ended.set()

        reader = threading.Thread(target=read)
        reader.start()
        fake.device_log.live.put(line(3, "live"))
        assert ended.wait(10)
        reader.join()

    assert seen == ["old", "older", "live"]
    assert log.dropped == 3
    assert fake.device_log.requests[0].follow and list(fake.device_log.requests[0].tags) == ["Shop"]
    assert fake.device_log.cancelled.wait(10)


def test_follow_ends_when_the_server_ends_the_stream(fake, device):
    fake.device_log.entries = [line(1, "only")]
    fake.device_log.live.put(None)

    assert [entry.cursor for entry in device.logs.follow()] == [1]


def test_attach_captures_logs_unless_turned_off(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("logs") as connection:
            connection.attach_device("emulator-5554")
            connection.attach_device("emulator-5556", capture_logs=False)

    first, second = fake.devices.attach_requests
    assert not first.HasField("capture_logs")
    assert second.HasField("capture_logs") and not second.capture_logs


def test_a_priority_is_named_by_word_or_letter():
    assert LogPriority.of("warn") is LogPriority.of("W") is LogPriority.of("WARNING") is LogPriority.WARN
    assert LogPriority.of(LogPriority.FATAL).letter == "F"


def test_calls_inside_a_step_carry_its_id_and_a_read_can_ask_for_the_step(fake, device):
    fake.device_log.entries = [
        pb.LogEntry(cursor=1, action=pb.LogAction(operation="tap", request_id=4, step="s-1")),
        pb.LogEntry(cursor=2, action=pb.LogAction(operation="launch")),
    ]
    device.press_back()
    with step("s-1") as outer:
        device.press_back()
        with step() as inner:
            device.press_back()
        assert current_step() == "s-1"
    device.press_back()

    assert outer == "s-1" and inner != outer and current_step() is None
    assert [s for s in fake.devices.steps if s != inner] == [None, "s-1", None]
    assert inner in fake.devices.steps
    entries = device.logs.read(step=outer)
    assert [e.action.step for e in entries] == ["s-1", None]
    assert fake.device_log.requests[-1].step == "s-1"
    for bad in ("", "two words", "x" * 65):
        with pytest.raises(ValueError):
            with step(bad):
                pass
