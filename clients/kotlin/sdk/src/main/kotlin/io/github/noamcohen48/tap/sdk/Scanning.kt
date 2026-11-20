package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.CommandResult
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Observed motion, not acceptance of the input. */
enum class Movement { UNKNOWN, MOVED, UNCHANGED }

/** Directional edge evidence at that moment, not permanent dataset exhaustion. */
enum class ScrollEdge { UNKNOWN, REACHED, NOT_REACHED }

/** Missing evidence (including older drivers) stays unknown, never a detected end. */
data class ScrollResult(
    val movement: Movement = Movement.UNKNOWN,
    val edge: ScrollEdge = ScrollEdge.UNKNOWN,
)

internal fun CommandResult.scrollResult(): ScrollResult =
    ScrollResult(
        when (scrollObservation.movementValue) {
            1 -> Movement.MOVED
            2 -> Movement.UNCHANGED
            else -> Movement.UNKNOWN
        },
        when (scrollObservation.edgeValue) {
            1 -> ScrollEdge.REACHED
            2 -> ScrollEdge.NOT_REACHED
            else -> ScrollEdge.UNKNOWN
        },
    )

/** Why a bounded scan stopped. NO_PROGRESS does not establish an edge. */
enum class ScanStopReason { EDGE_DETECTED, SCROLL_LIMIT, ITEM_LIMIT, TIME_LIMIT, NO_PROGRESS }

/** First/latest values for a caller-defined identity and number of reads containing it. */
data class CollectedObservation<K : Any>(
    val key: K,
    val firstSnapshot: ElementSnapshot,
    val lastSnapshot: ElementSnapshot,
    val seenInReads: Int,
)

/**
 * Detached observations in first-seen order, not guaranteed dataset order or completeness.
 * [stopReason] is null only for failure evidence attached to an exception.
 */
data class ScanResult<K : Any>(
    val observations: List<CollectedObservation<K>>,
    val stopReason: ScanStopReason?,
    /** Scroll commands attempted, including failing/indeterminate attempts in partial results. */
    val scrolls: Int,
    /** Successful collection reads, excluding separate singular container checks. */
    val reads: Int,
    val elapsed: Duration,
    val lastScroll: ScrollResult?,
    val warnings: Set<String>,
) {
    /** Always NOT_GUARANTEED, including when an edge was detected. */
    val coverage: String get() = "NOT_GUARANTEED"
}

/** Partial observations attached as a suppressed exception; the original failure is rethrown. */
class ScanPartialException internal constructor(
    val partial: ScanResult<*>,
) : RuntimeException("Partial scan observations; original failure remains authoritative")

/** Partial scan evidence, without replacing the original command error type/code. */
val Throwable.scanPartial: ScanResult<*>?
    get() = suppressed.filterIsInstance<ScanPartialException>().lastOrNull()?.partial

internal suspend fun <K : Any> boundedScan(
    container: Element,
    items: Selector,
    key: (ElementSnapshot) -> K,
    direction: Direction,
    maxScrolls: Int,
    maxItems: Int,
    timeout: Duration,
    distancePercent: Int,
    noProgressLimit: Int,
): ScanResult<K> {
    require(maxScrolls >= 0 && maxItems > 0 && noProgressLimit > 0) { "Invalid scan limits" }
    require(timeout.isFinite() && timeout > Duration.ZERO) { "timeout must be positive and finite" }
    require(distancePercent in 1..100) { "distancePercent must be in 1..100" }
    require(!container.selector.hasPick && !items.hasPick) { "scan does not accept first()/at() selectors" }
    val rows = Elements(container.device, container.selector.descendant(items))
    val started = TimeSource.Monotonic.markNow()
    val collected = linkedMapOf<K, CollectedObservation<K>>()
    val warnings = mutableSetOf<String>()
    var scrolls = 0
    var reads = 0
    var stalled = 0
    var lastScroll: ScrollResult? = null
    var previous: List<Pair<K, Bounds>>? = null

    fun remaining() = timeout - started.elapsedNow()

    fun result(reason: ScanStopReason?) =
        ScanResult(
            collected.values.toList(),
            reason,
            scrolls,
            reads,
            started.elapsedNow(),
            lastScroll,
            warnings.toSet(),
        )
    try {
        while (true) {
            if (remaining() <= Duration.ZERO) return result(ScanStopReason.TIME_LIMIT)
            container.snapshot(minOf(remaining(), 120.seconds))
            if (remaining() <= Duration.ZERO) return result(ScanStopReason.TIME_LIMIT)
            val batch = rows.snapshots(minOf(remaining(), 120.seconds))
            reads++
            val keys = batch.map(key)
            require(keys.toSet().size == keys.size) { "scan key collision within one collection read" }
            val fingerprint = keys.zip(batch.map { it.bounds })
            keys.zip(batch).forEach { (value, row) ->
                val old = collected[value]
                if (old != null) {
                    if (old.lastSnapshot.copy(bounds = row.bounds) != row) warnings.add("CONTENT_CHANGED")
                    collected[value] = old.copy(lastSnapshot = row, seenInReads = old.seenInReads + 1)
                } else if (collected.size < maxItems) {
                    collected[value] = CollectedObservation(value, row, row, 1)
                } else {
                    warnings.add("ITEMS_OMITTED")
                }
            }
            if (collected.size >= maxItems) return result(ScanStopReason.ITEM_LIMIT)
            if (lastScroll?.edge == ScrollEdge.REACHED) return result(ScanStopReason.EDGE_DETECTED)
            if (remaining() <= Duration.ZERO) return result(ScanStopReason.TIME_LIMIT)
            if (previous != null) {
                stalled = if (fingerprint == previous && lastScroll?.movement != Movement.MOVED) stalled + 1 else 0
                if (stalled >= noProgressLimit) return result(ScanStopReason.NO_PROGRESS)
            }
            previous = fingerprint
            if (scrolls >= maxScrolls) return result(ScanStopReason.SCROLL_LIMIT)
            val stepTimeout = minOf(remaining(), 120.seconds)
            if (stepTimeout <= Duration.ZERO) return result(ScanStopReason.TIME_LIMIT)
            scrolls++
            lastScroll = container.scroll(direction, distancePercent, stepTimeout)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.addSuppressed(ScanPartialException(result(null)))
        throw failure
    }
}
