"""App deaths for tests: listing the server's record, and the ``guard`` / ``watch_deaths`` /
``expect_death`` blocks behind ``Device`` and ``App``."""

from __future__ import annotations

import time
from types import TracebackType
from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .client import mapped_errors
from .errors import AppDied, CommandError
from .models import AppDeath

if TYPE_CHECKING:
    from .device import Device

SETTLE = 0.3
"""Seconds a block, and the pytest plugin's end-of-test check, wait after their last call before
asking for deaths: Android logs a crash 10–200 ms after the input that caused it, so a crash the
block's last tap caused is still seen. One logged later is seen by the next command or check."""

# Deadline of each ListAppDeaths call: it reads the server's record, not the device.
_LIST_TIMEOUT = 10.0
# How often expect_death asks again while it waits for the death after its block.
_EXPECT_POLL = 0.1


def list_deaths(device: Device, after: int, package_name: str | None, sequence_only: bool = False) -> tuple[list[AppDeath], int]:
    """``ListAppDeaths``: the deaths after ``after`` (of ``package_name`` when given) and the
    latest sequence."""
    device._ensure_usable("app_deaths")
    request = pb.ListAppDeathsRequest(
        client_connection_id=device.owner_connection.id,
        attached_device_id=device.attached_device_id,
        after_sequence=after,
        sequence_only=sequence_only,
    )
    if package_name is not None:
        request.package_name = package_name
    with mapped_errors(device.serial):
        response = device.client.device_stub.ListAppDeaths(request, timeout=_LIST_TIMEOUT)
    return [_proto.app_death(death) for death in response.deaths], response.last_sequence


def mark(device: Device) -> int:
    """The latest death sequence: deaths after it are new."""
    return list_deaths(device, 0, None, sequence_only=True)[1]


def unhandled(device: Device, after: int) -> list[AppDeath]:
    """Watched deaths after ``after`` that nothing handed over yet: no error carried them and no
    block claimed them."""
    deaths, _ = list_deaths(device, after, None)
    return [d for d in deaths if d.watched and not d.reported and d.sequence not in device._claimed_deaths]


class Guard:
    """``Device.guard()`` / ``App.guard()``: a context manager raising ``AppDied`` for a watched
    death inside its block. ``package_name`` None = every watched app."""

    def __init__(self, device: Device, package_name: str | None):
        self._device = device
        self._package = package_name
        self._mark = 0

    def __enter__(self) -> Guard:
        self._mark = mark(self._device)
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> bool:
        if exc is not None and not isinstance(exc, Exception):
            return False  # KeyboardInterrupt, SystemExit, …: never replaced
        carried = exc.app_death if isinstance(exc, CommandError) else None
        if carried is not None and self._package is not None and carried.package_name != self._package:
            carried = None
        if carried is None:
            time.sleep(SETTLE)
        claimed = self._device._claimed_deaths
        later, _ = list_deaths(self._device, self._mark, self._package)
        deaths = ([carried] if carried else []) + [
            d for d in later if d.watched and not d.reported and d.sequence not in claimed and d.sequence != (carried.sequence if carried else None)
        ]
        if not deaths:
            return False
        deaths.sort(key=lambda d: d.sequence)
        claimed.update(d.sequence for d in deaths)
        raise AppDied(deaths, exc) from exc


class WatchedDeaths:
    """``Device.watch_deaths()`` / ``App.watch_deaths()``: a context manager whose ``deaths`` are
    the watched deaths during its block, set when the block ends. Never raises for a death."""

    def __init__(self, device: Device, package_name: str | None):
        self._device = device
        self._package = package_name
        self._mark = 0
        self.deaths: list[AppDeath] = []
        """The watched deaths during the block, oldest first; empty until the block ends."""

    def __enter__(self) -> WatchedDeaths:
        self._mark = mark(self._device)
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> bool:
        if exc is not None and not isinstance(exc, Exception):
            return False
        time.sleep(SETTLE)
        claimed = self._device._claimed_deaths
        later, _ = list_deaths(self._device, self._mark, self._package)
        self.deaths = [d for d in later if d.watched and d.sequence not in claimed]
        claimed.update(d.sequence for d in self.deaths)
        return False


class ExpectedDeath:
    """``Device.expect_death()`` / ``App.expect_death()``: a context manager requiring a watched
    death in its block (or within ``timeout`` seconds after it); ``death`` is set when the block
    ends. A ``CommandError`` carrying that death does not fail the block."""

    def __init__(self, device: Device, package_name: str | None, timeout: float):
        self._device = device
        self._package = package_name
        self._timeout = timeout
        self._mark = 0
        self.death: AppDeath | None = None
        """The death the block caused; None until the block ends."""

    def __enter__(self) -> ExpectedDeath:
        self._mark = mark(self._device)
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> bool:
        carried = exc.app_death if isinstance(exc, CommandError) else None
        if carried is not None and self._package is not None and carried.package_name != self._package:
            carried = None
        if exc is not None and carried is None:
            return False  # the block failed in another way
        claimed = self._device._claimed_deaths
        deadline = time.monotonic() + self._timeout
        while True:
            later, _ = list_deaths(self._device, self._mark, self._package)
            watched = [d for d in later if d.watched and d.sequence not in claimed]
            death = carried or (watched[0] if watched else None)
            if death is not None:
                claimed.update(d.sequence for d in watched)
                claimed.add(death.sequence)
                self.death = death
                return True  # suppresses the carried CommandError, if any
            if time.monotonic() >= deadline:
                who = self._package or "a watched app"
                raise AssertionError(
                    f"expected {who} on {self._device.serial} to crash or stop responding within {self._timeout} s; it did not"
                )
            time.sleep(_EXPECT_POLL)
