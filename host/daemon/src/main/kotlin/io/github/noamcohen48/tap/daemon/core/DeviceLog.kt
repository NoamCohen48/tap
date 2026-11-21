package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LogAction
import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogLine
import io.github.noamcohen48.tap.api.v1.LogPriority
import io.github.noamcohen48.tap.host.LogcatCapture
import io.github.noamcohen48.tap.host.LogcatLine
import io.github.noamcohen48.tap.host.MediaClock
import io.github.noamcohen48.tap.protocol.ActionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/** The attachment was opened without log capture (`AttachRequest.capture_logs` false). */
class LogCaptureOffException(
    serial: String,
) : DaemonPreconditionException("Log capture is off for this attachment of $serial: attach with capture_logs to read its log")

/** No marker with this name was written to the log. Maps to NOT_FOUND. */
class UnknownLogMarkerException(
    name: String,
) : NoSuchElementException("No log marker named '$name'")

/** A follower fell [LOG_READER_CAPACITY] entries behind; it reads again after the last cursor it got. */
class LogReaderOverflowException : RuntimeException("log reader fell behind; read again after the last received cursor")

/** A tail selection exceeded the bounded server-side selection buffer. */
internal class LogReadSelectionLimitException :
    RuntimeException("selected log tail exceeds 16 MiB; request fewer entries or narrower filters")

/** Where a read of the log starts. */
internal sealed interface LogStart {
    /** The oldest entry still kept. */
    object Oldest : LogStart

    data class After(
        val cursor: Long,
    ) : LogStart

    /** The newest marker named [name], included. */
    data class Marker(
        val name: String,
    ) : LogStart

    /** The newest [count] entries that pass the filter. */
    data class Tail(
        val count: Int,
    ) : LogStart

    /** The start of the newest action, included. */
    object LastAction : LogStart
}

/** Which entries a read returns. The line filters leave markers, actions and gaps in. */
internal data class LogFilter(
    val uids: Set<Int>? = null,
    val minPriority: LogPriority = LogPriority.LOG_PRIORITY_UNSPECIFIED,
    val tags: Set<String> = emptySet(),
    val message: Pattern? = null,
    val excludeActions: Boolean = false,
    /** Only from the first action of this step to its last ([LogAction.getStep]). */
    val step: String? = null,
) {
    /** [entry], already redacted. */
    fun passes(entry: LogEntry): Boolean =
        when (entry.kindCase) {
            LogEntry.KindCase.ACTION -> {
                !excludeActions
            }

            LogEntry.KindCase.LINE -> {
                val line = entry.line
                (uids == null || (line.hasUid() && line.uid in uids)) &&
                    line.priorityValue >= minPriority.number &&
                    (tags.isEmpty() || line.tag in tags) &&
                    (message == null || message.matcher(line.message).find())
            }

            else -> {
                true
            }
        }
}

/**
 * The log of one attachment (`DeviceLogService`): the device's logcat lines as the capture
 * delivers them, the driver's own lines turned into actions, and the markers, host actions and
 * gaps the daemon writes in between, numbered by [LogEntry.getCursor] from 1 in arrival order.
 *
 * It keeps the newest [maxBytes] of text in memory and nothing on disk; [dropped] counts what it
 * forgot. Entries are stored as the device logged them and redacted when read, so a pattern
 * added later covers earlier lines. Appending never blocks on a reader: a follower whose queue
 * is full is ended with [LogReaderOverflowException].
 */
internal class DeviceLog(
    private val maxBytes: Long = LOG_BUFFER_BYTES,
    private val readerCapacity: Int = LOG_READER_CAPACITY,
    /** The device's installed packages and their uids (`pm list packages -U`); null: lines name no package. */
    private val installedPackages: (suspend () -> Map<String, Int>)? = null,
    private val retained: RetainedLogs? = null,
    internal val archive: RetainedLogs.Archive? = null,
) : LogcatCapture.Sink {
    val redactor =
        LogRedactor().apply {
            changed = { archive?.let { retained?.redact(it) { entry -> entry.deviceLog()?.let(::redacted)?.stored() ?: entry } } }
        }

    /** The uid the driver logs as ([io.github.noamcohen48.tap.api.v1.DeviceInfo.getDriverUid]); its `Tap` lines are actions. */
    @Volatile var driverUid: Int? = null

    private val packagesLock = Mutex()

    private data class PackageSnapshot(
        val names: Map<Int, List<String>>,
        val atMs: Long,
        val atNs: Long,
        val epoch: Long,
    )

    @Volatile private var packageSnapshot: PackageSnapshot? = null

    @Volatile private var packageScope: CoroutineScope? = null
    private val packageChanges = AtomicInteger()
    private val packageEpoch = AtomicLong()
    private var packagesReadAtNs: Long? = null

    private val entries = ArrayDeque<LogEntry>()
    private val readers = HashMap<Channel<LogEntry>, LogFilter>()

    /** The step of each driver request whose lines may still arrive, oldest first. */
    private val steps = LinkedHashMap<Long, String>()
    private var bytes = 0L
    private var lastCursor = 0L
    private var dropped = 0L

    @Volatile private var closed = false

    /** What a read starts from, and for a follower the queue its live entries arrive on. */
    class Subscription(
        val backlog: List<LogEntry>,
        val dropped: Long,
        internal val live: Channel<LogEntry>?,
    )

    private val barriers = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /**
     * A barrier for [token]: the caller logs it on the device under [BARRIER_TAG], and the result
     * completes once the capture delivered that line. Logcat prints in time order, so every line
     * logged before the barrier has then been stored. The barrier line itself is not kept.
     */
    internal fun barrier(token: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { barriers[token] = it }

    internal fun forgetBarrier(token: String) {
        barriers.remove(token)
    }

    override fun line(line: LogcatLine) {
        if (line.tag == BARRIER_TAG) {
            barriers.remove(line.message.trim())?.complete(Unit)
            return
        }
        val action = if (line.tag == ActionLog.TAG && line.uid != null && line.uid == driverUid) ActionLog.parse(line.message) else null
        if (action != null) {
            append(
                LogEntry.newBuilder().setAction(
                    LogAction
                        .newBuilder()
                        .setOperation(action.op)
                        .setFinished(action.finished)
                        .setRequestId(action.requestId)
                        .setDeviceTimeMs(line.deviceTimeMs)
                        .apply {
                            action.target?.let(::setTarget)
                            action.outcome?.let(::setOutcome)
                            action.durationMs?.let(::setDurationMs)
                        },
                ),
            )
            return
        }
        append(
            LogEntry.newBuilder().setLine(
                LogLine
                    .newBuilder()
                    .setDeviceTimeMs(line.deviceTimeMs)
                    .setPid(line.pid)
                    .setTid(line.tid)
                    .setPriority(priority(line.priority))
                    .setTag(line.tag)
                    .setMessage(line.message)
                    .apply { line.uid?.let(::setUid) },
            ),
        )
    }

    override fun interrupted(reason: String) {
        append(LogEntry.newBuilder().setGap(reason))
    }

    /** Writes a marker; returns its cursor. */
    @Synchronized
    fun mark(name: String): Long {
        if (closed) throw DaemonPreconditionException("log capture closed before the marker could be retained")
        if (archive?.error.orEmpty().isNotEmpty()) throw DaemonPreconditionException("marker cannot be retained: ${archive?.error}")
        val cursor = append(LogEntry.newBuilder().setMarker(name))
        archive?.let { retained?.sync(it) } // acknowledged only once it is on disk
        if (archive?.error.orEmpty().isNotEmpty()) throw DaemonPreconditionException("marker could not be retained: ${archive?.error}")
        return cursor
    }

    /**
     * The driver request [requestId], sent at or after [sentAtHostMs], belongs to [step]: its
     * action entries carry the step, those that arrived before this call included (a follower
     * that already got one of those got it without). Request ids start again when the driver
     * reconnects, so only entries newer than [sentAtHostMs] are touched.
     */
    @Synchronized
    fun commandStep(
        requestId: Long,
        step: String,
        sentAtHostMs: Long,
    ) {
        if (closed) return
        steps.remove(requestId)
        steps[requestId] = step
        if (steps.size > MAX_PENDING_STEPS) steps.remove(steps.keys.first())
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            if (entry.hostTimeMs < sentAtHostMs) break
            if (!entry.hasAction() || !entry.action.hasRequestId() || entry.action.requestId != requestId ||
                entry.action.hasStep()
            ) {
                continue
            }
            entries[index] = entry.toBuilder().setAction(entry.action.toBuilder().setStep(step)).build()
            bytes += step.length
            if (entry.action.finished) steps.remove(requestId)
        }
        val previousError = archive?.error.orEmpty()
        archive?.let { retained?.commandStep(it, requestId, step, sentAtHostMs) }
        noticeStorageFailure(previousError)
    }

    /** An action the host ran itself (an app or device call), at its start or its end. */
    fun hostAction(
        operation: String,
        target: String?,
        outcome: String? = null,
        durationMs: Long? = null,
        step: String? = null,
    ) {
        append(
            LogEntry.newBuilder().setAction(
                LogAction.newBuilder().setOperation(operation).setFinished(outcome != null).apply {
                    target?.let(::setTarget)
                    outcome?.let(::setOutcome)
                    durationMs?.let(::setDurationMs)
                    step?.let(::setStep)
                },
            ),
        )
    }

    @Synchronized
    private fun append(entry: LogEntry.Builder): Long {
        if (closed) return lastCursor
        if (entry.hasAction() && entry.action.hasRequestId()) {
            val requestId = entry.action.requestId
            steps[requestId]?.let { step ->
                entry.setAction(entry.action.toBuilder().setStep(step))
                if (entry.action.finished) steps.remove(requestId)
            }
        }
        if (entry.hasLine()) {
            val value =
                entry.line
                    .toBuilder()
                    .clearPackages()
                    .clearPackagesObservedAtMs()
            val snapshot = packageSnapshot
            if (snapshot != null && packageChanges.get() == 0 && snapshot.epoch == packageEpoch.get() &&
                System.nanoTime() - snapshot.atNs <= PACKAGES_REFRESH_NS && value.hasUid()
            ) {
                val names = snapshot.names[appId(value.uid)].orEmpty()
                if (names.isNotEmpty()) value.addAllPackages(names).setPackagesObservedAtMs(snapshot.atMs)
            }
            entry.setLine(value)
        }
        val recorded =
            entry
                .setCursor(
                    ++lastCursor,
                ).setHostTimeMs(System.currentTimeMillis())
                .setHostMonotonicNs(MediaClock.nowNs())
                .build()
        // Persist only the currently redacted representation, never the raw log line.
        val previousError = archive?.error.orEmpty()
        archive?.let { retained?.append(it, redacted(recorded)) }
        entries.addLast(recorded)
        bytes += size(recorded)
        while (bytes > maxBytes && entries.size > 1) {
            bytes -= size(entries.removeFirst())
            dropped++
        }
        if (readers.isNotEmpty()) {
            val redacted = redacted(recorded)
            val iterator = readers.iterator()
            while (iterator.hasNext()) {
                val (reader, filter) = iterator.next()
                if (filter.passes(redacted) && reader.trySend(redacted).isFailure) {
                    iterator.remove()
                    reader.close(LogReaderOverflowException())
                }
            }
        }
        noticeStorageFailure(previousError)
        return recorded.cursor
    }

    private fun noticeStorageFailure(previous: String) {
        val error = archive?.error.orEmpty()
        if (previous.isEmpty() && error.isNotEmpty()) append(LogEntry.newBuilder().setGap("durable capture incomplete: $error"))
    }

    /**
     * The redacted entries from [start] that pass [filter], and with [follow] a queue registered
     * in the same step, so backlog and live entries neither overlap nor leave a gap.
     */
    @Synchronized
    fun read(
        start: LogStart = LogStart.Oldest,
        filter: LogFilter = LogFilter(),
        follow: Boolean = false,
        recording: String? = null,
        memoryOnly: Boolean = follow,
    ): Subscription {
        val snapshot = archive?.let { retained?.snapshot(it, recording) }
        val firstExpected = snapshot?.first ?: 1L
        val lastExpected = snapshot?.last ?: lastCursor
        val entries =
            try {
                if (memoryOnly) {
                    entries.filter { snapshot == null || snapshot.allows(it.stored()) }
                } else {
                    snapshot?.entries()?.mapNotNull { it.deviceLog() }?.toList()
                        ?: entries.toList()
                }
            } finally {
                snapshot?.close()
            }
        val from: Long =
            when (start) {
                LogStart.Oldest, is LogStart.Tail -> {
                    0
                }

                is LogStart.After -> {
                    start.cursor
                }

                is LogStart.Marker -> {
                    (
                        entries.lastOrNull { it.hasMarker() && it.marker == start.name } ?: throw UnknownLogMarkerException(
                            start.name,
                        )
                    ).cursor -
                        1
                }

                LogStart.LastAction -> {
                    entries.lastOrNull { it.hasAction() && !it.action.finished }?.let { it.cursor - 1 } ?: 0
                }
            }
        val range =
            if (filter.step == null) {
                null
            } else {
                val ofStep = entries.filter { it.hasAction() && it.action.hasStep() && it.action.step == filter.step }
                if (ofStep.isEmpty()) LongRange.EMPTY else ofStep.first().cursor..ofStep.last().cursor
            }
        val matching =
            entries
                .asSequence()
                .filter { it.cursor > from && (range == null || it.cursor in range) }
                .map(::redacted)
                .filter(filter::passes)
                .toList()
        val backlog = if (start is LogStart.Tail) matching.takeLast(start.count) else matching
        val lower = maxOf(firstExpected, if (from == Long.MAX_VALUE) Long.MAX_VALUE else from + 1, range?.first ?: firstExpected)
        val upper = minOf(lastExpected, range?.last ?: lastExpected)
        val available = entries.count { it.cursor in lower..upper }
        val missed = if (start is LogStart.Tail || upper < lower) 0 else ((upper - lower + 1) - available).coerceAtLeast(0)
        val live = if (follow) Channel<LogEntry>(readerCapacity).also { if (closed) it.close() else readers[it] = filter } else null
        return Subscription(backlog, missed, live)
    }

    /** The follower's live entries, batched by what is already queued; ends the reader on exit. */
    fun live(subscription: Subscription): Flow<List<LogEntry>> =
        flow {
            val channel = subscription.live ?: return@flow
            try {
                for (first in channel) {
                    val batch = mutableListOf(first)
                    while (batch.size < LOG_BATCH_ENTRIES) batch += channel.tryReceive().getOrNull() ?: break
                    emit(batch)
                }
            } finally {
                synchronized(this@DeviceLog) { readers.remove(channel) }
                channel.cancel()
            }
        }

    /** Start only after registration, so failed/orphaned attachments cannot leak a worker. */
    @Synchronized
    internal fun startPackageRefresh() {
        if (closed || installedPackages == null || packageScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        packageScope = scope
        scope.launch {
            while (isActive) {
                delay(PACKAGES_REFRESH_NS / 1_000_000)
                refreshPackageOwnership()
            }
        }
    }

    /** Sample before logcat starts, then periodically; reads never reattribute saved lines. */
    internal suspend fun refreshPackageOwnership(force: Boolean = false) =
        packagesLock.withLock {
            val resolve = installedPackages ?: return@withLock
            if (closed || packageChanges.get() != 0) return@withLock
            val now = System.nanoTime()
            val last = packagesReadAtNs
            if (!force && last != null && now - last < PACKAGES_REFRESH_NS) return@withLock
            packagesReadAtNs = now
            val epoch = packageEpoch.get()
            try {
                val packages = withTimeoutOrNull(1_000) { resolve() }
                if (packages == null) {
                    packageSnapshot = null
                    return@withLock
                }
                val names = packages.entries.groupBy({ appId(it.value) }, { it.key }).mapValues { (_, names) -> names.sorted() }
                if (!closed && packageChanges.get() == 0 && packageEpoch.get() == epoch) {
                    packageSnapshot = PackageSnapshot(names, System.currentTimeMillis(), System.nanoTime(), epoch)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                packageSnapshot = null
            }
        }

    internal fun beginPackageChange() {
        packageEpoch.incrementAndGet()
        packageChanges.incrementAndGet()
        packageSnapshot = null
    }

    internal fun endPackageChange() {
        if (packageChanges.decrementAndGet() == 0) packageScope?.launch { refreshPackageOwnership(force = true) }
    }

    /** The device is detached: followers finish what is queued, then complete; the entries are forgotten. */
    @Synchronized
    fun close() {
        closed = true
        packageScope?.cancel()
        packageScope = null
        packageSnapshot = null
        readers.keys.forEach { it.close() }
        readers.clear()
        steps.clear()
        archive?.let { retained?.close(it) }
        entries.clear()
        bytes = 0
    }

    internal fun redacted(entry: LogEntry): LogEntry =
        when {
            entry.hasLine() -> {
                entry
                    .toBuilder()
                    .setLine(
                        entry.line
                            .toBuilder()
                            .setMessage(redactor.redact(entry.line.message))
                            .setTag(redactor.redact(entry.line.tag)),
                    ).build()
            }

            entry.hasAction() && entry.action.hasTarget() -> {
                entry
                    .toBuilder()
                    .setAction(
                        entry.action.toBuilder().setTarget(redactor.redact(entry.action.target)),
                    ).build()
            }

            entry.hasGap() -> {
                entry.toBuilder().setGap(redactor.redact(entry.gap)).build()
            }

            else -> {
                entry
            }
        }

    private fun size(entry: LogEntry): Long =
        ENTRY_OVERHEAD_BYTES +
            when (entry.kindCase) {
                LogEntry.KindCase.LINE -> entry.line.tag.length + entry.line.message.length + entry.line.packagesList.sumOf { it.length }
                LogEntry.KindCase.MARKER -> entry.marker.length
                LogEntry.KindCase.GAP -> entry.gap.length
                LogEntry.KindCase.ACTION -> entry.action.operation.length + entry.action.target.length + entry.action.step.length
                else -> 0
            }

    companion object {
        /** What one entry costs besides its text: the message objects and their numbers. */
        private const val ENTRY_OVERHEAD_BYTES = 96L

        /** Driver requests whose step is remembered until their lines arrive. */
        private const val MAX_PENDING_STEPS = 1_024

        fun priority(letter: Char): LogPriority =
            when (letter) {
                'V' -> LogPriority.LOG_PRIORITY_VERBOSE
                'D' -> LogPriority.LOG_PRIORITY_DEBUG
                'I' -> LogPriority.LOG_PRIORITY_INFO
                'W' -> LogPriority.LOG_PRIORITY_WARN
                'E' -> LogPriority.LOG_PRIORITY_ERROR
                'F' -> LogPriority.LOG_PRIORITY_FATAL
                else -> LogPriority.LOG_PRIORITY_UNSPECIFIED
            }

        private fun letter(priority: LogPriority): Char =
            when (priority) {
                LogPriority.LOG_PRIORITY_VERBOSE -> 'V'
                LogPriority.LOG_PRIORITY_DEBUG -> 'D'
                LogPriority.LOG_PRIORITY_INFO -> 'I'
                LogPriority.LOG_PRIORITY_WARN -> 'W'
                LogPriority.LOG_PRIORITY_ERROR -> 'E'
                LogPriority.LOG_PRIORITY_FATAL -> 'F'
                else -> '?'
            }

        /**
         * [entry] as one line of text, for `DeviceLogs.logcat`: a log line as
         * `<device epoch s.ms> <uid> <pid> <tid> <P> <tag>: <message>`, anything else after `──`.
         */

        /** `MM-dd HH:mm:ss.SSS` on the daemon host's clock and zone: the time the daemon got an entry. */
        private val STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        /**
         * [entry] as one line, led by the time the daemon got it ([LogEntry.getHostTimeMs], the
         * host's clock, as every other Tap record); a line then has uid, pid, tid, priority, tag
         * and message, like `logcat -v threadtime`. The device's own clock is left out: it can be
         * days away from the host's.
         */
        fun text(entry: LogEntry): String {
            val stamp = STAMP.format(Instant.ofEpochMilli(entry.hostTimeMs))
            return when (entry.kindCase) {
                LogEntry.KindCase.LINE -> {
                    with(entry.line) {
                        "%s %5s %5d %5d %c %s: %s".format(
                            stamp,
                            if (hasUid()) uid.toString() else "?",
                            pid,
                            tid,
                            letter(priority),
                            tag,
                            message,
                        )
                    }
                }

                LogEntry.KindCase.MARKER -> {
                    "$stamp ── marker ${entry.marker}"
                }

                LogEntry.KindCase.GAP -> {
                    "$stamp ── gap ${entry.gap}"
                }

                LogEntry.KindCase.ACTION -> {
                    with(entry.action) {
                        buildString {
                            append(stamp).append(" ── ").append(operation)
                            if (hasTarget()) append("  ").append(target)
                            if (finished) append("  → ").append(outcome)
                            if (hasDurationMs()) append("  ").append(durationMs).append(" ms")
                        }
                    }
                }

                else -> {
                    stamp
                }
            }
        }
    }
}

/** How much log text an attachment keeps; older entries are dropped. */
const val LOG_BUFFER_BYTES = 16L * 1024 * 1024

/** Sampling interval and maximum age for package attribution saved with a line. */
const val PACKAGES_REFRESH_NS = 5_000_000_000L

/** UID within one Android user, including shared system UIDs when the package map names them. */
internal fun appId(uid: Int): Int = Math.floorMod(uid, 100_000)

/** Queued entries per follower before it is ended as too slow. */
const val LOG_READER_CAPACITY = 8_192

/** The tag of the line the daemon logs on a device to learn that its capture caught up ([DeviceLog.barrier]). */
const val BARRIER_TAG = "TapLogBarrier"

/** How long a history recording's stop waits for each device's capture to catch up. */
const val LOG_CATCH_UP_MS = 5_000L

/** Most entries in one `ReadLogResponse`. */
const val LOG_BATCH_ENTRIES = 1_000
