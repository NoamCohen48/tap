package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.DeleteLogRecordingRequest
import io.github.noamcohen48.tap.api.v1.ListLogRecordingsRequest
import io.github.noamcohen48.tap.api.v1.ListRetainedLogsRequest
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.StartLogRecordingRequest
import io.github.noamcohen48.tap.api.v1.StopLogRecordingRequest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Original attachment identity for ordinary logs retained after release/restart. */
data class RetainedLog(
    val connectionId: String,
    val attachedDeviceId: String,
    val serial: String,
    val lastCursor: Long,
    val closed: Boolean,
    val captureError: String,
    val clockId: String = "",
    val events: Boolean = false,
    val retainedBytes: Long = 0,
)

/** One protected original attachment range; [throughCursor] is null while open. */
data class LogRecordingRange(
    val attachedDeviceId: String,
    val serial: String,
    val afterCursor: Long,
    val throughCursor: Long?,
    val events: Boolean = false,
)

/** Named protected log evidence. [complete] means final capture, never a passing test. */
data class LogRecording(
    val name: String,
    val connectionId: String,
    val startedAtMs: Long,
    val stoppedAtMs: Long?,
    val ranges: List<LogRecordingRange>,
    val complete: Boolean,
    val captureError: String,
    val retainedBytes: Long = 0,
    val agentStepsExpected: Boolean = false,
)

private fun io.github.noamcohen48.tap.api.v1.LogRecording.recordingModel() =
    LogRecording(
        name,
        clientConnectionId,
        startedAtMs,
        if (hasStoppedAtMs()) stoppedAtMs else null,
        rangesList.map {
            LogRecordingRange(
                it.attachedDeviceId,
                it.serial,
                it.afterCursor,
                if (it.hasThroughCursor()) it.throughCursor else null,
                it.events,
            )
        },
        complete,
        captureError,
        retainedBytes,
        agentStepsExpected,
    )

/** Daemon-owned protected ranges ([TapClient.logRecordings]); controls do not require a live original connection. */
class LogRecordings internal constructor(
    private val client: TapClient,
) {
    /** Discover retained ordinary logs; an empty connection id lists all of this daemon's archives. */
    suspend fun archives(
        connectionId: String = "",
        includeEvents: Boolean = false,
    ): List<RetainedLog> =
        mapped {
            client.deviceLog
                .withDeadlineAfter(
                    30,
                    TimeUnit.SECONDS,
                ).listRetainedLogs(ListRetainedLogsRequest.newBuilder().setClientConnectionId(connectionId).build())
                .logsList
                .filter { includeEvents || !it.events }
                .map {
                    RetainedLog(
                        it.clientConnectionId,
                        it.attachedDeviceId,
                        it.serial,
                        it.lastCursor,
                        it.closed,
                        it.captureError,
                        it.clockId,
                        it.events,
                        it.retainedBytes,
                    )
                }
        }

    /** Protect the connection's current device set from now on, without restarting logcat. */
    suspend fun start(
        connection: TapConnection,
        name: String,
    ): LogRecording =
        mapped {
            client.deviceLog
                .withDeadlineAfter(
                    30,
                    TimeUnit.SECONDS,
                ).startLogRecording(
                    StartLogRecordingRequest
                        .newBuilder()
                        .setClientConnectionId(connection.id)
                        .setName(name)
                        .build(),
                ).recording
                .recordingModel()
        }

    /** Finalize a range; repeated stop returns the same end boundary. */
    suspend fun stop(name: String): LogRecording =
        mapped {
            client.deviceLog
                .withDeadlineAfter(
                    30,
                    TimeUnit.SECONDS,
                ).stopLogRecording(StopLogRecordingRequest.newBuilder().setName(name).build())
                .recording
                .recordingModel()
        }

    /** List protected ranges, including open and incomplete captures. */
    suspend fun list(): List<LogRecording> =
        mapped {
            client.deviceLog
                .withDeadlineAfter(
                    30,
                    TimeUnit.SECONDS,
                ).listLogRecordings(ListLogRecordingsRequest.getDefaultInstance())
                .recordingsList
                .map {
                    it.recordingModel()
                }
        }

    /** Explicitly remove protection from a stopped range; rolling eviction may then remove its logs. */
    suspend fun delete(name: String): Unit =
        mapped {
            client.deviceLog
                .withDeadlineAfter(
                    30,
                    TimeUnit.SECONDS,
                ).deleteLogRecording(DeleteLogRecordingRequest.newBuilder().setName(name).build())
            Unit
        }

    /** Read a protected range by serial, even after release/restart. Package filters need a live attachment. */
    suspend fun read(
        name: String,
        filter: LogFilter = LogFilter(),
        timeout: Duration = 30.seconds,
    ): Map<String, LogEntries> {
        val recording = list().firstOrNull { it.name == name } ?: throw IllegalArgumentException("No recording named '$name'")
        return recording.ranges.filterNot { it.events }.associate { range ->
            range.serial to
                readRequest(
                    ReadLogRequest
                        .newBuilder()
                        .setClientConnectionId(
                            recording.connectionId,
                        ).setAttachedDeviceId(range.attachedDeviceId)
                        .setRecording(name),
                    filter,
                    timeout,
                )
        }
    }

    /** Read ordinary retained evidence without a live original connection. */
    suspend fun readArchive(
        archive: RetainedLog,
        filter: LogFilter = LogFilter(),
        timeout: Duration = 30.seconds,
    ): LogEntries =
        readRequest(
            ReadLogRequest.newBuilder().setClientConnectionId(archive.connectionId).setAttachedDeviceId(archive.attachedDeviceId),
            filter,
            timeout,
        )

    /** Export a named range without stopping it; durable events are always included, device logs optionally. */
    suspend fun export(
        name: String,
        logs: Boolean = false,
    ): HistoryExport {
        val recording = list().firstOrNull { it.name == name } ?: throw IllegalArgumentException("No recording named '$name'")
        val sources =
            recording.ranges.filter { it.events || logs }.map { range ->
                RetainedLog(
                    recording.connectionId,
                    range.attachedDeviceId,
                    range.serial,
                    range.throughCursor ?: range.afterCursor,
                    recording.stoppedAtMs != null,
                    "",
                    events = range.events,
                ) to name
            }
        return exportSources(recording.connectionId, recording, sources, logs)
    }

    /**
     * Export original-connection activity after release/restart, including independent crashes/ANRs.
     * [fromTime] is inclusive and [untilTime] exclusive, on the host receipt clock for every entry
     * kind. [logs] only controls device-log inclusion, never death diagnostics. Missing evidence
     * remains explicit; this does not invoke a CLI, recreate a session or infer query results.
     */
    suspend fun history(
        connectionId: String,
        logs: Boolean = false,
        fromTime: java.time.Instant? = null,
        untilTime: java.time.Instant? = null,
    ): HistoryExport {
        require(connectionId.isNotBlank()) { "history needs the original connection id" }
        require(fromTime == null || untilTime == null || !untilTime.isBefore(fromTime)) { "untilTime must not precede fromTime" }
        val sources = archives(connectionId, includeEvents = true).filter { it.events || logs }.map { it to null }
        return exportSources(connectionId, null, sources, logs, fromTime, untilTime)
    }

    private suspend fun exportSources(
        connectionId: String,
        recording: LogRecording?,
        sources: List<Pair<RetainedLog, String?>>,
        logsIncluded: Boolean,
        fromTime: java.time.Instant? = null,
        untilTime: java.time.Instant? = null,
    ): HistoryExport {
        val captured = mutableListOf<HistoryCapture>()
        val logs = linkedMapOf<String, LogEntries>()
        val logArchives = linkedMapOf<String, RetainedLog>()
        var complete = sources.isNotEmpty() && (recording?.complete ?: true)
        for ((archive, name) in sources) {
            val request = ReadLogRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(archive.attachedDeviceId)
            name?.let { request.recording = it }
            val received = readRequest(request, LogFilter(), 30.seconds)
            val entries =
                received.copy(
                    entries =
                        received.entries.filter {
                            (fromTime == null || !it.at.isBefore(fromTime)) && (untilTime == null || it.at.isBefore(untilTime))
                        },
                )
            if (archive.events) {
                captured += HistoryCapture(archive, entries)
            } else {
                val key = if (archive.serial in logs) "${archive.serial}:${archive.attachedDeviceId}" else archive.serial
                logs[key] = entries
                logArchives[key] = archive
            }
            if (!archive.closed || entries.dropped > 0 || entries.captureError.isNotEmpty() ||
                entries.any { it.gap != null || it.event?.indeterminate == true }
            ) {
                complete = false
            }
        }
        val events = captured.flatMap { it.entries.mapNotNull { entry -> entry.event } }.sortedWith(compareBy({ it.atEpochMs }, { it.seq }))
        val evidenceComplete = complete && captured.isNotEmpty()
        return HistoryExport(
            connectionId,
            recording,
            events,
            captured,
            logs,
            logsIncluded,
            evidenceComplete && recording?.agentStepsExpected != true,
            logArchives,
            evidenceComplete,
        )
    }

    private suspend fun readRequest(
        request: ReadLogRequest.Builder,
        filter: LogFilter,
        timeout: Duration,
    ): LogEntries =
        mapped {
            require(filter.packages.isEmpty()) { "retained logs support tag/priority/grep filters, not package names" }
            request.addAllTags(filter.tags).setExcludeActions(!filter.actions)
            filter.grep?.let { request.messageRegex = it }
            filter.step?.let { request.step = it }
            filter.minPriority?.let {
                request.minPriority =
                    io.github.noamcohen48.tap.api.v1.LogPriority
                        .forNumber(it.ordinal + 1)
            }
            val entries = mutableListOf<LogEntry>()
            var dropped = 0L
            var captureError = ""
            var clockId = ""
            client.deviceLog.withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS).readLog(request.build()).collect { response ->
                dropped += response.dropped
                if (response.captureError.isNotEmpty()) captureError = response.captureError
                if (response.clockId.isNotEmpty()) clockId = response.clockId
                response.entriesList.mapTo(entries) { it.toModel() }
            }
            LogEntries(entries, dropped, captureError, clockId)
        }
}
