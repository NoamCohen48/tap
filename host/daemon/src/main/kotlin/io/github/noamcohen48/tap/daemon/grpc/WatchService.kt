package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.WatchLogRequest
import io.github.noamcohen48.tap.api.v1.WatchLogResponse
import io.github.noamcohen48.tap.api.v1.WatchRequest
import io.github.noamcohen48.tap.api.v1.WatchResponse
import io.github.noamcohen48.tap.api.v1.WatchServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.WatchVideoRequest
import io.github.noamcohen48.tap.api.v1.WatchVideoResponse
import io.github.noamcohen48.tap.daemon.core.DaemonPreconditionException
import io.github.noamcohen48.tap.daemon.core.DeviceStatus
import io.github.noamcohen48.tap.daemon.core.LOG_BATCH_ENTRIES
import io.github.noamcohen48.tap.daemon.core.LogStart
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.MediaClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Read-only watching (`watch.proto`). No RPC names a client connection: nothing here
 * renews, claims or ends one, and a watcher leaving changes nothing for what it watched.
 */
class WatchService(
    private val daemon: TapDaemon,
) : WatchServiceGrpcKt.WatchServiceCoroutineImplBase() {
    override fun watch(request: WatchRequest): Flow<WatchResponse> =
        flow {
            argument(request.afterSeq >= 0) { "after_seq must be >= 0" }
            val (connections, subscription) = daemon.watch(request.afterSeq)
            emit(
                WatchResponse
                    .newBuilder()
                    .addAllConnections(connections.sortedBy { it.name }.map { it.toEntry() })
                    .addAllActivities(subscription.backlog)
                    .setDropped(subscription.dropped)
                    .build(),
            )
            daemon.activity.live(subscription).collect { batch ->
                emit(WatchResponse.newBuilder().addAllActivities(batch).build())
            }
        }.mapErrors()

    override fun watchVideo(request: WatchVideoRequest): Flow<WatchVideoResponse> =
        flow {
            argument(request.serial.isNotBlank()) { "serial is required" }
            if (daemon.config.scrcpyServer == null) {
                throw DaemonPreconditionException("No scrcpy server configured: start the daemon with --scrcpy-server")
            }
            val device = daemon.devices().firstOrNull { it.serial == request.serial }
            argument(device != null) { "${request.serial} is not in the device inventory" }
            argument(device!!.status !is DeviceStatus.Unavailable) { "${request.serial} is not online" }
            emitAll(daemon.video.watch(request.serial))
        }.mapErrors()

    override fun watchLog(request: WatchLogRequest): Flow<WatchLogResponse> =
        flow {
            argument(request.attachedDeviceId.isNotBlank()) { "attached_device_id is required" }
            argument(request.afterCursor >= 0) { "after_cursor must be >= 0" }
            val log = daemon.watchedLog(request.attachedDeviceId)
            val subscription = log.read(LogStart.After(request.afterCursor), follow = true)
            val batches = subscription.backlog.chunked(LOG_BATCH_ENTRIES)
            // Always a first response, so a watcher learns the clock and what it missed.
            emit(
                WatchLogResponse
                    .newBuilder()
                    .addAllEntries(log.withPackages(batches.firstOrNull().orEmpty()))
                    .setDropped(subscription.dropped)
                    .setClockId(MediaClock.id)
                    .build(),
            )
            batches.drop(1).forEach { emit(WatchLogResponse.newBuilder().addAllEntries(log.withPackages(it)).build()) }
            log.live(subscription).collect { emit(WatchLogResponse.newBuilder().addAllEntries(log.withPackages(it)).build()) }
        }.mapErrors()

    private fun <T> Flow<T>.mapErrors(): Flow<T> =
        catch { error ->
            throw if (error is CancellationException) error else error.toStatus()
        }
}
