package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.label
import io.github.noamcohen48.tap.protocol.mayHaveMutated
import io.github.noamcohen48.tap.protocol.message
import io.github.noamcohen48.tap.protocol.retryable
import io.github.noamcohen48.tap.wire.v1.Response

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
        append(code.label)
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
        /** [response] must be an error response. */
        fun from(
            response: Response,
            operation: String,
            requestId: Long,
            sessionGeneration: Long,
            serial: String? = null,
            selector: String? = null,
            timeoutMs: Long? = null,
        ): RemoteCommandException = RemoteCommandException(
            code = requireNotNull(response.errorCode) { "Not an error response" },
            detail = response.detail,
            remoteMessage = response.message,
            durationMs = response.result.durationMs,
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
    "${code.label} during $operation request $requestId in generation $sessionGeneration ($transmissionState)",
    cause,
) {
    init {
        require(code == ErrorCode.ERR_TRANSPORT_LOST || code == ErrorCode.ERR_INDETERMINATE) {
            "Transport failures are TRANSPORT_LOST or INDETERMINATE, not ${code.label}"
        }
    }
}

enum class TransmissionState {
    NOT_WRITTEN,
    WRITING,
    WRITTEN,
    TERMINAL_RESPONSE,
}
