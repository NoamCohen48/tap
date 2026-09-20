package com.company.tap.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ErrorCodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun everySendableCodeRoundTripsByName() {
        ErrorCode.entries.filter { it != ErrorCode.UNKNOWN }.forEach { code ->
            val response = Response.failure(code, durationMs = 1, detail = "D", message = "m")
            val encoded = json.encodeToString(response)
            assertTrue("\"code\":\"${code.name}\"" in encoded, encoded)
            assertEquals(response, json.decodeFromString<Response>(encoded))
        }
    }

    @Test
    fun unknownCodeDecodesToUnknownInsteadOfFailing() {
        val decoded = json.decodeFromString<Response>(
            """{"type":"error","code":"FROM_THE_FUTURE","durationMs":3}""",
        )
        assertEquals(ErrorCode.UNKNOWN, decoded.errorCode)
        assertTrue(decoded.errorCode!!.mayHaveMutated)
        assertFalse(decoded.errorCode!!.retryable)
    }

    @Test
    fun unknownIsNeverEncoded() {
        assertFailsWith<IllegalArgumentException> {
            json.encodeToString(Response.failure(ErrorCode.UNKNOWN, durationMs = 0))
        }
    }

    @Test
    fun okAndErrorAreDistinctVariants() {
        val ok = Response.ok(Done, durationMs = 0)
        val error = Response.failure(ErrorCode.NOT_FOUND, durationMs = 0)
        assertTrue(ok.ok && ok.errorCode == null && ok.result == Done)
        assertFalse(error.ok)
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
        assertEquals(null, error.result)
    }

    @Test
    fun goldenErrorResponse() {
        val encoded = json.encodeToString(
            Response.failure(ErrorCode.NOT_FOUND, durationMs = 42, detail = ErrorDetail.END_REACHED),
        )
        assertEquals("""{"type":"error","code":"NOT_FOUND","detail":"END_REACHED","durationMs":42}""", encoded)
    }

    @Test
    fun retryableCodesNeverAdmitMutation() {
        ErrorCode.entries.filter { it.retryable }.forEach { code ->
            assertFalse(code.mayHaveMutated, "$code is retryable but may have mutated")
        }
    }

    @Test
    fun taxonomyMatchesTheDesignList() {
        val planned = """
            INVALID_REQUEST INVALID_SELECTOR UNSUPPORTED UNAUTHENTICATED
            SESSION_MISMATCH DUPLICATE_OR_STALE AUT_MISMATCH NOT_FOUND AMBIGUOUS NOT_INTERACTABLE
            ACTION_REJECTED WAIT_TIMEOUT CANCELLED DEADLINE_EXCEEDED
            STALE_DURING_COMMAND AUT_NOT_INSTALLED AUT_CRASHED AUT_ANR
            SYNC_PROVIDER_UNAVAILABLE DRIVER_UNHEALTHY TRANSPORT_LOST INDETERMINATE
            ARTIFACT_TRANSFER_FAILED INTERNAL
        """.trim().split(Regex("\\s+")).toSet()
        val additions = setOf("OVERLOADED", "PAYLOAD_TOO_LARGE", "UNKNOWN")
        assertEquals(planned + additions, ErrorCode.entries.map { it.name }.toSet())
    }
}
