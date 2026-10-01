"""Exception hierarchy. Driver failures arrive as data (CommandResult.error) and become
CommandError; server-level failures arrive as gRPC status codes and are mapped in client.py."""

from __future__ import annotations

from typing import TYPE_CHECKING

from . import _proto
from .models import ErrorCode, FailureReason, WaitReason

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
    condition and what was observed so a log line alone is diagnosable: ``reason`` and
    ``match_count`` for the waits the device runs (``visible``, ``one``, ``gone``,
    ``App.await_visible``, ``App.await_screen_stable``), ``last_observation`` and ``polls`` for the
    ones the client polls."""

    def __init__(
        self,
        description: str,
        serial: str,
        elapsed_ms: int,
        polls: int = 0,
        last: str | None = None,
        reason: WaitReason | None = None,
        match_count: int | None = None,
    ):
        self.description = description
        self.serial = serial
        self.elapsed_ms = elapsed_ms
        self.polls = polls
        self.last_observation = last
        self.reason = reason
        """Why the device-side condition was still unmet at its last poll; None for client-polled
        waits."""
        self.match_count = match_count
        """Matches at the last poll of ``visible`` / ``one`` / ``gone`` (capped at 1000)."""
        why = ""
        if reason is not None:
            why = f"; {reason.value}" + (f" ({match_count} matches)" if match_count is not None else "")
        suffix = f"; last observed: {last}" if last else ""
        polled = f" after {polls} polls" if polls else ""
        super().__init__(
            f"Timed out after {elapsed_ms} ms{polled} waiting for {description} on {serial}{why}{suffix}"
        )

    @classmethod
    def _from_result(
        cls, result: pb.CommandResult, description: str, serial: str, last: str | None = None
    ) -> WaitTimeoutError:
        """From a device ``WAIT_TIMEOUT`` result: its ``detail`` and ``match_count``."""
        error = result.error
        return cls(
            description,
            serial,
            result.duration_ms,
            last=last,
            reason=_proto.wait_reason(error.detail) if error.HasField("detail") else None,
            match_count=error.match_count if error.HasField("match_count") else None,
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
