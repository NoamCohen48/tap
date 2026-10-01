package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_ACTION_REJECTED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_AMBIGUOUS
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_ARTIFACT_TRANSFER_FAILED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_AUT_ANR
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_AUT_CRASHED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_AUT_MISMATCH
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_AUT_NOT_INSTALLED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_CANCELLED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_DEADLINE_EXCEEDED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_DRIVER_UNHEALTHY
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_DUPLICATE_OR_STALE
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_INDETERMINATE
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_INTERNAL
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_INVALID_REQUEST
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_INVALID_SELECTOR
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_NOT_FOUND
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_NOT_INTERACTABLE
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_OVERLOADED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_PAYLOAD_TOO_LARGE
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_SESSION_MISMATCH
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_STALE_DURING_COMMAND
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_SYNC_PROVIDER_UNAVAILABLE
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_TRANSPORT_LOST
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_UNAUTHENTICATED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_UNKNOWN
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_UNSPECIFIED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_UNSUPPORTED
import io.github.noamcohen48.tap.api.v1.ErrorCode.ERR_WAIT_TIMEOUT
import io.github.noamcohen48.tap.api.v1.ErrorCode.UNRECOGNIZED

/*
 * The closed remote error taxonomy is `tap.v1.ErrorCode`. A response carries exactly one code;
 * finer, stable sub-reasons travel in `Error.detail` ([ErrorDetail]) so the enum stays small and
 * every client can branch on it without knowing driver internals. Both `when`s below are
 * exhaustive, so a new code does not compile until it is classified.
 */

/**
 * The safety property: `false` guarantees the command did not change device state, so a
 * caller's policy may retry it. `true` codes must never be retried blindly; anything this build
 * cannot name counts as `true`.
 */
val ErrorCode.mayHaveMutated: Boolean
    get() =
        when (this) {
            ERR_INVALID_REQUEST, ERR_INVALID_SELECTOR, ERR_UNSUPPORTED, ERR_UNAUTHENTICATED,
            ERR_SESSION_MISMATCH, ERR_DUPLICATE_OR_STALE, ERR_OVERLOADED, ERR_AUT_MISMATCH,
            ERR_NOT_FOUND, ERR_AMBIGUOUS, ERR_NOT_INTERACTABLE, ERR_WAIT_TIMEOUT, ERR_CANCELLED,
            ERR_DEADLINE_EXCEEDED, ERR_AUT_NOT_INSTALLED, ERR_SYNC_PROVIDER_UNAVAILABLE,
            ERR_DRIVER_UNHEALTHY, ERR_TRANSPORT_LOST,
            -> false

            ERR_STALE_DURING_COMMAND, ERR_ACTION_REJECTED, ERR_AUT_CRASHED, ERR_AUT_ANR,
            ERR_INDETERMINATE, ERR_ARTIFACT_TRANSFER_FAILED, ERR_PAYLOAD_TOO_LARGE, ERR_INTERNAL,
            ERR_UNKNOWN, ERR_UNSPECIFIED, UNRECOGNIZED,
            -> true
        }

/** Whether the same command may reasonably succeed if tried again later. */
val ErrorCode.retryable: Boolean
    get() =
        when (this) {
            ERR_OVERLOADED, ERR_NOT_FOUND, ERR_NOT_INTERACTABLE, ERR_WAIT_TIMEOUT, ERR_CANCELLED,
            ERR_DEADLINE_EXCEEDED, ERR_SYNC_PROVIDER_UNAVAILABLE,
            -> true

            ERR_INVALID_REQUEST, ERR_INVALID_SELECTOR, ERR_UNSUPPORTED, ERR_UNAUTHENTICATED,
            ERR_SESSION_MISMATCH, ERR_DUPLICATE_OR_STALE, ERR_AUT_MISMATCH, ERR_AMBIGUOUS,
            ERR_STALE_DURING_COMMAND, ERR_ACTION_REJECTED, ERR_AUT_NOT_INSTALLED, ERR_AUT_CRASHED,
            ERR_AUT_ANR, ERR_DRIVER_UNHEALTHY, ERR_TRANSPORT_LOST, ERR_INDETERMINATE,
            ERR_ARTIFACT_TRANSFER_FAILED, ERR_PAYLOAD_TOO_LARGE, ERR_INTERNAL, ERR_UNKNOWN,
            ERR_UNSPECIFIED, UNRECOGNIZED,
            -> false
        }

/**
 * A received code as this build understands it: a value it does not know (a newer peer), or
 * the zero value, is [ERR_UNKNOWN]. Senders never put either on the wire.
 */
fun ErrorCode.normalized(): ErrorCode = if (this == UNRECOGNIZED || this == ERR_UNSPECIFIED) ERR_UNKNOWN else this

/** `NOT_FOUND` for `ERR_NOT_FOUND`: the protocol name used in messages and logs. */
val ErrorCode.label: String get() = normalized().name.removePrefix("ERR_")

/** Stable machine-readable sub-reasons carried in `Error.detail`. */
object ErrorDetail {
    // INVALID_SELECTOR
    const val SELECTOR_TOO_DEEP = "SELECTOR_TOO_DEEP"
    const val SELECTOR_TOO_LARGE = "SELECTOR_TOO_LARGE"
    const val STRING_TOO_LONG = "STRING_TOO_LONG"
    const val EMPTY_NODE = "EMPTY_NODE"
    const val EMPTY_VALUE = "EMPTY_VALUE"
    const val INVALID_REGEX = "INVALID_REGEX"
    const val UNSPECIFIED_VALUE = "UNSPECIFIED_VALUE"
    const val QUALIFIED_RESOURCE_NAME = "QUALIFIED_RESOURCE_NAME"

    // INVALID_REQUEST
    const val UNSUPPORTED_CHARACTERS = "UNSUPPORTED_CHARACTERS"

    // WAIT_TIMEOUT: why the condition was still unmet at the last poll. wait_visible /
    // wait_gone also set Error.match_count.
    const val NO_MATCH = "NO_MATCH"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val STILL_PRESENT = "STILL_PRESENT"

    // WAIT_TIMEOUT (screen stability, wait_app_visible)
    const val SCREEN_CHANGING = "SCREEN_CHANGING"
    const val APP_NOT_VISIBLE = "APP_NOT_VISIBLE"

    // NOT_INTERACTABLE: the gesture's touch point is inside a window above the target's.
    const val OBSCURED = "OBSCURED"

    // ACTION_REJECTED / STALE_DURING_COMMAND / INDETERMINATE (key input)
    const val KEY_RELEASE_FAILED = "KEY_RELEASE_FAILED"
    const val PARTIAL_INPUT = "PARTIAL_INPUT"
    const val TARGET_GONE = "TARGET_GONE"
    const val TARGET_AMBIGUOUS = "TARGET_AMBIGUOUS"

    // AUT_MISMATCH (synchronization identity)
    const val PROCESS_RESTARTED = "PROCESS_RESTARTED"
    const val PROCESS_MISMATCH = "PROCESS_MISMATCH"

    // SYNC_PROVIDER_UNAVAILABLE
    const val CERTIFICATE_MISMATCH = "CERTIFICATE_MISMATCH"
    const val UNINITIALIZED = "UNINITIALIZED"
    const val MALFORMED_STATE = "MALFORMED_STATE"
    const val PROVIDER_ERROR = "PROVIDER_ERROR"
    const val PROVIDER_TIMEOUT = "PROVIDER_TIMEOUT"
    const val PROVIDER_POISONED = "PROVIDER_POISONED"

    // ARTIFACT_TRANSFER_FAILED
    const val CAPTURE_FAILED = "CAPTURE_FAILED"
    const val ARTIFACT_TOO_LARGE = "ARTIFACT_TOO_LARGE"
    const val BLOB_UNEXPECTED = "BLOB_UNEXPECTED"
    const val BLOB_OUT_OF_ORDER = "BLOB_OUT_OF_ORDER"
    const val BLOB_MALFORMED = "BLOB_MALFORMED"
    const val BLOB_LENGTH_MISMATCH = "BLOB_LENGTH_MISMATCH"
    const val BLOB_CHECKSUM_MISMATCH = "BLOB_CHECKSUM_MISMATCH"
    const val BLOB_INCOMPLETE = "BLOB_INCOMPLETE"

    // DEADLINE_EXCEEDED / CANCELLED / DRIVER_UNHEALTHY (pipeline)
    const val EXPIRED_IN_QUEUE = "EXPIRED_IN_QUEUE"
    const val CANCELLED_IN_QUEUE = "CANCELLED_IN_QUEUE"
    const val TRANSPORT_CLOSED = "TRANSPORT_CLOSED"
    const val WATCHDOG = "WATCHDOG"
    const val HEARTBEAT_EXPIRED = "HEARTBEAT_EXPIRED"
}

/**
 * Thrown by a [CommandHandler] method to end the command with an error response. The engine
 * converts it; handlers never build responses themselves.
 */
class CommandFailure(
    val code: ErrorCode,
    val detail: String? = null,
    message: String? = null,
    /** `Error.match_count`: matches at a wait's last poll. */
    val matchCount: Int? = null,
) : RuntimeException(message ?: code.label) {
    val remoteMessage: String? = message
}
