package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * What a device itself recorded since an attachment opened (`DeviceService.DeviceLogs`): the
 * failure evidence the driver cannot see. The log is the attachment's captured one ([DeviceLog])
 * when it has one, the device's ring buffers otherwise; either way secrets are redacted. Every part is read independently, at once, with its
 * own deadline; a part that fails or runs out of time holds one `<unavailable: …>` line instead
 * of failing the others.
 */
internal data class DeviceEvidence(
    val logcat: String,
    val logcatTruncated: Boolean,
    val crashes: String,
    val activities: String,
    val windows: String,
) {
    companion object {
        /** Larger logcat output keeps its newest lines up to this many characters. */
        const val MAX_LOGCAT_CHARS = 4 * 1024 * 1024

        /** How long one part may take when the caller's own deadline leaves room for it. */
        const val PART_TIMEOUT_MS = 20_000L

        /**
         * A part's deadline for a call with [remainingMs] left (null: no deadline): short enough
         * that the reply gets back before the caller gives up. A deadline as long as the call's
         * would lose every part to one stuck `dumpsys`, the call failing while it waits.
         */
        fun partTimeoutMs(remainingMs: Long?): Long =
            if (remainingMs == null) PART_TIMEOUT_MS else min(PART_TIMEOUT_MS, (remainingMs * 3 / 4).coerceAtLeast(1))

        suspend fun collect(
            adb: Adb,
            serial: String,
            sinceEpochSeconds: Long?,
            partTimeoutMs: Long = PART_TIMEOUT_MS,
            captured: DeviceLog? = null,
        ): DeviceEvidence =
            coroutineScope {
                suspend fun part(read: suspend () -> String) = part(partTimeoutMs, read)
                val redactor = captured?.redactor ?: LogRedactor()

                suspend fun logPart(read: suspend () -> String) = part { read().lineSequence().joinToString("\n", transform = redactor::redact) }
                val logcat =
                    async {
                        if (captured != null) captured(captured) else logPart { adb.logcat(serial, listOf("main", "system", "crash"), sinceEpochSeconds) } to false
                    }
                val crashBuffer = async { logPart { adb.logcat(serial, listOf("crash"), sinceEpochSeconds) } }
                val events = async { logPart { adb.logcat(serial, listOf("events"), sinceEpochSeconds, tags = listOf("am_crash", "am_anr")) } }
                val activities = async { part { adb.dumpsys(serial, "activity", "activities") } }
                val windows = async { part { adb.dumpsys(serial, "window", "windows") } }
                val (full, forgotten) = logcat.await()
                val (text, truncated) = newest(full, MAX_LOGCAT_CHARS)
                DeviceEvidence(
                    logcat = text,
                    logcatTruncated = truncated || forgotten,
                    crashes = crashes(crashBuffer.await(), events.await()),
                    activities = activities.await(),
                    windows = windows.await(),
                )
            }

        /** The captured log as text, one entry a line, and whether the buffer had forgotten its start. */
        private fun captured(log: DeviceLog): Pair<String, Boolean> {
            val read = log.read()
            return read.backlog.joinToString("") { DeviceLog.text(it) + "\n" } to (read.dropped > 0)
        }

        private suspend fun part(
            timeoutMs: Long,
            read: suspend () -> String,
        ): String =
            try {
                withTimeoutOrNull(timeoutMs) { read() } ?: "<unavailable: not read within $timeoutMs ms>"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "<unavailable: ${error.message}>"
            }

        /** The last whole lines of [text] that fit in [limit] characters, and whether any were dropped. */
        internal fun newest(
            text: String,
            limit: Int,
        ): Pair<String, Boolean> {
            if (text.length <= limit) return text to false
            val cut = text.length - limit
            val start = text.indexOf('\n', cut).let { if (it < 0) cut else it + 1 }
            return text.substring(start) to true
        }

        /**
         * The crash buffer and the crash/ANR events, empty when both hold only logcat's own
         * `--------- beginning of …` banners.
         */
        internal fun crashes(
            crashBuffer: String,
            events: String,
        ): String {
            fun meaningful(text: String) = text.lineSequence().any { it.isNotBlank() && !it.startsWith("--------- beginning of") }
            return buildString {
                if (meaningful(crashBuffer)) append(crashBuffer.trimEnd()).append('\n')
                if (meaningful(events)) {
                    if (isNotEmpty()) append('\n')
                    append(events.trimEnd()).append('\n')
                }
            }
        }
    }
}
