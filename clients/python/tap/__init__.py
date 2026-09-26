"""Tap Python client: drives Android devices through the Tap host server.

from tap import TapServer, text, res

server = TapServer()                                  # a running server (`tap start`)
with server.connect("smoke") as connection:
    with connection.attach_device("emulator-5554", "com.example.app") as device:
        device.app().cold_launch()
        device.element(res("login")).tap()
        device.wait(text("Welcome")).visible()
"""
# pyright: reportAttributeAccessIssue=false

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _dist_version

from . import _gen as pb
from .app import App, ProcessIdentity
from .device import KEYCODE_BACK, KEYCODE_HOME, Device, Timeouts
from .element import DOWN, LEFT, RIGHT, UP, Element, ElementWait
from .errors import (
    AppLifecycleError,
    CommandError,
    DeviceBusyError,
    ErrorCode,
    ServerError,
    TapError,
    WaitTimeoutError,
)
from .selectors import (
    CONTAINS,
    ENDS_WITH,
    EXACT,
    REGEX,
    STARTS_WITH,
    Selector,
    all_of,
    any_of,
    class_name,
    clickable,
    desc,
    hint,
    raw_res,
    res,
    res_id,
    scrollable,
    text,
    text_contains,
    text_matches,
    text_starts_with,
)
from .server import (
    ClientConnection,
    DaemonStartResult,
    TapServer,
    resolve_address,
    running_server,
    start_daemon,
    stop_daemon,
)

STABILITY_TREE = pb.STABILITY_TREE
STABILITY_PIXELS = pb.STABILITY_PIXELS
STABILITY_ALL = pb.STABILITY_ALL

__all__ = [
    "CONTAINS",
    "DOWN",
    "ENDS_WITH",
    "EXACT",
    "KEYCODE_BACK",
    "KEYCODE_HOME",
    "LEFT",
    "REGEX",
    "RIGHT",
    "STARTS_WITH",
    "UP",
    "App",
    "AppLifecycleError",
    "ClientConnection",
    "CommandError",
    "DaemonStartResult",
    "Device",
    "DeviceBusyError",
    "Element",
    "ElementWait",
    "ErrorCode",
    "ProcessIdentity",
    "Selector",
    "ServerError",
    "TapError",
    "TapServer",
    "Timeouts",
    "WaitTimeoutError",
    "all_of",
    "any_of",
    "class_name",
    "clickable",
    "desc",
    "hint",
    "pb",
    "raw_res",
    "res",
    "res_id",
    "resolve_address",
    "running_server",
    "scrollable",
    "start_daemon",
    "stop_daemon",
    "text",
    "text_contains",
    "text_matches",
    "text_starts_with",
]

try:
    __version__ = _dist_version("tap-e2e")
except PackageNotFoundError:  # running from a checkout without an install
    __version__ = "0.0.0+unknown"
