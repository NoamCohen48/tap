import math

import pytest  # pyright: ignore[reportMissingImports]  # Installed in the test venv.

from tap_e2e import _gen as pb
from tap_e2e import res, TapClient

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("timed-touch") as connection:
            yield connection.attach_device("emulator-5554")


def test_counted_taps_and_timed_press_send_one_command_each(fake, device):
    commands = []

    def respond(command):
        commands.append(command)
        return pb.CommandResult(done=pb.Done())

    fake.devices.responder = respond
    element = device.screen.element(res("target"))
    element.tap()
    element.long_tap()
    element.double_tap()
    element.triple_tap()
    element.tap(count=7, interval=0.2)
    element.long_tap(duration=2.5, timeout=4)
    assert [c.WhichOneof("op") for c in commands] == ["tap", "long_tap", "double_tap", "multi_tap", "multi_tap", "hold"]
    assert commands[3].multi_tap.count == 3
    assert commands[3].multi_tap.interval_ms == 100
    assert commands[4].multi_tap.count == 7
    assert commands[4].multi_tap.interval_ms == 200
    assert commands[5].hold.duration_ms == 2500
    assert commands[5].timeout_ms == 4000
    assert commands[5].hold.selector == res("target")._proto


@pytest.mark.parametrize("kwargs", [
    {"count": 0}, {"count": -1}, {"count": 101}, {"count": True}, {"count": 1.5},
    {"interval": 0.039}, {"interval": 1.001}, {"interval": math.nan}, {"interval": math.inf},
    {"count": 100, "interval": 1},
])
def test_invalid_taps_never_send_input(fake, device, kwargs):
    before = len(fake.devices.commands)
    with pytest.raises(ValueError):
        device.screen.element(res("target")).tap(**kwargs)
    assert len(fake.devices.commands) == before


@pytest.mark.parametrize("duration", [0, -1, 0.0009, 30.001, math.nan, math.inf, True])
def test_invalid_hold_never_sends_input(fake, device, duration):
    before = len(fake.devices.commands)
    with pytest.raises(ValueError):
        device.screen.element(res("target")).long_tap(duration=duration)
    assert len(fake.devices.commands) == before
