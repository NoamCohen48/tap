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
    private val command: Command,
    private val clock: Clock,
    private val mutationGate: (Command) -> Unit,
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
    fun transferBlob(mediaType: String, bytes: ByteArray): Pair<BlobTransfer, BlobTransfer.Outcome> {
        val blob = BlobTransfer(command, mediaType, bytes)
        transfer(blob)
        return blob to blob.await()
    }

    /**
     * Bounded sleep that returns early when the command is cancelled. Returns normally on both
     * timeout and cancellation; the caller decides via [checkpoint].
     */
    fun sleep(maxMs: Long) {
        val budget = minOf(maxMs, remainingMs()).coerceAtLeast(0)
        val untilNanos = System.nanoTime() + budget * 1_000_000L
        while (!command.cancelRequested) {
            val remaining = (untilNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return
            Thread.sleep(minOf(10L, remaining))
        }
    }
}
