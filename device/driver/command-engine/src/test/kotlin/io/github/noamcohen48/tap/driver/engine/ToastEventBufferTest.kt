package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.api.v1.ReadToastHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastEventBufferTest {
    private fun query(
        after: Long = 0,
        stream: String = "",
    ) = ReadToastHistory
        .newBuilder()
        .setAfterSequence(after)
        .setStreamId(stream)
        .build()

    @Test
    fun historyOutlivesTheToastAndReadsDoNotConsume() {
        val buffer = ToastEventBuffer("one")
        assertEquals(1, buffer.read(query()).oldestSequence)
        buffer.record("Saved", "com.example", 1)
        buffer.record("Done", "com.example", 100_000)
        val page = buffer.read(query())
        assertEquals(listOf("Saved", "Done"), page.eventsList.map { it.toast.text })
        assertEquals(listOf(1L, 2L), page.eventsList.map { it.sequence })
        assertEquals(listOf(1L, 100_000L), page.eventsList.map { it.atEpochMs })
        assertEquals(page, buffer.read(query()))
        assertFalse(page.gap)
        assertEquals(listOf(2L), buffer.read(query(1, "one")).eventsList.map { it.sequence })
        val empty = buffer.read(query(2, "one"))
        assertTrue(empty.eventsList.isEmpty())
        assertEquals(2, empty.nextSequence)
        assertFalse(empty.gap)
    }

    @Test
    fun evictionAndTruncationAreExplicitAndBounded() {
        val buffer = ToastEventBuffer("one")
        repeat(140) { buffer.record("x".repeat(2000), "com.example", it.toLong()) }
        val page = buffer.read(query(1, "one"))
        assertEquals(128, page.eventsCount)
        assertEquals(13, page.oldestSequence)
        assertEquals(140, page.nextSequence)
        assertTrue(page.gap)
        assertFalse(page.reset)
        assertTrue(page.eventsList.all { it.textTruncated && it.toast.text.length == 1024 })
        assertFalse(buffer.read(query(12, "one")).gap)
    }

    @Test
    fun resetAndAheadCursorsCannotHideNewEvents() {
        val buffer = ToastEventBuffer("one")
        buffer.record("Old", "com.example", 1)
        buffer.reset()
        val empty = buffer.read(query(1, "one"))
        assertTrue(empty.reset && empty.gap)
        assertTrue(empty.eventsList.isEmpty())
        buffer.record("New", "com.example", 2)
        val page = buffer.read(query(1, "one"))
        assertTrue(page.reset && page.gap)
        assertEquals(
            "New",
            page.eventsList
                .single()
                .toast.text,
        )
        assertTrue(buffer.read(query(99, page.streamId)).reset)
    }

    @Test
    fun concurrentWritersHaveOneStrictOrder() {
        val buffer = ToastEventBuffer()
        val writers = List(4) { Thread { repeat(25) { buffer.record("toast", "com.example", 1) } } }
        writers.forEach { it.start() }
        writers.forEach { it.join() }
        assertEquals((1L..100L).toList(), buffer.read(query()).eventsList.map { it.sequence })
    }
}
