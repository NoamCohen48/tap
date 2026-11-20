package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.DeviceEvent
import io.github.noamcohen48.tap.api.v1.DeviceEventBatch
import io.github.noamcohen48.tap.api.v1.DeviceEventType
import io.github.noamcohen48.tap.protocol.eventType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Accessibility events were lost (the driver's buffer or a subscriber's queue overflowed). */
class DeviceEventOverflowException(
    message: String,
) : IllegalStateException(message)

/** The attachment already serves [MAX_DEVICE_EVENT_SUBSCRIPTIONS] subscriptions. */
class DeviceEventSubscriptionLimitException :
    IllegalStateException("At most $MAX_DEVICE_EVENT_SUBSCRIPTIONS device event subscriptions per attachment")

/** The driver connection did not negotiate accessibility events (protocol 5.3 and `events.accessibility.v1`). */
class DeviceEventsUnsupportedException(
    version: String,
) : IllegalStateException("The driver connection (protocol $version) did not negotiate device events")

/** Most subscriptions one driver connection serves at a time. */
const val MAX_DEVICE_EVENT_SUBSCRIPTIONS = 64

/** Events a subscriber may fall behind by before its subscription ends with an overflow. */
const val DEVICE_EVENT_SUBSCRIBER_QUEUE = 1_024

/**
 * One driver connection's live fanout of accessibility events: no history, no UI handles, never
 * blocking the frame reader. The driver forwards only the types some subscription wants:
 * [sendFilter] carries the union to it whenever that changes, and since the driver reads frames
 * in order, every command sent after [subscribe] returns has its events delivered.
 */
class DeviceEvents internal constructor(
    private val sendFilter: suspend (Set<DeviceEventType>) -> Unit,
) {
    private val lock = Any()
    private val filterMutex = Mutex()
    private val subscriptions = LinkedHashSet<Subscription>()
    private var forwarded: Set<DeviceEventType> = emptySet()
    private var ended = false
    private var endCause: Throwable? = null

    /** One subscriber's queue. [events] closes cleanly when the connection closes, with the cause otherwise. */
    class Subscription internal constructor(
        val types: Set<DeviceEventType>,
        val packageName: String?,
        private val owner: DeviceEvents,
    ) {
        internal val queue = Channel<DeviceEvent>(DEVICE_EVENT_SUBSCRIBER_QUEUE)
        val events: ReceiveChannel<DeviceEvent> get() = queue

        internal fun wants(event: DeviceEvent): Boolean =
            event.eventType in types && (packageName == null || packageName == event.packageName)

        /** Unsubscribes; idempotent and never throws, so it is safe in a `finally`. */
        suspend fun close() = owner.remove(this)
    }

    /** Installs a subscription and tells the driver to forward its types before returning. */
    suspend fun subscribe(
        types: Set<DeviceEventType>,
        packageName: String? = null,
    ): Subscription {
        require(types.isNotEmpty()) { "at least one event type is required" }
        require(types.none { it == DeviceEventType.UNRECOGNIZED || it == DeviceEventType.DEVICE_EVENT_TYPE_UNSPECIFIED }) {
            "event types must be known values"
        }
        require(packageName == null || packageName.isNotBlank()) { "package_name must not be blank" }
        val subscription = Subscription(types.toSet(), packageName, this)
        synchronized(lock) {
            if (ended) throw endCause ?: IllegalStateException("Driver connection closed")
            if (subscriptions.size >= MAX_DEVICE_EVENT_SUBSCRIPTIONS) throw DeviceEventSubscriptionLimitException()
            subscriptions.add(subscription)
        }
        try {
            updateFilter()
        } catch (error: Throwable) {
            remove(subscription)
            throw error
        }
        return subscription
    }

    /** Called on the frame reader: never suspends or blocks. */
    internal fun publish(batch: DeviceEventBatch) {
        synchronized(lock) {
            if (ended) return
            if (batch.dropped > 0) {
                // The driver's buffer overflowed: every subscriber may have missed events.
                val error = DeviceEventOverflowException("The driver dropped ${batch.dropped} accessibility events")
                subscriptions.forEach { it.queue.close(error) }
                subscriptions.clear()
            }
            val iterator = subscriptions.iterator()
            while (iterator.hasNext()) {
                val subscription = iterator.next()
                for (event in batch.eventsList) {
                    if (!subscription.wants(event)) continue
                    if (subscription.queue.trySend(event).isFailure) {
                        subscription.queue.close(DeviceEventOverflowException("The subscriber fell behind by $DEVICE_EVENT_SUBSCRIBER_QUEUE events"))
                        iterator.remove()
                        break
                    }
                }
            }
        }
    }

    /** Ends every subscription: cleanly when [cause] is null (a deliberate close), with [cause] otherwise. */
    internal fun end(cause: Throwable?) {
        synchronized(lock) {
            if (ended) return
            ended = true
            endCause = cause
            subscriptions.forEach { it.queue.close(cause) }
            subscriptions.clear()
        }
    }

    private suspend fun remove(subscription: Subscription) {
        val removed = synchronized(lock) { subscriptions.remove(subscription) }
        subscription.queue.cancel()
        if (removed) withContext(NonCancellable) { runCatching { updateFilter() } }
    }

    /** Sends the union of the wanted types when it changed; serialised so the last write wins. */
    private suspend fun updateFilter() =
        filterMutex.withLock {
            val wanted =
                synchronized(lock) {
                    if (ended) return@withLock
                    subscriptions.flatMapTo(HashSet()) { it.types }
                }
            if (wanted == forwarded) return@withLock
            sendFilter(wanted)
            forwarded = wanted
        }
}
