package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.CommandResult

/** Base of everything the client raises deliberately. */
open class TapException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The host server rejected or failed a call (unknown connection or device, bad argument, driver
 * start failure, ...). [status] is the gRPC status code name; [reason] is the server's structured
 * failure reason, [FailureReason.UNSPECIFIED] when the failure did not come from the daemon.
 */
class ServerException(
    val status: String,
    val details: String,
    cause: Throwable? = null,
    val reason: FailureReason = FailureReason.UNSPECIFIED,
) : TapException("$status: $details", cause)

/**
 * A driver command's outcome was `error`. [code] is the protocol error code; [detail] refines it
 * (e.g. `NOT_FOUND`/`END_REACHED`); `TRANSPORT_LOST`/`INDETERMINATE` carry the transmission
 * state in [detail]. Never retry a mutation on `INDETERMINATE`. [requestId] and [generation]
 * identify the command in the driver and daemon logs.
 */
class CommandException(
    val code: ErrorCode,
    val detail: String?,
    /** The driver's human-readable explanation, when it gave one. */
    val driverMessage: String?,
    val operation: String,
    val serial: String,
    val selector: String?,
    val requestId: Long,
    val generation: Long,
    val durationMs: Long,
) : TapException(
        buildString {
            append(code.name)
            if (detail != null) append('/').append(detail)
            append(" during ").append(operation)
            if (selector != null) append(' ').append(selector)
            append(" on ").append(serial)
            append(" (request $requestId, generation $generation, $durationMs ms)")
            if (driverMessage != null) append(": ").append(driverMessage)
        },
    ) {
    internal constructor(
        result: CommandResult,
        operation: String,
        serial: String,
        selector: String?,
    ) : this(
        code = result.error.code.toModel(),
        detail = if (result.error.hasDetail()) result.error.detail else null,
        driverMessage = if (result.error.hasMessage()) result.error.message else null,
        operation = operation,
        serial = serial,
        selector = selector,
        requestId = result.requestId,
        generation = result.sessionGeneration,
        durationMs = result.durationMs,
    )
}

/**
 * A wait ran out of time. Carries what was observed so the failure is diagnosable without a
 * rerun: [reason] and [matchCount] for the waits the device runs (`visible`, `one`, `gone`,
 * `App.awaitVisible`, `App.awaitScreenStable`), [lastObservation] and [polls] for the ones the client
 * polls.
 */
class WaitTimeoutException(
    val description: String,
    val serial: String,
    val elapsedMs: Long,
    val polls: Int = 0,
    val lastObservation: String? = null,
    cause: Throwable? = null,
    /** Why the device-side condition was still unmet at its last poll; null for client-polled waits. */
    val reason: WaitReason? = null,
    /** Matches at the last poll of `visible` / `one` / `gone` (capped at 1000). */
    val matchCount: Int? = null,
) : TapException(
        buildString {
            append("Timed out after ${elapsedMs}ms waiting for $description on $serial")
            if (polls > 0) append(" ($polls polls)")
            if (reason != null) append("; ").append(reason.name).append(matchCount?.let { " ($it matches)" } ?: "")
            if (lastObservation != null) append("; last observed: $lastObservation")
        },
        cause,
    ) {
    internal companion object {
        /** From a device `WAIT_TIMEOUT` result: its `detail` and `match_count`. */
        fun of(
            result: CommandResult,
            description: String,
            serial: String,
            lastObservation: String? = null,
        ) = WaitTimeoutException(
            description,
            serial,
            result.durationMs,
            lastObservation = lastObservation,
            reason = if (result.error.hasDetail()) WaitReason.of(result.error.detail) else null,
            matchCount = if (result.error.hasMatchCount()) result.error.matchCount else null,
        )
    }
}

/** An AUT lifecycle postcondition did not hold (process still alive, window never appeared...). */
class AppLifecycleException(
    message: String,
    cause: Throwable? = null,
) : TapException(message, cause)

/**
 * Another session — in this process or any other — holds the device, and [DeviceOptions.waitForDevice]
 * was zero or ran out. The device is fine; try later or pick another serial.
 */
class DeviceBusyException(
    message: String,
    cause: Throwable? = null,
) : TapException(message, cause)

/**
 * The device's session journal keeps it out of service (a mutation whose outcome could not be
 * proven, a corrupt journal, an uncertain ADB cleanup). It stays unusable until an explicit
 * reset; retrying or waiting does not help.
 */
class DeviceQuarantinedException(
    val serial: String,
    message: String,
    cause: Throwable? = null,
) : TapException(message, cause)

/**
 * A suspending framework call was made outside its owning scope: `tapTest { ... }` without an
 * active `@TapTest` binding, a nested `tapTest`/`tapScope`, or a `Device`/`App`/`Element` call
 * from a coroutine that is not a child of that scope (for example `GlobalScope`). Scripts must
 * wrap device use in `tapScope { ... }`; JUnit tests must use `tapTest { ... }`.
 */
class TapUsageException(
    message: String,
    cause: Throwable? = null,
) : TapException(message, cause)
