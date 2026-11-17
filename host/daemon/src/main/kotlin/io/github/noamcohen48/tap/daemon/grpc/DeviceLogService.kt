package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.DeleteLogRecordingRequest
import io.github.noamcohen48.tap.api.v1.DeleteLogRecordingResponse
import io.github.noamcohen48.tap.api.v1.DeviceLogServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.ListLogRecordingsRequest
import io.github.noamcohen48.tap.api.v1.ListLogRecordingsResponse
import io.github.noamcohen48.tap.api.v1.ListRetainedLogsRequest
import io.github.noamcohen48.tap.api.v1.ListRetainedLogsResponse
import io.github.noamcohen48.tap.api.v1.MarkLogRequest
import io.github.noamcohen48.tap.api.v1.MarkLogResponse
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.ReadLogResponse
import io.github.noamcohen48.tap.api.v1.RedactLogRequest
import io.github.noamcohen48.tap.api.v1.RedactLogResponse
import io.github.noamcohen48.tap.api.v1.RetainedLog
import io.github.noamcohen48.tap.api.v1.StartLogRecordingRequest
import io.github.noamcohen48.tap.api.v1.StartLogRecordingResponse
import io.github.noamcohen48.tap.api.v1.StopLogRecordingRequest
import io.github.noamcohen48.tap.api.v1.StopLogRecordingResponse
import io.github.noamcohen48.tap.daemon.core.DeviceLog
import io.github.noamcohen48.tap.daemon.core.InvalidRedactionException
import io.github.noamcohen48.tap.daemon.core.LOG_BATCH_ENTRIES
import io.github.noamcohen48.tap.daemon.core.LogCaptureOffException
import io.github.noamcohen48.tap.daemon.core.LogFilter
import io.github.noamcohen48.tap.daemon.core.LogStart
import io.github.noamcohen48.tap.daemon.core.RetainedLogs
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.daemon.core.UnknownAttachedDeviceException
import io.github.noamcohen48.tap.daemon.core.UnknownClientConnectionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The log of an attached device (`device_log.proto`): a thin, owner-checked adapter over the
 * attachment's [DeviceLog]. Reading the log needs no working driver, so a session whose driver
 * was lost can still be read for evidence.
 */
class DeviceLogService(
    private val daemon: TapDaemon,
) : DeviceLogServiceGrpcKt.DeviceLogServiceCoroutineImplBase() {
    override fun readLog(request: ReadLogRequest): Flow<ReadLogResponse> =
        flow {
            val start =
                when (request.fromCase) {
                    ReadLogRequest.FromCase.AFTER_CURSOR -> {
                        argument(request.afterCursor >= 0) { "after_cursor exceeds the supported cursor range" }
                        LogStart.After(request.afterCursor)
                    }

                    ReadLogRequest.FromCase.MARKER -> {
                        LogStart.Marker(request.marker)
                    }

                    ReadLogRequest.FromCase.TAIL -> {
                        argument(request.tail in 1..MAX_TAIL) { "tail must be 1 to $MAX_TAIL" }
                        LogStart.Tail(request.tail)
                    }

                    ReadLogRequest.FromCase.LAST_ACTION -> {
                        if (request.lastAction) LogStart.LastAction else LogStart.Oldest
                    }

                    ReadLogRequest.FromCase.FROM_NOT_SET, null -> {
                        LogStart.Oldest
                    }
                }
            val message =
                if (!request.hasMessageRegex()) {
                    null
                } else {
                    argument(request.messageRegex.length in 1..MAX_REGEX_CHARS) { "message_regex must be 1 to $MAX_REGEX_CHARS characters" }
                    try {
                        Pattern.compile(request.messageRegex)
                    } catch (error: PatternSyntaxException) {
                        throw InvalidArgumentException("message_regex is not a regular expression: ${error.description}")
                    }
                }
            if (request.hasRecording()) recordingName(request.recording)
            val attached =
                try {
                    daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId, requireDriver = false)
                } catch (_: UnknownAttachedDeviceException) {
                    null
                } catch (_: UnknownClientConnectionException) {
                    null
                }
            if (attached != null && attached.logs == null) throw LogCaptureOffException(attached.deviceSession.serial)
            val archive = daemon.retainedLogs.archive(request.attachedDeviceId, request.clientConnectionId)
            argument(!request.follow || (attached != null && !request.hasRecording())) { "retained/recording reads cannot follow" }
            val log = attached?.logs ?: DeviceLog(retained = daemon.retainedLogs, archive = archive)
            argument(attached != null || request.packagesCount == 0) {
                "package-name filtering needs a live attachment; use tags/priority/grep on retained logs"
            }
            val uids =
                if (request.packagesCount == 0) {
                    null
                } else {
                    val installed = daemon.config.adb.packageUids(archive.serial)
                    request.packagesList
                        .map { name ->
                            argumentNotNull(installed[name]) { "$name is not installed on ${archive.serial}" }
                        }.toSet()
                }
            val step = if (request.hasStep()) request.step else null
            argument(step == null || !request.follow) { "step cannot be combined with follow" }
            argument(step == null || StepInterceptor.valid(step)) { "step must be 1 to ${StepInterceptor.MAX_CHARS} of A-Z a-z 0-9 . _ -" }
            val filter = LogFilter(uids, request.minPriority, request.tagsList.toSet(), message, request.excludeActions, step)
            if (!request.follow) {
                val snapshot = daemon.retainedLogs.snapshot(archive, if (request.hasRecording()) request.recording else null)
                try {
                    retainedLog(snapshot, log, start, filter, archive.clockId)
                } finally {
                    snapshot.close()
                }
                return@flow
            }
            val subscription = log.read(start, filter, request.follow, if (request.hasRecording()) request.recording else null)
            val batches = subscription.backlog.chunked(LOG_BATCH_ENTRIES)
            // Always a first response, so a caller learns `dropped` and that the read began.
            emit(
                ReadLogResponse
                    .newBuilder()
                    .addAllEntries(
                        log.withPackages(batches.firstOrNull().orEmpty()),
                    ).setDropped(
                        subscription.dropped,
                    ).setCaptureError(
                        daemon.retainedLogs.captureError(archive, if (request.hasRecording()) request.recording else null),
                    ).setClockId(archive.clockId)
                    .build(),
            )
            batches.drop(1).forEach { emit(ReadLogResponse.newBuilder().addAllEntries(log.withPackages(it)).build()) }
            log.live(subscription).collect { emit(ReadLogResponse.newBuilder().addAllEntries(log.withPackages(it)).build()) }
        }.catch { error ->
            throw if (error is CancellationException) error else error.toStatus()
        }

    override suspend fun listRetainedLogs(request: ListRetainedLogsRequest): ListRetainedLogsResponse =
        reply {
            ListRetainedLogsResponse
                .newBuilder()
                .addAllLogs(
                    daemon.retainedLogs.archives(request.clientConnectionId).map { archive ->
                        RetainedLog
                            .newBuilder()
                            .setClientConnectionId(archive.owner)
                            .setAttachedDeviceId(archive.id)
                            .setSerial(archive.serial)
                            .setLastCursor(archive.cursor)
                            .setClosed(archive.closed)
                            .setCaptureError(archive.error)
                            .setClockId(archive.clockId)
                            .setEvents(archive.events)
                            .setRetainedBytes(archive.segments.sumOf { it.bytes })
                            .build()
                    },
                ).build()
        }

    override suspend fun startLogRecording(request: StartLogRecordingRequest): StartLogRecordingResponse =
        reply {
            StartLogRecordingResponse
                .newBuilder()
                .setRecording(
                    daemon.startLogRecording(request.clientConnectionId, recordingName(request.name), request.agentStepsExpected),
                ).build()
        }

    override suspend fun stopLogRecording(request: StopLogRecordingRequest): StopLogRecordingResponse =
        reply {
            StopLogRecordingResponse.newBuilder().setRecording(daemon.stopLogRecording(recordingName(request.name))).build()
        }

    override suspend fun listLogRecordings(request: ListLogRecordingsRequest): ListLogRecordingsResponse =
        reply {
            ListLogRecordingsResponse.newBuilder().addAllRecordings(daemon.retainedLogs.list()).build()
        }

    override suspend fun deleteLogRecording(request: DeleteLogRecordingRequest): DeleteLogRecordingResponse =
        reply {
            daemon.retainedLogs.delete(recordingName(request.name))
            DeleteLogRecordingResponse.getDefaultInstance()
        }

    override suspend fun markLog(request: MarkLogRequest): MarkLogResponse =
        reply {
            argument(request.name.length in 1..MAX_MARKER_CHARS && request.name.none { it.isISOControl() }) {
                "name must be 1 to $MAX_MARKER_CHARS characters with no line breaks"
            }
            MarkLogResponse.newBuilder().setCursor(log(request.attachedDeviceId, request.clientConnectionId).mark(request.name)).build()
        }

    override suspend fun redactLog(request: RedactLogRequest): RedactLogResponse =
        reply {
            argument(request.patternsCount > 0) { "patterns is required" }
            val log = log(request.attachedDeviceId, request.clientConnectionId)
            try {
                log.redactor.add(request.patternsList)
            } catch (invalid: InvalidRedactionException) {
                throw InvalidArgumentException(invalid.message ?: "invalid redaction pattern")
            }
            RedactLogResponse.getDefaultInstance()
        }

    private suspend fun log(
        attachedDeviceId: String,
        clientConnectionId: String,
    ): DeviceLog {
        val attached = daemon.attachedDevice(attachedDeviceId, clientConnectionId, requireDriver = false)
        return attached.logs ?: throw LogCaptureOffException(attached.deviceSession.serial)
    }

    private fun recordingName(value: String): String {
        try {
            RetainedLogs.validName(value)
        } catch (invalid: IllegalArgumentException) {
            throw InvalidArgumentException(invalid.message ?: "invalid recording name")
        }
        return value
    }

    private companion object {
        const val MAX_TAIL = 100_000
        const val MAX_REGEX_CHARS = 512
        const val MAX_MARKER_CHARS = 200
    }
}
