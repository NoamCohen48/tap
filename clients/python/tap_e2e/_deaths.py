"""App deaths for tests: listing and acknowledging the server's record, and the ``guard`` /
``allow_deaths`` / ``expect_death`` blocks behind ``Device`` and ``App``."""

from __future__ import annotations

import time
from collections.abc import Collection
from types import TracebackType
from typing import TYPE_CHECKING

from . import _gen as pb
from . import _proto
from .client import mapped_errors
from .errors import AppDiedError, CommandError
from .models import AppDeath, AppDeathKind

if TYPE_CHECKING:
    from .device import Device

# What a check asks the server to wait for after the latest input before it lists
# (ListAppDeathsRequest.settle_ms): Android logs a crash 10-200 ms after the input that caused it.
_SETTLE_MS = 300
# Deadline of each ListAppDeaths / AcknowledgeAppDeaths call: they read the server's record (plus
# the settle wait), not the device.
_LIST_TIMEOUT = 10.0
# How often expect_death asks again while it waits for the death after its block.
_EXPECT_POLL = 0.1
# The longest server-side wait of one ListAppDeaths call; await_deaths asks again for longer.
_MAX_SERVER_WAIT = 600.0


def list_deaths(
    device: Device, after: int, package_name: str | None, sequence_only: bool = False, settle: bool = False, wait: float = 0.0
) -> tuple[list[AppDeath], int]:
    """``ListAppDeaths``: the deaths after ``after`` (of ``package_name`` when given) and the
    latest sequence; ``settle`` first waits until 300 ms after the latest input, ``wait`` (seconds)
    for a death after ``after`` of any app."""
    device._ensure_usable("app_deaths")
    request = pb.ListAppDeathsRequest(
        client_connection_id=device.owner_connection.id,
        attached_device_id=device.attached_device_id,
        after_sequence=after,
        sequence_only=sequence_only,
        settle_ms=_SETTLE_MS if settle else 0,
        wait_ms=int(wait * 1000),
    )
    if package_name is not None:
        request.package_name = package_name
    with mapped_errors(device.serial):
        response = device.client.device_stub.ListAppDeaths(request, timeout=_LIST_TIMEOUT + wait)
    return [_proto.app_death(death, device.serial) for death in response.deaths], response.last_sequence


def await_deaths(device: Device, after: int, timeout: float, package_name: str | None) -> list[AppDeath]:
    """``Device.await_app_deaths``: the server wakes for any app's death, so another app's makes
    this wait again."""
    deadline = time.monotonic() + timeout
    cursor = after
    while True:
        left = deadline - time.monotonic()
        listed, last = list_deaths(device, cursor, None, wait=min(max(left, 0.0), _MAX_SERVER_WAIT))
        wanted = [d for d in listed if package_name is None or d.package_name == package_name]
        if wanted or time.monotonic() >= deadline:
            return wanted
        cursor = max(cursor, last)


def acknowledge(device: Device, deaths: Collection[AppDeath]) -> None:
    """``AcknowledgeAppDeaths``: ``deaths`` are handled, so they explain no later failure and fail
    no test."""
    sequences = [d.sequence for d in deaths if not d.handled]
    if not sequences:
        return
    device._ensure_usable("app_deaths")
    request = pb.AcknowledgeAppDeathsRequest(
        client_connection_id=device.owner_connection.id,
        attached_device_id=device.attached_device_id,
        sequences=sequences,
    )
    with mapped_errors(device.serial):
        device.client.device_stub.AcknowledgeAppDeaths(request, timeout=_LIST_TIMEOUT)


def mark(device: Device) -> int:
    """The latest death sequence: deaths after it are new."""
    return list_deaths(device, 0, None, sequence_only=True)[1]


def open_deaths(device: Device, after: int, package_name: str | None = None, settle: bool = True) -> list[AppDeath]:
    """Watched deaths after ``after`` that no client has yet (``AppDeath.handled``)."""
    deaths, _ = list_deaths(device, after, package_name, settle=settle)
    return [d for d in deaths if d.watched and not d.handled]


class DeathGuard:
    """What ``Device.guard()`` / ``App.guard()`` return: a context manager raising
    ``AppDiedError`` for a watched death inside its block."""

    def __init__(self, device: Device, package_name: str | None):
        self._device = device
        self._package = package_name
        self._mark = 0

    def __enter__(self) -> DeathGuard:
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
        later = [
            d for d in open_deaths(self._device, self._mark, self._package, settle=carried is None)
            if carried is None or d.sequence != carried.sequence
        ]
        acknowledge(self._device, later)
        deaths = sorted(([carried] if carried else []) + later, key=lambda d: d.sequence)
        if not deaths:
            return False
        raise AppDiedError(deaths, exc) from exc


class AllowedDeaths:
    """What ``Device.allow_deaths()`` / ``App.allow_deaths()`` return: a context manager that lets
    watched apps die inside its block. Those deaths are handled (they fail no test and explain no
    later failure) and are its ``deaths`` once the block ends; the block's outcome, including a
    ``CommandError`` for a death, comes through unchanged."""

    def __init__(self, device: Device, package_name: str | None):
        self._device = device
        self._package = package_name
        self._mark = 0
        self.deaths: list[AppDeath] = []
        """The watched deaths during the block, oldest first; empty until the block ends."""

    def __enter__(self) -> AllowedDeaths:
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
        # Every watched death since the mark, handled ones included: an error carried those.
        listed, _ = list_deaths(self._device, self._mark, self._package, settle=True)
        self.deaths = [d for d in listed if d.watched]
        acknowledge(self._device, self.deaths)
        return False


class ExpectedDeath:
    """What ``Device.expect_death()`` / ``App.expect_death()`` return: a context manager requiring
    a watched death (of ``kind`` when given) in its block or within ``timeout`` seconds after it.
    A ``CommandError`` carrying that death does not fail the block. Only that death is handled:
    another one during the block still fails the test."""

    def __init__(self, device: Device, package_name: str | None, kind: AppDeathKind | None, timeout: float):
        self._device = device
        self._package = package_name
        self._kind = kind
        self._timeout = timeout
        self._mark = 0
        self._death: AppDeath | None = None

    @property
    def death(self) -> AppDeath:
        """The death the block caused. Raises ``RuntimeError`` before the block has ended."""
        if self._death is None:
            raise RuntimeError("the expect_death block has not ended")
        return self._death

    def _expected(self, death: AppDeath) -> bool:
        return (self._package is None or death.package_name == self._package) and (self._kind is None or death.kind is self._kind)

    def __enter__(self) -> ExpectedDeath:
        self._mark = mark(self._device)
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> bool:
        if exc is not None:
            carried = exc.app_death if isinstance(exc, CommandError) else None
            if carried is None or not self._expected(carried):
                return False  # the block failed in another way
            self._death = carried  # the server handled it when the error carried it
            return True  # suppresses the carried CommandError
        deadline = time.monotonic() + self._timeout
        settle = True
        while True:
            death = next((d for d in open_deaths(self._device, self._mark, self._package, settle) if self._expected(d)), None)
            settle = False
            if death is not None:
                acknowledge(self._device, [death])
                self._death = death
                return False
            if time.monotonic() >= deadline:
                who = self._package or "a watched app"
                how = {AppDeathKind.CRASH: "crash", AppDeathKind.ANR: "stop responding"}.get(self._kind, "crash or stop responding")  # type: ignore[arg-type]
                raise AssertionError(f"expected {who} on {self._device.serial} to {how} within {self._timeout} s; it did not")
            time.sleep(_EXPECT_POLL)
