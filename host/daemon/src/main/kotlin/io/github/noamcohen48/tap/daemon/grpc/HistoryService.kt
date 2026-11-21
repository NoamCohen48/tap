package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.DeleteHistoryRecordingRequest
import io.github.noamcohen48.tap.api.v1.DeleteHistoryRecordingResponse
import io.github.noamcohen48.tap.api.v1.HistoryIssueKind
import io.github.noamcohen48.tap.api.v1.HistoryRecording
import io.github.noamcohen48.tap.api.v1.HistoryServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.HistorySpan
import io.github.noamcohen48.tap.api.v1.ListHistoryRecordingsRequest
import io.github.noamcohen48.tap.api.v1.ListHistoryRecordingsResponse
import io.github.noamcohen48.tap.api.v1.ListHistorySourcesRequest
import io.github.noamcohen48.tap.api.v1.ListHistorySourcesResponse
import io.github.noamcohen48.tap.api.v1.ReadHistoryRequest
import io.github.noamcohen48.tap.api.v1.ReadHistoryResponse
import io.github.noamcohen48.tap.api.v1.StartHistoryRecordingRequest
import io.github.noamcohen48.tap.api.v1.StartHistoryRecordingResponse
import io.github.noamcohen48.tap.api.v1.StopHistoryRecordingRequest
import io.github.noamcohen48.tap.api.v1.StopHistoryRecordingResponse
import io.github.noamcohen48.tap.daemon.core.HistoryReader
import io.github.noamcohen48.tap.daemon.core.RetainedLogs
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.daemon.core.historyIssue
import io.github.noamcohen48.tap.daemon.core.historySource
import io.github.noamcohen48.tap.daemon.storage.StoredRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException

/** Daemon history (`history.proto`). The token authorizes it; a connection id selects, it does not identify the caller. */
class HistoryService(
    private val daemon: TapDaemon,
) : HistoryServiceGrpcKt.HistoryServiceCoroutineImplBase() {
    private val reader = HistoryReader(daemon.retainedLogs, daemon.config.stateDir.resolve("history-logs"))

    override suspend fun listHistorySources(request: ListHistorySourcesRequest): ListHistorySourcesResponse =
        historyReply {
            withContext(Dispatchers.IO) {
                synchronized(daemon.retainedLogs) {
                    ListHistorySourcesResponse.newBuilder().addAllSources(reader.sources(request.selection).map(::historySource)).build()
                }
            }
        }

    override suspend fun readHistory(request: ReadHistoryRequest): ReadHistoryResponse =
        historyReply {
            withContext(Dispatchers.IO) { reader.read(request) }
        }

    override suspend fun startHistoryRecording(request: StartHistoryRecordingRequest): StartHistoryRecordingResponse =
        historyReply {
            RetainedLogs.validName(request.name)
            StartHistoryRecordingResponse
                .newBuilder()
                .setRecording(recording(daemon.startHistoryRecording(request.connectionId, request.name)))
                .build()
        }

    override suspend fun stopHistoryRecording(request: StopHistoryRecordingRequest): StopHistoryRecordingResponse =
        historyReply {
            StopHistoryRecordingResponse.newBuilder().setRecording(recording(daemon.stopHistoryRecording(request.recordingId))).build()
        }

    override suspend fun listHistoryRecordings(request: ListHistoryRecordingsRequest): ListHistoryRecordingsResponse =
        historyReply {
            val owners = request.connectionIdsList.toSet()
            ListHistoryRecordingsResponse
                .newBuilder()
                .addAllRecordings(
                    daemon.retainedLogs
                        .list()
                        .filter { owners.isEmpty() || it.connectionId in owners }
                        .map { recording(it) },
                ).build()
        }

    override suspend fun deleteHistoryRecording(request: DeleteHistoryRecordingRequest): DeleteHistoryRecordingResponse =
        historyReply {
            daemon.retainedLogs.delete(request.recordingId)
            DeleteHistoryRecordingResponse.getDefaultInstance()
        }

    private fun recording(value: StoredRecording): HistoryRecording = recordingMessage(value, daemon.retainedLogs.issues(value))

    private suspend fun <T> historyReply(block: suspend () -> T): T =
        reply {
            try {
                block()
            } catch (invalid: PatternSyntaxException) {
                throw InvalidArgumentException("message_regex is invalid: ${invalid.description}")
            } catch (invalid: IllegalArgumentException) {
                throw InvalidArgumentException(invalid.message ?: "invalid history selection")
            }
        }
}

private fun recordingMessage(
    value: StoredRecording,
    issues: List<Pair<String, String>>,
): HistoryRecording =
    HistoryRecording
        .newBuilder()
        .setId(value.id)
        .setConnectionId(value.connectionId)
        .setName(value.name)
        .setStartedAtMs(value.startedAtMs)
        .setRetainedBytes(value.retainedBytes)
        .apply {
            if (value.hasStoppedAtMs()) setStoppedAtMs(value.stoppedAtMs)
            addAllSpans(
                value.spansList.map { span ->
                    HistorySpan
                        .newBuilder()
                        .setSourceId(span.sourceId)
                        .setAfterSequence(span.afterCursor)
                        .apply { if (span.hasThroughCursor()) setThroughSequence(span.throughCursor) }
                        .build()
                },
            )
            issues.forEach { (source, message) ->
                addIssues(historyIssue(source, HistoryIssueKind.HISTORY_ISSUE_KIND_INCOMPLETE, message.take(512)))
            }
        }.build()
