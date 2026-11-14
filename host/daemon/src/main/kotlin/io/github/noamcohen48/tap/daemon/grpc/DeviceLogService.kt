package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.DeviceLogServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.MarkLogRequest
import io.github.noamcohen48.tap.api.v1.MarkLogResponse
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.ReadLogResponse
import io.github.noamcohen48.tap.api.v1.RedactLogRequest
import io.github.noamcohen48.tap.api.v1.RedactLogResponse
import io.github.noamcohen48.tap.daemon.core.DeviceLog
import io.github.noamcohen48.tap.daemon.core.InvalidRedactionException
import io.github.noamcohen48.tap.daemon.core.LOG_BATCH_ENTRIES
import io.github.noamcohen48.tap.daemon.core.LogCaptureOffException
import io.github.noamcohen48.tap.daemon.core.LogFilter
import io.github.noamcohen48.tap.daemon.core.LogStart
import io.github.noamcohen48.tap.daemon.core.TapDaemon
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
                    ReadLogRequest.FromCase.AFTER_CURSOR -> LogStart.After(request.afterCursor)
                    ReadLogRequest.FromCase.MARKER -> LogStart.Marker(request.marker)
                    ReadLogRequest.FromCase.TAIL -> {
                        argument(request.tail in 1..MAX_TAIL) { "tail must be 1 to $MAX_TAIL" }
                        LogStart.Tail(request.tail)
                    }
                    ReadLogRequest.FromCase.LAST_ACTION -> if (request.lastAction) LogStart.LastAction else LogStart.Oldest
                    ReadLogRequest.FromCase.FROM_NOT_SET, null -> LogStart.Oldest
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
            val attached = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId, requireDriver = false)
            val log = attached.logs ?: throw LogCaptureOffException(attached.deviceSession.serial)
            val uids =
                if (request.packagesCount == 0) {
                    null
                } else {
                    val installed = daemon.config.adb.packageUids(attached.deviceSession.serial)
                    request.packagesList.map { name -> argumentNotNull(installed[name]) { "$name is not installed on ${attached.deviceSession.serial}" } }.toSet()
                }
            val step = if (request.hasStep()) request.step else null
            argument(step == null || !request.follow) { "step cannot be combined with follow" }
            argument(step == null || StepInterceptor.valid(step)) { "step must be 1 to ${StepInterceptor.MAX_CHARS} of A-Z a-z 0-9 . _ -" }
            val filter = LogFilter(uids, request.minPriority, request.tagsList.toSet(), message, request.excludeActions, step)
            val subscription = log.read(start, filter, request.follow)
            val batches = subscription.backlog.chunked(LOG_BATCH_ENTRIES)
            // Always a first response, so a caller learns `dropped` and that the read began.
            emit(ReadLogResponse.newBuilder().addAllEntries(log.withPackages(batches.firstOrNull().orEmpty())).setDropped(subscription.dropped).build())
            batches.drop(1).forEach { emit(ReadLogResponse.newBuilder().addAllEntries(log.withPackages(it)).build()) }
            log.live(subscription).collect { emit(ReadLogResponse.newBuilder().addAllEntries(log.withPackages(it)).build()) }
        }.catch { error ->
            throw if (error is CancellationException) error else error.toStatus()
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

    private companion object {
        const val MAX_TAIL = 100_000
        const val MAX_REGEX_CHARS = 512
        const val MAX_MARKER_CHARS = 200
    }
}
