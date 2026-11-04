package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells a command that failed because the app died from one that failed on its own. The driver
 * only sees "no such node"; Android's event log says the app crashed or stopped responding. So
 * when a command fails in a way a dead app explains, and an app this attachment started has a
 * new `am_crash` / `am_anr` event, the failure becomes `AUT_CRASHED` / `AUT_ANR`, naming the
 * app, with the code it replaces as its detail.
 *
 * Only apps started through this attachment count: another app crashing in the background says
 * nothing about the test. Each event is reported once, and starting the app again makes its
 * earlier events old. Nothing is read unless a command failed, so passing commands never pay
 * for this, and a read that fails leaves the driver's result as it was.
 */
internal class AppDeaths(
    private val adb: Adb,
    private val serial: String,
    private val sinceEpochSeconds: Long?,
    private val log: (String) -> Unit = {},
) {
    private val watched = ConcurrentHashMap.newKeySet<String>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** From now on [packageName]'s new deaths explain failures; those already logged do not. */
    suspend fun watch(packageName: String) {
        events()?.filter { it.names(packageName) }?.let(reported::addAll)
        watched += packageName
    }

    /** [result], or when an app's death explains its failure, that failure. */
    suspend fun explain(result: CommandResult): CommandResult {
        if (!result.hasError() || result.error.code !in EXPLAINED || watched.isEmpty()) return result
        val fresh = events().orEmpty().filter { it !in reported }
        val (line, packageName) =
            fresh.firstNotNullOfOrNull { line -> watched.firstOrNull(line::names)?.let { line to it } } ?: return result
        reported += fresh.filter { it.names(packageName) }
        val anr = DEATH_EVENT.find(line)?.groupValues?.get(1) == ANR_TAG
        val replaced = result.error.code.name.removePrefix("ERR_")
        val original = listOfNotNull(replaced, result.error.takeIf { it.hasDetail() }?.detail, result.error.takeIf { it.hasMessage() }?.message)
        return result
            .toBuilder()
            .setError(
                result.error
                    .toBuilder()
                    .setCode(if (anr) ErrorCode.ERR_AUT_ANR else ErrorCode.ERR_AUT_CRASHED)
                    .setDetail(replaced)
                    .setMessage(
                        "$packageName ${if (anr) "stopped responding" else "crashed"}: ${line.substringAfter(": ").trim()}; " +
                            "the command reported ${original.joinToString(": ")}",
                    ),
            ).build()
    }

    /** The crash and ANR events logged since the attachment; null when they cannot be read in time. */
    private suspend fun events(): List<String>? =
        try {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                adb
                    .logcat(serial, listOf("events"), sinceEpochSeconds, tags = listOf(CRASH_TAG, ANR_TAG))
                    .lines()
                    .filter { CRASH_TAG in it || ANR_TAG in it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("crash events of $serial unreadable: ${error.message}")
            null
        }

    private companion object {
        const val CRASH_TAG = "am_crash"
        const val ANR_TAG = "am_anr"
        const val READ_TIMEOUT_MS = 2_000L

        /**
         * What a dead app explains. Not `AMBIGUOUS` (several matches means an app is there), nor
         * `INDETERMINATE` and the transport codes, which say something a crash must not hide.
         */
        val EXPLAINED =
            setOf(
                ErrorCode.ERR_NOT_FOUND,
                ErrorCode.ERR_WAIT_TIMEOUT,
                ErrorCode.ERR_NOT_INTERACTABLE,
                ErrorCode.ERR_STALE_BEFORE_INPUT,
                ErrorCode.ERR_STALE_DURING_COMMAND,
                ErrorCode.ERR_ACTION_REJECTED,
            )
    }
}

/**
 * Whether an event line (`I am_crash: [pid,uid,process,…]`, `I am_anr: [user,pid,package,…]`, whose
 * field order varies by Android version) is about [packageName] or one of its `:` processes.
 */
private val DEATH_EVENT = Regex("""\b(am_crash|am_anr)\s*:\s*\[""")

private fun String.names(packageName: String): Boolean {
    val event = DEATH_EVENT.find(this) ?: return false
    // Both event formats put the process/package third; exception text is not identity.
    val process = substring(event.range.last + 1).split(',', limit = 4).getOrNull(2)?.trim() ?: return false
    return process == packageName || process.startsWith("$packageName:")
}
