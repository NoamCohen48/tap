package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.api.v1.ReadToastHistory
import io.github.noamcohen48.tap.api.v1.Toast
import io.github.noamcohen48.tap.api.v1.ToastEvent
import io.github.noamcohen48.tap.api.v1.ToastHistory
import java.util.UUID

/** Value-only, bounded history, independent of UI handles and the command execution loop. */
class ToastEventBuffer(
    private var streamId: String = UUID.randomUUID().toString(),
) {
    private val events = ArrayDeque<ToastEvent>()
    private var sequence = 0L

    @Synchronized
    fun record(
        text: String,
        packageName: String,
        atEpochMs: Long,
    ) {
        events.addLast(
            ToastEvent
                .newBuilder()
                .setSequence(++sequence)
                .setAtEpochMs(atEpochMs)
                .setToast(Toast.newBuilder().setText(text.take(MAX_TEXT)).setPackageName(packageName.take(256)))
                .setTextTruncated(text.length > MAX_TEXT)
                .build(),
        )
        while (events.size > CAPACITY) events.removeFirst()
    }

    /** Forget the previous session before the instrumentation process is reused. */
    @Synchronized
    fun reset() {
        events.clear()
        sequence = 0L
        streamId = UUID.randomUUID().toString()
    }

    @Synchronized
    fun read(request: ReadToastHistory): ToastHistory {
        val reset = (request.streamId.isNotEmpty() && request.streamId != streamId) || request.afterSequence > sequence
        val after = if (reset) 0L else request.afterSequence
        val oldest = events.firstOrNull()?.sequence ?: sequence + 1
        return ToastHistory
            .newBuilder()
            .setStreamId(streamId)
            .addAllEvents(events.filter { it.sequence > after })
            .setNextSequence(sequence)
            .setOldestSequence(oldest)
            .setReset(reset)
            .setGap(reset || after < oldest - 1)
            .build()
    }

    companion object {
        const val CAPACITY = 128
        const val MAX_TEXT = 1024
    }
}
