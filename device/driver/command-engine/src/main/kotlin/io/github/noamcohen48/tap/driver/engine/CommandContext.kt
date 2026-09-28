package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.api.v1.ErrorCode

/**
 * Per-command view of the pipeline handed to command code running on the executor.
 *
 * Deadlines are absolute and measured from acceptance, so queue residence consumes the same
 * budget as execution. Cancellation is cooperative: command code calls [checkpoint] between
 * polls and [markMutationStarted] immediately before the first irreversible platform call.
 * After a mutation has started, cancellation and the pipeline deadline no longer interrupt the
 * command; it must produce its own definitive result.
 */
class CommandContext internal constructor(
    private val command: PendingCommand,
    private val clock: Clock,
    private val mutationGate: (PendingCommand) -> Unit,
    private val transfer: (BlobTransfer) -> Unit,
) {
    val requestId: Long get() = command.requestId
    val acceptedAtMs: Long get() = command.acceptedAtMs
    val timeoutMs: Long get() = command.timeoutMs
    val deadlineMs: Long get() = command.deadlineMs
    val isCancelRequested: Boolean get() = command.cancelRequested
    val mutationStarted: Boolean get() = command.mutationStarted

    fun nowMs(): Long = clock.nowMs()

    fun remainingMs(): Long = deadlineMs - clock.nowMs()

    fun isExpired(): Boolean = clock.nowMs() >= deadlineMs

    /**
     * Stops the command with `CANCELLED` or `DEADLINE_EXCEEDED` if either applies and no
     * mutation has started yet. Safe to call from any point that has not yet mutated state.
     */
    fun checkpoint() {
        if (command.mutationStarted) return
        if (command.cancelRequested) throw CommandInterrupted(ErrorCode.ERR_CANCELLED)
        if (isExpired()) throw CommandInterrupted(ErrorCode.ERR_DEADLINE_EXCEEDED)
    }

    /**
     * Like [checkpoint] but only honors cancellation. Waits use this so an expired wait still
     * reports `WAIT_TIMEOUT` rather than `DEADLINE_EXCEEDED`.
     */
    fun checkCancelled() {
        if (command.mutationStarted) return
        if (command.cancelRequested) throw CommandInterrupted(ErrorCode.ERR_CANCELLED)
    }

    /**
     * Final gate before injecting input or otherwise mutating the device. Atomically refuses if
     * the pipeline is poisoned, the command was cancelled, or the deadline passed; otherwise the
     * command is marked as mutating and can no longer be cancelled.
     */
    fun markMutationStarted() {
        mutationGate(command)
    }

    /**
     * Streams [bytes] to the host as blob frames ahead of this command's terminal response and
     * blocks until the writer is done. Must be called before [markMutationStarted] for pure
     * artifact commands so an aborted transfer can still report `CANCELLED`.
     */
    fun transferBlob(mediaType: String, bytes: ByteArray): BlobResult {
        val blob = BlobTransfer(command, mediaType, bytes)
        transfer(blob)
        return BlobResult(blob, blob.await())
    }

    /** What [transferBlob] sent ([blob], for the response metadata) and how it ended. */
    data class BlobResult(val blob: BlobTransfer, val outcome: BlobTransfer.Outcome)

    /**
     * Bounded sleep on the injected [Clock] that returns as soon as the command is cancelled
     * (signalled, not polled). Returns normally on both timeout and cancellation; the caller
     * decides via [checkpoint]. It re-reads the clock at least every [SLEEP_SLICE_MS], so a
     * manual test clock advanced from another thread ends it too.
     */
    fun sleep(maxMs: Long) {
        val until = clock.nowMs() + minOf(maxMs, remainingMs()).coerceAtLeast(0)
        while (!command.cancelRequested) {
            val remaining = until - clock.nowMs()
            if (remaining <= 0) return
            command.awaitCancel(minOf(SLEEP_SLICE_MS, remaining))
        }
    }

    private companion object {
        const val SLEEP_SLICE_MS = 50L
    }
}
