package io.github.noamcohen48.tap.junit5

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Coroutine coordination for simultaneous multi-device phases. All waiting is suspension
 * (`await`), never thread blocking, and cancellation is safe: a waiter cancelled before the
 * barrier trips leaves the barrier intact for the rest.
 *
 * Two semantics, chosen at construction and documented here because mixing them is the usual
 * bug:
 *
 * - **Reusable (default, `oneShot = false`):** every [parties] arrivals trip the barrier and
 *   immediately reset it for the next round. Use when several phases must each start together
 *   (for example "both devices ready, then both tap").
 * - **One-shot (`oneShot = true`):** the first [parties] arrivals trip once; every later
 *   [await] returns immediately. Use for a single rendezvous ("both sessions open before any
 *   device acts"). It never resets. After the release [waiting] reports zero: the trip
 *   clears the arrival count (the tripped gate is kept, so late arrivals still pass through).
 *
 * Backend propagation still uses the observing device's UI condition, not a barrier: use a
 * barrier only when the test genuinely needs simultaneity (both sides ready before either
 * proceeds). A barrier never carries data and never replaces `coroutineScope`/`async` for
 * fan-out; sibling failure still cancels the scope, including waiters parked in [await].
 */
class DeviceBarrier(
    val parties: Int,
    val oneShot: Boolean = false,
) {
    init {
        require(parties >= 2) { "DeviceBarrier needs at least 2 parties, got $parties" }
    }

    // A plain monitor, not a coroutine Mutex: no critical section suspends, and the withdrawal
    // on cancellation must not itself be cancellable (a cancelled coroutine cannot acquire a
    // contended Mutex, which would leave its arrival counted).
    private val lock = Any()
    private var arrived = 0
    private var tripped: CompletableGate = CompletableGate()

    private class CompletableGate {
        private val deferred = CompletableDeferred<Unit>()

        suspend fun await() = deferred.await()

        fun trip() {
            if (!deferred.isCompleted) deferred.complete(Unit)
        }

        val isTripped: Boolean get() = deferred.isCompleted
    }

    /**
     * Waits until [parties] callers have arrived. The last arrival trips the barrier and
     * returns without suspending; the rest suspend until then. Cancellation before the trip
     * removes the arrival so the barrier still needs a full [parties]; cancellation after the
     * trip is too late (the call already returned). Never blocks a thread.
     */
    suspend fun await() {
        val gate: CompletableGate
        val isLast: Boolean
        synchronized(lock) {
            // One-shot already tripped: late arrivals pass through.
            if (oneShot && tripped.isTripped) return
            arrived++
            gate = tripped
            isLast = arrived == parties
            if (isLast) {
                gate.trip()
                arrived = 0
                if (!oneShot) {
                    tripped = CompletableGate()
                }
            }
        }
        if (isLast) return
        try {
            gate.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                // Still the same untripped round: withdraw the arrival. A tripped or recycled
                // barrier is untouched — the cancellation came too late to matter.
                if (gate === tripped && !gate.isTripped) arrived--
            }
            throw cancelled
        }
    }

    /** Arrivals currently waiting in this round (diagnostics). */
    suspend fun waiting(): Int = synchronized(lock) { arrived }
}
