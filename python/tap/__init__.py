"""Tap Python client: drives Android devices through the Tap host service.

    from tap import Service, text, res_id

    service = Service()                                  # discovers or starts `tap serve`
    with service.open_run("smoke") as run:
        run.acquire({"device": {"serial": "emulator-5554"}}, timeout=60)
        with run.open_device("emulator-5554", "com.example.app") as device:
            device.app().cold_launch()
            device.element(res_id("com.example.app", "login")).tap()
            device.wait(text("Welcome")).visible()
"""
from ._gen import tap_pb2 as pb
from .app import App, ProcessIdentity
from .device import KEYCODE_BACK, KEYCODE_HOME, Device, Timeouts
from .element import DOWN, LEFT, RIGHT, UP, Element, ElementWait
from .errors import AppLifecycleError, CommandError, ErrorCode, ServiceError, TapError, WaitTimeoutError
from .selectors import (
    CONTAINS, EXACT, REGEX, STARTS_WITH, Selector, class_name, clickable, desc, hint, raw_res, res_id,
    scrollable, text, text_contains, text_matches, text_starts_with,
)
from .service import DeviceFacts, Run, Service, resolve_address

__all__ = [
    "pb", "App", "ProcessIdentity", "Device", "Timeouts", "KEYCODE_BACK", "KEYCODE_HOME",
    "Element", "ElementWait", "DOWN", "UP", "LEFT", "RIGHT",
    "AppLifecycleError", "CommandError", "ErrorCode", "ServiceError", "TapError", "WaitTimeoutError",
    "Selector", "EXACT", "CONTAINS", "STARTS_WITH", "REGEX", "class_name", "clickable", "desc", "hint",
    "raw_res", "res_id", "scrollable", "text", "text_contains", "text_matches", "text_starts_with",
    "DeviceFacts", "Run", "Service", "resolve_address",
]
