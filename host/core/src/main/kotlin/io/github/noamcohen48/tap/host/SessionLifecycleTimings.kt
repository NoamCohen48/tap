package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException

/** Duration-only diagnostics: no commands, credentials, payloads or exception messages. */
internal class SessionLifecycleTimings(
    private val serial: String,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun <T> measure(
        stage: String,
        block: suspend () -> T,
    ): T {
        val started = nanoTime()
        var outcome = "failed"
        try {
            val result = block()
            outcome = "ok"
            return result
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            val elapsedMs = (nanoTime() - started).coerceAtLeast(0) / 1_000_000
            // Diagnostics must not turn successful acquisition into a leaked resource or
            // replace a primary failure/cancellation. This callback never suspends.
            runCatching { log("lifecycle serial=$serial stage=$stage outcome=$outcome elapsed_ms=$elapsedMs") }
        }
    }
}
