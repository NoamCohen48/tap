"""Exception hierarchy. Driver failures arrive as data (CommandResult.error) and become
CommandError; server-level failures arrive as gRPC status codes and are mapped in client.py."""

from __future__ import annotations

from typing import TYPE_CHECKING

from . import _proto
from .models import ErrorCode, FailureReason

if TYPE_CHECKING:
    from . import _gen as pb


class TapError(Exception):
    """Base class for everything this package raises deliberately."""


class ServerError(TapError):
    """The host server rejected or failed a call (unknown connection or device, bad argument,
    driver start failure, ...). ``code`` is the gRPC status code name; ``reason`` is the server's
    structured ``FailureReason``, ``FailureReason.UNSPECIFIED`` when the failure did not come
    from the daemon."""

    def __init__(
        self, code: str, details: str, reason: FailureReason = FailureReason.UNSPECIFIED
    ):
        super().__init__(f"{code}: {details}")
        self.code = code
        self.details = details
        self.reason = reason


class CommandError(TapError):
    """A driver command's outcome was ``error``. ``code`` is an ErrorCode; ``detail`` refines it
    (e.g. NOT_FOUND/END_REACHED); TRANSPORT_LOST/INDETERMINATE carry the transmission state."""

    def __init__(
        self,
        code: ErrorCode,
        detail: str | None,
        driver_message: str | None,
        operation: str,
        serial: str,
        selector: str | None,
        request_id: int,
        generation: int,
        duration_ms: int,
    ):
        self.code = code
        self.detail = detail
        self.driver_message = driver_message
        self.operation = operation
        self.serial = serial
        self.selector = selector
        self.request_id = request_id
        self.generation = generation
        self.duration_ms = duration_ms
        where = f" {selector}" if selector else ""
        detail = f"/{self.detail}" if self.detail else ""
        message = f": {self.driver_message}" if self.driver_message else ""
        super().__init__(
            f"{self.code.name}{detail} during {operation}{where} on {serial} "
            f"(request {self.request_id}, generation {self.generation}, {self.duration_ms} ms){message}"
        )


    @classmethod
    def _from_result(
        cls,
        result: pb.CommandResult,
        operation: str,
        serial: str,
        selector: str | None,
    ) -> CommandError:
        error = result.error
        return cls(
            _proto.error_code(error.code),
            error.detail if error.HasField("detail") else None,
            error.message if error.HasField("message") else None,
            operation,
            serial,
            selector,
            result.request_id,
            result.session_generation,
            result.duration_ms,
        )


class WaitTimeoutError(TapError):
    """A condition did not hold within its timeout; the message names the device, the
    condition and the last observation so a log line alone is diagnosable."""

    def __init__(
        self,
        description: str,
        serial: str,
        elapsed_ms: int,
        polls: int = 0,
        last: str | None = None,
    ):
        self.description = description
        self.serial = serial
        self.elapsed_ms = elapsed_ms
        self.polls = polls
        self.last_observation = last
        suffix = f"; last observed: {last}" if last else ""
        polled = f" after {polls} polls" if polls else ""
        super().__init__(
            f"Timed out after {elapsed_ms} ms{polled} waiting for {description} on {serial}{suffix}"
        )


class AppLifecycleError(TapError):
    """Install/launch/stop did not reach the verified end state."""


class DeviceBusyError(TapError):
    """Another session — in this process or any other — holds the device, and ``wait_for_device``
    was zero or ran out. The device is fine; try later or pick another serial."""


class DeviceQuarantinedError(TapError):
    """The device's session journal keeps it out of service (a mutation whose outcome could not
    be proven, a corrupt journal, an uncertain ADB cleanup). It stays unusable until an explicit
    reset; retrying or waiting does not help."""

    def __init__(self, serial: str, details: str):
        super().__init__(details)
        self.serial = serial
