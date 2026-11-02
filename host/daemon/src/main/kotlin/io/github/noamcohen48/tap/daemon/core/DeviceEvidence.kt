package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * What a device itself recorded since an attachment opened (`DeviceService.DeviceLogs`): the
 * failure evidence the driver cannot see. Every part is read independently, at once; a part
 * that fails holds one `<unavailable: …>` line instead of failing the others.
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

        suspend fun collect(
            adb: Adb,
            serial: String,
            sinceEpochSeconds: Long?,
        ): DeviceEvidence =
            coroutineScope {
                val logcat = async { part { adb.logcat(serial, listOf("main", "system", "crash"), sinceEpochSeconds) } }
                val crashBuffer = async { part { adb.logcat(serial, listOf("crash"), sinceEpochSeconds) } }
                val events = async { part { adb.logcat(serial, listOf("events"), sinceEpochSeconds, tags = listOf("am_crash", "am_anr")) } }
                val activities = async { part { adb.dumpsys(serial, "activity", "activities") } }
                val windows = async { part { adb.dumpsys(serial, "window", "windows") } }
                val (text, truncated) = newest(logcat.await(), MAX_LOGCAT_CHARS)
                DeviceEvidence(
                    logcat = text,
                    logcatTruncated = truncated,
                    crashes = crashes(crashBuffer.await(), events.await()),
                    activities = activities.await(),
                    windows = windows.await(),
                )
            }

        private suspend fun part(read: suspend () -> String): String =
            try {
                read()
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
