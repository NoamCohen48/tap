package com.company.tap.sdk

import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.ErrorCode

/** Base of everything the client raises deliberately. */
open class TapException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The host service rejected or failed a call (unknown run/session, bad argument, ...). */
class ServiceException(val status: String, val details: String, cause: Throwable? = null) :
    TapException("$status: $details", cause)

/**
 * A driver command returned `ok=false`. [code] is the protocol error code; [detail] refines it
 * (e.g. `NOT_FOUND`/`END_REACHED`); `TRANSPORT_LOST`/`INDETERMINATE` carry the transmission
 * state in [detail]. Never retry a mutation on `INDETERMINATE`.
 */
class CommandException(
    val result: CommandResult,
    val operation: String,
    val serial: String,
    val selector: String?,
) : TapException(
    buildString {
        append(result.errorCode.name.removePrefix("ERR_"))
        if (result.hasDetail()) append('/').append(result.detail)
        append(" during ").append(operation)
        if (selector != null) append(' ').append(selector)
        append(" on ").append(serial)
        append(" (request ${result.requestId}, generation ${result.sessionGeneration}, ${result.durationMs} ms)")
        if (result.hasMessage()) append(": ").append(result.message)
    },
) {
    val code: ErrorCode get() = result.errorCode
    val detail: String? get() = if (result.hasDetail()) result.detail else null
    val requestId: Long get() = result.requestId
    val generation: Long get() = result.sessionGeneration
}

/** A wait ran out of time. Carries what was observed so the failure is diagnosable without a rerun. */
class WaitTimeoutException(
    val description: String,
    val serial: String,
    val elapsedMs: Long,
    val polls: Int = 0,
    val lastObservation: String? = null,
    cause: Throwable? = null,
) : TapException(
    buildString {
        append("Timed out after ${elapsedMs}ms waiting for $description on $serial")
        if (polls > 0) append(" ($polls polls)")
        if (lastObservation != null) append("; last observed: $lastObservation")
    },
    cause,
)

/** An AUT lifecycle postcondition did not hold (process still alive, window never appeared...). */
class AppLifecycleException(message: String, cause: Throwable? = null) : TapException(message, cause)
