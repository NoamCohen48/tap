package com.company.tap.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Closed remote error taxonomy. A response carries exactly one code; finer, stable sub-reasons
 * travel in [Response.Error.detail] (see [ErrorDetail]) so the enum stays small and every client can
 * branch on it without knowing driver internals.
 *
 * [mayHaveMutated] is the safety property: a code with `false` guarantees the device state was
 * not changed by this command, so a caller's policy may retry it. `true` codes must never be
 * retried blindly.
 */
@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode(val mayHaveMutated: Boolean, val retryable: Boolean) {
    // Request rejected before any work.
    INVALID_REQUEST(false, false),
    INVALID_SELECTOR(false, false),
    UNSUPPORTED(false, false),
    UNAUTHENTICATED(false, false),
    SESSION_MISMATCH(false, false),
    DUPLICATE_OR_STALE(false, false),
    OVERLOADED(false, true),

    // Target state.
    AUT_MISMATCH(false, false),
    NOT_FOUND(false, true),
    AMBIGUOUS(false, false),
    NOT_INTERACTABLE(false, true),
    STALE_DURING_COMMAND(true, false),

    // Outcome of an attempted action or wait.
    ACTION_REJECTED(true, false),
    WAIT_TIMEOUT(false, true),
    CANCELLED(false, true),
    DEADLINE_EXCEEDED(false, true),

    // Application lifecycle.
    AUT_NOT_INSTALLED(false, false),
    AUT_CRASHED(true, false),
    AUT_ANR(true, false),
    SYNC_PROVIDER_UNAVAILABLE(false, true),

    // Session and transport.
    DRIVER_UNHEALTHY(false, false),
    TRANSPORT_LOST(false, false),
    INDETERMINATE(true, false),
    ARTIFACT_TRANSFER_FAILED(true, false),
    PAYLOAD_TOO_LARGE(true, false),
    INTERNAL(true, false),

    /** Host-side decode fallback for a code this build does not know. Never sent by a driver. */
    UNKNOWN(true, false);

    companion object {
        fun fromWire(name: String): ErrorCode = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** Stable machine-readable sub-reasons carried in [Response.Error.detail]. */
object ErrorDetail {
    // INVALID_SELECTOR
    const val SCOPE_DENIED = "SCOPE_DENIED"
    const val SCOPE_MISMATCH = "SCOPE_MISMATCH"
    const val SELECTOR_TOO_DEEP = "SELECTOR_TOO_DEEP"
    const val SELECTOR_TOO_LARGE = "SELECTOR_TOO_LARGE"
    const val STRING_TOO_LONG = "STRING_TOO_LONG"
    const val EMPTY_NODE = "EMPTY_NODE"
    const val EMPTY_VALUE = "EMPTY_VALUE"
    const val INVALID_REGEX = "INVALID_REGEX"

    // INVALID_REQUEST
    const val UNSUPPORTED_CHARACTERS = "UNSUPPORTED_CHARACTERS"

    // NOT_FOUND (scrolling)
    const val END_REACHED = "END_REACHED"
    const val MAX_SCROLLS = "MAX_SCROLLS"

    // WAIT_TIMEOUT (screen stability)
    const val SCREEN_CHANGING = "SCREEN_CHANGING"
    const val APP_NOT_VISIBLE = "APP_NOT_VISIBLE"

    // ACTION_REJECTED / STALE_DURING_COMMAND / INDETERMINATE (key input)
    const val FOCUS_TIMEOUT = "FOCUS_TIMEOUT"
    const val FOCUS_LOST = "FOCUS_LOST"
    const val KEY_RELEASE_FAILED = "KEY_RELEASE_FAILED"
    const val TEXT_MISMATCH = "TEXT_MISMATCH"
    const val DEADLINE_AFTER_FOCUS = "DEADLINE_AFTER_FOCUS"
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

/** Encodes as the enum name; decodes unknown names to [ErrorCode.UNKNOWN] instead of failing. */
object ErrorCodeSerializer : KSerializer<ErrorCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.company.tap.protocol.ErrorCode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ErrorCode) {
        require(value != ErrorCode.UNKNOWN) { "UNKNOWN is a decode fallback and is never sent" }
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ErrorCode = ErrorCode.fromWire(decoder.decodeString())
}
