package com.company.tap.service.servicer

import com.company.tap.host.AppLifecycleException
import com.company.tap.host.DeviceBusyException
import com.company.tap.host.HostWaitTimeoutException
import com.company.tap.protocol.ENGINE_VERSION
import com.company.tap.service.UnknownConnectionException
import com.company.tap.service.UnknownSessionException
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException

const val SERVICE_VERSION = ENGINE_VERSION

/** Defaults applied when a request leaves its timeout at 0. */
const val DEFAULT_ACTION_TIMEOUT_MS = 10_000L
const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
const val DEFAULT_LIFECYCLE_TIMEOUT_MS = 30_000L

/** Maps service exceptions to gRPC status codes; everything else is INTERNAL with the message. */
internal fun Throwable.toStatus(): StatusRuntimeException = when (this) {
    is StatusRuntimeException -> this
    is UnknownConnectionException, is UnknownSessionException -> Status.NOT_FOUND.withDescription(message).asRuntimeException()
    is IllegalArgumentException -> Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException()
    // A busy device is a precondition failure when no wait was asked for, a timeout when it was.
    is DeviceBusyException -> (if (waitedMs > 0) Status.DEADLINE_EXCEEDED else Status.FAILED_PRECONDITION).withDescription(message).asRuntimeException()
    is HostWaitTimeoutException -> Status.DEADLINE_EXCEEDED.withDescription(message).asRuntimeException()
    is AppLifecycleException, is IllegalStateException -> Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException()
    else -> Status.INTERNAL.withDescription("${this::class.simpleName}: $message").withCause(this).asRuntimeException()
}

/** Runs [block] and returns its result; failures become the mapped gRPC status. Cancellation
 * propagates untouched: grpc-kotlin reports it as CANCELLED, and mapping it would lie. */
internal suspend fun <T> reply(block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    throw error.toStatus()
}

