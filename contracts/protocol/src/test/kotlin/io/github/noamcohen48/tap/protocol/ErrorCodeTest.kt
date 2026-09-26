package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorCodeTest {
    private val sendable = ErrorCode.entries - setOf(ErrorCode.UNRECOGNIZED, ErrorCode.ERR_UNSPECIFIED, ErrorCode.ERR_UNKNOWN)

    @Test
    fun mutationRiskIsExplicitForEveryCode() {
        val safe =
            setOf(
                ErrorCode.ERR_INVALID_REQUEST, ErrorCode.ERR_INVALID_SELECTOR, ErrorCode.ERR_UNSUPPORTED,
                ErrorCode.ERR_UNAUTHENTICATED, ErrorCode.ERR_SESSION_MISMATCH, ErrorCode.ERR_DUPLICATE_OR_STALE,
                ErrorCode.ERR_OVERLOADED, ErrorCode.ERR_AUT_MISMATCH, ErrorCode.ERR_NOT_FOUND, ErrorCode.ERR_AMBIGUOUS,
                ErrorCode.ERR_NOT_INTERACTABLE, ErrorCode.ERR_WAIT_TIMEOUT, ErrorCode.ERR_CANCELLED,
                ErrorCode.ERR_DEADLINE_EXCEEDED, ErrorCode.ERR_AUT_NOT_INSTALLED, ErrorCode.ERR_SYNC_PROVIDER_UNAVAILABLE,
                ErrorCode.ERR_DRIVER_UNHEALTHY, ErrorCode.ERR_TRANSPORT_LOST,
            )
        ErrorCode.entries.forEach { code ->
            assertEquals(code !in safe, code.mayHaveMutated, "$code mutation risk")
        }
    }

    @Test
    fun unknownCodesCountAsPossiblyMutatedAndNotRetryable() {
        listOf(ErrorCode.UNRECOGNIZED, ErrorCode.ERR_UNSPECIFIED, ErrorCode.ERR_UNKNOWN).forEach { code ->
            assertTrue(code.mayHaveMutated, "$code")
            assertFalse(code.retryable, "$code")
        }
    }

    @Test
    fun onlyNonMutatingCodesAreRetryable() {
        val retryable =
            setOf(
                ErrorCode.ERR_OVERLOADED, ErrorCode.ERR_NOT_FOUND, ErrorCode.ERR_NOT_INTERACTABLE, ErrorCode.ERR_WAIT_TIMEOUT,
                ErrorCode.ERR_CANCELLED, ErrorCode.ERR_DEADLINE_EXCEEDED, ErrorCode.ERR_SYNC_PROVIDER_UNAVAILABLE,
            )
        ErrorCode.entries.forEach { code ->
            assertEquals(code in retryable, code.retryable, "$code retryable")
            if (code.retryable) assertFalse(code.mayHaveMutated, "retryable $code must not mutate")
        }
    }

    @Test
    fun normalizationMapsDecodeFallbacksToUnknown() {
        assertEquals(ErrorCode.ERR_UNKNOWN, ErrorCode.UNRECOGNIZED.normalized())
        assertEquals(ErrorCode.ERR_UNKNOWN, ErrorCode.ERR_UNSPECIFIED.normalized())
        (sendable + ErrorCode.ERR_UNKNOWN).forEach { assertEquals(it, it.normalized()) }
    }

    @Test
    fun labelsAreTheProtocolNames() {
        assertEquals("NOT_FOUND", ErrorCode.ERR_NOT_FOUND.label)
        assertEquals("UNKNOWN", ErrorCode.UNRECOGNIZED.label)
        sendable.forEach { code ->
            assertEquals(code.name, "ERR_" + code.label)
            assertTrue(code.label.matches(Regex("[A-Z_]+")), code.label)
        }
    }

    @Test
    fun commandFailureDefaultsItsMessageToTheLabel() {
        val failure = CommandFailure(ErrorCode.ERR_AMBIGUOUS, ErrorDetail.TARGET_AMBIGUOUS)
        assertEquals("AMBIGUOUS", failure.message)
        assertEquals(null, failure.remoteMessage)
        assertEquals("why", CommandFailure(ErrorCode.ERR_INTERNAL, message = "why").remoteMessage)
    }
}
