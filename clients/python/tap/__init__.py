"""Tap Python client: drives Android devices through the Tap host service.

    from tap import Service, text, res

    service = Service()                                  # a running service (`tap start`)
    with service.connect("smoke") as connection:
        with connection.open_device("emulator-5554", "com.example.app") as device:
            device.app().cold_launch()
            device.element(res("login")).tap()
            device.wait(text("Welcome")).visible()
"""
from importlib.metadata import PackageNotFoundError, version as _dist_version

from . import _gen as pb
from .app import App, ProcessIdentity
from .device import KEYCODE_BACK, KEYCODE_HOME, Device, Timeouts
from .element import DOWN, LEFT, RIGHT, UP, Element, ElementWait

STABILITY_TREE = pb.STABILITY_TREE
STABILITY_PIXELS = pb.STABILITY_PIXELS
STABILITY_ALL = pb.STABILITY_ALL
from .errors import AppLifecycleError, CommandError, DeviceBusyError, ErrorCode, ServiceError, TapError, WaitTimeoutError
from .selectors import (
    CONTAINS, ENDS_WITH, EXACT, REGEX, STARTS_WITH, Selector, all_of, any_of, class_name, clickable, desc, hint,
    raw_res, res, res_id, scrollable, text, text_contains, text_matches, text_starts_with,
)
from .service import Connection, Service, StartResult, resolve_address, running_service, start_service, stop_service

__all__ = [
    "pb", "App", "ProcessIdentity", "Device", "Timeouts", "KEYCODE_BACK", "KEYCODE_HOME",
    "Element", "ElementWait", "DOWN", "UP", "LEFT", "RIGHT",
    "AppLifecycleError", "CommandError", "DeviceBusyError", "ErrorCode", "ServiceError", "TapError", "WaitTimeoutError",
    "Selector", "EXACT", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "REGEX", "all_of", "any_of", "class_name", "clickable",
    "desc", "hint", "raw_res", "res", "res_id", "scrollable", "text", "text_contains", "text_matches", "text_starts_with",
    "Connection", "Service", "StartResult", "resolve_address", "running_service", "start_service", "stop_service",
]

try:
    __version__ = _dist_version("tap-e2e")
except PackageNotFoundError:  # running from a checkout without an install
    __version__ = "0.0.0+unknown"
