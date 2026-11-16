"""Collections are value-only reads, never count/at loops or diagnostic dumps."""

from dataclasses import FrozenInstanceError
from decimal import Decimal

import pytest  # pyright: ignore[reportMissingImports]  # Installed in the test venv.

from tap_e2e import CommandError, Elements, ErrorCode, TapClient, checkable, res
from tap_e2e import _gen as pb

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with TapClient.create(fake.address, TOKEN) as client, client.connect("collections") as connection:
        yield connection.attach_device("emulator-5554")


def reply(*values):
    return pb.CommandResult(snapshots=pb.ElementSnapshotList(elements=values))


def test_lazy_collection_reads_titles_and_prices_in_one_command_each(fake, device):
    commands = []
    values = [pb.ElementSnapshot(text=value) for value in ("Socks", "Shirt", "Coat")]

    def respond(command):
        commands.append(command)
        return reply(*values)

    fake.devices.responder = respond
    titles = device.app("com.example.shop").elements(res("products").descendant(res("title")))
    assert isinstance(titles, Elements)
    assert commands == []
    assert titles.texts(timeout=2.5) == ["Socks", "Shirt", "Coat"]
    assert len(commands) == 1
    command = commands[0]
    assert command.WhichOneof("op") == "snapshots"
    assert command.timeout_ms == 2500
    expected = res("products").descendant(res("title"))._in_package("com.example.shop")
    assert command.snapshots.selector == expected._proto

    values[:] = [pb.ElementSnapshot(text=value) for value in ("4.50", "9.99", "30.00")]
    prices = []
    for value in device.app("com.example.shop").elements(res("price")).texts():
        assert value is not None
        prices.append(Decimal(value))
    assert prices == sorted(prices)
    assert len(commands) == 2
    assert all(c.WhichOneof("op") == "snapshots" for c in commands)


def test_screen_collection_keeps_scope_and_matches_order_duplicates_and_missing_text(fake, device):
    commands = []

    def respond(command):
        commands.append(command)
        return reply(
            pb.ElementSnapshot(text="duplicate", package_name="com.example.app"),
            pb.ElementSnapshot(),
            pb.ElementSnapshot(text=""),
            pb.ElementSnapshot(text="duplicate", package_name="android"),
            pb.ElementSnapshot(text="Hint", hint="Hint", showing_hint=True),
        )

    fake.devices.responder = respond
    selector = res("title")
    assert device.screen.elements(selector).texts() == ["duplicate", None, "", "duplicate", "Hint"]
    assert len(commands) == 1
    assert commands[0].snapshots.selector == selector._proto
    assert commands[0].timeout_ms == int(device.timeouts.action * 1000)


def test_checkbox_collection_carries_state_and_values_are_immutable(fake, device):
    commands = []

    def respond(command):
        commands.append(command)
        return reply(
            pb.ElementSnapshot(text="Email", checkable=True, checked=True, enabled=True, selected=True),
            pb.ElementSnapshot(text="SMS", checkable=True, checked=False, enabled=False),
        )

    fake.devices.responder = respond
    values = device.app("com.example.shop").elements(res("preferences").descendant(checkable())).snapshots()
    assert [(value.text, value.checkable, value.checked, value.enabled, value.selected) for value in values] == [
        ("Email", True, True, True, True), ("SMS", True, False, False, False),
    ]
    assert len(commands) == 1
    with pytest.raises(FrozenInstanceError):
        values[0].checked = False


def test_each_call_resolves_afresh_and_empty_matches_succeed(fake, device):
    commands = []
    values = [pb.ElementSnapshot(text="old")]

    def respond(command):
        commands.append(command)
        return reply(*values)

    fake.devices.responder = respond
    collection = device.screen.elements(res("title"))
    old = collection.snapshots()
    values[:] = [pb.ElementSnapshot(text="new"), pb.ElementSnapshot(text="another")]
    assert collection.texts() == ["new", "another"]
    assert old[0].text == "old"
    values.clear()
    assert collection.snapshots() == []
    assert collection.texts() == []
    assert len(commands) == 4
    assert all(command.snapshots.selector == res("title")._proto for command in commands)


@pytest.mark.parametrize("scope", ["app", "screen"])
@pytest.mark.parametrize("picked", [res("title").first(), res("title").at(0)])
def test_picks_are_rejected_without_io(fake, device, scope, picked):
    context = device.app("com.example.shop") if scope == "app" else device.screen
    before = len(fake.devices.commands)
    with pytest.raises(ValueError, match=r"first\(\)/at\(\)"):
        context.elements(picked)
    assert len(fake.devices.commands) == before


@pytest.mark.parametrize("code", [pb.ERR_STALE_BEFORE_INPUT, pb.ERR_PAYLOAD_TOO_LARGE])
@pytest.mark.parametrize("method", ["snapshots", "texts"])
def test_failed_reads_raise_once_with_selector_context(fake, device, code, method):
    commands = []

    def respond(command):
        commands.append(command)
        return pb.CommandResult(error=pb.Error(code=code, message="bulk read failed"))

    fake.devices.responder = respond
    with pytest.raises(CommandError) as failure:
        getattr(device.app("com.example.shop").elements(res("title")), method)()
    assert failure.value.code == (ErrorCode.STALE_BEFORE_INPUT if code == pb.ERR_STALE_BEFORE_INPUT else ErrorCode.PAYLOAD_TOO_LARGE)
    assert "title" in str(failure.value)
    assert len(commands) == 1
