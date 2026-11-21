package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.ReadLogResponse
import io.github.noamcohen48.tap.daemon.core.DeviceLog
import io.github.noamcohen48.tap.daemon.core.LOG_BATCH_ENTRIES
import io.github.noamcohen48.tap.daemon.core.LogFilter
import io.github.noamcohen48.tap.daemon.core.LogReadSelectionLimitException
import io.github.noamcohen48.tap.daemon.core.LogStart
import io.github.noamcohen48.tap.daemon.core.RetainedLogs
import io.github.noamcohen48.tap.daemon.core.UnknownLogMarkerException
import io.github.noamcohen48.tap.daemon.core.deviceLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector

/** Stream disk evidence with bounded heap usage, even when a recording is larger than memory. */
internal suspend fun FlowCollector<ReadLogResponse>.retainedLog(
    snapshot: RetainedLogs.Snapshot,
    log: DeviceLog,
    start: LogStart,
    filter: LogFilter,
    clockId: String,
) {
    val context = currentCoroutineContext()

    fun entries(reverse: Boolean = false) =
        snapshot
            .entries(reverse)
            .onEach {
                context.ensureActive()
            }.mapNotNull { it.deviceLog() }
            .map(log::redacted)
    val after =
        when (start) {
            LogStart.Oldest, is LogStart.Tail -> {
                snapshot.first - 1
            }

            is LogStart.After -> {
                start.cursor
            }

            is LogStart.Marker -> {
                (
                    entries().lastOrNull { it.hasMarker() && it.marker == start.name }
                        ?: throw UnknownLogMarkerException(start.name)
                ).cursor - 1
            }

            LogStart.LastAction -> {
                entries().lastOrNull { it.hasAction() && !it.action.finished }?.cursor?.minus(1) ?: (snapshot.first - 1)
            }
        }
    var first = maxOf(snapshot.first, if (after == Long.MAX_VALUE) Long.MAX_VALUE else after + 1)
    var last = snapshot.last
    if (filter.step != null) {
        var stepFirst: Long? = null
        var stepLast: Long? = null
        for (entry in entries()) {
            if (entry.hasAction() && entry.action.hasStep() && entry.action.step == filter.step) {
                if (stepFirst == null) stepFirst = entry.cursor
                stepLast = entry.cursor
            }
        }
        first = maxOf(first, stepFirst ?: Long.MAX_VALUE)
        last = minOf(last, stepLast ?: 0)
    }
    emit(
        ReadLogResponse
            .newBuilder()
            .setCaptureError(snapshot.error)
            .setClockId(clockId)
            .build(),
    )
    val selected = entries().filter { it.cursor in first..last }
    if (start is LogStart.Tail) {
        val tail = ArrayDeque<LogEntry>()
        var bytes = 0L
        for (entry in entries(reverse = true).filter { it.cursor in first..last && filter.passes(it) }.take(start.count)) {
            tail.addFirst(entry)
            bytes += entry.serializedSize
            if (bytes >
                MAX_TAIL_BYTES
            ) {
                throw LogReadSelectionLimitException()
            }
        }
        tail.asSequence().batches().forEach { emit(ReadLogResponse.newBuilder().addAllEntries(it).build()) }
        return
    }
    var previous = first - 1
    var missing = 0L
    val batch = mutableListOf<LogEntry>()
    var bytes = 0
    for (entry in selected) {
        missing += (entry.cursor - previous - 1).coerceAtLeast(0)
        previous = entry.cursor
        if (filter.passes(entry)) {
            batch += entry
            bytes += entry.serializedSize
        }
        if (batch.size >= LOG_BATCH_ENTRIES || bytes >= BATCH_BYTES) {
            emit(
                ReadLogResponse
                    .newBuilder()
                    .addAllEntries(batch)
                    .setDropped(missing)
                    .build(),
            )
            batch.clear()
            bytes = 0
            missing = 0
        }
    }
    missing += (last - previous).coerceAtLeast(0)
    if (batch.isNotEmpty() ||
        missing > 0
    ) {
        emit(
            ReadLogResponse
                .newBuilder()
                .addAllEntries(batch)
                .setDropped(missing)
                .build(),
        )
    }
}

private fun Sequence<LogEntry>.batches(): Sequence<List<LogEntry>> =
    sequence {
        var batch = mutableListOf<LogEntry>()
        var bytes = 0
        for (entry in this@batches) {
            batch += entry
            bytes += entry.serializedSize
            if (batch.size >= LOG_BATCH_ENTRIES || bytes >= BATCH_BYTES) {
                yield(batch)
                batch = mutableListOf()
                bytes = 0
            }
        }
        if (batch.isNotEmpty()) yield(batch)
    }

private const val BATCH_BYTES = 1024 * 1024
private const val MAX_TAIL_BYTES = 16L * 1024 * 1024
