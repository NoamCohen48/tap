package io.github.noamcohen48.tap.host

import java.util.UUID

internal data class RuntimeObservation(
    val rotation: List<SavedState>,
    val process: Adb.ProcessStat,
)

internal data class AttachmentProcessObservation(
    val pids: List<Int>,
    val process: Adb.ProcessStat,
) {
    fun matches(expectedPid: Int, expectedToken: String): Boolean =
        pids == listOf(expectedPid) && process == Adb.ProcessStat.Live(expectedToken)
}

/** Independent read results from one serial-specific shell; never interprets a failed part as absence. */
internal suspend fun readAttachmentProbeResults(
    serial: String,
    commands: List<List<String>>,
    execute: suspend (List<String>) -> Adb.Result,
): List<Adb.Result> {
    require(commands.isNotEmpty())
    // Device values must not be able to impersonate a later probe's status or process identity.
    val prefix = "__TAP_PROBE_${UUID.randomUUID().toString().replace("-", "")}_"
    val script =
        commands
            .mapIndexed { index, command ->
                // Android mksh's printf is external; echo is builtin. Markers contain no options or escapes.
                "echo '${prefix}${index}_BEGIN__'; " +
                    command.joinToString(" ", transform = ::shellQuote) +
                    " 2>&1; status=\$?; echo; echo \"${prefix}${index}_END__:\$status\""
            }.joinToString("; ")
    // adb already invokes the device shell; another `sh -c` would add an unnecessary process.
    val command = listOf("shell", script)
    val result = execute(command)

    fun malformed(): Nothing =
        throw AdbCommandException(
            serial,
            command,
            result.exitCode,
            result.output,
            "Incomplete or invalid attachment probe results on $serial",
        )
    if (result.exitCode != 0) malformed()
    val lines = result.output.replace("\r\n", "\n").split('\n')
    var cursor = 0
    val results =
        commands.indices.map { index ->
            if (lines.getOrNull(cursor++) != "${prefix}${index}_BEGIN__") malformed()
            val start = cursor
            val end = "${prefix}${index}_END__:"
            while (cursor < lines.size && !lines[cursor].startsWith(end)) cursor++
            if (cursor == lines.size) malformed()
            val status = lines[cursor].removePrefix(end).toIntOrNull()?.takeIf { it in 0..255 } ?: malformed()
            val output = lines.subList(start, cursor).joinToString("\n")
            cursor++
            Adb.Result(status, output)
        }
    if (lines.drop(cursor).any { it.isNotEmpty() }) malformed()
    return results
}

internal fun parseProcessStat(
    serial: String,
    pid: Int,
    result: Adb.Result,
    command: List<String>,
): Adb.ProcessStat {
    if (result.exitCode != 0) {
        if ("No such file" !in result.output && "No such process" !in result.output) {
            throw AdbCommandException(
                serial,
                command,
                result.exitCode,
                result.output,
                "Unable to observe process $pid on $serial: ${result.output}",
            )
        }
        return Adb.ProcessStat.Gone
    }

    fun malformed(what: String) = AdbCommandException(serial, command, null, result.output, "$what /proc stat for PID $pid on $serial")
    // The kernel's comm field can contain spaces and parentheses; numeric fields follow its last ')'.
    val closingName = result.output.lastIndexOf(')')
    if (closingName < 0) throw malformed("Malformed")
    val fields =
        result.output
            .substring(closingName + 1)
            .trim()
            .split(Regex("\\s+"))
    if (fields.size <= 19) throw malformed("Incomplete")
    return Adb.ProcessStat.Live(fields[19]) // stat field 22: starttime in ticks since boot
}

internal fun parseProcessIds(
    serial: String,
    result: Adb.Result,
    command: List<String>,
): List<Int> {
    if (result.exitCode != 0) {
        if (result.exitCode != 1 || result.output.isNotBlank()) {
            throw AdbCommandException(
                serial,
                command,
                result.exitCode,
                result.output,
                "Unable to observe process IDs for $serial: ${result.output}",
            )
        }
        return emptyList()
    }
    val tokens = result.output.split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty()) {
        throw AdbCommandException(serial, command, null, result.output, "pidof succeeded without reporting a PID on $serial")
    }
    return tokens.map { token ->
        token.toIntOrNull()
            ?: throw AdbCommandException(serial, command, null, result.output, "pidof returned a nonnumeric PID on $serial: $token")
    }
}
