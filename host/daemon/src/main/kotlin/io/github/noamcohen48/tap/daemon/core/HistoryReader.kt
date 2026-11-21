package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.HistoryConnectionSource
import io.github.noamcohen48.tap.api.v1.HistoryDeviceSource
import io.github.noamcohen48.tap.api.v1.HistoryEntry
import io.github.noamcohen48.tap.api.v1.HistoryIssue
import io.github.noamcohen48.tap.api.v1.HistoryIssueKind
import io.github.noamcohen48.tap.api.v1.HistoryPageInfo
import io.github.noamcohen48.tap.api.v1.HistorySelection
import io.github.noamcohen48.tap.api.v1.HistorySource
import io.github.noamcohen48.tap.api.v1.ReadHistoryRequest
import io.github.noamcohen48.tap.api.v1.ReadHistoryResponse
import io.github.noamcohen48.tap.daemon.storage.StoredEntry
import io.github.noamcohen48.tap.daemon.storage.StoredHistoryCursor
import io.github.noamcohen48.tap.daemon.storage.StoredPosition
import io.github.noamcohen48.tap.daemon.storage.StoredSourceBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.regex.Pattern

/** Bounded server-side selection. Segment time bounds avoid reading unrelated retained files. */
internal class HistoryReader(
    private val store: RetainedLogs,
    root: Path,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val codec by lazy { HistoryCursorCodec(root) }

    fun sources(selection: HistorySelection): List<RetainedLogs.Archive> =
        synchronized(store) {
            validate(ReadHistoryRequest.newBuilder().setSelection(selection).build())
            val spans =
                if (selection.hasRecordingId()) {
                    store.get(selection.recordingId).spansList.mapTo(HashSet()) { it.sourceId }
                } else {
                    null
                }
            store.archives("").filter { source ->
                (spans == null || source.id in spans) &&
                    (selection.connectionIdsCount == 0 || source.owner in selection.connectionIdsList) &&
                    (selection.sourceIdsCount == 0 || source.id in selection.sourceIdsList) &&
                    (source.events || selection.serialsCount == 0 || source.serial in selection.serialsList)
            }
        }

    suspend fun read(request: ReadHistoryRequest): ReadHistoryResponse {
        val context = currentCoroutineContext()
        val explicit = canonical(request)
        val limit = if (request.limit == 0) DEFAULT_LIMIT else request.limit
        require(limit in 1..MAX_LIMIT) { "limit must be 1 to $MAX_LIMIT" }
        val firstPage = !request.hasCursor()
        val state =
            if (firstPage) {
                initial(explicit)
            } else {
                codec.decode(request.cursor).also {
                    require(explicit == ReadHistoryRequest.getDefaultInstance() || explicit == it.query) {
                        "a history cursor continues its own read: send it alone, or with that read's request unchanged"
                    }
                }
            }
        val query = state.query
        validate(query)
        val backwards = state.newestFirst
        val natural = NATURAL
        val ordering = if (backwards) natural.reversed() else natural
        val position =
            HistoryEntry
                .newBuilder()
                .setReceivedAtMs(
                    state.receivedAtMs,
                ).setSourceId(state.sourceId)
                .setSequence(state.sequence)
                .build()
        val issues = linkedMapOf<String, HistoryIssue>()

        fun issue(
            source: String,
            kind: HistoryIssueKind,
            message: String,
            missing: Long = 0,
        ) {
            issues.putIfAbsent("$source:$kind:$message", historyIssue(source, kind, message, missing))
        }
        val descriptors = mutableListOf<HistorySource>()
        val heap = PriorityQueue(ordering.reversed())
        var heapBytes = 0L
        var rejected = false
        var cutoff: HistoryEntry? = null

        fun offer(entry: HistoryEntry) {
            if (state.hasFirst() && natural.compare(entry, state.first.key()) < 0) return
            if (state.hasLast() && natural.compare(entry, state.last.key()) > 0) return
            if (state.positioned && ordering.compare(entry, position) <= 0) return
            if (cutoff?.let { ordering.compare(entry, it) >= 0 } == true) {
                rejected = true
                return
            }
            heap.add(entry)
            heapBytes += entry.serializedSize + 16
            while (heap.size > limit + 1 || (heapBytes > CANDIDATE_BYTES && heap.size > 1)) {
                val removed = heap.remove()
                heapBytes -= removed.serializedSize + 16
                val previousCutoff = cutoff
                if (previousCutoff == null || ordering.compare(removed, previousCutoff) < 0) cutoff = removed
                rejected = true
            }
        }
        val pattern = if (query.logFilter.hasMessageRegex()) Pattern.compile(query.logFilter.messageRegex) else null
        val selected = sources(query.selection).associateBy { it.id }
        val knownIds = selected.keys
        val recording = if (query.selection.hasRecordingId()) store.get(query.selection.recordingId) else null
        if (recording != null) {
            store.issues(recording).forEach { (source, message) ->
                issue(source, HistoryIssueKind.HISTORY_ISSUE_KIND_INCOMPLETE, message.take(512))
            }
            val retainedIds = store.archives("").mapTo(HashSet()) { it.id }
            recording.spansList.filterNot { it.sourceId in retainedIds }.forEach {
                issue(it.sourceId, HistoryIssueKind.HISTORY_ISSUE_KIND_UNAVAILABLE, "a recorded source is no longer kept")
            }
        }
        query.selection.sourceIdsList.filterNot { it in knownIds }.forEach {
            issue(it, HistoryIssueKind.HISTORY_ISSUE_KIND_UNAVAILABLE, "requested history source is not retained")
        }
        for (boundary in state.sourcesList) {
            context.ensureActive()
            val source = selected[boundary.sourceId]
            if (source == null) {
                issue(boundary.sourceId, HistoryIssueKind.HISTORY_ISSUE_KIND_EXPIRED, "history source expired while paging")
                continue
            }
            val descriptor = synchronized(store) { historySource(source) }
            // A read reports every issue once, in `ReadHistoryResponse.issues`.
            descriptors += descriptor.toBuilder().clearIssues().setLastSequence(boundary.throughSequence).build()
            descriptor.issuesList.forEach { issue(it.sourceId, it.kind, it.message) }
            val lower = if (state.hasFromTimeMs()) state.fromTimeMs else null
            val span = recording?.spansList?.firstOrNull { it.sourceId == source.id }
            val (firstKept, firstKeptAt) =
                synchronized(store) { source.segments.firstOrNull()?.let { it.first to it.firstAt } ?: (source.cursor + 1 to Long.MAX_VALUE) }
            if (firstPage) {
                // Entries the range asked for that retention already removed. A read without a
                // start begins at the oldest kept entry, so only a bounded or recorded range loses any.
                val rangeFirst = (span?.afterCursor ?: 0) + 1
                val removedInRange =
                    when {
                        firstKept <= rangeFirst || boundary.throughSequence < rangeFirst -> false
                        span != null -> true
                        lower == null -> false
                        else -> lower <= synchronized(store) { source.removedAt }.takeIf { it > 0 }.let { it ?: firstKeptAt }
                    }
                if (removedInRange) {
                    issue(
                        source.id,
                        HistoryIssueKind.HISTORY_ISSUE_KIND_EXPIRED,
                        "retention removed entries in the range, before ${java.time.Instant.ofEpochMilli(firstKeptAt)}",
                        if (span != null) firstKept - rangeFirst else 0,
                    )
                }
            } else if (firstKept > boundary.firstSequence && boundary.throughSequence >= boundary.firstSequence) {
                // A forward read already past the removed entries lost none of them.
                val passed = !backwards && state.positioned && state.receivedAtMs >= firstKeptAt
                if (!passed) {
                    issue(
                        source.id,
                        HistoryIssueKind.HISTORY_ISSUE_KIND_EXPIRED,
                        "retention removed older entries while paging",
                        firstKept - boundary.firstSequence,
                    )
                }
            }
            val protection = recording?.id
            try {
                store.snapshot(source, protection, lower, state.untilTimeMs, boundary.throughSequence).use { snapshot ->
                    val runs = if (query.hasStep()) stepRuns(snapshot, source.events, query.step) else null
                    val agedBefore = snapshot.expired
                    for (entry in snapshot.entries()) {
                        context.ensureActive()
                        if (runs != null &&
                            !(if (source.events) entry.hasEvent() || runs.any { entry.hostTimeMs in it } else runs.any { entry.cursor in it })
                        ) {
                            continue
                        }
                        val serial =
                            when {
                                entry.hasEvent() -> entry.event.serial
                                entry.hasDeath() -> entry.death.serial
                                else -> source.serial
                            }
                        if (query.selection.serialsCount > 0 && serial.isNotEmpty() && serial !in query.selection.serialsList) continue
                        if (entry.hasEvent() && query.hasStep() && (!entry.event.hasStep() || entry.event.step != query.step)) continue
                        if (query.includeLogs && query.selection.sourceIdsCount == 0 && source.events) {
                            val deviceId =
                                when {
                                    entry.hasEvent() -> entry.event.attachedDeviceId
                                    entry.hasDeath() -> entry.death.attachedDeviceId
                                    else -> ""
                                }
                            if (deviceId.isNotEmpty() && deviceId !in knownIds) {
                                issue(
                                    deviceId,
                                    HistoryIssueKind.HISTORY_ISSUE_KIND_UNAVAILABLE,
                                    "device logs are not retained; capture may have been disabled or expired",
                                )
                            }
                        }
                        if (!source.events && !entry.hasGap()) {
                            if (!query.includeLogs) continue
                            if (entry.hasAction() && query.logFilter.excludeActions) continue
                            if (entry.hasLine()) {
                                val line = entry.line
                                val filter = query.logFilter
                                if (line.priorityValue < filter.minPriorityValue) continue
                                if (filter.tagsCount > 0 && line.tag !in filter.tagsList) continue
                                if (pattern != null && !pattern.matcher(line.message).find()) continue
                                if (!filter.includeDriverLines && driverLine(line)) continue
                                if (filter.packagesCount > 0) {
                                    if (!line.hasPackagesObservedAtMs()) {
                                        issue(
                                            source.id,
                                            HistoryIssueKind.HISTORY_ISSUE_KIND_PACKAGE_UNKNOWN,
                                            "some lines were saved without their app; the package filter returned them unfiltered",
                                        )
                                    } else if (line.packagesList.none { it in filter.packagesList }) {
                                        continue
                                    }
                                }
                            }
                        }
                        offer(historyEntry(source.id, entry))
                    }
                    val aged = snapshot.expired - agedBefore
                    if (aged > 0) {
                        issue(
                            source.id,
                            HistoryIssueKind.HISTORY_ISSUE_KIND_EXPIRED,
                            "entries older than the retention age were removed from the range",
                            aged,
                        )
                    }
                }
            } catch (_: RedactionFailedException) {
                // Its entries are withheld; the source's INCOMPLETE issue above says so.
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                issue(
                    source.id,
                    HistoryIssueKind.HISTORY_ISSUE_KIND_UNAVAILABLE,
                    "could not read retained source (${error.javaClass.simpleName})",
                )
            }
        }
        val ordered = heap.toList().sortedWith(ordering)
        var more = rejected || ordered.size > limit
        val output = ordered.take(limit).toMutableList()

        fun result(): ReadHistoryResponse =
            ReadHistoryResponse
                .newBuilder()
                .addAllSources(descriptors)
                .addAllEntries(output)
                .addAllIssues(issues.values)
                .setPage(
                    HistoryPageInfo
                        .newBuilder()
                        .setNextCursor(if (more && output.isNotEmpty()) codec.encode(positioned(state, output.last())) else "")
                        .setThroughTimeMs(state.untilTimeMs),
                ).build()
        var response = result()
        while (response.serializedSize > MAX_PAGE_BYTES && output.size > 1) {
            output.removeAt(output.lastIndex)
            more = true
            response = result()
        }
        check(response.serializedSize <= MAX_PAGE_BYTES) { "history page metadata is too large; narrow the selection" }
        return response
    }

    /**
     * Where step [step] ran in one source, as runs: consecutive actions (device log, by sequence)
     * or calls (connection, by received time from the first call's start) of that step, ended by
     * an action or call of another step. A step name used again later makes a second run, so the
     * entries of the steps between them are not included.
     */
    private fun stepRuns(
        snapshot: RetainedLogs.Snapshot,
        events: Boolean,
        step: String,
    ): List<LongRange> {
        val runs = mutableListOf<LongRange>()
        var first: Long? = null
        var last = 0L
        for (entry in snapshot.entries()) {
            val tagged =
                when {
                    events && entry.hasEvent() && entry.event.hasStep() -> entry.event.step
                    !events && entry.hasAction() && entry.action.hasStep() -> entry.action.step
                    else -> continue
                }
            if (tagged == step) {
                val begins = if (events) minOf(entry.event.atEpochMs, entry.hostTimeMs) else entry.cursor
                val ends = if (events) entry.hostTimeMs else entry.cursor
                if (first == null) first = begins
                last = maxOf(last, ends)
            } else if (first != null) {
                runs += first..last
                first = null
            }
        }
        if (first != null) runs += first..last
        return runs
    }

    private fun initial(query: ReadHistoryRequest): StoredHistoryCursor {
        validate(query)
        val candidates = synchronized(store) { sources(query.selection) }
        val recording = if (query.selection.hasRecordingId()) query.selection.recordingId else null
        val explicitUntil = if (query.endCase == ReadHistoryRequest.EndCase.UNTIL_TIME_MS) query.untilTimeMs else null
        val first =
            if (query.hasFromMarker()) markers(query.fromMarker, candidates, recording, explicitUntil).minWith(NATURAL) else null
        val last = if (query.hasUntilMarker()) markers(query.untilMarker, candidates, recording, null).maxWith(NATURAL) else null
        val from =
            when {
                query.hasFromTimeMs() -> query.fromTimeMs
                first != null -> first.receivedAtMs
                else -> null
            }
        val until =
            when {
                query.hasUntilTimeMs() -> query.untilTimeMs
                last != null -> last.receivedAtMs + 1
                else -> now().coerceAtMost(Long.MAX_VALUE - 1) + 1
            }
        require(from == null || until >= from && (first == null || last == null || NATURAL.compare(first, last) <= 0)) {
            if (query.endCase == ReadHistoryRequest.EndCase.UNTIL_MARKER || query.startCase == ReadHistoryRequest.StartCase.FROM_MARKER) {
                "the end of the read precedes its start: the until marker is older than the from marker"
            } else {
                "until_time_ms must not precede from_time_ms"
            }
        }
        return synchronized(store) {
            val selected =
                sources(query.selection).filter { source ->
                    query.selection.sourceIdsCount > 0 || recording != null || source.error.isNotEmpty() ||
                        source.segments.any { segment -> (from == null || segment.at >= from) && segment.firstAt < until }
                }
            require(selected.size <= MAX_SOURCES) { "too many history sources; narrow the time, connection or device selection" }
            StoredHistoryCursor
                .newBuilder()
                .setVersion(CURSOR_VERSION)
                .setQuery(query)
                .apply {
                    if (from != null) setFromTimeMs(from)
                    if (first != null) setFirst(first.position())
                    if (last != null) setLast(last.position())
                }
                .setUntilTimeMs(until)
                .setNewestFirst(query.newestFirst)
                .addAllSources(
                    selected.map { source ->
                        val span = recording?.let { id -> store.get(id).spansList.first { it.sourceId == source.id } }
                        val through = if (span != null && span.hasThroughCursor()) span.throughCursor else source.cursor
                        StoredSourceBoundary
                            .newBuilder()
                            .setSourceId(source.id)
                            .setThroughSequence(through)
                            .setFirstSequence(source.segments.firstOrNull()?.first ?: source.cursor + 1)
                            .build()
                    },
                ).build()
        }
    }

    /**
     * Each selected device's newest marker named [name], as an entry key: per serial, across that
     * serial's attachments in the selection. NOT_FOUND when no selected device has one.
     */
    private fun markers(
        name: String,
        sources: List<RetainedLogs.Archive>,
        recording: String?,
        untilTimeMs: Long?,
    ): List<HistoryEntry> =
        sources
            .filterNot { it.events }
            .mapNotNull { source ->
                try {
                    store.snapshot(source, recording, untilTimeMs = untilTimeMs).use { snapshot ->
                        snapshot.entries(reverse = true).firstOrNull { it.hasMarker() && it.marker == name }?.let { marker ->
                            source.serial to
                                HistoryEntry
                                    .newBuilder()
                                    .setReceivedAtMs(marker.hostTimeMs)
                                    .setSourceId(source.id)
                                    .setSequence(marker.cursor)
                                    .build()
                        }
                    }
                } catch (_: RedactionFailedException) {
                    null
                }
            }.groupBy({ it.first }, { it.second })
            .values
            .map { it.maxWith(NATURAL) }
            .ifEmpty { throw UnknownLogMarkerException(name) }

    private fun canonical(request: ReadHistoryRequest): ReadHistoryRequest =
        request
            .toBuilder()
            .clearCursor()
            .clearLimit()
            .apply {
                if (selection == HistorySelection.getDefaultInstance()) clearSelection()
            }.build()

    private fun validate(query: ReadHistoryRequest) {
        require(!query.hasUntilTimeMs() || !query.hasFromTimeMs() || query.untilTimeMs >= query.fromTimeMs) {
            "until_time_ms must not precede from_time_ms"
        }
        require(query.includeLogs || !query.hasLogFilter()) { "log_filter needs include_logs" }
        for (marker in listOfNotNull(query.fromMarker.takeIf { query.hasFromMarker() }, query.untilMarker.takeIf { query.hasUntilMarker() })) {
            require(marker.length in 1..200 && marker.none(Char::isISOControl)) { "a marker name is 1 to 200 characters on one line" }
        }
        val fields =
            listOf(
                query.selection.connectionIdsList,
                query.selection.sourceIdsList,
                query.selection.serialsList,
                query.logFilter.packagesList,
                query.logFilter.tagsList,
            )
        fields.forEach { values ->
            require(
                values.size <= 64 &&
                    values.all {
                        it.length in 1..256 && it.none(Char::isISOControl)
                    },
            ) { "history filters need at most 64 nonempty values of 1..256 characters" }
        }
        if (query.hasStep()) require(query.step.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "step must be 1..64 letters/digits/._-" }
        require(query.logFilter.minPriorityValue in 0..6) { "invalid log priority" }
        if (query.logFilter.hasMessageRegex()) {
            require(query.logFilter.messageRegex.length in 1..512) { "message_regex must be 1..512 characters" }
            Pattern.compile(query.logFilter.messageRegex)
        }
    }

    private fun positioned(
        state: StoredHistoryCursor,
        entry: HistoryEntry,
    ): StoredHistoryCursor =
        state
            .toBuilder()
            .setPositioned(true)
            .setReceivedAtMs(entry.receivedAtMs)
            .setSourceId(entry.sourceId)
            .setSequence(entry.sequence)
            .build()

    private fun HistoryEntry.position(): StoredPosition =
        StoredPosition
            .newBuilder()
            .setReceivedAtMs(receivedAtMs)
            .setSourceId(sourceId)
            .setSequence(sequence)
            .build()

    private fun StoredPosition.key(): HistoryEntry =
        HistoryEntry
            .newBuilder()
            .setReceivedAtMs(receivedAtMs)
            .setSourceId(sourceId)
            .setSequence(sequence)
            .build()

    private companion object {
        /** The read order: received time, then source, then sequence. */
        val NATURAL = compareBy<HistoryEntry>({ it.receivedAtMs }, { it.sourceId }, { it.sequence })
        const val CURSOR_VERSION = 2
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 5000
        const val MAX_SOURCES = 512
        const val CANDIDATE_BYTES = 1536 * 1024
        const val MAX_PAGE_BYTES = 3 * 1024 * 1024
        val DRIVER_PACKAGES = setOf("io.github.noamcohen48.tap.driver", "io.github.noamcohen48.tap.driver.test")
        val DRIVER_TAGS = setOf(io.github.noamcohen48.tap.protocol.ActionLog.TAG)
    }

    /** The Tap driver's own logcat line: by its packages, or by its tags for lines saved without packages. */
    private fun driverLine(line: io.github.noamcohen48.tap.api.v1.LogLine): Boolean =
        if (line.packagesCount > 0) line.packagesList.all { it in DRIVER_PACKAGES } else line.tag in DRIVER_TAGS
}

internal fun historySource(source: RetainedLogs.Archive): HistorySource =
    HistorySource
        .newBuilder()
        .setId(source.id)
        .setClockId(source.clockId)
        .setClosed(source.closed)
        .setLastSequence(source.cursor)
        .setRetainedBytes(source.segments.sumOf { it.bytes })
        .apply {
            if (source.events) {
                setConnection(HistoryConnectionSource.newBuilder().setConnectionId(source.owner))
            } else {
                setDeviceLog(
                    HistoryDeviceSource
                        .newBuilder()
                        .setConnectionId(source.owner)
                        .setAttachedDeviceId(source.id)
                        .setSerial(source.serial),
                )
            }
            if (source.error.isNotEmpty()) {
                addIssues(
                    historyIssue(source.id, HistoryIssueKind.HISTORY_ISSUE_KIND_INCOMPLETE, source.error.take(256)),
                )
            }
        }.build()

internal fun historyIssue(
    source: String,
    kind: HistoryIssueKind,
    message: String,
    missing: Long = 0,
): HistoryIssue =
    HistoryIssue
        .newBuilder()
        .setSourceId(source)
        .setKind(kind)
        .setMessage(message)
        .setMissingEntries(missing)
        .build()

private fun historyEntry(
    source: String,
    entry: StoredEntry,
): HistoryEntry =
    HistoryEntry
        .newBuilder()
        .setSourceId(source)
        .setSequence(entry.cursor)
        .setReceivedAtMs(entry.hostTimeMs)
        .setReceivedMonotonicNs(entry.hostMonotonicNs)
        .apply {
            when (entry.kindCase) {
                StoredEntry.KindCase.LINE -> setLine(entry.line)
                StoredEntry.KindCase.ACTION -> setAction(entry.action)
                StoredEntry.KindCase.MARKER -> setMarker(entry.marker)
                StoredEntry.KindCase.EVENT -> setCall(entry.event)
                StoredEntry.KindCase.DEATH -> setDeath(entry.death)
                StoredEntry.KindCase.GAP -> setGap(entry.gap)
                else -> error("retained entry without a kind")
            }
        }.build()
