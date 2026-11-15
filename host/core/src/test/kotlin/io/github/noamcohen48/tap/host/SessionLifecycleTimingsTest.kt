package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionLifecycleTimingsTest {
    @Test
    fun `reports monotonic elapsed time without changing the result`() =
        runTest {
            val logs = mutableListOf<String>()
            var now = 7_000_000L
            val timings = SessionLifecycleTimings("phone", logs::add) { now }
            val value = Any()
            assertSame(
                value,
                timings.measure("attach_health") {
                    delay(1)
                    now += 23_500_000
                    value
                },
            )
            assertEquals(listOf("lifecycle serial=phone stage=attach_health outcome=ok elapsed_ms=23"), logs)
        }

    @Test
    fun `reports failure but never its possibly sensitive message`() =
        runTest {
            val logs = mutableListOf<String>()
            val timings = SessionLifecycleTimings("phone", logs::add) { 0 }
            val failure = IllegalStateException("secret request payload")
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    timings.measure("attach_authentication") { throw failure }
                },
            )
            assertEquals(listOf("lifecycle serial=phone stage=attach_authentication outcome=failed elapsed_ms=0"), logs)
        }

    @Test
    fun `cancellation stays cancellation even if the logger fails`() =
        runTest {
            val logs = mutableListOf<String>()
            val timings =
                SessionLifecycleTimings("phone", {
                    logs += it
                    error("broken log sink")
                }) { 0 }
            val cancelled = CancellationException("secret payload")
            assertSame(
                cancelled,
                assertFailsWith<CancellationException> {
                    timings.measure("attach_instrumentation") { throw cancelled }
                },
            )
            assertTrue(logs.single().contains("outcome=cancelled"))
        }

    @Test
    fun `a broken logger cannot lose a successfully acquired resource`() =
        runTest {
            val timings = SessionLifecycleTimings("phone", { error("broken log sink") }) { 0 }
            val resource = Any()
            assertSame(resource, timings.measure("attach_lock") { resource })
        }
}
