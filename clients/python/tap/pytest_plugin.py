"""pytest integration: per-test device sessions through the Tap service.

Configuration (ini option, or environment variable):

    tap_aut       / TAP_AUT        AUT package (required)
    tap_serials   / TAP_SERIALS    comma-separated serials to use; roles map to them in
                                   order. Unset = whatever the service's device list offers.
    tap_artifacts / TAP_ARTIFACTS  failure artifact directory (default tap-artifacts)
    tap_service   / TAP_SERVICE    host:port of a running service (default: the one `tap start` recorded)
    tap_manage_service / TAP_MANAGE_SERVICE
                                   true = run `tap start` before the first test and `tap stop`
                                   after the last one if that start created the service
                                   (default false: a service must already be running)
    tap_acquire_timeout            seconds to wait for a device another session holds (default 120)

Fixtures: ``tap_device`` (role "device") and ``tap_devices`` (dict role → Device).
Markers: ``@pytest.mark.tap_devices("left", "right")`` declares roles. A test needing more roles
than available devices is skipped. Before each test one driver session per role is opened, in
sorted serial order so concurrent multi-device tests cannot deadlock; after it, failure
artifacts (screenshot, hierarchy, device info, driver log) are captured while sessions are
live, then the sessions are closed, which frees the devices. Sessions never outlive a test.
"""
from __future__ import annotations

import os
import pathlib
import re
import traceback
from dataclasses import dataclass, field

import pytest

from .device import Device
from .service import Connection, Service, start_service, stop_service

DEFAULT_ROLE = "device"


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addini("tap_aut", "AUT package name")
    parser.addini("tap_serials", "comma-separated device serials")
    parser.addini("tap_artifacts", "failure artifact directory", default="tap-artifacts")
    parser.addini("tap_service", "host:port of a running tap service")
    parser.addini("tap_manage_service", "start the service before the run and stop it afterwards if started here", default="false")
    parser.addini("tap_acquire_timeout", "seconds to wait for a device another session holds", default="120")


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", "tap_devices(*roles): device roles this test needs (default: one 'device')")


@dataclass
class TapConfig:
    aut: str
    serials: list[str]
    artifacts: pathlib.Path
    service: str | None
    acquire_timeout: float
    manage_service: bool

    @classmethod
    def from_pytest(cls, config: pytest.Config) -> "TapConfig":
        def option(name: str, env: str, default: str = "") -> str:
            return os.environ.get(env) or str(config.getini(name) or default)

        serials = [s.strip() for s in option("tap_serials", "TAP_SERIALS").split(",") if s.strip()]
        return cls(
            aut=option("tap_aut", "TAP_AUT"),
            serials=serials,
            artifacts=pathlib.Path(option("tap_artifacts", "TAP_ARTIFACTS", "tap-artifacts")),
            service=option("tap_service", "TAP_SERVICE") or None,
            acquire_timeout=float(option("tap_acquire_timeout", "TAP_ACQUIRE_TIMEOUT", "120")),
            manage_service=option("tap_manage_service", "TAP_MANAGE_SERVICE", "false").lower() in ("1", "true", "yes"),
        )


@dataclass
class TestDevices:
    devices: dict[str, Device]
    serials: list[str]
    failure: BaseException | None = None
    reports: dict[str, pytest.TestReport] = field(default_factory=dict)


@pytest.fixture(scope="session")
def tap_config(pytestconfig: pytest.Config) -> TapConfig:
    config = TapConfig.from_pytest(pytestconfig)
    if not config.aut:
        pytest.fail("tap_aut (or TAP_AUT) must name the AUT package", pytrace=False)
    return config


@pytest.fixture(scope="session")
def tap_service(tap_config: TapConfig) -> Service:
    """The service channel. With ``tap_manage_service`` the service is started here and, if
    that start created it, stopped after the session; one already running is left alone."""
    started = False
    if tap_config.service:
        service = Service(tap_config.service)
    else:
        if tap_config.manage_service:
            started = start_service().started
        service = Service()
    yield service
    service.close()
    if started:
        stop_service()


@pytest.fixture(scope="session")
def tap_connection(tap_service: Service, request: pytest.FixtureRequest) -> Connection:
    """One ``Connection`` per pytest session; the service tears everything down if this process dies."""
    connection = tap_service.connect(f"pytest {os.getpid()} {request.config.rootpath.name}")
    connection.attach()
    yield connection
    connection.close()


def _declared_roles(item: pytest.Item) -> list[str]:
    marker = item.get_closest_marker("tap_devices")
    roles = list(marker.args) if marker and marker.args else [DEFAULT_ROLE]
    return list(dict.fromkeys(roles))


def _open_all(connection: Connection, config: TapConfig, assignment: dict[str, str]) -> dict[str, Device]:
    """Opens the sessions one at a time in sorted serial order. Every process takes device locks
    in the same order, so two tests wanting the same two devices cannot deadlock; the second
    waits (up to ``tap_acquire_timeout``) for the first to finish."""
    opened: dict[str, Device] = {}
    try:
        for role, serial in sorted(assignment.items(), key=lambda item: item[1]):
            opened[role] = connection.open_device(serial, config.aut, wait_for_device=config.acquire_timeout)
    except BaseException:
        for device in opened.values():
            try:
                device.close()
            except Exception:  # noqa: BLE001
                pass
        raise
    return {role: opened[role] for role in assignment}


@pytest.fixture
def tap_devices(request: pytest.FixtureRequest, tap_connection: Connection, tap_config: TapConfig) -> dict[str, Device]:
    roles = _declared_roles(request.node)
    # Roles → serials is decided here, in declaration order; the service only knows serials.
    available = tap_config.serials or tap_connection.available_serials()
    if len(roles) > len(available):
        where = f"tap_serials lists {tap_config.serials}" if tap_config.serials else f"the service lists {available}"
        pytest.skip(f"{request.node.name} needs {len(roles)} devices but {where}")
    assignment = dict(zip(roles, available))
    devices = _open_all(tap_connection, tap_config, assignment)
    state = TestDevices(devices, list(assignment.values()))
    request.node._tap_state = state  # type: ignore[attr-defined]
    yield devices
    failed = any(report.failed for report in state.reports.values())
    try:
        if failed:
            _capture_artifacts(request.node, tap_config, state)
    finally:
        close_errors = []
        for device in devices.values():
            try:
                quarantine = device.close()
                if quarantine:
                    close_errors.append(RuntimeError(f"{device.serial} quarantined: {quarantine}"))
            except Exception as error:  # noqa: BLE001
                close_errors.append(error)
        # A cleanup failure after a passing test is a real failure: the device may be quarantined.
        if close_errors and not failed:
            raise close_errors[0]


@pytest.fixture
def tap_device(tap_devices: dict[str, Device]) -> Device:
    if DEFAULT_ROLE not in tap_devices:
        pytest.fail(f"tap_device needs role '{DEFAULT_ROLE}' but the test declared {list(tap_devices)}", pytrace=False)
    return tap_devices[DEFAULT_ROLE]


@pytest.hookimpl(hookwrapper=True, tryfirst=True)
def pytest_runtest_makereport(item: pytest.Item, call: pytest.CallInfo):
    outcome = yield
    report = outcome.get_result()
    state = getattr(item, "_tap_state", None)
    if state is not None and call.when in ("setup", "call"):
        state.reports[call.when] = report
        if report.failed and call.excinfo is not None:
            state.failure = call.excinfo.value


def _capture_artifacts(item: pytest.Item, config: TapConfig, state: TestDevices) -> None:
    directory = config.artifacts / re.sub(r"[^A-Za-z0-9._-]", "_", item.nodeid)
    directory.mkdir(parents=True, exist_ok=True)
    for role, device in state.devices.items():
        prefix = f"{role}-{device.serial}"
        _capture(directory / f"{prefix}.png", lambda d=device: d.screenshot())
        _capture(directory / f"{prefix}.xml", lambda d=device: d.dump_hierarchy().encode())
        _capture(directory / f"{prefix}.device-info.txt", lambda d=device: str(d.info()).encode())
        _capture(directory / f"{prefix}.driver.log", lambda d=device: "\n".join(d.driver_log()).encode())
    if state.failure is not None:
        _capture(directory / "failure.txt", lambda: "".join(traceback.format_exception(state.failure)).encode())


def _capture(path: pathlib.Path, produce) -> None:
    # Artifact capture must never mask the test failure or block cleanup.
    try:
        path.write_bytes(produce())
    except Exception:  # noqa: BLE001
        pass
