package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.daemon.core.LoggingPolicy
import java.nio.file.Path

/** Read/update local logging policy without starting or contacting a daemon. */
internal fun loggingPolicyConfiguration(
    line: CommandLine,
    directory: Path,
): String {
    val previous = LoggingPolicy.read(directory)
    val policy =
        if (line.command == "config logging-policy rolling") {
            val updated =
                try {
                    LoggingPolicy(
                        line.options["--max-age"]?.let(::ageLimit) ?: previous.maxAgeMs,
                        line.options["--max-size"]?.let(::byteLimit) ?: previous.maxBytes,
                        line.options["--hard-size"]?.let(::byteLimit) ?: previous.hardBytes,
                        line.options["--min-free"]?.let(::byteLimit) ?: previous.minFreeBytes,
                    )
                } catch (invalid: IllegalArgumentException) {
                    throw UsageException(invalid.message, line.command)
                }
            LoggingPolicy.write(directory, updated)
            updated
        } else {
            previous
        }
    return "rolling max-age=${policy.maxAgeMs}ms max-size=${policy.maxBytes}B hard-size=${policy.hardBytes}B min-free=${policy.minFreeBytes}B\n" +
        "state-dir=$directory; applies to running capture on its next append/read; protected recordings bypass rolling eviction only"
}

internal fun ageLimit(value: String): Long = scaledLimit(value, mapOf("s" to 1_000L, "m" to 60_000L, "h" to 3_600_000L, "d" to 86_400_000L))

internal fun byteLimit(value: String): Long =
    scaledLimit(
        value,
        mapOf(
            "" to 1L,
            "B" to 1L,
            "KiB" to 1024L,
            "MiB" to 1024L * 1024,
            "GiB" to 1024L * 1024 * 1024,
        ),
    )

private fun scaledLimit(
    value: String,
    units: Map<String, Long>,
): Long {
    val match = Regex("([0-9]+)([A-Za-z]*)").matchEntire(value) ?: throw UsageException("invalid limit '$value'")
    val scale = units[match.groupValues[2]] ?: throw UsageException("unsupported unit in '$value'")
    return try {
        Math.multiplyExact(match.groupValues[1].toLong(), scale)
    } catch (_: ArithmeticException) {
        throw UsageException("limit is too large")
    }
}
