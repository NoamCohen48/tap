package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Response

/**
 * Reader-lane checks for a parsed, accepted request, run before it is submitted to the
 * [CommandPipeline]: session identity (`SESSION_MISMATCH`), then the timeout range and the shared
 * [CommandValidation] (arguments, selector structure). They need no UI and no
 * session config, so an invalid request is answered at once through [CommandPipeline.respond]
 * instead of waiting behind a running command (where it could come back `OVERLOADED` or expire in
 * the queue). The caller has already consumed the request ID.
 */
object RequestScreening {
    /** Returns the rejection to send for [request], or null when it may be enqueued. */
    fun screen(
        request: Request,
        sessionId: String,
        generation: Long,
    ): Response? {
        if (request.sessionId != sessionId || request.generation != generation) {
            return Responses.failure(
                ErrorCode.ERR_SESSION_MISMATCH,
                message = "Request is for session ${request.sessionId} generation ${request.generation}",
            )
        }
        if (request.timeoutMs !in 0..MAX_REQUEST_TIMEOUT_MS) {
            return Responses.failure(ErrorCode.ERR_INVALID_REQUEST, message = "timeout_ms must be in 0..$MAX_REQUEST_TIMEOUT_MS")
        }
        return try {
            CommandValidation.validate(request)
            null
        } catch (invalid: InvalidCommandException) {
            Responses.failure(invalid.code, detail = invalid.detail, message = invalid.message)
        }
    }
}
