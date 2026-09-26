package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.wire.v1.Response

/**
 * Marks [ValidationTransport], the fault-probing side door into a [DriverClient]. It bypasses
 * request-ID allocation and command validation on purpose, so only validation tooling
 * (`:host:validation`) and host/core's own tests opt in.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "ValidationTransport bypasses request-ID allocation and command validation; it is for validation probes only.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ValidationApi

/**
 * Sends what [DriverClient.submit] refuses to send, to probe how the driver fences it: explicit
 * (stale, duplicate, skipped) request IDs, foreign session identities, arbitrary payload bytes,
 * and a simulated transport failure. Obtained from [DriverClient.validationTransport]; requests
 * still pass the owning session's admission gate and share the client's ordered writer.
 */
@ValidationApi
interface ValidationTransport {
    /**
     * Sends `health` under [requestId] with the given envelope identity (null keeps the
     * client's own) and returns the driver's raw response. Transport loss throws its cause.
     */
    suspend fun executeHealth(
        requestId: Long,
        sessionId: String? = null,
        generation: Long? = null,
    ): Response

    /**
     * Sends [payload] verbatim as a `REQUEST` under [requestId] — malformed protobuf, an unset
     * body, invalid arguments — and returns the driver's raw response. The pending entry is
     * classified as a non-mutating `health`.
     */
    suspend fun executeRaw(
        requestId: Long,
        payload: ByteArray,
    ): Response

    /** Poisons the client as if its transport had failed; it must be healthy. */
    fun disconnect()
}
