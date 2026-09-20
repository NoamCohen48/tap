package com.company.tap.host

import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.MatchLimit
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.NodeSelector
import com.company.tap.protocol.TargetScope

/**
 * A driver command that did not succeed. Every failure carries the typed [code] plus enough
 * routing context to reproduce or classify it without the original call site: the device,
 * generation, request ID, operation (`op` name), rendered selector, and timeout.
 *
 * [retryable] is a hint for a caller-owned policy; nothing in Tap retries automatically.
 * [mayHaveMutated] is the safety property: when true, the device state may have changed and
 * the command must never be replayed blindly.
 */
sealed class CommandException(
    val code: ErrorCode,
    val operation: String,
    val requestId: Long,
    val sessionGeneration: Long,
    val serial: String?,
    val selector: String?,
    val timeoutMs: Long?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val retryable: Boolean get() = code.retryable
    val mayHaveMutated: Boolean get() = code.mayHaveMutated
}

/** The driver replied with a terminal error response. */
class RemoteCommandException(
    code: ErrorCode,
    val detail: String?,
    val remoteMessage: String?,
    val durationMs: Long,
    operation: String,
    requestId: Long,
    sessionGeneration: Long,
    serial: String? = null,
    selector: String? = null,
    timeoutMs: Long? = null,
) : CommandException(
    code,
    operation,
    requestId,
    sessionGeneration,
    serial,
    selector,
    timeoutMs,
    buildString {
        append(code)
        if (detail != null) append('/').append(detail)
        append(" from ").append(operation)
        if (selector != null) append(' ').append(selector)
        append(" (request ").append(requestId)
        append(", generation ").append(sessionGeneration)
        if (serial != null) append(", device ").append(serial)
        if (timeoutMs != null) append(", timeout ").append(timeoutMs).append("ms")
        append(", took ").append(durationMs).append("ms)")
        if (!remoteMessage.isNullOrBlank()) append(": ").append(remoteMessage)
    },
) {
    companion object {
        fun from(
            response: Response.Error,
            operation: String,
            requestId: Long,
            sessionGeneration: Long,
            serial: String? = null,
            selector: String? = null,
            timeoutMs: Long? = null,
        ): RemoteCommandException = RemoteCommandException(
            code = response.code,
            detail = response.detail,
            remoteMessage = response.message,
            durationMs = response.durationMs,
            operation = operation,
            requestId = requestId,
            sessionGeneration = sessionGeneration,
            serial = serial,
            selector = selector,
            timeoutMs = timeoutMs,
        )
    }
}

/**
 * No terminal response was obtained. [code] is `INDETERMINATE` when a mutating request may have
 * reached the driver and `TRANSPORT_LOST` otherwise; [transmissionState] says how far the
 * request got.
 */
class CommandTransportException(
    code: ErrorCode,
    operation: String,
    requestId: Long,
    sessionGeneration: Long,
    val transmissionState: TransmissionState,
    cause: Throwable,
    serial: String? = null,
    selector: String? = null,
    timeoutMs: Long? = null,
) : CommandException(
    code,
    operation,
    requestId,
    sessionGeneration,
    serial,
    selector,
    timeoutMs,
    "$code during $operation request $requestId in generation $sessionGeneration ($transmissionState)",
    cause,
) {
    init {
        require(code == ErrorCode.TRANSPORT_LOST || code == ErrorCode.INDETERMINATE) {
            "Transport failures are TRANSPORT_LOST or INDETERMINATE, not $code"
        }
    }
}

enum class TransmissionState {
    NOT_WRITTEN,
    WRITING,
    WRITTEN,
    TERMINAL_RESPONSE,
}

/** Compact, log-safe rendering used in exception messages and reports. */
fun Selector.render(): String = buildString {
    append(node.render())
    when (limit) {
        MatchLimit.EXACTLY_ONE -> Unit
        MatchLimit.FIRST -> append(".first()")
        MatchLimit.AT -> append(".at(").append(index).append(')')
    }
    if (scope == TargetScope.SYSTEM) append(" in system:").append(scopePackage)
}

fun NodeSelector.render(): String = buildString {
    val parts = mutableListOf<String>()
    stringProperties.forEach { (name, match) -> parts += "$name${match.mode.render()}\"${match.value}\"" }
    resource?.let { parts += if (it.packageName == null) "res=\"${it.name}\"" else "id=${it.packageName}:\"${it.name}\"" }
    booleanProperties.forEach { (name, value) -> parts += "$name=$value" }
    relations.forEach { (name, related) -> parts += "$name(${related.render()})" }
    append(parts.joinToString(" "))
}

private fun MatchMode.render(): String = when (this) {
    MatchMode.EXACT -> "="
    MatchMode.CONTAINS -> "~="
    MatchMode.STARTS_WITH -> "^="
    MatchMode.ENDS_WITH -> "$="
    MatchMode.REGEX -> "/="
}
