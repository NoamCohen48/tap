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
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .selectors import Selector

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


class Orientation(enum.Enum):
    """Display geometry: portrait is at least as tall as it is wide, whatever the device's natural
    orientation (``Device.set_orientation``, ``DeviceInfo.orientation``)."""

    PORTRAIT = "PORTRAIT"
    LANDSCAPE = "LANDSCAPE"


class DisplayRotation(enum.Enum):
    """Display rotation relative to the device's natural orientation, named like AndroidX
    ``UiDevice``: ``LEFT`` is ``Surface.ROTATION_90``, ``UPSIDE_DOWN`` 180, ``RIGHT`` 270."""

    NATURAL = "NATURAL"
    LEFT = "LEFT"
    UPSIDE_DOWN = "UPSIDE_DOWN"
    RIGHT = "RIGHT"


class PermissionChoice(enum.Enum):
    """A button of Android's runtime-permission dialog. Which ones a dialog offers depends on the
    permission, the Android version and whether it was asked before
    (``Device.await_permission_prompt``)."""

    ALLOW = "ALLOW"
    """"Allow" (API 29 and older, and permissions without a foreground/one-time split)."""
    ALLOW_FOREGROUND_ONLY = "ALLOW_FOREGROUND_ONLY"
    """"While using the app"."""
    ALLOW_ONE_TIME = "ALLOW_ONE_TIME"
    """"Only this time" (API 30+)."""
    ALLOW_ALWAYS = "ALLOW_ALWAYS"
    """"Allow all the time" (background location on API 29)."""
    ALLOW_SELECTED = "ALLOW_SELECTED"
    """"Select photos and videos" (API 34+ partial media access)."""
    ALLOW_ALL = "ALLOW_ALL"
    """"Allow all" (API 34+ media access)."""
    DENY = "DENY"
    """"Deny" / "Don't allow"."""
    DENY_AND_DONT_ASK_AGAIN = "DENY_AND_DONT_ASK_AGAIN"
    """"Deny & don't ask again" (API 29/30 on a repeated request)."""
    KEEP_FOREGROUND_ONLY = "KEEP_FOREGROUND_ONLY"
    """"Keep while using the app" when an upgrade to background access is offered."""
    KEEP_ONE_TIME = "KEEP_ONE_TIME"
    """"Keep only this time" when an upgrade is offered."""


class StabilitySignal(enum.Enum):
    """What ``App.await_screen_stable`` watches: the accessibility tree, the window pixels
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
    UNSUPPORTED_API = "UNSUPPORTED_API"
    """The device's API level is too low for the call (detail ``REQUIRES_API_<n>``)."""
    DEVICE_SETTING = "DEVICE_SETTING"
    """A device setting did not read back as written (or could not be restored)."""


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
    """``App.await_visible`` / ``App.await_screen_stable``: the package never owned the focused
    window."""
    NO_PERMISSION_PROMPT = "NO_PERMISSION_PROMPT"
    """``await_permission_prompt``: no runtime-permission dialog showed a known choice."""

    NO_TOAST = "NO_TOAST"
    """``await_toast``: no matching toast was shown."""


class DeviceState(enum.Enum):
    """A device's state in ``TapClient.devices()``. Only ``FREE`` and ``LEASED`` devices can be
    attached; ``UNKNOWN`` is a state this client version does not know."""

    FREE = "FREE"
    LEASED = "LEASED"
    QUARANTINED = "QUARANTINED"
    OFFLINE = "OFFLINE"
    UNAUTHORIZED = "UNAUTHORIZED"
    UNKNOWN = "UNKNOWN"


class NodeFlag(enum.Enum):
    """A boolean property of a node; ``ScreenNode.flags`` holds the ones that are true."""

    ENABLED = "ENABLED"
    CHECKED = "CHECKED"
    CHECKABLE = "CHECKABLE"
    CLICKABLE = "CLICKABLE"
    FOCUSED = "FOCUSED"
    FOCUSABLE = "FOCUSABLE"
    LONG_CLICKABLE = "LONG_CLICKABLE"
    SCROLLABLE = "SCROLLABLE"
    SELECTED = "SELECTED"


class NodeChange(enum.Enum):
    """A ``ScreenNode`` against the previous snapshot of the same attached device. ``NONE``:
    there was no previous snapshot."""

    NONE = "NONE"
    ADDED = "ADDED"
    UNCHANGED = "UNCHANGED"
    REMOVED = "REMOVED"


class SelectorKind(enum.Enum):
    """How a ``SelectorCandidate`` singles out its node. ``PLAIN``: one property (resource id,
    text, description or hint); ``COMBINED``: two of the node's own properties; ``ANCESTOR``: a
    property plus an ancestor it sits under; ``BY_INDEX``: an index pick, right for this dump but
    fragile if the screen reorders. ``UNKNOWN``: a kind this client does not know."""

    PLAIN = "PLAIN"
    COMBINED = "COMBINED"
    ANCESTOR = "ANCESTOR"
    BY_INDEX = "BY_INDEX"
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


@dataclass(frozen=True)
class AttachedDeviceEntry:
    """A device attached to a connection (``ConnectionEntry.attached_devices``)."""

    attached_device_id: str
    serial: str
    generation: int


@dataclass(frozen=True)
class ConnectionEntry:
    """A live client connection (``TapClient.connections()``). ``hold`` is the idle timeout in
    seconds of a held connection, None for an observed one; ``idle`` is how long, in seconds,
    no call has named it (0 while one is running)."""

    id: str
    name: str
    hold: float | None
    idle: float
    attached_devices: tuple[AttachedDeviceEntry, ...]


@dataclass(frozen=True)
class LoggedEvent:
    """One device call from a connection's event log (``TapConnection.event_log()``).

    The call and its outcome are the server API's own messages in proto3 JSON form with the
    ``.proto`` field names, so any language's protobuf library can parse them: ``command`` is a
    ``tap.v1.Command`` (for Execute) and ``app`` a ``tap.v1.AppCall`` (for an app lifecycle call);
    exactly one is set. ``error`` is the ``tap.v1.Error`` the driver returned and ``failure`` the
    ``tap.v1.Failure`` of a call that failed as an RPC; both are None when the call succeeded.
    ``at`` is when the call started (Unix seconds); ``duration`` is in seconds."""

    seq: int
    at: float
    duration: float
    serial: str
    command: dict | None
    app: dict | None
    error: dict | None
    failure: dict | None

    @property
    def ok(self) -> bool:
        return self.error is None and self.failure is None

    def to_dict(self) -> dict:
        """A JSON-ready dict: the fields above with ``at`` as an ISO 8601 UTC timestamp and
        ``duration_ms``; None fields are left out."""
        import datetime

        at = datetime.datetime.fromtimestamp(self.at, datetime.timezone.utc)
        out: dict = {
            "seq": self.seq,
            "at": at.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "duration_ms": round(self.duration * 1000),
            "serial": self.serial,
            "ok": self.ok,
        }
        for key in ("command", "app", "error", "failure"):
            value = getattr(self, key)
            if value is not None:
                out[key] = value
        return out


@dataclass(frozen=True)
class EventLog:
    """``TapConnection.event_log()``: the kept events, oldest first, and how many older ones the
    server evicted (it keeps each connection's last 2000)."""

    events: tuple[LoggedEvent, ...]
    dropped: int


@dataclass(frozen=True)
class ScreenNode:
    """One visible node of a ``ScreenSnapshot``, in dump (pre-order) order.

    ``ref`` (``e<N>``) stays the same across snapshots while the node is unchanged and is never
    reused for another node. ``selector`` is one the daemon found to match only this node in the
    dump (None when there is none); ``by_index`` marks one that ends in an index pick, which is
    right for this dump but fragile if the screen reorders. ``window_package`` is the package of
    the window the node is in. ``interactive``: clickable, long-clickable, checkable, scrollable
    or editable."""

    ref: str
    depth: int
    window_package: str
    class_name: str | None
    resource_name: str | None
    text: str | None
    content_description: str | None
    hint: str | None
    bounds: Bounds
    flags: frozenset[NodeFlag]
    password: bool
    interactive: bool
    selector: Selector | None
    by_index: bool
    change: NodeChange
    candidates: tuple[SelectorCandidate, ...] = ()
    """Only from ``screen_snapshot(selector_candidates=True)``: every selector that matched only
    this node, best first (the first is ``selector``)."""


@dataclass(frozen=True)
class SelectorCandidate:
    """One selector that matched only its ``ScreenNode`` in the dump, and how it does so."""

    selector: Selector
    kind: SelectorKind


@dataclass(frozen=True)
class ScreenSnapshot:
    """The visible screen as ref-addressed nodes (``Device.screen_snapshot()``). ``removed``
    lists the previous snapshot's nodes that are gone; their refs no longer resolve.
    ``snapshot_id`` increases per attached device."""

    snapshot_id: int
    nodes: tuple[ScreenNode, ...]
    removed: tuple[ScreenNode, ...]
    rotation: int

    def node(self, ref: str) -> ScreenNode | None:
        """The node with ``ref`` (with or without the leading ``@``), or None."""
        ref = ref.removeprefix("@")
        return next((n for n in self.nodes if n.ref == ref), None)


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


class Recording(Artifact):
    """An opt-in device recording: MP4 video, Matroska video+audio, or Opus audio.

    ``Device.stop_recording()`` returns these verified bytes; save them wherever a test
    keeps its artifacts. A recording is never started implicitly on failure.
    """

    def __init__(self, data: builtins.bytes, format: str):  # noqa: A002
        if format not in ("mp4", "mkv", "opus"):
            raise ValueError(f"Unknown recording format: {format}")
        self._data = data
        self.extension = format
        self.media_type = {"mp4": "video/mp4", "mkv": "video/x-matroska", "opus": "audio/ogg"}[format]

    @property
    def bytes(self) -> builtins.bytes:
        """Encoded recording bytes."""
        return self._data


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
    """Static facts about the device plus its current display, screen and focus state
    (``Device.info()``). Serialized as JSON."""

    api_level: int
    manufacturer: str
    model: str
    product: str
    display_width: int
    display_height: int
    display_rotation: DisplayRotation
    current_package: str | None
    """The package owning the focused window, when there is one."""
    screen_on: bool
    """The screen is on (interactive)."""
    keyguard_locked: bool
    """The keyguard (lock screen) is showing."""
    keyguard_secure: bool
    """A PIN, pattern or password is set: Tap cannot dismiss this keyguard."""
    keyboard_shown: bool
    """A soft keyboard (any input method's window) is on screen."""
    auto_rotate: bool
    """Auto-rotate is on: the sensor turns the display (off while a rotation is frozen)."""
    animations_enabled: bool
    """Window, transition or animator animations run (any of the three scales is not 0)."""
    dark_mode: bool
    """The UI is in night mode (dark theme)."""
    font_scale: float
    """The font scale apps see (1.0 = the default size)."""
    density_dpi: int
    """The display density apps see, in dpi."""
    airplane_mode: bool
    """Airplane mode is on."""
    wifi_enabled: bool
    """Wi-Fi is switched on (also while airplane mode is on); says nothing about a connection."""
    mobile_data_enabled: bool
    """Mobile data is switched on; ``False`` on a device without telephony."""

    media_type = "application/json"
    extension = "json"

    @property
    def orientation(self) -> Orientation:
        """``PORTRAIT`` when the display is at least as tall as it is wide."""
        return Orientation.PORTRAIT if self.display_height >= self.display_width else Orientation.LANDSCAPE

    @property
    def bytes(self) -> builtins.bytes:
        fields = asdict(self)
        fields["display_rotation"] = self.display_rotation.value
        return json.dumps(fields).encode()


@dataclass(frozen=True)
class PermissionPrompt:
    """A runtime-permission dialog on screen (``Device.await_permission_prompt``): the package
    of its window (what ``in_package`` scopes its other elements with) and the choices it
    offers, in ``PermissionChoice`` order."""

    package_name: str
    choices: tuple[PermissionChoice, ...]


@dataclass(frozen=True)
class Toast:
    """A toast ``Device.await_toast`` saw: its text and the package that showed it (on Android
    11+ a text toast is drawn by SystemUI but still reported under the app that posted it)."""

    text: str
    package_name: str


@dataclass(frozen=True)
class Long:
    """An ``int`` sent as a 64-bit intent extra (``am start --el``), for an app that reads it with
    ``getLongExtra``: a plain ``int`` extra is 32-bit, and ``getLongExtra`` on it returns the
    default. ``App.launch(extras={"id": Long(42)})``."""

    value: int

    def __post_init__(self) -> None:
        if isinstance(self.value, bool) or not isinstance(self.value, int):
            raise TypeError(f"Long needs an int, not {type(self.value).__name__}")
        if not -(2**63) <= self.value < 2**63:
            raise ValueError(f"{self.value} does not fit in 64 bits")


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
