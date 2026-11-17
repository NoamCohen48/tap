package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.LoggedEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** Capture identity and loss metadata for one durable RPC event stream. */
data class HistoryCapture(
    val archive: RetainedLog,
    val entries: LogEntries,
)

/**
 * Portable redacted history, not an executable replay or proof of app correctness.
 * Export never stops capture. [events] are actual SDK calls, not inferred high-level agent steps.
 * [complete] means final capture without known loss or indeterminate admitted calls.
 * The caller chooses stdout, files or a test-report attachment; no I/O occurs on construction.
 */
class HistoryExport internal constructor(
    val connectionId: String,
    val recording: LogRecording?,
    val events: List<LoggedEvent>,
    val eventCapture: List<HistoryCapture>,
    val logs: Map<String, LogEntries>,
    val logsIncluded: Boolean,
    val complete: Boolean,
    val logArchives: Map<String, RetainedLog> = emptyMap(),
    val evidenceComplete: Boolean = complete,
) : Artifact {
    /** Standard proto3 JSON for calls, with exact string-encoded 64-bit values. */
    fun toJson(): String =
        buildJsonObject {
            put("format", "tap-history/1")
            put("connection_id", connectionId)
            put("complete", complete)
            put("evidence_complete", evidenceComplete)
            if (recording?.agentStepsExpected ==
                true
            ) {
                put("step_history_unavailable", "agent intent requires the original local agent history")
            }
            putJsonArray("steps") {}
            recording?.let { saved ->
                put("name", saved.name)
                put("recording", saved.name)
                put("state", if (saved.stoppedAtMs == null) "live" else "stopped")
                put(
                    "log_capture",
                    buildJsonObject {
                        put("complete", saved.complete)
                        put("capture_error", saved.captureError)
                        put("started_at_ms", saved.startedAtMs)
                        put("name", saved.name)
                        put("connection_id", saved.connectionId)
                        put(
                            "stopped_at_ms",
                            saved.stoppedAtMs?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull,
                        )
                        put("retained_bytes", saved.retainedBytes)
                        put("agent_steps_expected", saved.agentStepsExpected)
                        putJsonArray("ranges") {
                            saved.ranges.forEach { range ->
                                add(
                                    buildJsonObject {
                                        put("attached_device_id", range.attachedDeviceId)
                                        put("serial", range.serial)
                                        put("after_cursor", range.afterCursor)
                                        put(
                                            "through_cursor",
                                            range.throughCursor?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                                                ?: kotlinx.serialization.json.JsonNull,
                                        )
                                        put("events", range.events)
                                    },
                                )
                            }
                        }
                    },
                )
            }
            val cursors =
                eventCapture
                    .flatMap { capture ->
                        capture.entries.mapNotNull { entry ->
                            entry.event?.let { it.seq to "${capture.archive.attachedDeviceId}:${entry.cursor}" }
                        }
                    }.toMap()
            putJsonArray("events") { events.forEach { add(historyEventJson(it, cursors[it.seq])) } }
            putJsonArray("event_capture") {
                eventCapture.forEach { capture ->
                    add(
                        buildJsonObject {
                            put("attached_device_id", capture.archive.attachedDeviceId)
                            captureMetadata(capture.entries)
                            put("capture_scope", if (recording == null) "connection" else "recording")
                            putJsonArray("gaps") { capture.entries.filter { it.gap != null }.forEach { add(historyLogJson(it)) } }
                        },
                    )
                }
            }
            if (eventCapture.isEmpty()) put("events_unavailable", "no retained RPC event stream for this range")
            if (logsIncluded) {
                put(
                    "log",
                    buildJsonObject {
                        logs.forEach { (serial, entries) ->
                            put(
                                serial,
                                buildJsonObject {
                                    captureMetadata(entries)
                                    logArchives[serial]?.let { put("attached_device_id", it.attachedDeviceId) }
                                    put("capture_scope", if (recording == null) "connection" else "recording")
                                    putJsonArray("entries") { entries.forEach { add(historyLogJson(it)) } }
                                },
                            )
                        }
                    },
                )
            }
        }.toString()

    /** Human-readable chronological activity; log lines are observations, never inferred assertions. */
    val text: String get() {
        val rows =
            events.map { event ->
                val outcome =
                    when {
                        event.indeterminate -> "unknown"
                        event.hasError() -> event.error.code.name
                        event.hasFailure() -> event.failure.reason.name
                        else -> "ok"
                    }
                val encoded = eventJson(event)
                val call = encoded["command"] ?: encoded["app"] ?: encoded["device"]
                event.atEpochMs to "${Instant.ofEpochMilli(event.atEpochMs)}  ${event.seq}  ${event.serial}  $call → $outcome"
            } + logs.flatMap { (serial, entries) -> entries.map { it.at.toEpochMilli() to "$serial  $it" } }
        val lines = rows.sortedBy { it.first }.map { it.second }.toMutableList()
        (eventCapture.map { it.entries } + logs.values).forEach {
            if (it.captureError.isNotEmpty()) lines += "WARNING: ${it.captureError}"
            if (it.dropped > 0) lines += "WARNING: ${it.dropped} entries are unavailable"
        }
        eventCapture.flatMap { it.entries }.mapNotNull { it.gap }.forEach { lines += "WARNING: gap: $it" }
        if (recording != null) lines.add(0, "${recording.name} (${if (recording.stoppedAtMs == null) "live" else "stopped"})")
        if (recording?.agentStepsExpected == true) lines += "WARNING: agent intent requires the original local agent history"
        if (eventCapture.isEmpty()) lines += "WARNING: no retained RPC event stream for this range"
        return lines.ifEmpty { listOf("no retained activity") }.joinToString("\n") { line ->
            buildString { line.forEach { c -> if (c.code < 32 || c.code in 127..159) append("\\u%04x".format(c.code)) else append(c) } }
        }
    }

    override val bytes: ByteArray get() = toJson().encodeToByteArray()
    override val mediaType: String get() = "application/json"
    override val extension: String get() = "json"

    override fun toString(): String = text
}

private fun historyEventJson(
    event: LoggedEvent,
    cursor: String?,
): JsonObject =
    buildJsonObject {
        put("seq", event.seq)
        put(
            "at",
            java.time.format
                .DateTimeFormatterBuilder()
                .appendInstant(3)
                .toFormatter()
                .format(Instant.ofEpochMilli(event.atEpochMs)),
        )
        put("duration_ms", event.durationMs)
        put("serial", event.serial)
        put("ok", !event.indeterminate && !event.hasError() && !event.hasFailure())
        cursor?.let { put("cursor", it) }
        if (event.indeterminate) put("indeterminate", true)
        val raw = eventJson(event)
        for (key in listOf(
            "command",
            "app",
            "device",
            "error",
            "failure",
            "step",
            "clock_id",
            "attached_device_id",
            "started_monotonic_ns",
            "finished_monotonic_ns",
        )) {
            raw[key]?.let { put(key, it) }
        }
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.captureMetadata(entries: LogEntries) {
    put("dropped", entries.dropped)
    put("capture_error", entries.captureError)
    put("clock_id", entries.clockId)
}

private fun historyLogJson(entry: LogEntry): JsonObject =
    buildJsonObject {
        put("cursor", entry.cursor)
        put("at", entry.at.toString())
        entry.marker?.let {
            put("kind", "marker")
            put("marker", it)
        }
        entry.gap?.let {
            put("kind", "gap")
            put("gap", it)
        }
        entry.line?.let { line ->
            put("kind", "line")
            put(
                "line",
                buildJsonObject {
                    put("device_time", line.deviceTime.toEpochMilli() / 1000.0)
                    line.uid?.let { put("uid", it) }
                    put("pid", line.pid)
                    put("tid", line.tid)
                    line.priority?.let { put("priority", it.letter.toString()) }
                    put("tag", line.tag)
                    put("message", line.message)
                    putJsonArray("packages") { line.packages.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                },
            )
        }
        entry.action?.let { action ->
            put("kind", "action")
            put(
                "action",
                buildJsonObject {
                    put("operation", action.operation)
                    action.target?.let { put("target", it) }
                    put("finished", action.finished)
                    action.outcome?.let { put("outcome", it) }
                    action.duration?.let { put("duration", it.inWholeMilliseconds / 1000.0) }
                    action.requestId?.let { put("request_id", it) }
                    action.step?.let { put("step", it) }
                },
            )
        }
    }
