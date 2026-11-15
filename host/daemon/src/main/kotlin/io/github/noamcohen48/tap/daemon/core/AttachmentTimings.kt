package io.github.noamcohen48.tap.daemon.core

import kotlinx.coroutines.CancellationException

/** Duration-only diagnostics for the attachment work outside host-core open. */
internal class AttachmentTimings(
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
            runCatching { log("lifecycle serial=$serial stage=$stage outcome=$outcome elapsed_ms=$elapsedMs") }
        }
    }
}
