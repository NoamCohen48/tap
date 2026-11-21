package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.HistoryDeath
import io.github.noamcohen48.tap.api.v1.LoggedEvent

/** Keep only classified death metadata, while preserving original attachment identity. */
internal fun durableDeath(
    value: HistoryDeath,
    redactor: LogRedactor,
): HistoryDeath =
    HistoryDeath
        .newBuilder()
        .setAttachedDeviceId(value.attachedDeviceId)
        .setSerial(value.serial)
        .setDeath(redactAppDeath(value.death, redactor::redact))
        .build()

/** Redact using the actual admitting attachment, not a later attachment of the same serial. */
internal fun durableEvent(
    event: LoggedEvent,
    redactor: LogRedactor,
): LoggedEvent {
    val safe =
        redactLoggedEvent(event, redactor::redact)
            .toBuilder()
            .setSerial(event.serial)
            .setClockId(event.clockId)
            .setAttachedDeviceId(event.attachedDeviceId)
    if (event.hasStep()) safe.step = event.step
    if (event.hasCommand() && (
            (event.command.hasSetText() && event.command.setText.sensitive) ||
                (event.command.hasTypeText() && event.command.typeText.sensitive)
        )
    ) {
        safe.command = redactCommand(event.command) { LogRedactor.REDACTED }
    }
    return safe.build()
}
