"""pytest integration: per-test attached devices through the Tap server.

Configuration (ini option, or environment variable):

    tap_serials   / TAP_SERIALS    comma-separated serials to use; roles map to them in
                                   order, starting one device further on for each test
                                   (wrapping). Unset = whatever the server's device list offers.
    tap_artifacts / TAP_ARTIFACTS  failure artifact directory (default tap-artifacts)
    tap_capture   / TAP_CAPTURE    onFailure (default) = Device.capture() every device of a
                                   failed test into that directory; off = capture nothing
    tap_device_scope / TAP_DEVICE_SCOPE
                                   function (default) = attach before each test, detach after it;
                                   class / module / session = keep the devices for the next test of
                                   the same class / module / session (see below)
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
serial order so concurrent multi-device tests cannot deadlock; after it, a failed test's
devices are captured (``Device.capture()``: screenshot, hierarchy, device info, driver log)
while they remain attached, then they are detached. Attachments never outlive a test, unless ``tap_device_scope`` says
otherwise: then consecutive tests of one class / module / the session that declare the same roles
reuse the devices, saving the driver start of each attach (about 1 s on an emulator, several on a
slow phone). Nothing is reset between them — the app keeps the previous test's state, so each test
brings it where it needs it (``cold_launch()``, ``clear_data()``). A reused device is probed
before each test; one that stopped working is detached and a fresh one attached. Failure artifacts
are still captured per test; the devices are detached when the scope ends.
"""

from __future__ import annotations

import concurrent.futures
import contextlib
import itertools
import os
import pathlib
import re
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
    parser.addini("tap_serials", "comma-separated device serials")
    parser.addini(
        "tap_artifacts", "failure artifact directory", default="tap-artifacts"
    )
    parser.addini(
        "tap_capture", "onFailure or off: capture failed tests' devices", default="onFailure"
    )
    parser.addini(
        "tap_device_scope",
        "function, class, module or session: how long attached devices are kept",
        default="function",
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
    """The plugin's settings, read from ``pytest.ini`` (``tap_*`` keys) or ``TAP_*`` environment
    variables; the environment wins.

    Attributes:
        serials: Devices to use, in order (``tap_serials`` / ``TAP_SERIALS``, comma-separated).
            Empty means every device the server lists.
        artifacts: Where failure artifacts are written (``tap_artifacts`` / ``TAP_ARTIFACTS``,
            default ``tap-artifacts``).
        server: ``host:port`` of the server (``tap_server`` / ``TAP_SERVER``). ``None`` finds
            the one ``tap start`` recorded.
        acquire_timeout: Seconds to wait for a device another session holds
            (``tap_acquire_timeout`` / ``TAP_ACQUIRE_TIMEOUT``).
        manage_daemon: Start the server before the session and stop it afterwards if this run
            started it (``tap_manage_daemon`` / ``TAP_MANAGE_DAEMON``).
        capture_on_failure: Save screenshot, hierarchy, device info and driver log when a test
            fails (``tap_capture`` / ``TAP_CAPTURE``: ``onFailure`` or ``off``).
        device_scope: How long attached devices are kept: ``function`` (default), ``class``,
            ``module`` or ``session`` (``tap_device_scope`` / ``TAP_DEVICE_SCOPE``).
    """

    serials: list[str]
    artifacts: pathlib.Path
    server: str | None
    acquire_timeout: float
    manage_daemon: bool
    capture_on_failure: bool = True
    device_scope: str = "function"

    @classmethod
    def from_pytest(cls, config: pytest.Config) -> TapConfig:
        """Read the settings from the pytest configuration and the environment."""
        def option(name: str, env: str, default: str = "") -> str:
            return os.environ.get(env) or str(config.getini(name) or default)

        serials = [
            s.strip()
            for s in option("tap_serials", "TAP_SERIALS").split(",")
            if s.strip()
        ]
        return cls(
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
            capture_on_failure=_capture_mode(option("tap_capture", "TAP_CAPTURE", "onFailure")),
            device_scope=_device_scope(option("tap_device_scope", "TAP_DEVICE_SCOPE", "function")),
        )


_DEVICE_SCOPES = ("function", "class", "module", "session")


def _device_scope(value: str) -> str:
    """``tap_device_scope``: one of ``_DEVICE_SCOPES`` (case-insensitive)."""
    scope = value.strip().lower()
    if scope not in _DEVICE_SCOPES:
        raise pytest.UsageError(f"tap_device_scope must be one of {list(_DEVICE_SCOPES)}, was {value!r}")
    return scope


def _scope_key(item: pytest.Item, scope: str) -> str | None:
    """Which tests share devices under ``scope``: None for none (``function``, or ``class`` for a
    test outside a class)."""
    if scope == "session":
        return "session"
    if scope == "module":
        return str(item.path)
    if scope == "class" and getattr(item, "cls", None) is not None:
        return f"{item.path}::{item.cls.__qualname__}"  # type: ignore[attr-defined]
    return None


@dataclass
class _Held:
    """Devices kept across the tests of one scope (``tap_device_scope``)."""

    scope: str
    key: str
    roles: list[str]
    devices: dict[str, Device]


_HELD = pytest.StashKey[_Held | None]()


def _detach_all(devices: dict[str, Device]) -> list[Exception]:
    errors: list[Exception] = []
    for device in devices.values():
        try:
            quarantine = device.detach()
            if quarantine:
                errors.append(
                    DeviceQuarantinedError(device.serial, f"{device.serial} quarantined: {quarantine}")
                )
        except Exception as error:  # noqa: BLE001
            errors.append(error)
    return errors


def _reusable(config: pytest.Config, key: str | None, roles: list[str]) -> dict[str, Device] | None:
    """The held devices when they belong to ``key``, serve ``roles`` and all still answer
    (``info()``, a few ms); otherwise None, after detaching whatever was held. A detach failure of
    a device that already stopped working is dropped: the attach that follows reports the
    device's state (quarantined, offline) itself."""
    held = config.stash.get(_HELD, None)
    if held is None:
        return None
    if held.key == key and held.roles == roles:
        try:
            for device in held.devices.values():
                device.info()
            return held.devices
        except Exception:  # noqa: BLE001 - a broken session is replaced, not reported here
            pass
    config.stash[_HELD] = None
    _detach_all(held.devices)
    return None


@pytest.hookimpl(trylast=True)
def pytest_runtest_teardown(item: pytest.Item, nextitem: pytest.Item | None) -> None:
    """Ends a ``tap_device_scope``: after the last test of the scope, detach the held devices."""
    held = item.config.stash.get(_HELD, None)
    if held is None:
        return
    if nextitem is not None and _scope_key(nextitem, held.scope) == held.key:
        return
    item.config.stash[_HELD] = None
    errors = _detach_all(held.devices)
    if errors:
        raise errors[0]


def _capture_mode(value: str) -> bool:
    """``tap_capture``: True for ``onFailure``, False for ``off`` (case-insensitive)."""
    modes = {"onfailure": True, "off": False}
    try:
        return modes[value.strip().lower()]
    except KeyError:
        raise pytest.UsageError(f"tap_capture must be one of ['onFailure', 'off'], was {value!r}") from None


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
    """The plugin's [TapConfig][tap_e2e.pytest_plugin.TapConfig] for the session."""
    return TapConfig.from_pytest(pytestconfig)


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
                return connection.attach_device(serial)
            except DeviceBusyError:
                continue  # held by another session right now: try the next device
    return connection.attach_device(
        candidates[0], wait_for_device=config.acquire_timeout
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
                serial, wait_for_device=config.acquire_timeout
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
    """The test's devices by role, attached before the test and detached after it.

    Roles come from ``@pytest.mark.tap_devices("sender", "receiver")``; without the marker
    there is one role, ``"device"``. Each role gets a different serial, and the test is skipped
    when there are fewer devices than roles. If the test fails, failure artifacts are saved for
    every device under ``tap_artifacts/<test id>/``.
    """
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
    key = _scope_key(request.node, tap_config.device_scope)
    reused = _reusable(request.config, key, roles)
    start = next(_ROTATION)
    if reused is not None:
        devices = reused
    elif len(roles) == 1:
        device = _attach_single(
            tap_connection, tap_config, _rotate(available, start)
        )
        devices = {roles[0]: device}
    else:
        devices = _attach_all(
            tap_connection, tap_config, _assign(roles, available, start)
        )
    if key is not None and reused is None:
        request.config.stash[_HELD] = _Held(tap_config.device_scope, key, roles, devices)
    state = TestDevices(devices, [device.serial for device in devices.values()])
    request.node.stash[_STATE] = state
    yield devices
    failed = any(report.failed for report in state.reports.values())
    try:
        if failed and tap_config.capture_on_failure:
            _capture_artifacts(request.node, tap_config, state)
    finally:
        # Held devices stay for the next test of the scope; pytest_runtest_teardown ends it.
        close_errors = [] if key is not None else _detach_all(devices)
        # A cleanup failure after a passing test is a real failure: the device may be quarantined.
        if close_errors and not failed:
            raise close_errors[0]


@pytest.fixture
def tap_device(tap_devices: dict[str, Device]) -> Device:
    """The test's single device: ``tap_devices["device"]``."""
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


def _capture_artifacts(
    item: pytest.Item, config: TapConfig, state: TestDevices
) -> None:
    """``failure.txt`` with the traceback, then ``Device.capture()`` of every device in parallel,
    saved as ``<role>-<serial>.<part>.<ext>``. Never masks the test failure: a part or file that
    cannot be produced is simply missing."""
    directory = config.artifacts / re.sub(r"[^A-Za-z0-9._-]", "_", item.nodeid)
    directory.mkdir(parents=True, exist_ok=True)
    if state.failure is not None:
        _capture(
            directory / "failure.txt",
            lambda: "".join(traceback.format_exception(state.failure)).encode(),
        )
    if not state.devices:
        return
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=len(state.devices), thread_name_prefix="tap-artifacts"
    ) as pool:
        for role, device in state.devices.items():
            pool.submit(_capture_device, directory, role, device)


def _capture_device(directory: pathlib.Path, role: str, device: Device) -> None:
    with contextlib.suppress(Exception):
        device.capture().save_to(directory, f"{role}-{device.serial}")


def _capture(path: pathlib.Path, produce) -> None:
    # Artifact capture must never mask the test failure or block cleanup.
    with contextlib.suppress(Exception):
        path.write_bytes(produce())
