package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.Response
import io.github.noamcohen48.tap.protocol.Selector
import io.github.noamcohen48.tap.protocol.MatchMode
import io.github.noamcohen48.tap.protocol.Node
import io.github.noamcohen48.tap.protocol.NodeFlag
import io.github.noamcohen48.tap.protocol.Pick
import io.github.noamcohen48.tap.protocol.Scope
import io.github.noamcohen48.tap.protocol.TextProperty

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
) : TapHostException(message, cause) {
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
    when (val pick = pick) {
        Pick.ExactlyOne -> Unit
        Pick.First -> append(".first()")
        is Pick.At -> append(".at(").append(pick.index).append(')')
    }
    when (val scope = scope) {
        Scope.Aut -> Unit
        is Scope.System -> append(" in system:").append(scope.packageName)
    }
}

/** `text="OK" clickable=true` for a conjunction, `(a | b)` for a disjunction, `child(...)` for a relation. */
fun Node.render(): String = when (this) {
    is Node.Match -> "${property.render()}${mode.render()}\"$value\""
    is Node.Flag -> "${property.render()}=$value"
    is Node.Resource -> if (packageName == null) "res=\"$name\"" else "id=$packageName:\"$name\""
    is Node.Related -> "${relation.name.lowercase()}(${node.render()})"
    is Node.AllOf -> nodes.joinToString(" ") { it.render() }
    is Node.AnyOf -> nodes.joinToString(" | ", prefix = "(", postfix = ")") { it.render() }
}

private fun TextProperty.render(): String = when (this) {
    TextProperty.TEXT -> "text"
    TextProperty.CONTENT_DESCRIPTION -> "contentDescription"
    TextProperty.HINT -> "hint"
    TextProperty.CLASS_NAME -> "className"
}

/** `LONG_CLICKABLE` -> `longClickable`. */
private fun NodeFlag.render(): String =
    name.lowercase().replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }

private fun MatchMode.render(): String = when (this) {
    MatchMode.EXACT -> "="
    MatchMode.CONTAINS -> "~="
    MatchMode.STARTS_WITH -> "^="
    MatchMode.ENDS_WITH -> "$="
    MatchMode.REGEX -> "/="
}
