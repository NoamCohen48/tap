package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogRecording
import io.github.noamcohen48.tap.api.v1.LogRecordingRange
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** A named retained range that does not exist. */
internal class UnknownLogRecordingException(
    name: String,
) : NoSuchElementException("No log recording named '$name'")

/** A valid recording request that cannot be satisfied in the current retention state. */
internal class LogRecordingStateException(
    message: String,
) : DaemonPreconditionException(message)

/** Redacted, private segmented protobuf logs. All index and retention transitions share one lock. */
internal class RetainedLogs(
    private val stateDir: Path,
    private val now: () -> Long = System::currentTimeMillis,
    private val freeBytes: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    private val warning: (String) -> Unit = {},
) {
    private val root = stateDir.resolve("history-logs")
    private val recordingsDir = root.resolve("recordings")
    private val archives = linkedMapOf<String, Archive>()
    private val recordings = linkedMapOf<String, LogRecording>()
    private var policy = LoggingPolicy.read(stateDir)
    private var invalidPolicyWarned = false
    private val readPins = mutableMapOf<Path, Int>()

    internal class Archive(
        val id: String,
        val owner: String,
        val serial: String,
        val directory: Path,
        var cursor: Long = 0,
        var closed: Boolean = false,
        var error: String = "",
        var errorAt: Long = Long.MAX_VALUE,
        val clockId: String = io.github.noamcohen48.tap.host.MediaClock.id,
        var lastAt: Long = 0,
        val persisted: Boolean = true,
        val events: Boolean = false,
    ) {
        val segments = mutableListOf<Segment>()

        /** Segments written since the last [sync]. */
        val unsynced = mutableSetOf<Path>()
    }

    /** Archives with entries or metadata not yet synced to disk; see [sync]. */
    private val dirty = linkedSetOf<Archive>()
    private var flusher: Thread? = null

    internal data class Segment(
        val path: Path,
        val first: Long,
        var last: Long,
        var at: Long,
        var bytes: Long,
        val firstAt: Long,
        val gaps: MutableList<Long> = mutableListOf(),
    )

    init {
        privateDirectory(root)
        privateDirectory(recordingsDir)
        purgePending(recordingsDir)
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("[0-9a-f-]{36}")) }.sorted().forEach { directory ->
                privateDirectory(directory)
                purgePending(directory)
                if (!Files.exists(directory.resolve("archive.properties"), NOFOLLOW_LINKS)) {
                    Files.list(directory).use { require(!it.findAny().isPresent) { "retained archive has no metadata" } }
                    Files.delete(directory)
                    return@forEach
                }
                val p = load(directory.resolve("archive.properties"))
                val archive =
                    Archive(
                        directory.fileName.toString(),
                        p.getProperty("owner"),
                        p.getProperty("serial"),
                        directory,
                        p.getProperty("cursor").toLong(),
                        p.getProperty("closed").toBooleanStrict(),
                        p.getProperty("error", ""),
                        p.getProperty("error_at", Long.MAX_VALUE.toString()).toLong(),
                        p.getProperty("clock_id", ""),
                        p.getProperty("last_at", "0").toLong(),
                        events = p.getProperty("events", "false").toBooleanStrict(),
                    )
                if (!archive.closed) {
                    archive.closed = true
                    if (archive.error.isEmpty()) {
                        archive.error = "daemon ended before capture was finalized"
                        archive.errorAt = archive.cursor + 1
                    }
                }
                Files.list(directory).use { files ->
                    files.filter { it.fileName.toString().endsWith(".pb") }.sorted().forEach { path ->
                        privateFile(path)
                        try {
                            val entries =
                                readSegment(path) { next ->
                                    archive.error = "incomplete log segment after interrupted write"
                                    archive.errorAt = next
                                }
                            if (entries.isNotEmpty()) {
                                archive.segments +=
                                    Segment(
                                        path,
                                        entries.first().cursor,
                                        entries.last().cursor,
                                        entries.last().hostTimeMs,
                                        Files.size(path),
                                        entries.first().hostTimeMs,
                                        entries.filter { it.hasGap() }.mapTo(mutableListOf()) { it.cursor },
                                    )
                                archive.cursor = maxOf(archive.cursor, entries.last().cursor)
                            } else {
                                Files.delete(path) // A torn first frame leaves no committed entries to retain.
                            }
                        } catch (error: Exception) {
                            throw IllegalStateException("cannot recover retained log segment ${path.fileName}", error)
                        }
                    }
                }
                archives[archive.id] = archive
                save(archive)
            }
        }
        Files.list(recordingsDir).use { files ->
            files.filter { it.fileName.toString().endsWith(".pb") }.forEach { path ->
                privateFile(path)
                val recording = Files.newInputStream(path).use(LogRecording::parseFrom)
                validName(recording.name)
                require(path.fileName.toString() == "${recording.name}.pb") { "recording name mismatch" }
                val recovered = if (!recording.hasStoppedAtMs()) finalize(recording, "daemon restarted during recording") else recording
                recordings[recording.name] = recovered
                save(recovered)
            }
        }
        evict()
    }

    @Synchronized
    fun open(
        id: String,
        owner: String,
        serial: String,
        events: Boolean = false,
    ): Archive {
        require(id.matches(Regex("[0-9a-f-]{36}")) && id !in archives)
        refreshPolicy()
        evict()
        val directory = root.resolve(id)
        if (diskBytes() + metadataReserve() >= policy.hardBytes || freeBytes(root) < policy.minFreeBytes + metadataReserve()) {
            return Archive(
                id,
                owner,
                serial,
                directory,
                error = "log capture unavailable at hard storage/free-space limit",
                errorAt = 0,
                lastAt = now(),
                persisted = false,
                events = events,
            ).also {
                archives[id] = it
                warning("$serial: ${it.error}")
            }
        }
        privateDirectory(directory)
        return Archive(id, owner, serial, directory, lastAt = now(), events = events).also {
            save(it)
            archives[id] = it
        }
    }

    /** Also called while capture is idle, so age retention is not dependent on a reader. */
    @Synchronized
    fun maintain() {
        refreshPolicy()
        evict()
    }

    @Synchronized
    fun archives(owner: String): List<Archive> {
        refreshPolicy()
        evict()
        return archives.values.filter { owner.isEmpty() || it.owner == owner }
    }

    @Synchronized
    fun archive(
        id: String,
        owner: String,
    ): Archive {
        val archive = archives[id] ?: throw UnknownAttachedDeviceException(id)
        if (archive.owner != owner) throw NotOwnerException(id, owner)
        return archive
    }

    /** Event receipts and interruption boundaries share one atomic cursor allocator. */
    @Synchronized
    fun appendDeath(
        archive: Archive,
        death: io.github.noamcohen48.tap.api.v1.RecordedAppDeath,
    ) {
        append(
            archive,
            LogEntry
                .newBuilder()
                .setCursor(archive.cursor + 1)
                .setHostTimeMs(now())
                .setHostMonotonicNs(
                    io.github.noamcohen48.tap.host.MediaClock
                        .nowNs(),
                ).setDeath(death)
                .build(),
        )
    }

    @Synchronized
    fun appendEvent(
        archive: Archive,
        event: io.github.noamcohen48.tap.api.v1.LoggedEvent,
    ) {
        append(
            archive,
            LogEntry
                .newBuilder()
                .setCursor(archive.cursor + 1)
                .setHostTimeMs(now())
                .setHostMonotonicNs(
                    io.github.noamcohen48.tap.host.MediaClock
                        .nowNs(),
                ).setEvent(event)
                .build(),
        )
    }

    @Synchronized
    fun interruption(
        archive: Archive,
        reason: String,
    ) {
        append(
            archive,
            LogEntry
                .newBuilder()
                .setCursor(archive.cursor + 1)
                .setHostTimeMs(now())
                .setGap(reason)
                .build(),
        )
    }

    @Synchronized
    fun append(
        archive: Archive,
        entry: LogEntry,
    ) {
        archive.cursor = maxOf(archive.cursor, entry.cursor)
        archive.lastAt = now()
        if (archive.closed) {
            fail(archive, "capture received an entry after finalization")
            return
        }
        if (archive.error.isNotEmpty()) return
        try {
            refreshPolicy()
            evict()
            val encoded = entry.toByteArray()
            if (encoded.size > MAX_ENTRY_BYTES || diskBytes() + encoded.size + 4 + metadataReserve() > policy.hardBytes ||
                freeBytes(root) < policy.minFreeBytes + encoded.size + 4 + metadataReserve()
            ) {
                fail(archive, "log capture stopped at hard storage/free-space limit")
                return
            }
            var segment = archive.segments.lastOrNull()
            if (segment == null || segment.bytes >= SEGMENT_BYTES ||
                entry.hostTimeMs - segment.firstAt >= minOf(policy.maxAgeMs, 60_000L)
            ) {
                val path = archive.directory.resolve("%020d.pb".format(entry.cursor))
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                segment = Segment(path, entry.cursor, entry.cursor, entry.hostTimeMs, 0, entry.hostTimeMs)
                archive.segments += segment
            }
            privateFile(segment.path)
            // One write per frame, so a concurrent reader sees whole frames. Syncing is batched
            // ([sync]): a sync per line held the lock for milliseconds each, and a busy device's
            // log then fell seconds behind.
            val frame =
                java.nio.ByteBuffer
                    .allocate(4 + encoded.size)
                    .putInt(encoded.size)
                    .put(encoded)
                    .array()
            java.io.FileOutputStream(segment.path.toFile(), true).use { it.write(frame) }
            segment.bytes += frame.size
            segment.last = entry.cursor
            segment.at = entry.hostTimeMs
            if (entry.hasGap()) segment.gaps += entry.cursor
            archive.unsynced.add(segment.path)
            markDirty(archive)
            evict()
        } catch (_: Exception) {
            fail(archive, "durable log storage failed; retained evidence is incomplete")
        }
    }

    @Synchronized
    fun close(
        archive: Archive,
        reason: String = "",
    ) {
        if (reason.isNotEmpty()) {
            append(
                archive,
                LogEntry
                    .newBuilder()
                    .setCursor(archive.cursor + 1)
                    .setHostTimeMs(now())
                    .setGap(reason)
                    .build(),
            )
            fail(archive, reason)
        }
        archive.closed = true
        archive.lastAt = now()
        try {
            forceSegments(archive)
            save(archive)
            dirty -= archive
        } catch (_: Exception) {
            fail(archive, "could not finalize durable log capture")
        }
    }

    /**
     * Makes what [archive] received so far durable: the segments written since the last sync,
     * then its metadata. Entries are synced at least every [SYNC_INTERVAL_MS], and at once where
     * a reply depends on them: a marker, a capture's close, a recording's end.
     */
    @Synchronized
    fun sync(archive: Archive) {
        if (!dirty.remove(archive)) return
        try {
            forceSegments(archive)
            save(archive)
        } catch (_: Exception) {
            fail(archive, "durable log storage failed; retained evidence is incomplete")
        }
    }

    @Synchronized
    fun syncAll() = dirty.toList().forEach(::sync)

    private fun forceSegments(archive: Archive) {
        archive.unsynced.forEach { path ->
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                java.nio.channels.FileChannel
                    .open(path, java.nio.file.StandardOpenOption.WRITE)
                    .use { it.force(false) }
            }
        }
        archive.unsynced.clear()
    }

    /** Queues [archive] for the background sync, starting the flusher when none runs. */
    private fun markDirty(archive: Archive) {
        if (!archive.persisted) return
        dirty += archive
        if (flusher != null) return
        flusher =
            kotlin.concurrent.thread(isDaemon = true, name = "tap-history-sync") {
                while (true) {
                    try {
                        Thread.sleep(SYNC_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                    synchronized(this@RetainedLogs) {
                        syncAll()
                        if (dirty.isEmpty()) {
                            flusher = null
                            return@thread
                        }
                    }
                }
            }
    }

    /** Apply new rules to previously persisted entries; no secret rules themselves are persisted. */
    @Synchronized
    fun redact(
        archive: Archive,
        redact: (LogEntry) -> LogEntry,
    ) {
        try {
            archive.segments.forEach { segment ->
                val original = readSegment(segment.path)
                val entries = original.map(redact)
                if (entries != original) rewrite(segment, entries)
            }
        } catch (_: Exception) {
            // Fail closed: the caller must not believe its new rule reached durable evidence.
            fail(archive, "could not apply redaction to retained logs")
            throw DaemonPreconditionException("could not apply redaction to retained logs; inspect private storage before sharing")
        }
    }

    /** Backfill driver lines that arrived before the Execute response supplied their step id. */
    @Synchronized
    fun commandStep(
        archive: Archive,
        requestId: Long,
        step: String,
        sentAt: Long,
    ) {
        if (!archive.persisted || archive.error.isNotEmpty()) return
        try {
            archive.segments.filter { it.at >= sentAt }.forEach { segment ->
                val original = readSegment(segment.path)
                val updated =
                    original.map { entry ->
                        if (entry.hostTimeMs >= sentAt && entry.hasAction() && entry.action.hasRequestId() &&
                            entry.action.requestId == requestId &&
                            !entry.action.hasStep()
                        ) {
                            entry.toBuilder().setAction(entry.action.toBuilder().setStep(step)).build()
                        } else {
                            entry
                        }
                    }
                if (updated != original) rewrite(segment, updated)
            }
        } catch (_: Exception) {
            // The device command may already have mutated: evidence failure must not replace its result.
            fail(archive, "could not preserve log step association")
        }
    }

    private fun rewrite(
        segment: Segment,
        entries: List<LogEntry>,
    ) {
        require(entries.all { it.serializedSize <= MAX_ENTRY_BYTES }) { "rewritten log entry exceeds the storage frame limit" }
        val rewrittenBytes = entries.sumOf { it.serializedSize.toLong() + 4 }
        require(
            diskBytes() + rewrittenBytes + metadataReserve() <= policy.hardBytes &&
                freeBytes(root) >= policy.minFreeBytes + rewrittenBytes + metadataReserve(),
        ) { "insufficient log rewrite space" }
        atomicPrivateFile(segment.path) { out ->
            val data = java.io.DataOutputStream(out)
            entries.forEach { entry ->
                val bytes = entry.toByteArray()
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.flush()
        }
        segment.bytes = Files.size(segment.path)
    }

    fun entries(
        archive: Archive,
        recording: String? = null,
    ): List<LogEntry> = snapshot(archive, recording).use { it.entries().toList() }

    /** Stable cursor boundary and file leases, not a whole-history copy on the JVM heap. */
    inner class Snapshot internal constructor(
        val first: Long,
        val last: Long,
        val error: String,
        private val segments: List<Segment>,
        private val visible: (LogEntry) -> Boolean,
    ) : AutoCloseable {
        private var released = false

        fun allows(entry: LogEntry): Boolean = entry.cursor in first..last && visible(entry)

        fun entries(reverse: Boolean = false): Sequence<LogEntry> =
            sequence {
                for (segment in if (reverse) segments.asReversed() else segments) {
                    val entries = readSegment(segment.path, through = segment.last)
                    yieldAll((if (reverse) entries.asReversed() else entries).asSequence().filter(::allows))
                }
            }

        override fun close() {
            synchronized(this@RetainedLogs) {
                if (released) return
                released = true
                segments.forEach { segment ->
                    val remaining = readPins.getValue(segment.path) - 1
                    if (remaining == 0) readPins.remove(segment.path) else readPins[segment.path] = remaining
                }
                runCatching { evict() }.onFailure { warning("retained log cleanup failed after read") }
            }
        }
    }

    @Synchronized
    fun snapshot(
        archive: Archive,
        recording: String? = null,
    ): Snapshot {
        if (archive.error ==
            "could not apply redaction to retained logs"
        ) {
            throw DaemonPreconditionException("retained log redaction failed; inspect private storage before sharing")
        }
        refreshPolicy()
        evict()
        val range =
            recording?.let { name ->
                val saved = recordings[name] ?: throw UnknownLogRecordingException(name)
                if (saved.clientConnectionId != archive.owner) throw NotOwnerException(archive.id, saved.clientConnectionId)
                saved.rangesList.firstOrNull { it.attachedDeviceId == archive.id }
                    ?: throw LogRecordingStateException("attachment is not in this recording")
            }
        val first = (range?.afterCursor ?: 0) + 1
        val last = if (range != null && range.hasThroughCursor()) range.throughCursor else archive.cursor
        val segments = archive.segments.filter { it.last >= first && it.first <= last }.map { it.copy(gaps = mutableListOf()) }
        segments.forEach { readPins[it.path] = (readPins[it.path] ?: 0) + 1 }
        val cutoff = now() - policy.maxAgeMs
        val protectedRanges = recordings.values.flatMap { it.rangesList }.filter { it.attachedDeviceId == archive.id }
        return Snapshot(first, last, captureError(archive, recording), segments) { entry ->
            recording != null || entry.hostTimeMs >= cutoff ||
                protectedRanges.any { entry.cursor > it.afterCursor && (!it.hasThroughCursor() || entry.cursor <= it.throughCursor) }
        }
    }

    @Synchronized
    fun start(
        name: String,
        owner: String,
        ids: List<String>,
        agentStepsExpected: Boolean = false,
    ): LogRecording {
        validName(name)
        if (name in recordings) throw LogRecordingStateException("log recording '$name' already exists")
        if (ids.isEmpty()) throw LogRecordingStateException("attach a device before starting a recording")
        refreshPolicy()
        evict()
        if (diskBytes() + metadataReserve() >= policy.hardBytes ||
            freeBytes(root) < policy.minFreeBytes + metadataReserve()
        ) {
            throw LogRecordingStateException("insufficient storage to protect a recording")
        }
        val ranges =
            ids.map { id ->
                val archive = archive(id, owner)
                if (archive.closed ||
                    archive.error.isNotEmpty()
                ) {
                    throw LogRecordingStateException("log capture unavailable for ${archive.serial}: ${archive.error}")
                }
                LogRecordingRange
                    .newBuilder()
                    .setAttachedDeviceId(id)
                    .setSerial(archive.serial)
                    .setEvents(archive.events)
                    .setAfterCursor(archive.cursor)
                    .build()
            }
        return LogRecording
            .newBuilder()
            .setName(
                name,
            ).setClientConnectionId(owner)
            .setAgentStepsExpected(agentStepsExpected)
            .setStartedAtMs(now())
            .addAllRanges(ranges)
            .build()
            .also { saved ->
                val reserve = maxOf(metadataReserve(), saved.serializedSize.toLong() * 3 + METADATA_RESERVE)
                if (diskBytes() + reserve >= policy.hardBytes ||
                    freeBytes(root) < policy.minFreeBytes + reserve
                ) {
                    throw LogRecordingStateException("insufficient storage to protect a recording")
                }
                save(saved)
                recordings[name] = saved
            }
    }

    @Synchronized
    fun stop(name: String): LogRecording {
        val recording = recordings[name] ?: throw UnknownLogRecordingException(name)
        if (recording.hasStoppedAtMs()) return currentStatus(recording)
        return currentStatus(
            finalize(recording).also {
                save(it)
                recordings[name] = it
            },
        )
    }

    @Synchronized
    fun list(): List<LogRecording> = recordings.values.map(::currentStatus)

    private fun currentStatus(recording: LogRecording): LogRecording {
        val errors = rangeErrors(recording)
        val measured = recording.toBuilder().setRetainedBytes(recordingBytes(recording)).build()
        return if (errors.isEmpty()) {
            measured
        } else {
            measured
                .toBuilder()
                .setComplete(false)
                .setCaptureError((listOf(recording.captureError).filter(String::isNotEmpty) + errors).distinct().joinToString("; "))
                .build()
        }
    }

    /** Physical segment bytes backing a range, including shared segment tails. */
    @Synchronized
    fun recordingBytes(recording: LogRecording): Long =
        recording.rangesList
            .flatMap { range ->
                archives[range.attachedDeviceId]?.segments.orEmpty().filter {
                    it.last > range.afterCursor && (!range.hasThroughCursor() || it.first <= range.throughCursor)
                }
            }.distinctBy { it.path }
            .sumOf { it.bytes }

    @Synchronized
    fun delete(name: String) {
        val recording = recordings[name] ?: throw UnknownLogRecordingException(name)
        if (!recording.hasStoppedAtMs()) throw LogRecordingStateException("stop the log recording before deleting it")
        Files.delete(recordingsDir.resolve("$name.pb"))
        recordings.remove(name)
        evict()
    }

    private fun finalize(
        recording: LogRecording,
        reason: String = "",
    ): LogRecording {
        // The range ends at what its sources received, and that must be on disk before the end is.
        recording.rangesList.forEach { range -> archives[range.attachedDeviceId]?.let(::sync) }
        val errors = rangeErrors(recording).toMutableList()
        if (reason.isNotEmpty()) errors += reason
        return recording
            .toBuilder()
            .setStoppedAtMs(
                now(),
            ).setComplete(errors.isEmpty())
            .setCaptureError(errors.distinct().joinToString("; "))
            .clearRanges()
            .addAllRanges(
                recording.rangesList.map { range ->
                    range.toBuilder().setThroughCursor(archives[range.attachedDeviceId]?.cursor ?: range.afterCursor).build()
                },
            ).build()
    }

    private fun protected(
        archive: Archive,
        segment: Segment,
    ): Boolean =
        recordings.values.any { recording ->
            recording.rangesList.any {
                it.attachedDeviceId == archive.id && segment.last > it.afterCursor &&
                    (!it.hasThroughCursor() || segment.first <= it.throughCursor)
            }
        }

    @Synchronized
    fun captureError(
        archive: Archive,
        name: String?,
    ): String {
        if (name == null) return archive.error
        val recording = recordings[name] ?: throw UnknownLogRecordingException(name)
        return (listOf(recording.captureError).filter(String::isNotEmpty) + rangeErrors(recording)).distinct().joinToString("; ")
    }

    private fun rangeErrors(recording: LogRecording): List<String> =
        recording.rangesList.flatMap { range ->
            val archive = archives[range.attachedDeviceId] ?: return@flatMap listOf("recorded attachment metadata is missing")
            val through = if (range.hasThroughCursor()) range.throughCursor else archive.cursor
            val errors = mutableListOf<String>()
            if (archive.error.isNotEmpty() &&
                (archive.errorAt == 0L || (archive.errorAt > range.afterCursor && archive.errorAt <= through))
            ) {
                errors += archive.error
            }
            archive.segments.filter { it.last > range.afterCursor && it.first <= through }.forEach { segment ->
                segment.gaps.filter { it > range.afterCursor && it <= through }.forEach {
                    errors +=
                        "logcat capture interrupted"
                }
            }
            errors
        }

    private fun diskBytes(): Long =
        archives.values.sumOf {
            it.segments.sumOf(Segment::bytes) +
                if (it.persisted) Files.size(it.directory.resolve("archive.properties")) else 0
        } +
            recordings.values.sumOf { Files.size(recordingsDir.resolve("${it.name}.pb")) }

    private fun metadataReserve(): Long = METADATA_RESERVE + (recordings.values.maxOfOrNull { it.serializedSize.toLong() * 2 } ?: 0)

    private fun evict() {
        val cutoff = now() - policy.maxAgeMs
        val candidates =
            archives.values
                .flatMap { archive ->
                    archive.segments.filterNot { protected(archive, it) }.map { archive to it }
                }.sortedBy { it.second.at }
        var total = candidates.sumOf { it.second.bytes }
        for ((archive, segment) in candidates) {
            if ((segment.at >= cutoff && total <= policy.maxBytes) || segment.path in readPins) continue
            Files.deleteIfExists(segment.path)
            archive.segments.remove(segment)
            total -= segment.bytes
        }
        val expired =
            archives.values.filter { archive ->
                archive.closed && archive.segments.isEmpty() && archive.lastAt < cutoff &&
                    recordings.values.none { recording -> recording.rangesList.any { it.attachedDeviceId == archive.id } }
            }
        expired.forEach { archive ->
            Files.deleteIfExists(archive.directory.resolve("archive.properties"))
            Files.deleteIfExists(archive.directory)
            archives.remove(archive.id)
        }
    }

    private fun refreshPolicy() {
        try {
            policy = LoggingPolicy.read(stateDir)
            invalidPolicyWarned = false
        } catch (
            _: Exception,
        ) {
            if (!invalidPolicyWarned) warning("invalid logging policy; retaining last valid limits")
            invalidPolicyWarned = true
        }
    }

    private fun fail(
        archive: Archive,
        reason: String,
    ) {
        archive.error = reason
        archive.errorAt = minOf(archive.errorAt, archive.cursor)
        warning("${archive.serial}: $reason")
        runCatching { save(archive) }
    }

    private fun save(archive: Archive) =
        if (!archive.persisted) {
            Unit
        } else {
            atomicProperties(
                archive.directory.resolve("archive.properties"),
                Properties().apply {
                    setProperty("owner", archive.owner)
                    setProperty("serial", archive.serial)
                    setProperty("cursor", archive.cursor.toString())
                    setProperty("closed", archive.closed.toString())
                    setProperty("error", archive.error)
                    setProperty("error_at", archive.errorAt.toString())
                    setProperty("clock_id", archive.clockId)
                    setProperty("last_at", archive.lastAt.toString())
                    setProperty("events", archive.events.toString())
                },
            )
        }

    private fun save(recording: LogRecording) = atomicPrivateFile(recordingsDir.resolve("${recording.name}.pb")) { recording.writeTo(it) }

    private fun purgePending(directory: Path) {
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".pending-") }.forEach {
                privateFile(it)
                Files.delete(it)
            }
        }
    }

    private fun load(path: Path): Properties {
        privateFile(path)
        return Properties().apply { Files.newInputStream(path).use { load(it) } }
    }

    private fun readSegment(
        path: Path,
        through: Long = Long.MAX_VALUE,
        recover: ((Long) -> Unit)? = null,
    ): List<LogEntry> {
        privateFile(path)
        val result = mutableListOf<LogEntry>()
        java.io.RandomAccessFile(path.toFile(), if (recover == null) "r" else "rw").use { file ->
            var committed = 0L
            while (file.filePointer < file.length()) {
                try {
                    val length = file.readInt()
                    if (length !in 0..MAX_ENTRY_BYTES) throw java.io.EOFException("invalid frame size")
                    val bytes = ByteArray(length)
                    file.readFully(bytes)
                    val entry = LogEntry.parseFrom(bytes)
                    if (entry.cursor <= 0 || (result.lastOrNull()?.cursor ?: 0) >= entry.cursor ||
                        entry.kindCase == LogEntry.KindCase.KIND_NOT_SET
                    ) {
                        throw java.io.IOException("invalid log frame")
                    }
                    result += entry
                    committed = file.filePointer
                    if (result.last().cursor >= through) break
                } catch (invalid: java.io.IOException) {
                    if (recover == null) throw invalid
                    recover(
                        result.lastOrNull()?.cursor?.plus(1) ?: path.fileName
                            .toString()
                            .removeSuffix(".pb")
                            .toLongOrNull() ?: 0,
                    )
                    file.setLength(committed)
                    file.fd.sync()
                    break
                }
            }
        }
        return result
    }

    companion object {
        private const val SEGMENT_BYTES = 256 * 1024L
        private const val MAX_ENTRY_BYTES = 1024 * 1024
        private const val METADATA_RESERVE = 4096L
        private const val SYNC_INTERVAL_MS = 1_000L

        fun validName(name: String) {
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))) {
                "recording name must be 1..80 ASCII letters/digits/dots/dashes/underscores, starting with a letter or digit"
            }
        }
    }
}
