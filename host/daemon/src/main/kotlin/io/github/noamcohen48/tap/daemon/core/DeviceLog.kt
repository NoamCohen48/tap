package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LogAction
import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogLine
import io.github.noamcohen48.tap.api.v1.LogPriority
import io.github.noamcohen48.tap.host.LogcatCapture
import io.github.noamcohen48.tap.host.LogcatLine
import io.github.noamcohen48.tap.protocol.ActionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            LogEntry.KindCase.ACTION -> !excludeActions
            LogEntry.KindCase.LINE -> {
                val line = entry.line
                (uids == null || (line.hasUid() && line.uid in uids)) &&
                    line.priorityValue >= minPriority.number &&
                    (tags.isEmpty() || line.tag in tags) &&
                    (message == null || message.matcher(line.message).find())
            }
            else -> true
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
) : LogcatCapture.Sink {
    val redactor = LogRedactor()

    /** The uid the driver logs as ([io.github.noamcohen48.tap.api.v1.DeviceInfo.getDriverUid]); its `Tap` lines are actions. */
    @Volatile var driverUid: Int? = null

    private val packagesLock = Mutex()

    /** Package names by app id (uid modulo the per-user range), as last read from the device. */
    @Volatile private var packagesByAppId: Map<Int, List<String>> = emptyMap()
    private var packagesReadAtNs: Long? = null

    private val entries = ArrayDeque<LogEntry>()
    private val readers = HashMap<Channel<LogEntry>, LogFilter>()

    /** The step of each driver request whose lines may still arrive, oldest first. */
    private val steps = LinkedHashMap<Long, String>()
    private var bytes = 0L
    private var lastCursor = 0L
    private var dropped = 0L
    private var closed = false

    /** What a read starts from, and for a follower the queue its live entries arrive on. */
    class Subscription(
        val backlog: List<LogEntry>,
        val dropped: Long,
        internal val live: Channel<LogEntry>?,
    )

    override fun line(line: LogcatLine) {
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
    fun mark(name: String): Long = append(LogEntry.newBuilder().setMarker(name))

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
            if (!entry.hasAction() || !entry.action.hasRequestId() || entry.action.requestId != requestId || entry.action.hasStep()) continue
            entries[index] = entry.toBuilder().setAction(entry.action.toBuilder().setStep(step)).build()
            bytes += step.length
            if (entry.action.finished) steps.remove(requestId)
        }
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
        val recorded = entry.setCursor(++lastCursor).setHostTimeMs(System.currentTimeMillis()).build()
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
        return recorded.cursor
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
    ): Subscription {
        val from: Long =
            when (start) {
                LogStart.Oldest, is LogStart.Tail -> 0
                is LogStart.After -> start.cursor
                is LogStart.Marker ->
                    (entries.lastOrNull { it.hasMarker() && it.marker == start.name } ?: throw UnknownLogMarkerException(start.name)).cursor - 1
                LogStart.LastAction -> entries.lastOrNull { it.hasAction() && !it.action.finished }?.let { it.cursor - 1 } ?: 0
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
        val oldestKept = entries.firstOrNull()?.cursor ?: (lastCursor + 1)
        // Entries between the starting point and the oldest one kept are gone.
        val missed = if (start is LogStart.Tail) 0 else (oldestKept - 1 - from).coerceIn(0, dropped)
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

    /**
     * [entries] with each app line's [LogLine.getPackagesList] filled in: the packages installed
     * under its uid. The list is read from the device once and again when a line of an app id it
     * does not name arrives (an app installed since), at most every [PACKAGES_REFRESH_NS]; a read
     * that fails keeps the last list. System processes (uid below 10000) name no package.
     */
    suspend fun withPackages(entries: List<LogEntry>): List<LogEntry> {
        val resolve = installedPackages ?: return entries
        val ids = entries.mapNotNullTo(HashSet()) { if (it.hasLine() && it.line.hasUid()) appId(it.line.uid) else null }
        if (ids.isEmpty()) return entries
        if (!packagesByAppId.keys.containsAll(ids)) refreshPackages(resolve)
        val known = packagesByAppId
        return entries.map { entry ->
            val names = if (entry.hasLine() && entry.line.hasUid()) appId(entry.line.uid)?.let(known::get) else null
            if (names == null) entry else entry.toBuilder().setLine(entry.line.toBuilder().addAllPackages(names)).build()
        }
    }

    private suspend fun refreshPackages(resolve: suspend () -> Map<String, Int>) =
        packagesLock.withLock {
            val now = System.nanoTime()
            val last = packagesReadAtNs
            if (last != null && now - last < PACKAGES_REFRESH_NS) return@withLock
            packagesReadAtNs = now
            try {
                packagesByAppId =
                    resolve()
                        .entries
                        .mapNotNull { (name, uid) -> appId(uid)?.let { it to name } }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, names) -> names.sorted() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }

    /** The device is detached: followers finish what is queued, then complete; the entries are forgotten. */
    @Synchronized
    fun close() {
        closed = true
        readers.keys.forEach { it.close() }
        readers.clear()
        steps.clear()
        entries.clear()
        bytes = 0
    }

    private fun redacted(entry: LogEntry): LogEntry {
        if (!entry.hasLine()) return entry
        val message = redactor.redact(entry.line.message)
        return if (message == entry.line.message) entry else entry.toBuilder().setLine(entry.line.toBuilder().setMessage(message)).build()
    }

    private fun size(entry: LogEntry): Long =
        ENTRY_OVERHEAD_BYTES +
            when (entry.kindCase) {
                LogEntry.KindCase.LINE -> entry.line.tag.length + entry.line.message.length
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
        fun text(entry: LogEntry): String =
            when (entry.kindCase) {
                LogEntry.KindCase.LINE ->
                    with(entry.line) {
                        "%d.%03d %5s %5d %5d %c %s: %s".format(
                            deviceTimeMs / 1_000, deviceTimeMs % 1_000, if (hasUid()) uid.toString() else "?", pid, tid, letter(priority), tag, message,
                        )
                    }
                LogEntry.KindCase.MARKER -> "── marker ${entry.marker}"
                LogEntry.KindCase.GAP -> "── gap ${entry.gap}"
                LogEntry.KindCase.ACTION ->
                    with(entry.action) {
                        buildString {
                            append("── ").append(operation)
                            if (hasTarget()) append("  ").append(target)
                            if (finished) append("  → ").append(outcome)
                            if (hasDurationMs()) append("  ").append(durationMs).append(" ms")
                        }
                    }
                else -> ""
            }
    }
}

/** How much log text an attachment keeps; older entries are dropped. */
const val LOG_BUFFER_BYTES = 16L * 1024 * 1024

/** The shortest time between two reads of the installed packages ([DeviceLog.withPackages]). */
const val PACKAGES_REFRESH_NS = 5_000_000_000L

/** An app's id across users (`UserHandle.getAppId`), for an app uid (10000–19999); null otherwise. */
internal fun appId(uid: Int): Int? = (uid % 100_000).takeIf { it in 10_000..19_999 }

/** Queued entries per follower before it is ended as too slow. */
const val LOG_READER_CAPACITY = 8_192

/** Most entries in one `ReadLogResponse`. */
const val LOG_BATCH_ENTRIES = 1_000
