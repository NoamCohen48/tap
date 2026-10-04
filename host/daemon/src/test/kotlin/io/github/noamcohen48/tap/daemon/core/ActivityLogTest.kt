package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.Activity
import io.github.noamcohen48.tap.api.v1.ConnectionClosed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActivityLogTest {
    private fun closed(reason: String) = Activity.newBuilder().setConnectionClosed(ConnectionClosed.newBuilder().setReason(reason))

    @Test
    fun `backlog and live activity neither overlap nor leave a gap`(): Unit =
        runBlocking {
            val log = ActivityLog(capacity = 3)
            repeat(4) { log.append(closed("r$it")) }
            val subscription = log.subscribe(afterSeq = 2)
            assertEquals(listOf(3L, 4L), subscription.backlog.map { it.seq })
            assertEquals(1, subscription.dropped)
            repeat(2) { log.append(closed("live$it")) }
            log.close()
            val live = withTimeout(2_000) { log.live(subscription).toList() }
            assertEquals(listOf(5L, 6L), live.flatten().map { it.seq })
            assertTrue(live.flatten().all { it.atEpochMs > 0 })
        }

    @Test
    fun `a slow reader fails while appends and other readers continue`(): Unit =
        runBlocking {
            val log = ActivityLog(readerCapacity = 2)
            val slowSubscription = log.subscribe(0)
            val healthy = log.subscribe(0)
            val blocked = CompletableDeferred<Unit>()
            val slow =
                async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<ActivityReaderOverflowException> { log.live(slowSubscription).collect { blocked.await() } }
                }
            val received = async(start = CoroutineStart.UNDISPATCHED) { log.live(healthy).toList() }
            log.append(closed("a"))
            yield() // the slow reader now holds "a"; the healthy one has emitted it
            repeat(3) { log.append(closed("b$it")) }
            blocked.complete(Unit)
            withTimeout(2_000) { slow.await() }
            log.close()
            assertEquals(listOf(1L, 2L, 3L, 4L), withTimeout(2_000) { received.await() }.flatten().map { it.seq })
        }

    @Test
    fun `a closed log replays its backlog, takes no more and ends live readers`(): Unit =
        runBlocking {
            val log = ActivityLog()
            log.append(closed("before"))
            log.close()
            log.append(closed("after"))
            val subscription = log.subscribe(0)
            assertEquals(listOf("before"), subscription.backlog.map { it.connectionClosed.reason })
            assertEquals(emptyList(), withTimeout(2_000) { log.live(subscription).toList() })
        }
}
