package io.github.noamcohen48.tap.junit5

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Deterministic barrier behavior: release, reuse, one-shot and cancellation safety. */
class DeviceBarrierTest {
    @Test
    fun `two parties release each other`() =
        runBlocking {
            val barrier = DeviceBarrier(2)
            val secondArrived = CompletableDeferred<Unit>()
            val first =
                async {
                    barrier.await()
                }
            delay(50)
            assertTrue(first.isActive, "first waiter parks until the second arrives")
            val second =
                async {
                    secondArrived.complete(Unit)
                    barrier.await()
                }
            withTimeout(2_000) {
                first.await()
                second.await()
            }
        }

    @Test
    fun `reusable barrier trips every round`() =
        runBlocking {
            val barrier = DeviceBarrier(2)
            repeat(3) {
                val a = async { barrier.await() }
                val b = async { barrier.await() }
                withTimeout(2_000) {
                    a.await()
                    b.await()
                }
            }
        }

    @Test
    fun `one-shot barrier passes late arrivals through`() =
        runBlocking {
            val barrier = DeviceBarrier(2, oneShot = true)
            val a = async { barrier.await() }
            val b = async { barrier.await() }
            withTimeout(2_000) {
                a.await()
                b.await()
            }
            // Already tripped: returns immediately.
            withTimeout(2_000) { barrier.await() }
            withTimeout(2_000) { barrier.await() }
        }

    @Test
    fun `one-shot waiting resets after release`() =
        runBlocking {
            val barrier = DeviceBarrier(2, oneShot = true)
            val a = async { barrier.await() }
            val b = async { barrier.await() }
            withTimeout(2_000) {
                a.await()
                b.await()
            }
            // Released: nobody is waiting anymore, even though the gate stays tripped.
            assertEquals(0, barrier.waiting())
            // Late arrivals pass through without registering.
            withTimeout(2_000) { barrier.await() }
            withTimeout(2_000) { barrier.await() }
            assertEquals(0, barrier.waiting())
        }

    @Test
    fun `cancelled waiter leaves the barrier intact`() =
        runBlocking {
            val barrier = DeviceBarrier(2)
            val waiter =
                async {
                    barrier.await()
                }
            delay(50)
            assertEquals(1, barrier.waiting())
            waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled)
            assertEquals(0, barrier.waiting(), "cancelled arrival is withdrawn")
            // A full new pair still trips.
            val a = async { barrier.await() }
            val b = async { barrier.await() }
            withTimeout(2_000) {
                a.await()
                b.await()
            }
        }

    @Test
    fun `sibling failure cancels a parked waiter`() =
        runBlocking {
            val barrier = DeviceBarrier(2)
            val parkedCancelled = CompletableDeferred<Unit>()
            val failure =
                assertFailsWith<AssertionError> {
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            try {
                                barrier.await()
                            } catch (cancelled: CancellationException) {
                                parkedCancelled.complete(Unit)
                                throw cancelled
                            }
                        }
                        launch {
                            delay(50)
                            throw AssertionError("sibling boom")
                        }
                    }
                }
            assertEquals("sibling boom", failure.message)
            withTimeout(2_000) { parkedCancelled.await() }
        }

    @Test
    fun `three parties need all three`() =
        runBlocking {
            val barrier = DeviceBarrier(3)
            val a = async { barrier.await() }
            val b = async { barrier.await() }
            delay(50)
            assertTrue(a.isActive && b.isActive, "two of three still park")
            val c = async { barrier.await() }
            withTimeout(2_000) {
                a.await()
                b.await()
                c.await()
            }
        }
}
