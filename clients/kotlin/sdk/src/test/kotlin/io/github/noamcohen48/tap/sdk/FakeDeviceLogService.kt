package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.DeviceLogServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.MarkLogRequest
import io.github.noamcohen48.tap.api.v1.MarkLogResponse
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.ReadLogResponse
import io.github.noamcohen48.tap.api.v1.RedactLogRequest
import io.github.noamcohen48.tap.api.v1.RedactLogResponse
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transport fixture: serves [entries] as they are (the filters are the server's) and records
 * every request. A follower gets them, then whatever [live] carries, until it stops collecting
 * or [live] is closed (the device was detached).
 */
internal class FakeDeviceLogService : DeviceLogServiceGrpcKt.DeviceLogServiceCoroutineImplBase() {
    val entries = CopyOnWriteArrayList<LogEntry>()

    @Volatile var dropped = 0L
    var finalDropped = 0L
    var captureError = ""
    var clockId = ""
    var recording: io.github.noamcohen48.tap.api.v1.LogRecording =
        io.github.noamcohen48.tap.api.v1.LogRecording
            .getDefaultInstance()
    var archives = emptyList<io.github.noamcohen48.tap.api.v1.RetainedLog>()
    val starts = CopyOnWriteArrayList<io.github.noamcohen48.tap.api.v1.StartLogRecordingRequest>()
    val deletes = CopyOnWriteArrayList<String>()
    val reads = CopyOnWriteArrayList<ReadLogRequest>()
    val marks = CopyOnWriteArrayList<MarkLogRequest>()
    val redactions = CopyOnWriteArrayList<RedactLogRequest>()
    val live = Channel<LogEntry>(Channel.UNLIMITED)
    val followerEnded = CompletableDeferred<Unit>()

    override fun readLog(request: ReadLogRequest): Flow<ReadLogResponse> =
        flow {
            reads += request
            if (request.hasMarker() && entries.none { it.hasMarker() && it.marker == request.marker }) {
                throw daemonFailure(Status.NOT_FOUND, FailureReason.FAILURE_REASON_UNKNOWN_LOG_MARKER, "no marker ${request.marker}")
            }
            emit(
                ReadLogResponse
                    .newBuilder()
                    .addAllEntries(
                        entries.take(1),
                    ).setDropped(dropped)
                    .setCaptureError(captureError)
                    .setClockId(clockId)
                    .build(),
            )
            emit(
                ReadLogResponse
                    .newBuilder()
                    .addAllEntries(entries.drop(1))
                    .setDropped(finalDropped)
                    .build(),
            )
            if (!request.follow) return@flow
            try {
                for (entry in live) emit(ReadLogResponse.newBuilder().addEntries(entry).build())
            } finally {
                followerEnded.complete(Unit)
            }
        }

    override suspend fun startLogRecording(
        request: io.github.noamcohen48.tap.api.v1.StartLogRecordingRequest,
    ): io.github.noamcohen48.tap.api.v1.StartLogRecordingResponse {
        starts += request
        return io.github.noamcohen48.tap.api.v1.StartLogRecordingResponse
            .newBuilder()
            .setRecording(recording)
            .build()
    }

    override suspend fun stopLogRecording(
        request: io.github.noamcohen48.tap.api.v1.StopLogRecordingRequest,
    ): io.github.noamcohen48.tap.api.v1.StopLogRecordingResponse {
        recording =
            recording
                .toBuilder()
                .setStoppedAtMs(2)
                .setComplete(captureError.isEmpty())
                .setCaptureError(captureError)
                .clearRanges()
                .addAllRanges(
                    recording.rangesList.map {
                        it.toBuilder().setThroughCursor(entries.maxOfOrNull { e -> e.cursor } ?: it.afterCursor).build()
                    },
                ).build()
        return io.github.noamcohen48.tap.api.v1.StopLogRecordingResponse
            .newBuilder()
            .setRecording(recording)
            .build()
    }

    override suspend fun listLogRecordings(
        request: io.github.noamcohen48.tap.api.v1.ListLogRecordingsRequest,
    ): io.github.noamcohen48.tap.api.v1.ListLogRecordingsResponse =
        io.github.noamcohen48.tap.api.v1.ListLogRecordingsResponse
            .newBuilder()
            .apply {
                if (recording.name.isNotEmpty()) addRecordings(recording)
            }.build()

    override suspend fun deleteLogRecording(
        request: io.github.noamcohen48.tap.api.v1.DeleteLogRecordingRequest,
    ): io.github.noamcohen48.tap.api.v1.DeleteLogRecordingResponse {
        deletes += request.name
        recording =
            io.github.noamcohen48.tap.api.v1.LogRecording
                .getDefaultInstance()
        return io.github.noamcohen48.tap.api.v1.DeleteLogRecordingResponse
            .getDefaultInstance()
    }

    override suspend fun listRetainedLogs(
        request: io.github.noamcohen48.tap.api.v1.ListRetainedLogsRequest,
    ): io.github.noamcohen48.tap.api.v1.ListRetainedLogsResponse =
        io.github.noamcohen48.tap.api.v1.ListRetainedLogsResponse
            .newBuilder()
            .addAllLogs(archives)
            .build()

    override suspend fun markLog(request: MarkLogRequest): MarkLogResponse {
        marks += request
        entries +=
            LogEntry
                .newBuilder()
                .setCursor(entries.size + 1L)
                .setMarker(request.name)
                .build()
        return MarkLogResponse.newBuilder().setCursor(entries.size.toLong()).build()
    }

    override suspend fun redactLog(request: RedactLogRequest): RedactLogResponse {
        redactions += request
        return RedactLogResponse.getDefaultInstance()
    }
}
