"""Tap Python client: drives Android devices through the Tap host service.

    from tap import Service, text, res

    service = Service()                                  # discovers or starts `tap serve`
    with service.connect("smoke") as connection:
        with connection.open_device("emulator-5554", "com.example.app") as device:
            device.app().cold_launch()
            device.element(res("login")).tap()
            device.wait(text("Welcome")).visible()
"""
from importlib.metadata import PackageNotFoundError, version as _dist_version

from ._gen import tap_pb2 as pb
from .app import App, ProcessIdentity
from .device import KEYCODE_BACK, KEYCODE_HOME, Device, Timeouts
from .element import DOWN, LEFT, RIGHT, UP, Element, ElementWait

STABILITY_TREE = pb.STABILITY_TREE
STABILITY_PIXELS = pb.STABILITY_PIXELS
STABILITY_ALL = pb.STABILITY_ALL
from .errors import AppLifecycleError, CommandError, DeviceBusyError, ErrorCode, ServiceError, TapError, WaitTimeoutError
from .selectors import (
    CONTAINS, EXACT, REGEX, STARTS_WITH, Selector, class_name, clickable, desc, hint, raw_res, res, res_id,
    scrollable, text, text_contains, text_matches, text_starts_with,
)
from .service import Connection, Service, resolve_address

__all__ = [
    "pb", "App", "ProcessIdentity", "Device", "Timeouts", "KEYCODE_BACK", "KEYCODE_HOME",
    "Element", "ElementWait", "DOWN", "UP", "LEFT", "RIGHT",
    "AppLifecycleError", "CommandError", "DeviceBusyError", "ErrorCode", "ServiceError", "TapError", "WaitTimeoutError",
    "Selector", "EXACT", "CONTAINS", "STARTS_WITH", "REGEX", "class_name", "clickable", "desc", "hint",
    "raw_res", "res", "res_id", "scrollable", "text", "text_contains", "text_matches", "text_starts_with",
    "Connection", "Service", "resolve_address",
]

try:
    __version__ = _dist_version("tap-e2e")
except PackageNotFoundError:  # running from a checkout without an install
    __version__ = "0.0.0+unknown"
