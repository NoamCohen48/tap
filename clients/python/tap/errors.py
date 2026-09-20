"""Exception hierarchy. Driver failures arrive as data (CommandResult.ok == false) and become
CommandError; service-level failures arrive as gRPC status codes and are mapped in service.py."""
from __future__ import annotations

import enum

from ._gen import tap_pb2 as pb

# The error code enum is the proto's, minus the ERR_ prefix, so names match the wire protocol
# and the Kotlin SDK (e.g. ErrorCode.AMBIGUOUS).
ErrorCode = enum.Enum(  # type: ignore[misc]
    "ErrorCode",
    {name[len("ERR_"):]: value for name, value in pb.ErrorCode.items() if value != 0},
)


class TapError(Exception):
    """Base class for everything this package raises deliberately."""


class ServiceError(TapError):
    """The host service rejected or failed a call (unknown run/session, bad argument, ...)."""

    def __init__(self, code: str, details: str):
        super().__init__(f"{code}: {details}")
        self.code = code
        self.details = details


class CommandError(TapError):
    """A driver command returned ok=false. ``code`` is an ErrorCode; ``detail`` refines it
    (e.g. NOT_FOUND/END_REACHED); TRANSPORT_LOST/INDETERMINATE carry the transmission state."""

    def __init__(self, result: pb.CommandResult, operation: str, serial: str, selector: str | None):
        self.code = ErrorCode(result.error_code)
        self.detail = result.detail if result.HasField("detail") else None
        self.driver_message = result.message if result.HasField("message") else None
        self.operation = operation
        self.serial = serial
        self.selector = selector
        self.request_id = result.request_id
        self.generation = result.session_generation
        self.duration_ms = result.duration_ms
        where = f" {selector}" if selector else ""
        detail = f"/{self.detail}" if self.detail else ""
        message = f": {self.driver_message}" if self.driver_message else ""
        super().__init__(
            f"{self.code.name}{detail} during {operation}{where} on {serial} "
            f"(request {self.request_id}, generation {self.generation}, {self.duration_ms} ms){message}"
        )


class WaitTimeoutError(TapError):
    """A condition did not hold within its timeout; the message names the device, the
    condition and the last observation so a log line alone is diagnosable."""

    def __init__(self, description: str, serial: str, elapsed_ms: int, polls: int = 0, last: str | None = None):
        self.description = description
        self.serial = serial
        self.elapsed_ms = elapsed_ms
        self.polls = polls
        self.last_observation = last
        suffix = f"; last observed: {last}" if last else ""
        polled = f" after {polls} polls" if polls else ""
        super().__init__(f"Timed out after {elapsed_ms} ms{polled} waiting for {description} on {serial}{suffix}")


class AppLifecycleError(TapError):
    """Install/launch/stop did not reach the verified end state."""


class DeviceBusyError(TapError):
    """Another session — in this process or any other — holds the device, and ``wait_for_device``
    was zero or ran out. The device is fine; try later or pick another serial."""
