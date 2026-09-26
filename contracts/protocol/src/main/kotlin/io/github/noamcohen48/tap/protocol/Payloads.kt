package io.github.noamcohen48.tap.protocol

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.wire.v1.Request

/**
 * Parses a `REQUEST` payload; a payload that is not a `tap.wire.v1.Request` is `INVALID_REQUEST`.
 * The driver checks session identity and the request-id watermark next, then
 * [CommandValidation.validate] (unset body: `UNSUPPORTED`).
 *
 * @throws InvalidCommandException when the payload does not parse.
 */
fun parseRequest(payload: ByteArray): Request =
    try {
        Request.parseFrom(payload)
    } catch (error: InvalidProtocolBufferException) {
        throw InvalidCommandException(ErrorCode.ERR_INVALID_REQUEST, null, "Malformed request payload: ${error.message}", error)
    }

/**
 * Parses a control payload with [parse], mapping a malformed payload to [ProtocolException] so
 * callers treat it like any other framing violation.
 */
inline fun <T : MessageLite> parsePayload(
    what: String,
    payload: ByteArray,
    parse: (ByteArray) -> T,
): T =
    try {
        parse(payload)
    } catch (error: InvalidProtocolBufferException) {
        throw ProtocolException("Malformed $what payload: ${error.message}")
    }
