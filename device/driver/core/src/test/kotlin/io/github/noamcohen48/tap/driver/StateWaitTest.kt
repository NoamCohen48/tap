package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.StaleObjectException
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateWaitTest {
    /** A fake screen: each lookup takes the next answer; a node is its text. */
    private class Screen(vararg answers: () -> Pair<String?, ErrorCode?>) {
        private val queue = ArrayDeque(answers.toList())
        val recycled = mutableListOf<String>()
        var resolves = 0

        fun wait(
            holds: (String) -> Boolean = { it == "Pay" },
            parts: (String) -> List<Boolean> = { listOf(true, it == "Pay") },
        ) = StateWait(
            resolve = {
                resolves++
                queue.removeFirst()()
            },
            holds = holds,
            parts = parts,
            snapshot = { ElementSnapshot.newBuilder().setText(it).build() },
            recycle = { recycled += it },
        )
    }

    private fun node(text: String): () -> Pair<String?, ErrorCode?> = { text to null }

    private val missing: () -> Pair<String?, ErrorCode?> = { null to ErrorCode.ERR_NOT_FOUND }
    private val ambiguous: () -> Pair<String?, ErrorCode?> = { null to ErrorCode.ERR_AMBIGUOUS }
    private val stale: () -> Pair<String?, ErrorCode?> = { throw StaleObjectException() }

    private fun failure(block: () -> Unit): CommandFailure =
        try {
            block()
            throw AssertionError("expected a CommandFailure")
        } catch (failure: CommandFailure) {
            failure
        }

    @Test
    fun aStaleLookupOrReadKeepsPolling() {
        val screen = Screen(stale, node("Paying…"), missing, node("Pay"))
        val wait = screen.wait(holds = { if (it == "Paying…") throw StaleObjectException() else it == "Pay" })
        assertFalse(wait.poll()) // the lookup itself went stale
        assertFalse(wait.poll()) // the read went stale
        assertFalse(wait.poll()) // no node yet
        assertTrue(wait.poll())
        assertEquals(listOf("Paying…", "Pay"), screen.recycled)
    }

    @Test
    fun severalMatchesWithoutAPickFailAtOnce() {
        val wait = Screen(ambiguous).wait()
        assertEquals(ErrorCode.ERR_AMBIGUOUS, failure { wait.poll() }.code)
    }

    @Test
    fun theTimeoutReportsTheNodeAndEachPartFromOneRead() {
        val screen = Screen(node("Paying…"))
        val timeout = failure { screen.wait().timedOut() }
        assertEquals(ErrorCode.ERR_WAIT_TIMEOUT, timeout.code)
        assertEquals(ErrorDetail.STATE_NOT_REACHED, timeout.detail)
        assertEquals("Paying…", timeout.lastState?.text)
        assertEquals(listOf(true, false), timeout.conditionHolds)
        assertEquals(1, screen.resolves)
        assertEquals(listOf("Paying…"), screen.recycled)
    }

    @Test
    fun aStaleFinalReadIsTriedOnceMore() {
        val screen = Screen(stale, node("Paying…"))
        val timeout = failure { screen.wait().timedOut() }
        assertEquals(ErrorDetail.STATE_NOT_REACHED, timeout.detail)
        assertEquals(2, screen.resolves)

        val stillStale = failure { Screen(stale, stale).wait().timedOut() }
        assertEquals(ErrorDetail.NO_MATCH, stillStale.detail)
    }

    @Test
    fun aFinalReadWithNoNodeIsNoMatchAndWithSeveralIsAmbiguous() {
        val none = failure { Screen(missing).wait().timedOut() }
        assertEquals(ErrorCode.ERR_WAIT_TIMEOUT, none.code)
        assertEquals(ErrorDetail.NO_MATCH, none.detail)
        assertNull(none.lastState)

        assertEquals(ErrorCode.ERR_AMBIGUOUS, failure { Screen(ambiguous).wait().timedOut() }.code)
    }

    @Test
    fun aConditionThatCameTrueAfterTheLastPollIsStillATimeout() {
        val timeout = failure { Screen(node("Pay")).wait().timedOut() }
        assertEquals(ErrorDetail.STATE_NOT_REACHED, timeout.detail)
        assertEquals(listOf(true, true), timeout.conditionHolds)
    }
}
