"""Fixture app facts shared by the sample tests; a product suite would own an equivalent file.

Run against the local matrix from the repository root, with the fixture APK built
(`./gradlew :fixture-app:assembleDebug`) and the server on PATH or at TAP_BIN:

    TAP_SERIALS=emulator-5554,85e49002 pytest clients/python/tests
"""
# pyright: reportAttributeAccessIssue=false, reportIncompatibleMethodOverride=false, reportMissingImports=false

from __future__ import annotations

import os
import pathlib
import threading

import pytest  # type: ignore[import-not-found]
from tap_e2e import App, Device

PACKAGE = "io.github.noamcohen48.tap.fixture"
REPO = pathlib.Path(__file__).resolve().parents[3]
APK = pathlib.Path(
    os.environ.get("TAP_FIXTURE_APK")
    or REPO / "fixture-app/build/outputs/apk/debug/fixture-app-debug.apk"
)

os.environ.setdefault("TAP_AUT", PACKAGE)

_installed: set[str] = set()
_lock = threading.Lock()


def launch(device: Device, activity: str = ".MainActivity") -> App:
    """Installs once per process per device, then cold-launches the given activity."""
    app = device.app(PACKAGE)
    with _lock:
        first = device.serial not in _installed
        _installed.add(device.serial)
    if first or not app.is_installed():
        app.install(str(APK))
    app.cold_launch(activity)
    return app


def _device_run() -> bool:
    """The device suite runs only against named devices; without TAP_SERIALS it is skipped
    so ``pytest clients/python/tests`` stays usable offline (``tests/unit`` still runs)."""
    return bool(os.environ.get("TAP_SERIALS", "").strip())


def pytest_collection_modifyitems(
    config: pytest.Config, items: list[pytest.Item]
) -> None:
    if _device_run():
        return
    suite = pathlib.Path(__file__).parent
    unit = suite / "unit"
    skip = pytest.mark.skip(reason="device test: set TAP_SERIALS to run it")
    for item in items:
        # Only this suite's own items: a run may collect other packages' tests too.
        if suite in item.path.parents and unit not in item.path.parents:
            item.add_marker(skip)


def pytest_sessionstart(session: pytest.Session) -> None:
    if not _device_run():
        return
    if not APK.exists():
        raise pytest.UsageError(
            f"fixture APK not found at {APK}; build it or set TAP_FIXTURE_APK"
        )
