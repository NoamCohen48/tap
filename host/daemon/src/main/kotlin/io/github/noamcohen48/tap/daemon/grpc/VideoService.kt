package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.VideoServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.WatchVideoRequest
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.VideoException
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import io.github.noamcohen48.tap.api.v1.WatchVideoResponse as VideoResponse

/** Passive shared reads: deliberately no attached-device id or client connection. */
class VideoService(
    private val daemon: TapDaemon,
) : VideoServiceGrpcKt.VideoServiceCoroutineImplBase() {
    override fun watchVideo(request: WatchVideoRequest): Flow<VideoResponse> =
        flow {
            argument(request.serial.isNotBlank()) { "serial is required" }
            argument(daemon.devices().any { it.serial == request.serial }) { "serial is not in the device inventory" }
            emitAll(daemon.video.watch(request.serial))
        }.catch { error ->
            when (error) {
                is CancellationException -> throw error
                is VideoException -> throw failureStatus(
                    Status.UNAVAILABLE.withDescription(error.message),
                    Failure.newBuilder().setReason(FailureReason.FAILURE_REASON_DAEMON_PRECONDITION).build(),
                )
                else -> throw error.toStatus()
            }
        }
}
