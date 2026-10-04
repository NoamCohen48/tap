package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.Error as CommandError
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.daemon.core.AttachedDevice
import io.github.noamcohen48.tap.host.MediaClock
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] (a call on this device that was already validated) and appends it to the owning
 * connection's event log with its outcome: the driver error [errorOf] finds in the result, or
 * the RPC failure it threw (rethrown as its mapped status). A cancelled call is not logged: its
 * outcome is unknown and the caller is gone. Its start/finish boundaries are on [MediaClock], the
 * clock video frames are received on, so a viewer can line the two up.
 */
internal suspend fun <T> AttachedDevice.recorded(
    call: LoggedEvent.Builder.() -> Unit,
    errorOf: (T) -> CommandError? = { null },
    block: suspend () -> T,
): T {
    val started = MediaClock.nowNs()
    val event =
        LoggedEvent
            .newBuilder()
            .setAtEpochMs(System.currentTimeMillis())
            .setSerial(deviceSession.serial)
            .setStartedMonotonicNs(started)
            .setClockId(MediaClock.id)
            .apply(call)

    fun finish() {
        val finished = MediaClock.nowNs()
        events.append(event.setFinishedMonotonicNs(finished).setDurationMs((finished - started) / 1_000_000L))
    }
    val result =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val status = error.toStatus()
            status.trailers?.get(FAILURE_TRAILER)?.let { event.failure = it }
            finish()
            throw status
        }
    errorOf(result)?.let { event.error = it }
    finish()
    return result
}
