package com.company.tap.server

import com.company.tap.daemon.DaemonPreconditionException
import com.company.tap.daemon.NotOwnerException
import com.company.tap.daemon.UnknownAttachedDeviceException
import com.company.tap.daemon.UnknownClientConnectionException
import com.company.tap.host.AdbCommandException
import com.company.tap.host.AdbReapUncertainException
import com.company.tap.host.AdbRunnerGatedException
import com.company.tap.host.AdbTimeoutException
import com.company.tap.host.AppLifecycleException
import com.company.tap.host.CommandTransportException
import com.company.tap.host.DeviceBusyException
import com.company.tap.host.DeviceQuarantinedException
import com.company.tap.host.DriverBuildMismatchException
import com.company.tap.host.DriverStartException
import com.company.tap.host.HostWaitTimeoutException
import com.company.tap.host.RemoteCommandException
import com.company.tap.host.SessionUnusableException
import com.company.tap.protocol.ENGINE_VERSION
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException

const val DAEMON_VERSION = ENGINE_VERSION

/** Defaults applied when a request omits its timeout. */
const val DEFAULT_ACTION_TIMEOUT_MS = 10_000L
const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
const val DEFAULT_LIFECYCLE_TIMEOUT_MS = 30_000L

/** How long the app must stay quiet for `AwaitIdle` when the client sends no `stable_for_ms`. */
const val DEFAULT_IDLE_STABLE_MS = 200L

/** A client-given duration that must be > 0 (absent fields take the default instead). */
internal fun positive(
    value: Long,
    field: String,
): Long {
    require(value > 0) { "$field must be > 0 when set, got $value" }
    return value
}

internal fun nonNegative(
    value: Long,
    field: String,
): Long {
    require(value >= 0) { "$field must be >= 0 when set, got $value" }
    return value
}

/**
 * Maps known daemon and host-core exceptions to gRPC status codes. Anything else — including a
 * bare [IllegalStateException] from a `check()` — is a bug: INTERNAL, and logged with its stack.
 */
internal fun Throwable.toStatus(): StatusRuntimeException {
    val status: Status =
        when (this) {
            is StatusRuntimeException -> return this
            is UnknownClientConnectionException, is UnknownAttachedDeviceException -> Status.NOT_FOUND
            is NotOwnerException -> Status.PERMISSION_DENIED
            // Caller input: `require` in the servicers, selector validation, command constructors.
            is IllegalArgumentException -> Status.INVALID_ARGUMENT
            // A busy device is a precondition failure when no wait was asked for, a timeout when it was.
            is DeviceBusyException -> if (waitedMs > 0) Status.DEADLINE_EXCEEDED else Status.FAILED_PRECONDITION
            is HostWaitTimeoutException, is AdbTimeoutException -> Status.DEADLINE_EXCEEDED
            is DaemonPreconditionException,
            is DeviceQuarantinedException,
            is AdbReapUncertainException,
            is AppLifecycleException,
            is DriverBuildMismatchException,
            is RemoteCommandException,
            -> Status.FAILED_PRECONDITION
            // The session is unusable: detach and attach again.
            is SessionUnusableException -> Status.ABORTED
            is DriverStartException, is AdbCommandException, is AdbRunnerGatedException, is CommandTransportException ->
                Status.UNAVAILABLE
            else -> {
                System.err.println("[tap] INTERNAL: unexpected ${this::class.qualifiedName}")
                printStackTrace()
                return Status.INTERNAL
                    .withDescription("${this::class.simpleName}: $message")
                    .withCause(this)
                    .asRuntimeException()
            }
        }
    return status.withDescription(message).withCause(this).asRuntimeException()
}

/** Runs [block] and returns its result; failures become the mapped gRPC status. Cancellation
 * propagates untouched: grpc-kotlin reports it as CANCELLED, and mapping it would lie. */
internal suspend fun <T> reply(block: suspend () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw error.toStatus()
    }
