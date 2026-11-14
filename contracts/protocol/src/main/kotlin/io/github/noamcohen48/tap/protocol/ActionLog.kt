package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Command.OpCase
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Response

/**
 * The lines the driver writes to the device log (tag [TAG]) around each command it runs, and how
 * the host reads them back: `> #42 tap text="Pay"` just before the command starts and
 * `< #42 tap ok 243ms` (or `< #42 tap NOT_FOUND 5000ms`) once its response is decided. They sit in
 * the same log stream as the app's own lines, on the device's clock, so a reader sees which
 * command an app line follows.
 *
 * A line names the operation and the selector it targets, never an argument: typed text, clipboard
 * contents and coordinates stay out of the device log. Queries (`exists`, `count`, `snapshot`, …)
 * are not logged: a host polling a condition would bury the app's lines.
 */
object ActionLog {
    /** The log tag of every action line. A reader must also check the line's uid: any app can use a tag. */
    const val TAG = "Tap"

    /** Longest target kept in a line; a longer one ends in `…`. */
    const val MAX_TARGET_CHARS = 200

    /** The outcome of a command that returned a result. */
    const val OK = "ok"

    /** One parsed action line. [target] is the rendered selector, if the command has one. */
    data class Line(
        val requestId: Long,
        val op: String,
        val finished: Boolean,
        val target: String? = null,
        /** [OK] or the error code's label (`NOT_FOUND`); only when [finished]. */
        val outcome: String? = null,
        val durationMs: Long? = null,
    )

    /** Whether the driver logs [request]: a command that acts on the device or waits for it. */
    fun logs(request: Request): Boolean = request.bodyCase == Request.BodyCase.COMMAND && logs(request.command)

    fun logs(command: Command): Boolean = command.isMutation || command.opCase in WAITS

    /** The line before [command] starts. */
    fun started(
        requestId: Long,
        command: Command,
    ): String {
        val target = command.selectors.joinToString(" -> ") { it.render() }.let(::printable)
        return "> #$requestId ${command.op}" + if (target.isEmpty()) "" else " $target"
    }

    /** The line once [response] is what the host will get for [command]. */
    fun finished(
        requestId: Long,
        command: Command,
        response: Response,
    ): String = "< #$requestId ${command.op} ${response.errorCode?.label ?: OK} ${response.result.durationMs}ms"

    /** [message] as an action line, or null when it is not one. */
    fun parse(message: String): Line? {
        STARTED.matchEntire(message)?.let { match ->
            val (id, op, target) = match.destructured
            return Line(id.toLongOrNull() ?: return null, op, finished = false, target = target.ifEmpty { null })
        }
        FINISHED.matchEntire(message)?.let { match ->
            val (id, op, outcome, duration) = match.destructured
            return Line(
                id.toLongOrNull() ?: return null,
                op,
                finished = true,
                outcome = outcome,
                durationMs = duration.toLongOrNull() ?: return null,
            )
        }
        return null
    }

    /** One line, no control characters (logcat splits a message at each newline), bounded. */
    private fun printable(text: String): String {
        val flat = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        return if (flat.length <= MAX_TARGET_CHARS) flat else flat.take(MAX_TARGET_CHARS - 1) + "…"
    }

    private val WAITS =
        setOf(
            OpCase.WAIT_VISIBLE,
            OpCase.WAIT_GONE,
            OpCase.WAIT_STATE,
            OpCase.WAIT_APP_VISIBLE,
            OpCase.WAIT_SCREEN_STABLE,
            OpCase.WAIT_PERMISSION_PROMPT,
            OpCase.AWAIT_TOAST,
            OpCase.AWAIT_NOTIFICATION,
        )

    private val STARTED = Regex("""> #(\d+) ([a-z_]+)(?: (.*))?""")
    private val FINISHED = Regex("""< #(\d+) ([a-z_]+) ([A-Za-z_]+) (\d+)ms""")
}
