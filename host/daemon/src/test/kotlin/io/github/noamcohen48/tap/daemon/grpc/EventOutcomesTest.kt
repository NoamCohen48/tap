package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventOutcomesTest {
    @Test fun `typed transport loss and timeout remain unknown outcomes`() {
        for (reason in listOf(
            FailureReason.FAILURE_REASON_DRIVER_TRANSPORT,
            FailureReason.FAILURE_REASON_ADB_TIMEOUT,
            FailureReason.FAILURE_REASON_INTERNAL,
        )) {
            assertTrue(
                Failure
                    .newBuilder()
                    .setReason(reason)
                    .build()
                    .isIndeterminateOutcome(),
                reason.name,
            )
        }
        assertTrue(
            Failure
                .newBuilder()
                .setReason(FailureReason.FAILURE_REASON_DRIVER_COMMAND)
                .setErrorCode(ErrorCode.ERR_INDETERMINATE)
                .build()
                .isIndeterminateOutcome(),
        )
    }

    @Test fun `known validation and terminal driver errors are not transport uncertainty`() {
        assertFalse(
            Failure
                .newBuilder()
                .setReason(FailureReason.FAILURE_REASON_INVALID_ARGUMENT)
                .build()
                .isIndeterminateOutcome(),
        )
        assertFalse(
            Failure
                .newBuilder()
                .setReason(FailureReason.FAILURE_REASON_DRIVER_COMMAND)
                .setErrorCode(ErrorCode.ERR_NOT_FOUND)
                .build()
                .isIndeterminateOutcome(),
        )
    }
}
