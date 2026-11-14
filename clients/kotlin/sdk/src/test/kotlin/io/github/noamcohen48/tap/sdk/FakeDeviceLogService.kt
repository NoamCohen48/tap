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
            emit(ReadLogResponse.newBuilder().addAllEntries(entries.take(1)).setDropped(dropped).build())
            emit(ReadLogResponse.newBuilder().addAllEntries(entries.drop(1)).build())
            if (!request.follow) return@flow
            try {
                for (entry in live) emit(ReadLogResponse.newBuilder().addEntries(entry).build())
            } finally {
                followerEnded.complete(Unit)
            }
        }

    override suspend fun markLog(request: MarkLogRequest): MarkLogResponse {
        marks += request
        entries += LogEntry.newBuilder().setCursor(entries.size + 1L).setMarker(request.name).build()
        return MarkLogResponse.newBuilder().setCursor(entries.size.toLong()).build()
    }

    override suspend fun redactLog(request: RedactLogRequest): RedactLogResponse {
        redactions += request
        return RedactLogResponse.getDefaultInstance()
    }
}
