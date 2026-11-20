"""Bounded observation across virtualized viewports, with no completeness promise."""

import pytest  # pyright: ignore[reportMissingImports]

from tap_e2e import (
    CommandError,
    Movement,
    ScanStopReason,
    ScrollEdge,
    TapClient,
    res,
    scanning,
)
from tap_e2e import _gen as pb

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with (
        TapClient.create(fake.address, TOKEN) as client,
        client.connect("scan") as connection,
    ):
        yield connection.attach_device("emulator-5554")


def row(identity, top=0, text=None):
    return pb.ElementSnapshot(
        content_description=identity,
        text=text or identity,
        bounds=pb.Bounds(top=top, bottom=top + 100),
    )


def pages(fake, values, *, edges=(), movement=0):
    commands = []
    state = [0]

    def respond(command):
        commands.append(command)
        kind = command.WhichOneof("op")
        if kind == "snapshot":
            return pb.CommandResult(snapshot=pb.ElementSnapshot())
        if kind == "snapshots":
            return pb.CommandResult(
                snapshots=pb.ElementSnapshotList(
                    elements=values[min(state[0], len(values) - 1)]
                )
            )
        assert kind == "scroll"
        state[0] += 1
        return pb.CommandResult(
            done=pb.Done(),
            scroll_observation=pb.ScrollObservation(
                movement=pb.ScrollObservation.MOVEMENT_MOVED
                if movement
                else pb.ScrollObservation.MOVEMENT_UNSPECIFIED,
                edge=pb.ScrollObservation.EDGE_REACHED
                if state[0] in edges
                else pb.ScrollObservation.EDGE_UNSPECIFIED,
            ),
        )

    fake.devices.responder = respond
    return commands


def scan(device, **options):
    return device.screen.element(res("list")).scan(
        res("row"),
        key=lambda value: value.content_description,
        **({"max_scrolls": 10, "max_items": 100, "timeout": 5} | options),
    )


def test_collects_overlap_and_last_edge_viewport(fake, device):
    commands = pages(
        fake,
        [[row("a"), row("b", 100)], [row("b"), row("c", 100)]],
        edges=(1,),
        movement=1,
    )
    result = scan(device)
    assert result.stop_reason == ScanStopReason.EDGE_DETECTED
    assert [value.key for value in result.observations] == ["a", "b", "c"]
    assert result.observations[1].seen_in_reads == 2
    assert result.observations[1].first_snapshot.bounds.top == 100
    assert result.observations[1].last_snapshot.bounds.top == 0
    assert (result.reads, result.scrolls) == (2, 1)
    assert result.coverage == "NOT_GUARANTEED"
    assert result.last_scroll.edge == ScrollEdge.REACHED
    assert result.last_scroll.movement == Movement.MOVED
    assert all(0 < command.timeout_ms <= 5000 for command in commands)
    assert commands[1].snapshots.selector == res("list").descendant(res("row"))._proto


def test_no_progress_is_not_end(fake, device):
    pages(fake, [[row("a")]])
    result = scan(device)
    assert result.stop_reason == ScanStopReason.NO_PROGRESS
    assert result.scrolls == 2
    assert result.last_scroll.edge == ScrollEdge.UNKNOWN


def test_movement_without_new_keys_is_progress(fake, device):
    pages(fake, [[row("a")]], movement=1)
    assert scan(device, max_scrolls=3).stop_reason == ScanStopReason.SCROLL_LIMIT


def test_changed_bounds_are_progress(fake, device):
    pages(fake, [[row("a", n)] for n in range(4)])
    assert scan(device, max_scrolls=3).stop_reason == ScanStopReason.SCROLL_LIMIT


def test_volatile_text_is_not_progress_but_retains_latest(fake, device):
    pages(fake, [[row("a", text=str(n))] for n in range(3)])
    result = scan(device)
    assert result.stop_reason == ScanStopReason.NO_PROGRESS
    assert result.observations[0].first_snapshot.text == "0"
    assert result.observations[0].last_snapshot.text == "2"
    assert result.warnings == ("CONTENT_CHANGED",)


@pytest.mark.parametrize(
    "options,reason,scrolls",
    [
        ({"max_scrolls": 0}, ScanStopReason.SCROLL_LIMIT, 0),
        ({"max_items": 1}, ScanStopReason.ITEM_LIMIT, 0),
    ],
)
def test_read_only_and_item_limits(fake, device, options, reason, scrolls):
    pages(fake, [[row("a"), row("b")]])
    result = scan(device, **options)
    assert result.stop_reason == reason
    assert result.scrolls == scrolls
    if reason == ScanStopReason.ITEM_LIMIT:
        assert result.warnings == ("ITEMS_OMITTED",)


def test_key_collision_and_missing_key_fail_with_partial(fake, device):
    pages(fake, [[row("a")], [row("b"), row("b")]])
    with pytest.raises(ValueError, match="collision") as error:
        scan(device)
    assert [v.key for v in error.value.scan_partial.observations] == ["a"]
    assert error.value.scan_partial.stop_reason is None
    pages(fake, [[pb.ElementSnapshot()]])
    with pytest.raises(ValueError, match="None"):
        scan(device)


def test_original_command_failure_retained_without_replay(fake, device):
    commands = pages(fake, [[row("a")]])
    respond = fake.devices.responder

    def fail(command):
        if command.WhichOneof("op") == "scroll":
            return pb.CommandResult(error=pb.Error(code=pb.ERR_INDETERMINATE))
        return respond(command)

    fake.devices.responder = fail
    with pytest.raises(CommandError) as error:
        scan(device)
    assert error.value.code.name == "INDETERMINATE"
    assert error.value.scan_partial.scrolls == 1
    assert [v.key for v in error.value.scan_partial.observations] == ["a"]
    assert len(commands) == 2


def test_deadline_expiring_after_scroll_does_not_claim_edge(fake, device, monkeypatch):
    pages(fake, [[row("a")]], edges=(1,))
    now = [0.0]
    monkeypatch.setattr(scanning.time, "monotonic", lambda: now[0])
    respond = fake.devices.responder

    def expire(command):
        result = respond(command)
        if command.WhichOneof("op") == "scroll":
            now[0] = 10.0
        return result

    fake.devices.responder = expire
    result = scan(device)
    assert result.stop_reason == ScanStopReason.TIME_LIMIT
    assert result.reads == 1


@pytest.mark.parametrize(
    "options",
    [
        {"max_scrolls": -1},
        {"max_scrolls": True},
        {"max_items": 0},
        {"timeout": float("inf")},
        {"timeout": 0},
        {"no_progress_limit": 0},
        {"distance_percent": 101},
    ],
)
def test_invalid_limits_before_io(fake, device, options):
    commands = pages(fake, [[]])
    with pytest.raises(ValueError):
        scan(device, **options)
    assert commands == []


def test_picked_container_is_never_broadened(fake, device):
    commands = pages(fake, [[]])
    with pytest.raises(ValueError, match="first"):
        device.screen.element(res("list").first()).scan(
            res("row"), lambda x: x.text, max_scrolls=1, max_items=10, timeout=1
        )
    assert commands == []


@pytest.mark.parametrize("code", [pb.ERR_NOT_FOUND, pb.ERR_AMBIGUOUS])
def test_missing_or_ambiguous_container_never_reads_other_containers(fake, device, code):
    commands = []

    def refuse(command):
        commands.append(command)
        return pb.CommandResult(error=pb.Error(code=code))

    fake.devices.responder = refuse
    with pytest.raises(CommandError) as error:
        scan(device)
    assert error.value.scan_partial.observations == ()
    assert [command.WhichOneof("op") for command in commands] == ["snapshot"]


def test_long_scan_budget_respects_per_command_protocol_limit(fake, device):
    commands = pages(fake, [[row("a")]])
    assert scan(device, timeout=1000, max_scrolls=0).stop_reason == ScanStopReason.SCROLL_LIMIT
    assert all(0 < command.timeout_ms <= 120_000 for command in commands)


def test_unhashable_keys_preserve_type_error_with_partial(fake, device):
    pages(fake, [[row("a")]])
    with pytest.raises(TypeError) as error:
        device.screen.element(res("list")).scan(
            res("row"), lambda value: [], max_scrolls=1, max_items=10, timeout=1
        )
    assert error.value.scan_partial.stop_reason is None


def test_older_driver_scroll_result_is_unknown(fake, device):
    fake.devices.responder = lambda command: pb.CommandResult(done=pb.Done())
    result = device.screen.element(res("list")).scroll(scanning.Direction.DOWN)
    assert result.edge == ScrollEdge.UNKNOWN
    assert result.movement == Movement.UNKNOWN
