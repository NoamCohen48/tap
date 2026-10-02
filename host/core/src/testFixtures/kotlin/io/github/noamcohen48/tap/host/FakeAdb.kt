package io.github.noamcohen48.tap.host

import kotlinx.coroutines.awaitCancellation

/**
 * A fake device: canned results keyed by the command line after `-s <serial>`. Every call is
 * recorded in [calls]. Commands in [hangOn] suspend until cancelled, which lets teardown tests
 * prove bounded cleanup without touching a real device.
 */
class FakeAdb(
    private val replies: Map<String, Adb.Result> = emptyMap(),
    executable: String = "fake-adb",
) : Adb(executable) {
    private val recorded = mutableListOf<String>()

    /** Every recorded call; tests poll it from other threads, so it is a snapshot copy. */
    val calls: List<String> get() = synchronized(recorded) { recorded.toList() }
    val hangOn = mutableSetOf<String>()

    /** Stateful override consulted before [replies]; lets a test change answers over time. */
    var responder: ((serial: String, command: String) -> Adb.Result?)? = null

    override suspend fun execResult(
        serial: String,
        vararg arguments: String,
        timeoutMs: Long,
    ): Result {
        val command = arguments.joinToString(" ")
        synchronized(recorded) { recorded += "$serial: $command" }
        if (command in hangOn) awaitCancellation()
        responder?.invoke(serial, command)?.let { return it }
        // Every driver force-stop first looks for a bound notification listener: none, by default.
        if (command == "shell dumpsys notification" && command !in replies) return ok("")
        return replies[command] ?: error("unexpected adb -s $serial $command")
    }
}

fun ok(output: String) = Adb.Result(0, output)
