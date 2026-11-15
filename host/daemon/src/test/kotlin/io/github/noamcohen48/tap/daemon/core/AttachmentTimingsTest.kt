package io.github.noamcohen48.tap.daemon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AttachmentTimingsTest {
    private fun timings(log: (String) -> Unit): AttachmentTimings {
        val ticks = listOf(1_000_000L, 8_000_000L).iterator()
        return AttachmentTimings("phone", log) { ticks.next() }
    }

    @Test
    fun `success preserves result and logs only duration and outcome`() =
        runBlocking {
            val logs = mutableListOf<String>()
            val value = Any()
            assertSame(value, timings(logs::add).measure("daemon_attach_clock") { value })
            assertEquals(listOf("lifecycle serial=phone stage=daemon_attach_clock outcome=ok elapsed_ms=7"), logs)
        }

    @Test
    fun `failure preserves the exception without logging its message`() =
        runBlocking {
            val logs = mutableListOf<String>()
            val error = IllegalStateException("private failure text")
            assertSame(
                error,
                assertFailsWith<IllegalStateException> {
                    timings(logs::add).measure("daemon_attach_initial_info") { throw error }
                },
            )
            assertEquals(listOf("lifecycle serial=phone stage=daemon_attach_initial_info outcome=failed elapsed_ms=7"), logs)
        }

    @Test
    fun `cancellation remains cancellation`() =
        runBlocking {
            val logs = mutableListOf<String>()
            val error = CancellationException("private cancellation text")
            assertSame(
                error,
                assertFailsWith<CancellationException> {
                    timings(logs::add).measure("daemon_attach_total") { throw error }
                },
            )
            assertEquals(listOf("lifecycle serial=phone stage=daemon_attach_total outcome=cancelled elapsed_ms=7"), logs)
        }

    @Test
    fun `throwing log sink cannot change success or replace a failure`() =
        runBlocking {
            val value = Any()
            assertSame(value, timings { throw IllegalStateException("log failed") }.measure("daemon_attach_register") { value })
            val error = IllegalArgumentException("primary")
            assertSame(
                error,
                assertFailsWith<IllegalArgumentException> {
                    timings { throw IllegalStateException("log failed") }.measure("daemon_attach_register") { throw error }
                },
            )
        }
}
