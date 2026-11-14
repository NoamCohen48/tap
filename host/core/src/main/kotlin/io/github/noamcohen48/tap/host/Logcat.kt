package io.github.noamcohen48.tap.host

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One line of `logcat -v threadtime -v epoch -v uid`. */
data class LogcatLine(
    /** The device clock when the line was logged. */
    val deviceTimeMs: Long,
    /** The logging process's Linux uid; null when logcat printed a name it has no number for. */
    val uid: Int?,
    val pid: Int,
    val tid: Int,
    /** `V`, `D`, `I`, `W`, `E` or `F`. */
    val priority: Char,
    val tag: String,
    val message: String,
)

/**
 * [line] as logcat prints it with the epoch and uid modifiers,
 * `1794608507.879 10130 23461 23571 I Finsky  : message`, or null for anything else (the
 * `--------- beginning of main` banners, adb's own errors). logcat prints a uid by name when the
 * name is short (`root`, `shell`), by number otherwise, which covers every app.
 */
fun parseLogcatLine(line: String): LogcatLine? {
    val match = LOGCAT_LINE.matchEntire(line) ?: return null
    val (seconds, millis, uid, pid, tid, priority, tag, message) = match.destructured
    return LogcatLine(
        deviceTimeMs = (seconds.toLongOrNull() ?: return null) * 1_000 + millis.toLong(),
        uid = uid.toIntOrNull() ?: NAMED_UIDS[uid],
        pid = pid.toIntOrNull() ?: return null,
        tid = tid.toIntOrNull() ?: return null,
        priority = priority.single(),
        tag = tag,
        message = message,
    )
}

// The tag is everything up to the first `: ` (or a `:` that ends the line): logcat pads a short
// tag with spaces before the colon, and a tag may itself hold a colon.
private val LOGCAT_LINE = Regex("""\s*(\d+)\.(\d{3})\s+(\S+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:(?: (.*)|)""")

private val NAMED_UIDS = mapOf("root" to 0, "radio" to 1001, "log" to 1007, "wifi" to 1010, "media" to 1013, "nfc" to 1027, "shell" to 2000)

/**
 * Reads a device's log for as long as it runs: one `adb logcat` child ([Adb.startLogcat]) from
 * [sinceEpochSeconds] on the device clock (null: from now), each parsed line handed to [sink] on
 * this capture's own thread. When the child ends on its own (the device disconnected, adb
 * restarted) it is started again from the last line read, after a growing pause, until [close];
 * the sink is told once per interruption, since lines logged meanwhile may have scrolled out of
 * the device's buffers.
 */
class LogcatCapture(
    private val adb: Adb,
    private val serial: String,
    private val sinceEpochSeconds: Long?,
    private val sink: Sink,
    private val firstRetryMs: Long = FIRST_RETRY_MS,
) {
    interface Sink {
        fun line(line: LogcatLine)

        /** The capture stopped and is being started again; [reason] says what ended it. */
        fun interrupted(reason: String)
    }

    private val lock = Any()
    private var process: Process? = null
    private var closed = false
    private val reader = thread(start = false, isDaemon = true, name = "tap-logcat-$serial", block = ::run)

    fun start() = reader.start()

    /** Ends the child and waits briefly for the reader; idempotent, never throws. */
    fun close() {
        val running =
            synchronized(lock) {
                closed = true
                process
            }
        running?.let(::reap)
        reader.interrupt()
        runCatching { reader.join(JOIN_MS) }
    }

    private fun run() {
        var lastMs: Long? = null
        var retryMs = firstRetryMs
        var interrupted = false
        while (!isClosed()) {
            val since = lastMs?.let { "%d.%03d".format(it / 1_000, it % 1_000) } ?: sinceEpochSeconds?.let { "$it.000" }
            var noise: String? = null
            val ended =
                try {
                    val child = adb.startLogcat(serial, since)
                    val admitted = synchronized(lock) { if (closed) false else true.also { process = child } }
                    if (!admitted) {
                        reap(child)
                        return
                    }
                    child.inputStream.bufferedReader().use { output ->
                        while (true) {
                            val text = output.readLine() ?: break
                            val line = parseLogcatLine(text)
                            if (line == null) {
                                if (text.isNotBlank() && !text.startsWith("---------")) noise = text.trim()
                                continue
                            }
                            // A restart reads from the last line's millisecond again: skip what was already delivered.
                            if (interrupted && lastMs != null && line.deviceTimeMs <= lastMs!!) continue
                            interrupted = false
                            retryMs = firstRetryMs
                            lastMs = line.deviceTimeMs
                            sink.line(line)
                        }
                    }
                    reap(child)
                    "logcat ended" + (noise?.let { ": $it" } ?: "")
                } catch (error: Exception) {
                    "logcat could not be read: ${error.message ?: error}"
                } finally {
                    synchronized(lock) { process = null }
                }
            if (isClosed()) return
            if (!interrupted) {
                interrupted = true
                runCatching { sink.interrupted(ended) }
            }
            try {
                Thread.sleep(retryMs)
            } catch (_: InterruptedException) {
                return
            }
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun isClosed() = synchronized(lock) { closed }

    private fun reap(child: Process) {
        runCatching {
            child.destroy()
            if (!child.waitFor(REAP_MS, TimeUnit.MILLISECONDS)) child.destroyForcibly()
        }
    }

    private companion object {
        const val FIRST_RETRY_MS = 500L
        const val MAX_RETRY_MS = 5_000L
        const val REAP_MS = 2_000L
        const val JOIN_MS = 2_000L
    }
}
