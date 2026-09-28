package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.daemon.core.DaemonPreconditionException
import io.github.noamcohen48.tap.daemon.core.NotOwnerException
import io.github.noamcohen48.tap.daemon.core.UnknownAttachedDeviceException
import io.github.noamcohen48.tap.daemon.core.UnknownClientConnectionException
import io.github.noamcohen48.tap.daemon.snapshot.RefNotAddressableException
import io.github.noamcohen48.tap.daemon.snapshot.UnknownRefException
import io.github.noamcohen48.tap.host.AdbCommandException
import io.github.noamcohen48.tap.host.AdbReapUncertainException
import io.github.noamcohen48.tap.host.AdbRunnerGatedException
import io.github.noamcohen48.tap.host.AdbTimeoutException
import io.github.noamcohen48.tap.host.AppLifecycleException
import io.github.noamcohen48.tap.host.CommandTransportException
import io.github.noamcohen48.tap.host.DeviceBusyException
import io.github.noamcohen48.tap.host.DeviceQuarantinedException
import io.github.noamcohen48.tap.host.DriverBuildMismatchException
import io.github.noamcohen48.tap.host.DriverStartException
import io.github.noamcohen48.tap.host.HostWaitTimeoutException
import io.github.noamcohen48.tap.host.RemoteCommandException
import io.github.noamcohen48.tap.host.SessionUnusableException
import io.github.noamcohen48.tap.protocol.ENGINE_VERSION
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.lite.ProtoLiteUtils
import kotlinx.coroutines.CancellationException

const val DAEMON_VERSION = ENGINE_VERSION

/**
 * Defaults applied when a request omits a timeout, echoed in `InfoResponse.defaults` so clients
 * use the same numbers (the conformance table in `clients/conformance` pins the clients' copies).
 */
object Defaults {
    const val ACTION_TIMEOUT_MS = 10_000L
    const val WAIT_TIMEOUT_MS = 10_000L
    const val LIFECYCLE_TIMEOUT_MS = 30_000L

    /** How long the app must stay quiet for `AwaitIdle` when the client sends no `stable_for_ms`. */
    const val IDLE_STABLE_MS = 200L

    /** How long clients wait for a device another session holds. */
    const val ACQUIRE_TIMEOUT_MS = 300_000L

    val message: io.github.noamcohen48.tap.api.v1.Defaults =
        io.github.noamcohen48.tap.api.v1.Defaults
            .newBuilder()
            .setActionTimeoutMs(ACTION_TIMEOUT_MS)
            .setWaitTimeoutMs(WAIT_TIMEOUT_MS)
            .setLifecycleTimeoutMs(LIFECYCLE_TIMEOUT_MS)
            .setIdleStableMs(IDLE_STABLE_MS)
            .setAcquireTimeoutMs(ACQUIRE_TIMEOUT_MS)
            .build()
}

/** Caller input the daemon rejects before doing anything: INVALID_ARGUMENT. */
class InvalidArgumentException(
    message: String,
) : RuntimeException(message)

/** [require] for caller input: a failed check is [InvalidArgumentException], not a daemon bug. */
internal inline fun argument(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw InvalidArgumentException(message())
}

internal inline fun <T : Any> argumentNotNull(
    value: T?,
    message: () -> String,
): T = value ?: throw InvalidArgumentException(message())

/** A client-given duration that must be > 0 (absent fields take the default instead). */
internal fun positive(
    value: Long,
    field: String,
): Long {
    argument(value > 0) { "$field must be > 0 when set, got $value" }
    return value
}

internal fun nonNegative(
    value: Long,
    field: String,
): Long {
    argument(value >= 0) { "$field must be >= 0 when set, got $value" }
    return value
}

/** The binary trailer carrying a serialized [Failure] on every non-OK status (`failure.proto`). */
val FAILURE_TRAILER: Metadata.Key<Failure> =
    Metadata.Key.of("tap-failure-bin", ProtoLiteUtils.metadataMarshaller(Failure.getDefaultInstance()))

/** [status] with [failure] attached as the `tap-failure-bin` trailer. */
fun failureStatus(
    status: Status,
    failure: Failure,
): StatusRuntimeException = status.asRuntimeException(Metadata().apply { put(FAILURE_TRAILER, failure) })

/**
 * Maps known daemon and host-core exceptions to a gRPC status plus a [Failure] trailer whose
 * `reason` clients switch on. Anything else — a bare [IllegalArgumentException] or
 * [IllegalStateException] from a `require`/`check` included — is a bug: INTERNAL, logged with
 * its stack. Caller input is rejected with [InvalidArgumentException] or [InvalidCommandException].
 */
internal fun Throwable.toStatus(): StatusRuntimeException {
    if (this is StatusRuntimeException) return this
    val failure = Failure.newBuilder()
    val status: Status =
        when (this) {
            is UnknownClientConnectionException -> Status.NOT_FOUND.also { failure.reason = FailureReason.FAILURE_REASON_UNKNOWN_CLIENT_CONNECTION }
            is UnknownAttachedDeviceException -> Status.NOT_FOUND.also { failure.reason = FailureReason.FAILURE_REASON_UNKNOWN_ATTACHED_DEVICE }
            is NotOwnerException -> Status.PERMISSION_DENIED.also { failure.reason = FailureReason.FAILURE_REASON_NOT_OWNER }
            is InvalidArgumentException, is InvalidCommandException ->
                Status.INVALID_ARGUMENT.also { failure.reason = FailureReason.FAILURE_REASON_INVALID_ARGUMENT }
            // A busy device is a precondition failure when no wait was asked for, a timeout when it was.
            is DeviceBusyException -> {
                failure.setReason(FailureReason.FAILURE_REASON_DEVICE_BUSY).setSerial(serial).setWaitedMs(waitedMs)
                if (waitedMs > 0) Status.DEADLINE_EXCEEDED else Status.FAILED_PRECONDITION
            }
            is HostWaitTimeoutException -> {
                failure.setReason(FailureReason.FAILURE_REASON_HOST_WAIT_TIMEOUT).setSerial(serial).setWaitedMs(elapsedMs)
                Status.DEADLINE_EXCEEDED
            }
            is AdbTimeoutException -> {
                failure.setReason(FailureReason.FAILURE_REASON_ADB_TIMEOUT).setSerial(serial.orEmpty())
                Status.DEADLINE_EXCEEDED
            }
            is DeviceQuarantinedException -> {
                failure.setReason(FailureReason.FAILURE_REASON_DEVICE_QUARANTINED).setSerial(serial)
                Status.FAILED_PRECONDITION
            }
            is AdbReapUncertainException -> {
                failure.setReason(FailureReason.FAILURE_REASON_ADB_REAP_UNCERTAIN).setSerial(serial.orEmpty())
                Status.FAILED_PRECONDITION
            }
            is AppLifecycleException -> Status.FAILED_PRECONDITION.also { failure.reason = FailureReason.FAILURE_REASON_APP_LIFECYCLE }
            is DriverBuildMismatchException ->
                Status.FAILED_PRECONDITION.also { failure.reason = FailureReason.FAILURE_REASON_DRIVER_BUILD_MISMATCH }
            is RemoteCommandException -> {
                failure
                    .setReason(FailureReason.FAILURE_REASON_DRIVER_COMMAND)
                    .setSerial(serial.orEmpty())
                    .setErrorCode(code)
                    .setDetail(detail.orEmpty())
                Status.FAILED_PRECONDITION
            }
            is CommandTransportException -> {
                failure.setReason(FailureReason.FAILURE_REASON_DRIVER_TRANSPORT).setSerial(serial.orEmpty()).setErrorCode(code)
                Status.UNAVAILABLE
            }
            is DaemonPreconditionException ->
                Status.FAILED_PRECONDITION.also { failure.reason = FailureReason.FAILURE_REASON_DAEMON_PRECONDITION }
            is UnknownRefException -> Status.NOT_FOUND.also { failure.reason = FailureReason.FAILURE_REASON_UNKNOWN_REF }
            is RefNotAddressableException ->
                Status.FAILED_PRECONDITION.also { failure.reason = FailureReason.FAILURE_REASON_REF_NOT_ADDRESSABLE }
            // The session is unusable: detach and attach again.
            is SessionUnusableException -> {
                failure.setReason(FailureReason.FAILURE_REASON_SESSION_UNUSABLE).setSerial(serial)
                Status.ABORTED
            }
            is DriverStartException -> Status.UNAVAILABLE.also { failure.reason = FailureReason.FAILURE_REASON_DRIVER_START_FAILED }
            is AdbCommandException -> {
                failure.setReason(FailureReason.FAILURE_REASON_ADB_FAILED).setSerial(serial.orEmpty())
                Status.UNAVAILABLE
            }
            is AdbRunnerGatedException -> {
                failure.setReason(FailureReason.FAILURE_REASON_ADB_FAILED).setSerial(attemptSerial.orEmpty())
                Status.UNAVAILABLE
            }
            else -> {
                System.err.println("[tap] INTERNAL: unexpected ${this::class.qualifiedName}")
                printStackTrace()
                return failureStatus(
                    Status.INTERNAL.withDescription("${this::class.simpleName}: $message").withCause(this),
                    Failure.newBuilder().setReason(FailureReason.FAILURE_REASON_INTERNAL).build(),
                )
            }
        }
    return failureStatus(status.withDescription(message).withCause(this), failure.build())
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
