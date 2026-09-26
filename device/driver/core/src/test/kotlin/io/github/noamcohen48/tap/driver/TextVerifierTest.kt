package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextVerifierTest {
    @Test
    fun aShownHintIsNoText() {
        assertNull(TextVerifier.displayedText("Email", showingHintText = true))
        assertEquals("a@b.c", TextVerifier.displayedText("a@b.c", showingHintText = false))
        assertNull(TextVerifier.displayedText(null, showingHintText = false))
    }

    @Test
    fun typingAppendsToTheInitialText() {
        assertEquals("hello world", TextVerifier.expectedAfterTyping("hello ", "world"))
    }

    @Test
    fun awaitTextSucceedsOnceTheReadMatches() {
        val clock = FakeClock()
        val reads = ArrayDeque(listOf(null, "hel", "hello"))

        val matched = TextVerifier.awaitText("hello", 1_000, clock::now, clock::sleep) { reads.removeFirst() }

        assertTrue(matched)
        assertEquals(2 * TextVerifier.POLL_MS, clock.nowMs)
    }

    @Test
    fun awaitTextGivesUpAtTheDeadlineWithoutOversleeping() {
        val clock = FakeClock()

        val matched = TextVerifier.awaitText("hello", 60, clock::now, clock::sleep) { "hell" }

        assertFalse(matched)
        assertEquals(60, clock.nowMs)
    }

    @Test
    fun awaitTextReadsOnceEvenPastTheDeadline() {
        val clock = FakeClock().apply { nowMs = 500 }
        var reads = 0

        val matched = TextVerifier.awaitText("", 100, clock::now, clock::sleep) { reads++; "" }

        assertTrue(matched)
        assertEquals(1, reads)
    }

    private class FakeClock {
        var nowMs = 0L

        fun now(): Long = nowMs

        fun sleep(ms: Long) {
            nowMs += ms
        }
    }
}
