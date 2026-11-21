package io.github.noamcohen48.tap.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

private val DEATH_TIME = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

internal fun historyDeathJson(
    capture: HistoryCapture,
    entry: LogEntry,
): JsonObject =
    buildJsonObject {
        val envelope = requireNotNull(entry.death)
        val value = envelope.death
        put("connection_id", capture.archive.connectionId)
        put("attached_device_id", envelope.attachedDeviceId)
        put("serial", envelope.serial)
        put("sequence", value.sequence)
        put(
            "kind",
            value.kind.name
                .removePrefix("KIND_")
                .lowercase(),
        )
        put("at", DEATH_TIME.format(entry.at))
        put("timestamp_source", "host_receipt")
        put("clock_id", capture.entries.clockId)
        put("cursor", "${capture.archive.connectionId}:${entry.cursor}")
        put("host_monotonic_ns", entry.hostMonotonicNs.toString())
        if (value.atEpochMs != 0L) put("device_at", DEATH_TIME.format(Instant.ofEpochMilli(value.atEpochMs)))
        put("package_name", value.packageName)
        put("process_name", value.processName)
        put("pid", value.pid)
        put("exception_class", value.exceptionClass)
        put("message", value.message)
        put("stack_trace", value.stackTrace)
        put("stack_trace_available", value.stackTrace.isNotEmpty())
        put("watched", value.watched)
        put("handled_at_capture", value.handled)
    }

internal fun historyDeathRows(
    entry: LogEntry,
    details: Boolean,
): List<Pair<Long, String>> {
    val envelope = requireNotNull(entry.death)
    val value = envelope.death
    val at = entry.at.toEpochMilli()
    val description = listOf(value.exceptionClass, value.message).filter { it.isNotEmpty() }.joinToString(": ")
    val summary =
        "${entry.at}  ${envelope.serial}  ${value.kind.name.removePrefix("KIND_")} ${value.packageName} (pid ${value.pid})" +
            if (description.isEmpty()) "" else ": $description"
    val rows = mutableListOf(at to summary)
    if (details) {
        if (value.stackTrace.isEmpty()) {
            rows += at to "    | No stack trace was supplied by the captured death source"
        } else {
            value.stackTrace.lines().forEach { rows += at to "    | $it" }
        }
    }
    return rows
}
