"""App deaths over a fake server: listing, the error's death, guard, watch_deaths, expect_death
and the end-of-test check's building blocks."""
# pyright: reportMissingImports=false

from __future__ import annotations

import threading

import pytest  # type: ignore[import-not-found]

from tap_e2e import AppDeathKind, AppDied, CommandError, ErrorCode, TapClient, desc, text
from tap_e2e import _gen as pb

from .conftest import TOKEN

PKG = "com.example.shop"


@pytest.fixture
def device(fake):
    server = TapClient.create(fake.address, TOKEN)
    connection = server.connect("test")
    device = connection.attach_device("emulator-5554")
    yield device
    fake.devices.responder = None
    connection.close()
    server.close()


def death(sequence: int, package: str = PKG, watched: bool = True, reported: bool = False) -> pb.AppDeath:
    return pb.AppDeath(
        sequence=sequence,
        kind=pb.AppDeath.KIND_CRASH,
        package_name=package,
        process_name=package,
        pid=23554,
        at_epoch_ms=1_790_000_014_107,
        exception_class="java.lang.IllegalStateException",
        message="Out of stock",
        stack_trace="FATAL EXCEPTION: main\n\tat com.example.shop.Cart.pay(Cart.kt:41)",
        watched=watched,
        reported=reported,
    )


def crashes_with(fake, record: pb.AppDeath) -> None:
    """Execute fails as AUT_CRASHED carrying ``record``, which the record then also holds."""

    def respond(command: pb.Command) -> pb.CommandResult | None:
        if command.HasField("device_info"):
            return None
        fake.devices.deaths.append(record)
        return pb.CommandResult(
            error=pb.Error(code=pb.ERR_AUT_CRASHED, detail="WAIT_TIMEOUT", message="crashed", app_death=record)
        )

    fake.devices.responder = respond


def test_deaths_are_listed_and_mapped_and_an_apps_are_its_own(fake, device):
    fake.devices.deaths += [death(1), death(2, package="com.google.android.gms", watched=False)]
    listed = device.app_deaths()
    assert [d.sequence for d in listed] == [1, 2]
    first = listed[0]
    assert first.kind is AppDeathKind.CRASH
    assert first.at == 1_790_000_014.107
    assert first.summary == f"{PKG} crashed (pid 23554): java.lang.IllegalStateException: Out of stock"
    assert [d.sequence for d in device.app_deaths(after=1)] == [2]
    assert [d.package_name for d in device.app(PKG).deaths()] == [PKG]
    assert fake.devices.death_requests[-1].package_name == PKG
    assert device.death_sequence() == 2


def test_a_failure_carries_the_death_that_replaced_its_code(fake, device):
    crashes_with(fake, death(1, reported=True))
    with pytest.raises(CommandError) as failure:
        device.screen.element(text("Pay")).tap()
    assert failure.value.code is ErrorCode.AUT_CRASHED
    assert failure.value.app_death is not None and failure.value.app_death.message == "Out of stock"


def test_guard_passes_outcomes_through_and_raises_for_a_death_however_it_was_learned(fake, device):
    with device.guard():
        pass

    # A death that failed no command: raised when the block ends.
    with pytest.raises(AppDied) as silent:
        with device.guard():
            fake.devices.deaths.append(death(1))
    assert [d.sequence for d in silent.value.deaths] == [1]
    assert "Cart.kt:41" in str(silent.value)
    # Claimed: a later block does not see it again.
    with device.guard():
        pass

    # A command that failed because of it: that failure is the cause.
    crashes_with(fake, death(2, reported=True))
    with pytest.raises(AppDied) as carried:
        with device.guard():
            device.screen.element(text("Pay")).tap()
    assert carried.value.death.sequence == 2
    assert isinstance(carried.value.failure, CommandError) and carried.value.__cause__ is carried.value.failure
    fake.devices.responder = None

    # The block's own failure, with a death: the death wins, the failure is chained.
    with pytest.raises(AppDied) as own:
        with device.guard():
            fake.devices.deaths.append(death(3))
            raise AssertionError("Order placed was not shown")
    assert str(own.value.failure) == "Order placed was not shown"

    # An unwatched app, a death already reported, and another app's for App.guard change nothing.
    with device.guard():
        fake.devices.deaths += [death(4, package="com.google.android.gms", watched=False), death(5, reported=True)]
    with device.app("com.example.other").guard():
        fake.devices.deaths.append(death(6))
    # Nor does a plain failure without a death.
    with pytest.raises(ValueError):
        with device.guard():
            raise ValueError("own")


def test_watch_deaths_returns_them_and_expect_death_requires_one(fake, device):
    app = device.app(PKG)
    with app.watch_deaths() as watched:
        fake.devices.deaths.append(death(1))
    assert [d.sequence for d in watched.deaths] == [1]

    # The command that met the death does not fail the block.
    crashes_with(fake, death(2, reported=True))
    with app.expect_death() as expected:
        app.element(desc("crash")).tap()
    assert expected.death is not None and expected.death.sequence == 2
    fake.devices.responder = None

    # A death logged a little after the block still counts.
    later = threading.Timer(0.3, lambda: fake.devices.deaths.append(death(3)))
    later.start()
    with app.expect_death(timeout=5) as expected:
        pass
    later.join()
    assert expected.death is not None and expected.death.sequence == 3

    with pytest.raises(AssertionError, match=PKG):
        with app.expect_death(timeout=0.3):
            pass
    with pytest.raises(ValueError):
        with app.expect_death():
            raise ValueError("not a death")


def test_take_unhandled_deaths_skips_what_was_handed_over_and_claims_the_rest(fake, device):
    fake.devices.deaths.append(death(1))
    mark = device.death_sequence()
    fake.devices.deaths += [death(2), death(3, reported=True), death(4, watched=False)]
    assert [d.sequence for d in device.take_unhandled_deaths(mark)] == [2]
    assert device.take_unhandled_deaths(mark) == []
