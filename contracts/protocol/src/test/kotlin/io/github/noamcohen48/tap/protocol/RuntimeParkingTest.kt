package io.github.noamcohen48.tap.protocol

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.wire.v1.ParkRuntime
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RuntimeParkingTest {
    private fun valid() =
        ParkRuntime
            .newBuilder()
            .setNextSessionId("next")
            .setNextGeneration(8)
            .setNextSecret(ByteString.copyFrom(ByteArray(32) { 7 }))
            .setIdleTimeoutMs(60_000)
            .build()

    @Test
    fun `handoff requires a new identity exactly one generation and a bounded fresh secret`() {
        validateRuntimePark(valid(), "old", 7, ByteArray(32) { 9 })
        assertFailsWith<IllegalArgumentException> { validateRuntimePark(valid(), "old", 7, ByteArray(32) { 7 }) }
        for (bad in listOf(
            valid().toBuilder().setNextSessionId("").build(),
            valid().toBuilder().setNextSessionId("old").build(),
            valid().toBuilder().setNextSessionId("x".repeat(129)).build(),
            valid().toBuilder().setNextGeneration(7).build(),
            valid().toBuilder().setNextGeneration(9).build(),
            valid().toBuilder().setNextSecret(ByteString.EMPTY).build(),
            valid().toBuilder().setNextSecret(ByteString.copyFrom(ByteArray(31))).build(),
            valid().toBuilder().setIdleTimeoutMs(0).build(),
            valid().toBuilder().setIdleTimeoutMs(MAX_RUNTIME_IDLE_MS + 1).build(),
        )) {
            assertFailsWith<IllegalArgumentException> { validateRuntimePark(bad, "old", 7) }
        }
        assertFailsWith<IllegalArgumentException> { validateRuntimePark(valid(), "old", Long.MAX_VALUE) }
    }
}
