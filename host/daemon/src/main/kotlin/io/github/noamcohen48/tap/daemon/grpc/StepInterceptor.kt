package io.github.noamcohen48.tap.daemon.grpc

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

/** The step the running call belongs to (its `tap-step` header); null when it carried none. */
internal val STEP: Context.Key<String> = Context.key("tap-step")

/**
 * Makes a call's `tap-step` header readable as [STEP] while the call runs. A client that records
 * its own steps sends the id of the step with every call the step makes; the daemon copies it to
 * the device log's action entries and the event log, so the two records can be matched. A
 * malformed id is INVALID_ARGUMENT before anything runs.
 */
class StepInterceptor : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val step = headers.get(HEADER) ?: return next.startCall(call, headers)
        if (!valid(step)) {
            call.close(Status.INVALID_ARGUMENT.withDescription("tap-step must be 1 to $MAX_CHARS of A-Z a-z 0-9 . _ -"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return Contexts.interceptCall(Context.current().withValue(STEP, step), call, headers, next)
    }

    companion object {
        val HEADER: Metadata.Key<String> = Metadata.Key.of("tap-step", Metadata.ASCII_STRING_MARSHALLER)
        const val MAX_CHARS = 64

        fun valid(step: String): Boolean =
            step.length in 1..MAX_CHARS && step.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    }
}
