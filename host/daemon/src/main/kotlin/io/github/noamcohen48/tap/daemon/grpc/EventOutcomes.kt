package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason

/** A typed transport failure is still not a terminal device result. Never infer success. */
internal fun Failure.isIndeterminateOutcome(): Boolean =
    errorCode == ErrorCode.ERR_INDETERMINATE ||
        reason == FailureReason.FAILURE_REASON_DRIVER_TRANSPORT ||
        reason == FailureReason.FAILURE_REASON_ADB_TIMEOUT ||
        reason == FailureReason.FAILURE_REASON_INTERNAL
