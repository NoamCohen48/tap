"""pytest integration: per-test attached devices through the Tap server.

Configuration (ini option, or environment variable):

    tap_aut       / TAP_AUT        AUT package (required)
    tap_serials   / TAP_SERIALS    comma-separated serials to use; roles map to them in
                                   order, starting one device further on for each test
                                   (wrapping). Unset = whatever the server's device list offers.
    tap_artifacts / TAP_ARTIFACTS  failure artifact directory (default tap-artifacts)
    tap_server     / TAP_SERVER      host:port of a running server (default: the one `tap start` recorded)
                   TAP_TOKEN       bearer token for an explicit TAP_SERVER (default: read from
                                   daemon.json in the state dir)
    tap_manage_daemon / TAP_MANAGE_DAEMON
                                   true = run `tap start` before the first test and `tap stop`
                                   after the last one if that start created the server
                                   (default false: a server must already be running)
    tap_acquire_timeout            seconds to wait for a device another session holds (default 300)

Fixtures: ``tap_device`` (role "device") and ``tap_devices`` (dict role → Device).
Markers: ``@pytest.mark.tap_devices("left", "right")`` declares roles. A test needing more roles
than available devices is skipped. The starting device rotates from test to test, and a
single-role test moves on to the next device when one is held by another session (waiting only
when all are). Before each test one device per role is attached in sorted
serial order so concurrent multi-device tests cannot deadlock; after it, failure artifacts
(screenshot, hierarchy, device info, driver log) are captured while devices remain attached,
then they are detached. Attachments never outlive a test.
"""

from __future__ import annotations

import concurrent.futures
import contextlib
import itertools
import os
import pathlib
import re
import time
import traceback
from collections.abc import Generator
from dataclasses import dataclass, field

import pytest  # type: ignore[import-not-found]

from .device import Device
from .errors import DeviceBusyError, DeviceQuarantinedError
from .client import TapConnection, TapClient, start_daemon, stop_daemon

DEFAULT_ROLE = "device"

# Process-wide start index for role assignment; advanced once per test.
_ROTATION = itertools.count()

# Seconds to wait for a device another session holds (`InfoResponse.defaults.acquire_timeout_ms`).
DEFAULT_ACQUIRE_TIMEOUT = 300.0


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addini("tap_aut", "AUT package name")
    parser.addini("tap_serials", "comma-separated device serials")
    parser.addini(
        "tap_artifacts", "failure artifact directory", default="tap-artifacts"
    )
    parser.addini("tap_server", "host:port of a running tap server")
    parser.addini(
        "tap_manage_daemon",
        "start the daemon before the run and stop it afterwards if started here",
        default="false",
    )
    parser.addini(
        "tap_acquire_timeout",
        "seconds to wait for a device another session holds",
        default=str(DEFAULT_ACQUIRE_TIMEOUT),
    )


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers",
        "tap_devices(*roles): device roles this test needs (default: one 'device')",
    )


@dataclass
class TapConfig:
    aut: str
    serials: list[str]
    artifacts: pathlib.Path
    server: str | None
    acquire_timeout: float
    manage_daemon: bool

    @classmethod
    def from_pytest(cls, config: pytest.Config) -> TapConfig:
        def option(name: str, env: str, default: str = "") -> str:
            return os.environ.get(env) or str(config.getini(name) or default)

        serials = [
            s.strip()
            for s in option("tap_serials", "TAP_SERIALS").split(",")
            if s.strip()
        ]
        return cls(
            aut=option("tap_aut", "TAP_AUT"),
            serials=serials,
            artifacts=pathlib.Path(
                option("tap_artifacts", "TAP_ARTIFACTS", "tap-artifacts")
            ),
            server=option("tap_server", "TAP_SERVER") or None,
            acquire_timeout=float(
                option(
                    "tap_acquire_timeout",
                    "TAP_ACQUIRE_TIMEOUT",
                    str(DEFAULT_ACQUIRE_TIMEOUT),
                )
            ),
            manage_daemon=option(
                "tap_manage_daemon", "TAP_MANAGE_DAEMON", "false"
            ).lower()
            in ("1", "true", "yes"),
        )


@dataclass
class TestDevices:
    devices: dict[str, Device]
    serials: list[str]
    failure: BaseException | None = None
    reports: dict[str, pytest.TestReport] = field(default_factory=dict)


# The attached devices of the running test, shared by the fixture and the report hook.
_STATE = pytest.StashKey[TestDevices]()


@pytest.fixture(scope="session")
def tap_config(pytestconfig: pytest.Config) -> TapConfig:
    config = TapConfig.from_pytest(pytestconfig)
    if not config.aut:
        pytest.fail("tap_aut (or TAP_AUT) must name the AUT package", pytrace=False)
    return config


@pytest.fixture(scope="session")
def tap_client(tap_config: TapConfig) -> Generator[TapClient, None, None]:
    """The server channel. With ``tap_manage_daemon`` the daemon is started here and, if
    that start created it, stopped after the session; one already running is left alone."""
    started = False
    if tap_config.server:
        client = TapClient.create(tap_config.server)
    else:
        if tap_config.manage_daemon:
            started = start_daemon().started
        client = TapClient.create()
    yield client
    client.close()
    if started:
        stop_daemon()


@pytest.fixture(scope="session")
def tap_connection(
    tap_client: TapClient, request: pytest.FixtureRequest
) -> Generator[TapConnection, None, None]:
    """One ``TapConnection`` per pytest session (observing from the start); the server tears
    everything down if this process dies."""
    connection = tap_client.connect(
        f"pytest {os.getpid()} {request.config.rootpath.name}"
    )
    yield connection
    connection.close()


def _declared_roles(item: pytest.Item) -> list[str]:
    marker = item.get_closest_marker("tap_devices")
    roles = list(marker.args) if marker and marker.args else [DEFAULT_ROLE]
    return list(dict.fromkeys(roles))


def _rotate(serials: list[str], start: int) -> list[str]:
    """``serials`` starting at index ``start`` (modulo the length), wrapping around."""
    if not serials:
        return serials
    offset = start % len(serials)
    return serials[offset:] + serials[:offset]


def _assign(roles: list[str], available: list[str], start: int) -> dict[str, str]:
    """Roles → serials in declaration order over ``available`` rotated by ``start``."""
    return dict(zip(roles, _rotate(available, start), strict=False))


def _attach_single(
    connection: TapConnection, config: TapConfig, candidates: list[str]
) -> Device:
    """Attaches the first of ``candidates`` that is free right now, trying the next on
    ``DeviceBusyError``; when all are busy, waits for the first (up to ``tap_acquire_timeout``)."""
    if len(candidates) > 1:
        for serial in candidates:
            try:
                return connection.attach_device(serial, config.aut)
            except DeviceBusyError:
                continue  # held by another session right now: try the next device
    return connection.attach_device(
        candidates[0], config.aut, wait_for_device=config.acquire_timeout
    )


def _attach_all(
    connection: TapConnection, config: TapConfig, assignment: dict[str, str]
) -> dict[str, Device]:
    """Attaches devices one at a time in sorted serial order. Every process takes device locks
    in the same order, so two tests wanting the same two devices cannot deadlock; the second
    waits (up to ``tap_acquire_timeout``) for the first to finish."""
    opened: dict[str, Device] = {}
    try:
        for role, serial in sorted(assignment.items(), key=lambda item: item[1]):
            opened[role] = connection.attach_device(
                serial, config.aut, wait_for_device=config.acquire_timeout
            )
    except BaseException:
        for device in opened.values():
            with contextlib.suppress(Exception):
                device.detach()
        raise
    return {role: opened[role] for role in assignment}


@pytest.fixture
def tap_devices(
    request: pytest.FixtureRequest,
    tap_connection: TapConnection,
    tap_config: TapConfig,
) -> Generator[dict[str, Device], None, None]:
    roles = _declared_roles(request.node)
    # Roles → serials is decided here (declaration order over a rotated device list); the
    # server only knows serials.
    available = tap_config.serials or tap_connection.available_serials()
    if len(roles) > len(available):
        where = (
            f"tap_serials lists {tap_config.serials}"
            if tap_config.serials
            else f"the server lists {available}"
        )
        pytest.skip(f"{request.node.name} needs {len(roles)} devices but {where}")
    start = next(_ROTATION)
    if len(roles) == 1:
        device = _attach_single(
            tap_connection, tap_config, _rotate(available, start)
        )
        devices = {roles[0]: device}
    else:
        devices = _attach_all(
            tap_connection, tap_config, _assign(roles, available, start)
        )
    state = TestDevices(devices, [device.serial for device in devices.values()])
    request.node.stash[_STATE] = state
    yield devices
    failed = any(report.failed for report in state.reports.values())
    try:
        if failed:
            _capture_artifacts(request.node, tap_config, state)
    finally:
        close_errors = []
        for device in devices.values():
            try:
                quarantine = device.detach()
                if quarantine:
                    close_errors.append(
                        DeviceQuarantinedError(
                            device.serial, f"{device.serial} quarantined: {quarantine}"
                        )
                    )
            except Exception as error:  # noqa: BLE001
                close_errors.append(error)
        # A cleanup failure after a passing test is a real failure: the device may be quarantined.
        if close_errors and not failed:
            raise close_errors[0]


@pytest.fixture
def tap_device(tap_devices: dict[str, Device]) -> Device:
    if DEFAULT_ROLE not in tap_devices:
        pytest.fail(
            f"tap_device needs role '{DEFAULT_ROLE}' but the test declared {list(tap_devices)}",
            pytrace=False,
        )
    return tap_devices[DEFAULT_ROLE]


@pytest.hookimpl(hookwrapper=True, tryfirst=True)
def pytest_runtest_makereport(item: pytest.Item, call: pytest.CallInfo):
    outcome = yield
    report = outcome.get_result()
    state = item.stash.get(_STATE, None)
    if state is not None and call.when in ("setup", "call"):
        state.reports[call.when] = report
        if report.failed and call.excinfo is not None:
            state.failure = call.excinfo.value


#: Failure-artifact budget per device, in seconds; devices are captured concurrently.
ARTIFACT_BUDGET_S = 60.0
#: Upper bound for one device-side artifact call inside that budget.
_ARTIFACT_CALL_S = 30.0


def _capture_artifacts(
    item: pytest.Item, config: TapConfig, state: TestDevices
) -> None:
    directory = config.artifacts / re.sub(r"[^A-Za-z0-9._-]", "_", item.nodeid)
    directory.mkdir(parents=True, exist_ok=True)
    if state.failure is not None:
        _capture(
            directory / "failure.txt",
            lambda: "".join(traceback.format_exception(state.failure)).encode(),
        )
    if not state.devices:
        return
    # One thread per device, each within its own budget, so a slow device neither starves
    # the others nor holds teardown past the budget (a straggler is abandoned, not joined).
    pool = concurrent.futures.ThreadPoolExecutor(
        max_workers=len(state.devices), thread_name_prefix="tap-artifacts"
    )
    try:
        futures = [
            pool.submit(_capture_device, directory, role, device)
            for role, device in state.devices.items()
        ]
        concurrent.futures.wait(futures, timeout=ARTIFACT_BUDGET_S + 5)
    finally:
        pool.shutdown(wait=False, cancel_futures=True)


def _capture_device(directory: pathlib.Path, role: str, device: Device) -> None:
    deadline = time.monotonic() + ARTIFACT_BUDGET_S
    prefix = f"{role}-{device.serial}"

    def budget() -> float:
        return min(_ARTIFACT_CALL_S, deadline - time.monotonic())

    steps = [
        (f"{prefix}.png", lambda t: device.screenshot(timeout=t)),
        (f"{prefix}.xml", lambda t: device.dump_hierarchy(timeout=t).encode()),
        (f"{prefix}.device-info.txt", lambda t: str(device.info()).encode()),
        (f"{prefix}.driver.log", lambda t: "\n".join(device.driver_log()).encode()),
    ]
    for name, produce in steps:
        remaining = budget()
        if remaining <= 0:
            return
        _capture(directory / name, lambda p=produce, t=remaining: p(t))


def _capture(path: pathlib.Path, produce) -> None:
    # Artifact capture must never mask the test failure or block cleanup.
    with contextlib.suppress(Exception):
        path.write_bytes(produce())
