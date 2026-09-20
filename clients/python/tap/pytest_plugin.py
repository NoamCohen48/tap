"""pytest integration: per-test device sessions from the service pool.

Configuration (ini option, or environment variable):

    tap_aut       / TAP_AUT        AUT package (required)
    tap_serials   / TAP_SERIALS    comma-separated serials to use; roles map to them in
                                   order. Unset = any device in the pool.
    tap_artifacts / TAP_ARTIFACTS  failure artifact directory (default tap-artifacts)
    tap_service   / TAP_SERVICE    host:port of a running service (default: discover/auto-start)
    tap_acquire_timeout            seconds to wait for devices (default 120)

Fixtures: ``tap_device`` (role "device") and ``tap_devices`` (dict role → Device).
Markers: ``@pytest.mark.tap_devices("left", "right")`` declares roles. A test needing more roles
than configured serials is skipped. Before each test the roles are acquired all-or-none and one
driver session per role is opened in parallel; after it, failure artifacts (screenshot,
hierarchy, device info, driver log) are captured while sessions are live, then sessions are
closed and the devices released. Sessions never outlive a test.
"""
from __future__ import annotations

import os
import pathlib
import re
import traceback
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

import pytest

from .device import Device
from .service import Run, Service

DEFAULT_ROLE = "device"


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addini("tap_aut", "AUT package name")
    parser.addini("tap_serials", "comma-separated device serials")
    parser.addini("tap_artifacts", "failure artifact directory", default="tap-artifacts")
    parser.addini("tap_service", "host:port of a running tap service")
    parser.addini("tap_acquire_timeout", "seconds to wait for devices", default="120")


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", "tap_devices(*roles): device roles this test needs (default: one 'device')")


@dataclass
class TapConfig:
    aut: str
    serials: list[str]
    artifacts: pathlib.Path
    service: str | None
    acquire_timeout: float

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
    if tap_config.service:
        os.environ["TAP_SERVICE"] = tap_config.service
    service = Service()
    yield service
    service.close()


@pytest.fixture(scope="session")
def tap_run(tap_service: Service, request: pytest.FixtureRequest) -> Run:
    """One run per pytest session; the service tears everything down if this process dies."""
    run = tap_service.open_run(f"pytest {os.getpid()} {request.config.rootpath.name}")
    run.attach()
    yield run
    run.close()


def _declared_roles(item: pytest.Item) -> list[str]:
    marker = item.get_closest_marker("tap_devices")
    roles = list(marker.args) if marker and marker.args else [DEFAULT_ROLE]
    return list(dict.fromkeys(roles))


def _open_all(run: Run, config: TapConfig, assignment: dict[str, str]) -> dict[str, Device]:
    with ThreadPoolExecutor(max_workers=len(assignment)) as pool:
        futures = {role: pool.submit(run.open_device, serial, config.aut) for role, serial in assignment.items()}
        opened: dict[str, Device] = {}
        failure: BaseException | None = None
        for role, future in futures.items():
            try:
                opened[role] = future.result()
            except BaseException as error:  # noqa: BLE001 - re-raised after cleanup
                failure = failure or error
        if failure is not None:
            for device in opened.values():
                try:
                    device.close()
                except Exception:  # noqa: BLE001
                    pass
            raise failure
        return opened


@pytest.fixture
def tap_devices(request: pytest.FixtureRequest, tap_run: Run, tap_config: TapConfig) -> dict[str, Device]:
    roles = _declared_roles(request.node)
    # Roles → serials is decided here, in declaration order; the pool only leases serials.
    available = tap_config.serials or tap_run.free_serials()
    if len(roles) > len(available):
        where = f"tap_serials lists {tap_config.serials}" if tap_config.serials else f"the pool has {available}"
        pytest.skip(f"{request.node.name} needs {len(roles)} devices but {where}")
    assignment = dict(zip(roles, available))
    tap_run.acquire(list(assignment.values()), tap_config.acquire_timeout)
    try:
        devices = _open_all(tap_run, tap_config, assignment)
    except BaseException:
        tap_run.release(list(assignment.values()))
        raise
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
        tap_run.release(state.serials)
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
