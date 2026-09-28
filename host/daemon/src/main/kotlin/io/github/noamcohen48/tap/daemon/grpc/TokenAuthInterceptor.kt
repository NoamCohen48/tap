package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import java.security.MessageDigest

/**
 * Rejects every call that does not carry `authorization: Bearer <token>`. The token is the
 * per-instance secret from `daemon.json` (0600), so only processes that can read the owner's
 * state dir can drive the daemon. Loopback binding alone would admit any local user.
 */
class TokenAuthInterceptor(
    token: String,
) : ServerInterceptor {
    private val expected = "Bearer $token".toByteArray(Charsets.UTF_8)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val presented = headers.get(AUTHORIZATION)?.toByteArray(Charsets.UTF_8)
        if (presented == null || !MessageDigest.isEqual(presented, expected)) {
            val trailers = Metadata().apply { put(FAILURE_TRAILER, Failure.newBuilder().setReason(FailureReason.FAILURE_REASON_UNAUTHENTICATED).build()) }
            call.close(Status.UNAUTHENTICATED.withDescription("missing or wrong daemon token"), trailers)
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }

    companion object {
        val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}
