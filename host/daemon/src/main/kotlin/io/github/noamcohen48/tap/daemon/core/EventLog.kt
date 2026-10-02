package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LoggedEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A bounded reader fell behind; reconnect using the last received sequence number. */
class EventReaderOverflowException : RuntimeException("event reader fell behind; reconnect after the last received seq")

/**
 * A connection's event log (`event_log.proto`): the last [capacity] device calls it made, each
 * numbered by [LoggedEvent.getSeq] from 1. Readers never block appends or own the connection.
 */
class EventLog(
    private val capacity: Int = EVENT_LOG_CAPACITY,
    private val readerCapacity: Int = EVENT_READER_CAPACITY,
) {
    init {
        require(capacity > 0)
        require(readerCapacity > 0)
    }

    private val events = ArrayDeque<LoggedEvent>()
    private val readers = HashMap<Channel<Update>, Long>()
    private var lastSeq = 0L
    private var dropped = 0L
    private var closingReason: String? = null

    /** A backlog/live batch, or a terminal connection-closing notification. */
    data class Update(
        val events: List<LoggedEvent> = emptyList(),
        val dropped: Long = 0,
        val closingReason: String? = null,
    )

    /** Appends [event] with the next sequence number. Late completions after close are ignored. */
    @Synchronized
    fun append(event: LoggedEvent.Builder) {
        if (closingReason != null) return
        if (events.size == capacity) {
            events.removeFirst()
            dropped++
        }
        val recorded = event.setSeq(++lastSeq).build()
        events.addLast(recorded)
        if (readers.isEmpty()) return
        val update = Update(listOf(recorded), dropped)
        val iterator = readers.entries.iterator()
        while (iterator.hasNext()) {
            val (reader, afterSeq) = iterator.next()
            if (recorded.seq <= afterSeq) continue
            if (reader.trySend(update).isFailure) {
                iterator.remove()
                reader.close(EventReaderOverflowException())
            }
        }
    }

    /** The kept events with `seq > afterSeq`, oldest first, and the evicted count. */
    @Synchronized
    fun after(afterSeq: Long): Pair<List<LoggedEvent>, Long> = events.filter { it.seq > afterSeq } to dropped

    /**
     * Atomically registers a reader and takes its backlog. The same lock orders appends and
     * registration, so backlog and live events neither overlap nor leave a gap. Cancellation
     * only removes this reader; it never closes the connection. Reader queues are bounded.
     */
    fun watch(afterSeq: Long): Flow<Update> =
        flow {
            val reader = Channel<Update>(readerCapacity)
            val initial =
                synchronized(this@EventLog) {
                    val snapshot = Update(events.filter { it.seq > afterSeq }, dropped)
                    if (closingReason == null) readers[reader] = afterSeq else reader.close()
                    snapshot
                }
            try {
                emit(initial)
                for (update in reader) emit(update)
                val closed = synchronized(this@EventLog) { Update(dropped = dropped, closingReason = closingReason) }
                emit(closed)
            } finally {
                synchronized(this@EventLog) { readers.remove(reader) }
                reader.cancel()
            }
        }

    /** Completes all readers after their queued updates, with the connection's close reason. */
    @Synchronized
    fun close(reason: String) {
        if (closingReason != null) return
        closingReason = reason
        readers.keys.forEach { it.close() }
        readers.clear()
    }
}

/** How many events a connection's [EventLog] keeps. */
const val EVENT_LOG_CAPACITY = 2_000

/** Maximum queued live updates per reader; replay is bounded separately by the log capacity. */
const val EVENT_READER_CAPACITY = 128
