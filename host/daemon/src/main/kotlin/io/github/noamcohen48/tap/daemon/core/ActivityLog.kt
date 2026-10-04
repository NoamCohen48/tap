package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.Activity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A watcher fell [ACTIVITY_READER_CAPACITY] batches behind; it reconnects after the last seq it got. */
class ActivityReaderOverflowException : RuntimeException("activity reader fell behind; reconnect after the last received seq")

/**
 * The daemon-wide activity log (`WatchService.Watch`): connections opening and closing, devices
 * attaching and detaching, and every call any connection logs, numbered by [Activity.getSeq]
 * from 1. It keeps the last [capacity]; [dropped] counts the rest. Appending never blocks on a
 * reader: a reader whose bounded queue is full is closed with [ActivityReaderOverflowException].
 */
class ActivityLog(
    private val capacity: Int = ACTIVITY_LOG_CAPACITY,
    private val readerCapacity: Int = ACTIVITY_READER_CAPACITY,
) {
    private val activities = ArrayDeque<Activity>()
    private val readers = HashSet<Channel<Activity>>()
    private var lastSeq = 0L
    private var dropped = 0L
    private var closed = false

    /** The backlog a new reader starts from, and the queue its live activity arrives on. */
    class Subscription internal constructor(
        val backlog: List<Activity>,
        val dropped: Long,
        internal val live: Channel<Activity>,
    )

    /** Appends [activity] with the next sequence number and the current time. */
    @Synchronized
    fun append(activity: Activity.Builder) {
        if (closed) return
        if (activities.size == capacity) {
            activities.removeFirst()
            dropped++
        }
        val recorded = activity.setSeq(++lastSeq).setAtEpochMs(System.currentTimeMillis()).build()
        activities.addLast(recorded)
        val iterator = readers.iterator()
        while (iterator.hasNext()) {
            val reader = iterator.next()
            if (reader.trySend(recorded).isFailure) {
                iterator.remove()
                reader.close(ActivityReaderOverflowException())
            }
        }
    }

    /**
     * Registers a reader and takes its backlog in one step under the log's lock, which also
     * orders appends: backlog and live activity neither overlap nor leave a gap.
     */
    @Synchronized
    fun subscribe(afterSeq: Long): Subscription {
        val live = Channel<Activity>(readerCapacity)
        if (closed) live.close() else readers.add(live)
        return Subscription(activities.filter { it.seq > afterSeq }, dropped, live)
    }

    /** The subscription's live activity, batched by what is already queued; ends the reader on exit. */
    fun live(subscription: Subscription): Flow<List<Activity>> =
        flow {
            val channel = subscription.live
            try {
                for (first in channel) {
                    val batch = mutableListOf(first)
                    while (true) batch += channel.tryReceive().getOrNull() ?: break
                    emit(batch)
                }
            } finally {
                synchronized(this@ActivityLog) { readers.remove(channel) }
                channel.cancel()
            }
        }

    /** The daemon is stopping: live readers finish what is queued, then complete. */
    @Synchronized
    fun close() {
        closed = true
        readers.forEach { it.close() }
        readers.clear()
    }
}

/** How many activities the daemon keeps for a watcher that (re)connects. */
const val ACTIVITY_LOG_CAPACITY = 10_000

/** Queued activities per reader before it is dropped as too slow. */
const val ACTIVITY_READER_CAPACITY = 1_024
