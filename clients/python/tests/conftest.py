"""Fixture app facts shared by the sample tests; a product suite would own an equivalent file.

Run against the local matrix from the repository root, with the fixture APK built
(`./gradlew :fixture-app:assembleDebug`) and the service on PATH or at TAP_BIN:

    TAP_SERIALS=emulator-5554,85e49002 pytest clients/python/tests
"""
from __future__ import annotations

import os
import pathlib
import threading

import pytest

from tap import App, Device

PACKAGE = "com.company.tap.fixture"
REPO = pathlib.Path(__file__).resolve().parents[3]
APK = pathlib.Path(os.environ.get("TAP_FIXTURE_APK") or REPO / "fixture-app/build/outputs/apk/debug/fixture-app-debug.apk")

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


def pytest_sessionstart(session: pytest.Session) -> None:
    if not APK.exists():
        raise pytest.UsageError(f"fixture APK not found at {APK}; build it or set TAP_FIXTURE_APK")
