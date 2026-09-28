"""The client's own value types. The server API's protobuf messages stay an implementation detail
(mapped in ``_proto.py``), so a schema addition never changes these and callers never import
generated code."""

from __future__ import annotations

import builtins
import enum
import json
import os
import pathlib
from dataclasses import asdict, dataclass

# --- enums ----------------------------------------------------------------------------------------


class MatchMode(enum.Enum):
    """How a text property is compared with a selector's value."""

    EXACT = "EXACT"
    """The whole value equals the text."""
    CONTAINS = "CONTAINS"
    STARTS_WITH = "STARTS_WITH"
    ENDS_WITH = "ENDS_WITH"
    REGEX = "REGEX"
    """The text fully matches the value as an RE2 regular expression."""


class Direction(enum.Enum):
    """Gesture direction: where the finger moves for ``swipe``, the content edge scrolled towards
    for ``scroll`` (``DOWN`` reveals content below)."""

    UP = "UP"
    DOWN = "DOWN"
    LEFT = "LEFT"
    RIGHT = "RIGHT"


class StabilitySignal(enum.Enum):
    """What ``Device.await_screen_stable`` watches: the accessibility tree, the window pixels
    (0.5 % tolerance) or both."""

    TREE = "TREE"
    PIXELS = "PIXELS"
    ALL = "ALL"


class ErrorCode(enum.Enum):
    """Why a driver command failed (``CommandError.code``). ``UNKNOWN`` also stands for a code
    this client version does not know."""

    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_SELECTOR = "INVALID_SELECTOR"
    UNSUPPORTED = "UNSUPPORTED"
    UNAUTHENTICATED = "UNAUTHENTICATED"
    SESSION_MISMATCH = "SESSION_MISMATCH"
    DUPLICATE_OR_STALE = "DUPLICATE_OR_STALE"
    OVERLOADED = "OVERLOADED"
    AUT_MISMATCH = "AUT_MISMATCH"
    NOT_FOUND = "NOT_FOUND"
    AMBIGUOUS = "AMBIGUOUS"
    NOT_INTERACTABLE = "NOT_INTERACTABLE"
    STALE_DURING_COMMAND = "STALE_DURING_COMMAND"
    ACTION_REJECTED = "ACTION_REJECTED"
    WAIT_TIMEOUT = "WAIT_TIMEOUT"
    CANCELLED = "CANCELLED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    AUT_NOT_INSTALLED = "AUT_NOT_INSTALLED"
    AUT_CRASHED = "AUT_CRASHED"
    AUT_ANR = "AUT_ANR"
    SYNC_PROVIDER_UNAVAILABLE = "SYNC_PROVIDER_UNAVAILABLE"
    DRIVER_UNHEALTHY = "DRIVER_UNHEALTHY"
    TRANSPORT_LOST = "TRANSPORT_LOST"
    INDETERMINATE = "INDETERMINATE"
    ARTIFACT_TRANSFER_FAILED = "ARTIFACT_TRANSFER_FAILED"
    PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    INTERNAL = "INTERNAL"
    UNKNOWN = "UNKNOWN"


class FailureReason(enum.Enum):
    """The server's structured reason for a failed call (``ServerError.reason``).
    ``UNSPECIFIED`` means the failure did not come from the daemon (a transport failure, a proxy,
    a cancelled call) or carried a reason this client version does not know."""

    UNSPECIFIED = "UNSPECIFIED"
    INTERNAL = "INTERNAL"
    INVALID_ARGUMENT = "INVALID_ARGUMENT"
    UNAUTHENTICATED = "UNAUTHENTICATED"
    UNKNOWN_CLIENT_CONNECTION = "UNKNOWN_CLIENT_CONNECTION"
    UNKNOWN_ATTACHED_DEVICE = "UNKNOWN_ATTACHED_DEVICE"
    NOT_OWNER = "NOT_OWNER"
    DEVICE_BUSY = "DEVICE_BUSY"
    DEVICE_QUARANTINED = "DEVICE_QUARANTINED"
    HOST_WAIT_TIMEOUT = "HOST_WAIT_TIMEOUT"
    ADB_TIMEOUT = "ADB_TIMEOUT"
    ADB_FAILED = "ADB_FAILED"
    ADB_REAP_UNCERTAIN = "ADB_REAP_UNCERTAIN"
    APP_LIFECYCLE = "APP_LIFECYCLE"
    DRIVER_BUILD_MISMATCH = "DRIVER_BUILD_MISMATCH"
    DRIVER_START_FAILED = "DRIVER_START_FAILED"
    DRIVER_TRANSPORT = "DRIVER_TRANSPORT"
    DRIVER_COMMAND = "DRIVER_COMMAND"
    SESSION_UNUSABLE = "SESSION_UNUSABLE"
    DAEMON_PRECONDITION = "DAEMON_PRECONDITION"
    UNKNOWN_REF = "UNKNOWN_REF"
    REF_NOT_ADDRESSABLE = "REF_NOT_ADDRESSABLE"


class WaitReason(enum.Enum):
    """Why a device-side wait timed out (``WaitTimeoutError.reason``)."""

    NO_MATCH = "NO_MATCH"
    """``visible`` / ``one``: nothing matched."""
    AMBIGUOUS = "AMBIGUOUS"
    """``one``: several nodes matched (``WaitTimeoutError.match_count`` says how many)."""
    STILL_PRESENT = "STILL_PRESENT"
    """``gone``: the selector still matched."""
    SCREEN_CHANGING = "SCREEN_CHANGING"
    """``await_screen_stable``: the screen kept changing."""
    APP_NOT_VISIBLE = "APP_NOT_VISIBLE"
    """``await_app_visible`` / ``await_screen_stable``: the package never owned the focused
    window."""


class DeviceState(enum.Enum):
    """A device's state in ``TapClient.devices()``. Only ``FREE`` and ``LEASED`` devices can be
    attached; ``UNKNOWN`` is a state this client version does not know."""

    FREE = "FREE"
    LEASED = "LEASED"
    QUARANTINED = "QUARANTINED"
    OFFLINE = "OFFLINE"
    UNAUTHORIZED = "UNAUTHORIZED"
    UNKNOWN = "UNKNOWN"


# --- values ---------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Bounds:
    """A node's bounds in screen pixels."""

    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top

    @property
    def center(self) -> tuple[int, int]:
        return (self.left + self.right) // 2, (self.top + self.bottom) // 2


@dataclass(frozen=True)
class ElementSnapshot:
    """The state of one node at one instant (``Element.snapshot()``). Text properties are None
    when the node has none. ``text`` is the raw accessibility text: on API 26+ an empty field
    reports its hint there and ``showing_hint`` is True."""

    class_name: str | None
    package_name: str | None
    resource_name: str | None
    text: str | None
    content_description: str | None
    hint: str | None
    bounds: Bounds
    checkable: bool
    checked: bool
    clickable: bool
    enabled: bool
    focusable: bool
    focused: bool
    long_clickable: bool
    scrollable: bool
    selected: bool
    child_count: int
    showing_hint: bool


@dataclass(frozen=True)
class AppProcess:
    """A process of an app as the host sees it: PID plus the ``/proc`` start token that tells
    reused PIDs apart."""

    pid: int
    start_token: str


@dataclass(frozen=True)
class DeviceEntry:
    """One device ADB lists (``TapClient.devices()``). ``client_connection_id`` is the holding
    connection when this daemon holds the device; ``quarantine_reason`` says why it is
    ``QUARANTINED``."""

    serial: str
    state: DeviceState
    client_connection_id: str | None
    quarantine_reason: str | None


@dataclass(frozen=True)
class ServerDefaults:
    """The daemon's default timeouts in seconds (``ServerInfo.defaults``)."""

    action: float
    wait: float
    lifecycle: float
    idle_stable: float
    acquire: float


@dataclass(frozen=True)
class ServerInfo:
    """What the server reports about itself (``TapClient.info()``)."""

    daemon_version: str
    host_build_id: str
    protocol_version: str
    adb_executable: str
    state_dir: str
    driver_available: bool
    pid: int
    defaults: ServerDefaults


# --- artifacts ------------------------------------------------------------------------------------


class Artifact:
    """Something the device produced that a test may keep: a ``Screenshot``, a ``Hierarchy``, a
    ``DeviceInfo`` or a ``DriverLog``. Every artifact is plain data with its serialized ``bytes``
    and ``media_type``, so it can go to a file (``save``), a report, an upload or an assertion
    without touching the device again."""

    media_type: str
    """The IANA media type of ``bytes``, e.g. ``image/png``."""
    extension: str
    """The usual file extension for ``bytes``, without the dot, e.g. ``png``."""

    @property
    def bytes(self) -> builtins.bytes:
        """The serialized form: PNG, UTF-8 XML, UTF-8 JSON or UTF-8 text."""
        raise NotImplementedError

    def save(self, path: str | os.PathLike[str]) -> pathlib.Path:
        """Writes ``bytes`` to ``path`` (parent directories are created) and returns it."""
        target = pathlib.Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(self.bytes)
        return target


class ImageFormat(enum.Enum):
    """The encoding of a ``Screenshot``'s bytes."""

    PNG = "PNG"

    @property
    def media_type(self) -> str:
        return {"PNG": "image/png"}[self.value]

    @property
    def extension(self) -> str:
        return {"PNG": "png"}[self.value]


class Screenshot(Artifact):
    """A screen capture (``Device.screenshot()``): the encoded image in ``format`` and its size
    in pixels."""

    def __init__(self, data: builtins.bytes, format: ImageFormat, width: int, height: int):  # noqa: A002
        self._data = data
        self.format = format
        self.width = width
        self.height = height

    @classmethod
    def _png(cls, data: builtins.bytes) -> Screenshot:
        """A PNG with its size read from the ``IHDR`` chunk (0 x 0 when the header is not a PNG's)."""
        if len(data) >= 24 and data[:4] == b"\x89PNG":
            return cls(data, ImageFormat.PNG, int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big"))
        return cls(data, ImageFormat.PNG, 0, 0)

    @property
    def bytes(self) -> builtins.bytes:
        return self._data

    @property
    def media_type(self) -> str:  # type: ignore[override]
        return self.format.media_type

    @property
    def extension(self) -> str:  # type: ignore[override]
        return self.format.extension

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Screenshot) and (self.format, self.width, self.height, self._data) == (
            other.format,
            other.width,
            other.height,
            other._data,
        )

    def __hash__(self) -> int:
        return hash(self._data)

    def __repr__(self) -> str:
        return f"Screenshot({self.format.name} {self.width}x{self.height}, {len(self._data)} bytes)"


class Hierarchy(Artifact):
    """The accessibility hierarchy as XML (``Device.dump_hierarchy()``). Diagnostic only:
    selectors never use it, so keep it out of assertions."""

    media_type = "application/xml"
    extension = "xml"

    def __init__(self, xml: str):
        self.xml = xml

    @property
    def bytes(self) -> builtins.bytes:
        return self.xml.encode()

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Hierarchy) and other.xml == self.xml

    def __hash__(self) -> int:
        return hash(self.xml)

    def __repr__(self) -> str:
        return f"Hierarchy({len(self.xml)} chars)"


@dataclass(frozen=True)
class DeviceInfo(Artifact):
    """Static facts about the device plus the package owning the focused window
    (``Device.info()``). ``display_rotation`` is 0-3 quarter turns. Serialized as JSON."""

    api_level: int
    manufacturer: str
    model: str
    product: str
    display_width: int
    display_height: int
    display_rotation: int
    current_package: str | None

    media_type = "application/json"
    extension = "json"

    @property
    def bytes(self) -> builtins.bytes:
        return json.dumps(asdict(self)).encode()


class DriverLog(Artifact):
    """The driver instrumentation's recent output (``Device.driver_log()``), one entry per line."""

    media_type = "text/plain"
    extension = "txt"

    def __init__(self, lines: list[str]):
        self.lines = list(lines)

    @property
    def text(self) -> str:
        """The lines joined with ``\\n``."""
        return "\n".join(self.lines)

    @property
    def bytes(self) -> builtins.bytes:
        return self.text.encode()

    def __eq__(self, other: object) -> bool:
        return isinstance(other, DriverLog) and other.lines == self.lines

    def __hash__(self) -> int:
        return hash(tuple(self.lines))

    def __repr__(self) -> str:
        return f"DriverLog({len(self.lines)} lines)"


class Capture:
    """The diagnostics of one device at one moment (``Device.capture()``): a ``Screenshot``, the
    ``Hierarchy``, the ``DeviceInfo`` and the ``DriverLog``. A part is None when it could not be
    produced; ``failures`` says why, by part name."""

    SCREENSHOT = "screenshot"
    HIERARCHY = "hierarchy"
    DEVICE_INFO = "device-info"
    DRIVER_LOG = "driver-log"

    def __init__(
        self,
        serial: str,
        screenshot: Screenshot | None,
        hierarchy: Hierarchy | None,
        info: DeviceInfo | None,
        driver_log: DriverLog | None,
        failures: dict[str, BaseException],
    ):
        self.serial = serial
        self.screenshot = screenshot
        self.hierarchy = hierarchy
        self.info = info
        self.driver_log = driver_log
        self.failures = dict(failures)
        """Why each missing part is missing, keyed like ``artifacts``."""

    @property
    def artifacts(self) -> dict[str, Artifact]:
        """The parts that were produced, by name: ``screenshot``, ``hierarchy``,
        ``device-info``, ``driver-log``, in that order."""
        parts: list[tuple[str, Artifact | None]] = [
            (self.SCREENSHOT, self.screenshot),
            (self.HIERARCHY, self.hierarchy),
            (self.DEVICE_INFO, self.info),
            (self.DRIVER_LOG, self.driver_log),
        ]
        return {name: part for name, part in parts if part is not None}

    def save_to(self, directory: str | os.PathLike[str], prefix: str | None = None) -> list[pathlib.Path]:
        """Writes every produced part to ``directory`` as ``<prefix>.<name>.<extension>`` (for
        example ``emulator-5554.screenshot.png``; ``prefix`` defaults to the serial), creating
        ``directory``, and returns the written paths."""
        root = pathlib.Path(directory)
        stem = self.serial if prefix is None else prefix
        return [a.save(root / f"{stem}.{name}.{a.extension}") for name, a in self.artifacts.items()]

    def __repr__(self) -> str:
        missing = f", missing {list(self.failures)}" if self.failures else ""
        return f"Capture({self.serial}: {list(self.artifacts)}{missing})"
