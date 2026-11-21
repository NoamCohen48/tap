package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LoggedEvent

/**
 * A connection's event log (`event_log.proto`): the last [capacity] device calls it made, each
 * numbered by [LoggedEvent.getSeq] from 1. [dropped] counts the events evicted to stay within it.
 * [onAppend] sees each event once, in order, under the log's lock (the daemon's [ActivityLog]).
 */
class EventLog(
    private val capacity: Int = EVENT_LOG_CAPACITY,
    private val onAppend: (LoggedEvent) -> Unit = {},
    private val onPersist: (LoggedEvent, (LoggedEvent) -> LoggedEvent) -> Unit = { _, _ -> },
) {
    private val events = ArrayDeque<LoggedEvent>()
    private var lastSeq = 0L
    private var dropped = 0L

    /** Append a non-device event with built-in durable redaction. */
    fun append(event: LoggedEvent.Builder) = appendUsing(event) { durableEvent(it, LogRedactor()) }

    /** The admitting attachment's rules remain available even after detach or reattachment. */
    internal fun appendRedacted(
        event: LoggedEvent.Builder,
        redactor: LogRedactor,
    ) = appendUsing(event) { durableEvent(it, redactor) }

    @Synchronized
    private fun appendUsing(
        event: LoggedEvent.Builder,
        redact: (LoggedEvent) -> LoggedEvent,
    ) {
        if (events.size == capacity) {
            events.removeFirst()
            dropped++
        }
        val recorded = event.setSeq(++lastSeq).build()
        events.addLast(recorded)
        onAppend(recorded)
        onPersist(recorded, redact)
    }

    /** The kept events with `seq > afterSeq`, oldest first, and the evicted count. */
    @Synchronized
    fun after(afterSeq: Long): Pair<List<LoggedEvent>, Long> = events.filter { it.seq > afterSeq } to dropped
}

/** How many events a connection's [EventLog] keeps. */
const val EVENT_LOG_CAPACITY = 2_000
