import pytest  # pyright: ignore[reportMissingImports]  # Installed in the test venv.

from tap_e2e import _gen as pb
from tap_e2e import CommandError, ErrorCode, OutsideTouch, TapClient, res, text

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("tap-outside") as connection:
            yield connection.attach_device("emulator-5554")


def test_tap_outside_panel_sends_one_command_naming_the_panel_node(fake, device):
    commands = []

    def respond(command):
        commands.append(command)
        return pb.CommandResult(outside_touch=pb.OutsideTouch(x=540, y=1519, panel="window [28,943][1052,1456], below"))

    fake.devices.responder = respond
    touch = device.screen.element(text("Settings title")).tap_outside_panel()
    assert touch == OutsideTouch(x=540, y=1519, panel="window [28,943][1052,1456], below")
    device.screen.element(res("autocomplete")).tap_outside_panel(timeout=3, allow_reach_below=True)
    assert [c.WhichOneof("op") for c in commands] == ["tap_outside_panel", "tap_outside_panel"]
    assert commands[0].tap_outside_panel.selector == text("Settings title")._proto
    assert not commands[0].tap_outside_panel.allow_reach_below
    assert commands[1].tap_outside_panel.allow_reach_below
    assert commands[1].timeout_ms == 3000


def test_a_refusal_carries_its_detail(fake, device):
    fake.devices.responder = lambda command: pb.CommandResult(
        error=pb.Error(code=pb.ERR_NOT_INTERACTABLE, detail="NO_OUTSIDE_POINT", message="No point outside window [0,0][1080,2400]"))
    with pytest.raises(CommandError) as raised:
        device.screen.element(text("Under bottom")).tap_outside_panel()
    assert raised.value.code is ErrorCode.NOT_INTERACTABLE
    assert raised.value.detail == "NO_OUTSIDE_POINT"
