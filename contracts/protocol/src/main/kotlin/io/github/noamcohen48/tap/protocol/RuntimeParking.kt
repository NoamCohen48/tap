package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.wire.v1.ParkRuntime

/** Validate before draining/resetting; connection authentication is still required separately. */
fun validateRuntimePark(
    park: ParkRuntime,
    sessionId: String,
    generation: Long,
    currentSecret: ByteArray? = null,
) {
    require(park.nextSessionId.isNotBlank() && park.nextSessionId != sessionId && park.nextSessionId.length <= 128) {
        "Invalid next session identity"
    }
    require(generation < Long.MAX_VALUE && park.nextGeneration == generation + 1) { "Next generation must advance exactly once" }
    require(park.nextSecret.size() == 32) { "Next session secret must be 32 bytes" }
    require(currentSecret == null || !java.security.MessageDigest.isEqual(currentSecret, park.nextSecret.toByteArray())) {
        "Next session secret must change"
    }
    require(park.idleTimeoutMs in MIN_RUNTIME_IDLE_MS..MAX_RUNTIME_IDLE_MS) { "Idle runtime timeout is out of range" }
}
