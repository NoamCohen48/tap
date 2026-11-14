package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.MarkLogRequest
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.RedactLogRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.github.noamcohen48.tap.api.v1.LogEntry as LogEntryProto
import io.github.noamcohen48.tap.api.v1.LogPriority as LogPriorityProto

/** A logcat priority; a later entry is more severe. */
enum class LogPriority {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    ;

    /** The letter logcat prints: `V`, `D`, `I`, `W`, `E` or `F`. */
    val letter: Char get() = name[0]
}

/** One logcat line. A message of several lines arrives as several entries. */
data class LogLine(
    /** The device's clock when the line was logged; it need not agree with the host's. */
    val deviceTime: Instant,
    /** The Linux uid of the process that logged it; null when logcat printed no number. */
    val uid: Int?,
    val pid: Int,
    val tid: Int,
    /** Null for a priority this client does not know. */
    val priority: LogPriority?,
    val tag: String,
    val message: String,
    /** The apps installed under [uid]: one for an app, several for apps sharing a uid, none for a system process or an app no longer installed. */
    val packages: List<String> = emptyList(),
)

/**
 * Something Tap did on the device: one entry when it starts and one when it finishes.
 *
 * A command the driver runs (a tap, typed text, a wait) is logged by the driver itself, so its
 * entries are ordered exactly among the app's lines; queries (`exists`, `count`, a snapshot) are
 * left out. A call the host runs over ADB (an app launch, a device setting) is written by the
 * server when the call starts and ends.
 */
data class LogAction(
    /** The operation in snake_case: `tap`, `wait_visible`, `launch`, `set_network`. */
    val operation: String,
    /** What it acted on: a command's selector, an app call's package. Never typed text. */
    val target: String?,
    val finished: Boolean,
    /** With [finished]: `ok`, a command error code (`NOT_FOUND`) or a failure reason. */
    val outcome: String?,
    /** With [finished]: how long it took. */
    val duration: Duration?,
    /** Driver commands: the request the driver answered. */
    val requestId: Long?,
    /** The step the call that caused it was made in ([withStep]), or null. */
    val step: String? = null,
)

/**
 * One entry of the device log: exactly one of [line], [marker], [action] and [gap] is set.
 * [toString] is the entry as one line of text.
 */
data class LogEntry(
    /** 1, 2, 3, … per attachment, in the order the server got the entries. [LogStart.After] reads what came after it. */
    val cursor: Long,
    /** The server host's clock when it got the entry. */
    val at: Instant,
    val line: LogLine? = null,
    /** The name given to [DeviceLog.mark]. */
    val marker: String? = null,
    val action: LogAction? = null,
    /** The capture was interrupted here (the device disconnected, logcat ended) and lines may be missing: why. */
    val gap: String? = null,
) {
    override fun toString(): String =
        when {
            line != null ->
                with(line) {
                    val millis = deviceTime.toEpochMilli()
                    "%d.%03d %5s %5d %5d %s %s: %s".format(
                        millis / 1_000,
                        millis % 1_000,
                        uid?.toString() ?: "?",
                        pid,
                        tid,
                        priority?.letter ?: '?',
                        tag,
                        message,
                    )
                }
            marker != null -> "── marker $marker"
            gap != null -> "── gap $gap"
            action != null ->
                buildString {
                    append("── ").append(action.operation)
                    action.target?.let { append("  ").append(it) }
                    if (action.finished) append("  → ").append(action.outcome)
                    action.duration?.let { append("  ").append(it.inWholeMilliseconds).append(" ms") }
                }
            else -> ""
        }
}

/** What [DeviceLog.read] returns: the entries, oldest first. */
data class LogEntries(
    val entries: List<LogEntry>,
    /**
     * How many entries older than the starting point the server had already forgotten (it keeps
     * the newest 16 MiB per attachment). Always 0 for [LogStart.Tail].
     */
    val dropped: Long,
) : List<LogEntry> by entries {
    /** The entries as text, one per line. */
    val text: String get() = entries.joinToString("") { "$it\n" }

    /** The logcat lines among the entries. */
    val lines: List<LogLine> get() = entries.mapNotNull { it.line }

    /** The message of each logcat line. */
    val messages: List<String> get() = lines.map { it.message }
}

/** Where a read of the device log starts. */
sealed interface LogStart {
    /** The oldest entry the server still has. */
    data object Oldest : LogStart

    /**
     * The newest marker with this name ([DeviceLog.mark]), the marker included. A read fails with
     * [ServerException] ([FailureReason.UNKNOWN_LOG_MARKER]) when there is none.
     */
    data class Marker(
        val name: String,
    ) : LogStart

    /** What came after this cursor ([LogEntry.cursor], or what [DeviceLog.mark] returned). */
    data class After(
        val cursor: Long,
    ) : LogStart

    /** The newest [count] entries that pass the filter (1 to 100000). */
    data class Tail(
        val count: Int,
    ) : LogStart

    /** The start of the newest action, that entry included; everything when there is none. */
    data object LastAction : LogStart
}

/**
 * Which entries a read keeps. The line filters apply to logcat lines only: markers, actions and
 * gaps always pass.
 */
data class LogFilter(
    /** Lines of these apps only. A package that is not installed fails the read. */
    val packages: List<String> = emptyList(),
    /** Lines at least this severe. */
    val minPriority: LogPriority? = null,
    /** Lines with one of these tags, compared exactly. */
    val tags: List<String> = emptyList(),
    /** Lines whose message contains a match of this regular expression (Java syntax), tested after redaction. */
    val grep: String? = null,
    /** False leaves Tap's action entries out. */
    val actions: Boolean = true,
    /**
     * Only one step (the id [withStep] passed its block): the entries from the step's first
     * action to its last, nothing when it ran no action. Not for [DeviceLog.follow].
     */
    val step: String? = null,
)

/**
 * The device log since the attachment opened ([Device.logs]): every logcat line of the main,
 * system and crash buffers, with the actions Tap ran and the markers a test wrote in between, in
 * the order they happened.
 *
 * The server captures it from the moment the device is attached, unless the attachment turned
 * that off ([DeviceOptions.captureLogs]; every call here then fails with [ServerException],
 * [FailureReason.DAEMON_PRECONDITION]). It keeps the newest 16 MiB and forgets them at
 * [Device.detach]. Secrets are replaced by `<redacted>` in everything read: see [redact].
 *
 * ```
 * device.logs.mark("checkout")
 * pay.tap()
 * val errors = device.logs.read(LogStart.Marker("checkout"), LogFilter(packages = listOf("com.shop"), minPriority = LogPriority.WARN))
 * check(errors.lines.isEmpty()) { errors.text }
 * ```
 */
class DeviceLog internal constructor(
    private val device: Device,
) {
    private val stub get() = device.ownerConnection.client.deviceLog

    private fun request(
        start: LogStart,
        filter: LogFilter,
        follow: Boolean,
    ): ReadLogRequest =
        ReadLogRequest
            .newBuilder()
            .setClientConnectionId(device.ownerConnection.id)
            .setAttachedDeviceId(device.attachedDeviceId)
            .setFollow(follow)
            .addAllPackages(filter.packages)
            .addAllTags(filter.tags)
            .setExcludeActions(!filter.actions)
            .apply {
                when (start) {
                    LogStart.Oldest -> Unit
                    is LogStart.Marker -> marker = start.name
                    is LogStart.After -> afterCursor = start.cursor
                    is LogStart.Tail -> tail = start.count
                    LogStart.LastAction -> lastAction = true
                }
                filter.minPriority?.let { minPriority = LogPriorityProto.forNumber(it.ordinal + 1) }
                filter.grep?.let { messageRegex = it }
                filter.step?.let { step = it }
            }.build()

    /** The entries so far from [start] that pass [filter], oldest first. */
    suspend fun read(
        start: LogStart = LogStart.Oldest,
        filter: LogFilter = LogFilter(),
        timeout: Duration = 30.seconds,
    ): LogEntries {
        ensureTapBound("Device.logs.read")
        return device.admitted("Device.logs.read") {
            mapped(device.serial) {
                val entries = mutableListOf<LogEntry>()
                var dropped = 0L
                stub
                    .withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .readLog(request(start, filter, follow = false))
                    .collect { response ->
                        dropped += response.dropped
                        response.entriesList.mapTo(entries) { it.toModel() }
                    }
                LogEntries(entries, dropped)
            }
        }
    }

    /**
     * The entries from [start] and then every new one as it is logged. The flow is cold: each
     * collection is one stream, which ends when the collector is cancelled or the device is
     * detached. Collect it in its own coroutine, or to wait for a line:
     *
     * ```
     * val settled = device.logs.follow(LogStart.Marker("checkout"), LogFilter(tags = listOf("Payments")))
     *     .first { it.line?.message?.contains("settled") == true }
     * ```
     *
     * A collector that reads too slowly is ended with [ServerException] (`RESOURCE_EXHAUSTED`);
     * follow again [LogStart.After] the last entry it got. A follower does not keep
     * [Device.detach] waiting.
     */
    fun follow(
        start: LogStart = LogStart.Oldest,
        filter: LogFilter = LogFilter(),
    ): Flow<LogEntry> =
        flow {
            ensureTapBound("Device.logs.follow")
            // Admission is checked, not held: a stream that lives as long as the attachment
            // must not make detach wait for it.
            device.admitted("Device.logs.follow") {}
            mapped(device.serial) {
                stub.readLog(request(start, filter, follow = true)).collect { response ->
                    for (entry in response.entriesList) emit(entry.toModel())
                }
            }
        }

    /**
     * Writes a named marker into the log, at the time the server gets the call, and returns its
     * cursor. Read from it with [LogStart.Marker]; a later marker with the same name replaces it
     * as the starting point. [name] is 1 to 200 characters on one line.
     */
    suspend fun mark(
        name: String,
        timeout: Duration = 30.seconds,
    ): Long {
        ensureTapBound("Device.logs.mark")
        return device.admitted("Device.logs.mark") {
            mapped(device.serial) {
                stub
                    .withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .markLog(
                        MarkLogRequest
                            .newBuilder()
                            .setClientConnectionId(device.ownerConnection.id)
                            .setAttachedDeviceId(device.attachedDeviceId)
                            .setName(name)
                            .build(),
                    ).cursor
            }
        }
    }

    /**
     * Adds regular expressions (Java syntax) whose matches are replaced by `<redacted>` in
     * everything read from now on, lines already captured included: by [read] and [follow],
     * [Device.deviceLogs] and the failure artifacts. A match is replaced whole; a pattern with a
     * capturing group has only its first group replaced (`session=(\w+)` keeps `session=`).
     *
     * Redacted without any call: `Authorization` / `Cookie` style headers, `password=` /
     * `token=` style values, bearer tokens, JWTs, and the text of every `sensitive` `setText` /
     * `typeText` on this device. At most 64 patterns per attachment.
     */
    suspend fun redact(
        vararg patterns: String,
        timeout: Duration = 30.seconds,
    ) {
        ensureTapBound("Device.logs.redact")
        device.admitted("Device.logs.redact") {
            mapped(device.serial) {
                stub
                    .withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .redactLog(
                        RedactLogRequest
                            .newBuilder()
                            .setClientConnectionId(device.ownerConnection.id)
                            .setAttachedDeviceId(device.attachedDeviceId)
                            .addAllPatterns(patterns.asList())
                            .build(),
                    )
            }
        }
    }
}

internal fun LogEntryProto.toModel(): LogEntry =
    LogEntry(
        cursor = cursor,
        at = Instant.ofEpochMilli(hostTimeMs),
        line =
            if (hasLine()) {
                LogLine(
                    deviceTime = Instant.ofEpochMilli(line.deviceTimeMs),
                    uid = if (line.hasUid()) line.uid else null,
                    pid = line.pid,
                    tid = line.tid,
                    priority = LogPriority.entries.getOrNull(line.priorityValue - 1),
                    tag = line.tag,
                    message = line.message,
                    packages = line.packagesList,
                )
            } else {
                null
            },
        marker = if (hasMarker()) marker else null,
        action =
            if (hasAction()) {
                LogAction(
                    operation = action.operation,
                    target = if (action.hasTarget()) action.target else null,
                    finished = action.finished,
                    outcome = if (action.hasOutcome()) action.outcome else null,
                    duration = if (action.hasDurationMs()) action.durationMs.milliseconds else null,
                    requestId = if (action.hasRequestId()) action.requestId else null,
                    step = if (action.hasStep()) action.step else null,
                )
            } else {
                null
            },
        gap = if (hasGap()) gap else null,
    )
