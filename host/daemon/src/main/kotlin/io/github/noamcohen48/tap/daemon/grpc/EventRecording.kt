package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Error as CommandError
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.daemon.core.AttachedDevice
import io.github.noamcohen48.tap.host.MediaClock
import io.github.noamcohen48.tap.protocol.ActionLog
import io.github.noamcohen48.tap.protocol.label
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] (a call on this device that was already validated) and appends it to the owning
 * connection's event log with its outcome: the driver error [errorOf] finds in the result, or
 * the RPC failure it threw (rethrown as its mapped status). A cancelled call is not logged: its
 * outcome is unknown and the caller is gone. An app or device call is also written to the device's
 * log when it starts and when it ends ([io.github.noamcohen48.tap.daemon.core.DeviceLog.hostAction]). Its start/finish boundaries are on [MediaClock], the
 * clock video frames are received on, so a viewer can line the two up. The step the call carried
 * ([STEP]) is copied to the event and to those log entries.
 */
internal suspend fun <T> AttachedDevice.recorded(
    call: LoggedEvent.Builder.() -> Unit,
    errorOf: (T) -> CommandError? = { null },
    block: suspend () -> T,
): T {
    val started = MediaClock.nowNs()
    val step = STEP.get()
    val event =
        LoggedEvent
            .newBuilder()
            .setAtEpochMs(System.currentTimeMillis())
            .setSerial(deviceSession.serial)
            .setStartedMonotonicNs(started)
            .setClockId(MediaClock.id)
            .apply(call)
            .apply { step?.let(::setStep) }
    // A call the host runs itself goes into the device log here. A driver command does not: the
    // driver writes its own lines, in order with the app's.
    val operation = if (event.hasApp()) event.app.operation else if (event.hasDevice()) event.device.operation else null
    val target = if (event.hasApp()) event.app.packageName else if (event.hasDevice() && event.device.hasDevicePath()) event.device.devicePath else null
    if (operation != null) logs?.hostAction(operation, target, step = step)

    fun finish() {
        val finished = MediaClock.nowNs()
        val durationMs = (finished - started) / 1_000_000L
        events.append(event.setFinishedMonotonicNs(finished).setDurationMs(durationMs))
        if (operation != null) {
            val outcome =
                when {
                    event.hasError() -> event.error.code.label
                    event.hasFailure() -> event.failure.reason.name.removePrefix("FAILURE_REASON_")
                    else -> ActionLog.OK
                }
            logs?.hostAction(operation, target, outcome, durationMs, step)
        }
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

/**
 * This command as the event log keeps it: a `sensitive` set_text / type_text without its text
 * (the flag stays, so a reader knows text was typed and withheld). Everything else as sent.
 */
internal fun Command.forLog(): Command =
    when {
        hasSetText() && setText.sensitive -> toBuilder().setSetText(setText.toBuilder().clearText()).build()
        hasTypeText() && typeText.sensitive -> toBuilder().setTypeText(typeText.toBuilder().clearText()).build()
        else -> this
    }

/** The text of a `sensitive` set_text / type_text, which must not be readable anywhere else. */
internal fun Command.sensitiveText(): String? =
    when {
        hasSetText() && setText.sensitive -> setText.text
        hasTypeText() && typeText.sensitive -> typeText.text
        else -> null
    }
