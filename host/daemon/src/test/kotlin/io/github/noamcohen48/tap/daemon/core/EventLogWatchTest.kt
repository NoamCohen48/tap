package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LoggedEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventLogWatchTest {
    @Test
    fun `append during backlog delivery is not lost or duplicated`(): Unit =
        runBlocking {
            val log = EventLog(capacity = 3)
            repeat(3) { log.append(LoggedEvent.newBuilder()) }
            val updates = mutableListOf<EventLog.Update>()
            withTimeout(2_000) {
                log.watch(1).collect { update ->
                    updates.add(update)
                    if (updates.size == 1) {
                        // Backlog has been snapshotted, but its emit has not finished.
                        repeat(3) { log.append(LoggedEvent.newBuilder()) }
                        log.close("done")
                    }
                }
            }
            assertEquals(listOf(2L, 3L, 4L, 5L, 6L), updates.flatMap { it.events }.map { it.seq })
            assertEquals(listOf(0L, 1L, 2L, 3L, 3L), updates.map { it.dropped })
            assertEquals("done", updates.last().closingReason)
        }

    @Test
    fun `cancelling a reader neither closes the log nor consumes another readers events`(): Unit =
        runBlocking {
            val log = EventLog()
            log.watch(0).take(1).toList()
            val remaining = async(start = CoroutineStart.UNDISPATCHED) { log.watch(0).toList() }
            log.append(LoggedEvent.newBuilder())
            log.close("end")
            val updates = withTimeout(2_000) { remaining.await() }
            assertEquals(listOf(1L), updates.flatMap { it.events }.map { it.seq })
            assertEquals("end", updates.last().closingReason)
        }

    @Test
    fun `a slow reader fails while appends and healthy readers continue`(): Unit =
        runBlocking {
            val log = EventLog(readerCapacity = 1)
            val blocked = CompletableDeferred<Unit>()
            val slow =
                async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<EventReaderOverflowException> { log.watch(0).collect { blocked.await() } }
                }
            log.append(LoggedEvent.newBuilder())
            log.append(LoggedEvent.newBuilder())
            blocked.complete(Unit)
            withTimeout(2_000) { slow.await() }
            val snapshot =
                log
                    .watch(0)
                    .take(1)
                    .toList()
                    .single()
            assertEquals(listOf(1L, 2L), snapshot.events.map { it.seq })
        }

    @Test
    fun `a closed log can replay its backlog and immediately report closing`(): Unit =
        runBlocking {
            val log = EventLog()
            log.append(LoggedEvent.newBuilder())
            log.close("disconnected")
            log.close("another reason")
            log.append(LoggedEvent.newBuilder())
            val updates = withTimeout(2_000) { log.watch(0).toList() }
            assertEquals(listOf(1L), updates.first().events.map { it.seq })
            assertEquals("disconnected", updates.last().closingReason)
        }

    @Test
    fun `a future cursor filters later appends until it is reached`(): Unit =
        runBlocking {
            val log = EventLog()
            val watcher = async(start = CoroutineStart.UNDISPATCHED) { log.watch(2).toList() }
            repeat(3) { log.append(LoggedEvent.newBuilder()) }
            log.close("end")
            assertEquals(listOf(3L), withTimeout(2_000) { watcher.await() }.flatMap { it.events }.map { it.seq })
        }
}
