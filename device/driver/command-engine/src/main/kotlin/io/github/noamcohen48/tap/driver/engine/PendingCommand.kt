package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.wire.v1.Response
import java.util.concurrent.atomic.AtomicReference

/** One accepted request inside the pipeline, from admission to its single terminal response. */
internal class PendingCommand(
    val requestId: Long,
    val timeoutMs: Long,
    val acceptedAtMs: Long,
    val work: (CommandContext) -> Response,
) {
    val deadlineMs: Long = acceptedAtMs + timeoutMs
    private val terminal = AtomicReference<Response?>(null)
    private val cancelSignal = Object()

    @Volatile var cancelRequested: Boolean = false
        private set

    @Volatile var mutationStarted: Boolean = false

    val isTerminal: Boolean get() = terminal.get() != null

    /** Records the single terminal response. Returns false if one was already recorded. */
    fun complete(response: Response): Boolean = terminal.compareAndSet(null, response)

    /** Requests cooperative cancellation and wakes a [awaitCancel] in progress. */
    fun requestCancel() {
        synchronized(cancelSignal) {
            cancelRequested = true
            cancelSignal.notifyAll()
        }
    }

    /** Waits up to [maxMs] real milliseconds or until [requestCancel]; returns early on either. */
    fun awaitCancel(maxMs: Long) {
        if (maxMs <= 0) return
        synchronized(cancelSignal) {
            if (!cancelRequested) cancelSignal.wait(maxMs)
        }
    }
}
