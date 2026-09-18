package com.company.tap.sdk

import com.company.tap.host.CommandException
import com.company.tap.protocol.Selector

/** Base of SDK-level failures. Driver-reported failures surface as [CommandException] instead. */
open class TapException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A wait ran out of time. Carries what was observed so the failure is diagnosable without a rerun. */
class WaitTimeoutException(
    val description: String,
    val serial: String,
    val selector: Selector?,
    val elapsedMs: Long,
    val polls: Int,
    val lastObservation: String?,
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
