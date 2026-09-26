package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException

/** Trailers as the daemon sends them: a `tap-failure-bin` [Failure] with [reason]. */
internal fun failureTrailers(
    reason: FailureReason,
    serial: String = "",
): Metadata =
    Metadata().apply { put(FAILURE_TRAILER, Failure.newBuilder().setReason(reason).setSerial(serial).build()) }

/** A daemon-shaped failure: [status] with [description] and a [reason] trailer. */
internal fun daemonFailure(
    status: Status,
    reason: FailureReason,
    description: String,
    serial: String = "",
): StatusRuntimeException = status.withDescription(description).asRuntimeException(failureTrailers(reason, serial))
