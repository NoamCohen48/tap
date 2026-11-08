package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.StaleObjectException
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * The polls of one `wait_state` over nodes of type [T] (`UiObject2` on a device), with the
 * lookup and the reads passed in, so the rules are tested without one.
 *
 * - [resolve] picks the node: the node, or no node with the error code (`NOT_FOUND`,
 *   `AMBIGUOUS`). It may throw `StaleObjectException` when the tree changes under the lookup.
 * - A missing node and a stale lookup or read keep the wait polling. `AMBIGUOUS` fails at once:
 *   there is no one node to wait for.
 * - Every resolved node is [recycle]d.
 */
internal class StateWait<T : Any>(
    private val resolve: () -> Pair<T?, ErrorCode?>,
    private val holds: (T) -> Boolean,
    private val parts: (T) -> List<Boolean>,
    private val snapshot: (T) -> ElementSnapshot,
    private val recycle: (T) -> Unit,
) {
    /** One poll: whether the picked node satisfies the condition now. */
    fun poll(): Boolean =
        try {
            withNode(onMissing = { false }) { holds(it) }
        } catch (_: StaleObjectException) {
            false
        }

    /**
     * After the deadline: one more lookup supplies the node's snapshot and each part's result,
     * so both describe the same node at the same moment. A stale lookup or read is tried once
     * more. Always throws: `STATE_NOT_REACHED` with them (the condition may have come true
     * since the last poll; that is still a timeout), `AMBIGUOUS` when the selector now matches
     * several nodes, or `NO_MATCH` when no node is found or the tree stays stale.
     */
    fun timedOut(): Nothing {
        repeat(FINAL_READ_ATTEMPTS) {
            try {
                val (state, results) =
                    withNode(onMissing = { throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, detail = ErrorDetail.NO_MATCH) }) {
                        snapshot(it) to parts(it)
                    }
                throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, detail = ErrorDetail.STATE_NOT_REACHED, lastState = state, conditionHolds = results)
            } catch (_: StaleObjectException) {
                // The tree changed under the read: once more.
            }
        }
        throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, detail = ErrorDetail.NO_MATCH)
    }

    private inline fun <R> withNode(
        onMissing: () -> R,
        read: (T) -> R,
    ): R {
        val (node, error) = resolve()
        if (node == null) {
            if (error == ErrorCode.ERR_AMBIGUOUS) throw CommandFailure(ErrorCode.ERR_AMBIGUOUS)
            return onMissing()
        }
        return try {
            read(node)
        } finally {
            recycle(node)
        }
    }

    private companion object {
        const val FINAL_READ_ATTEMPTS = 2
    }
}
