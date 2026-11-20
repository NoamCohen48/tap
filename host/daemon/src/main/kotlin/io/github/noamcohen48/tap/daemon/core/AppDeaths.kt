package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.AppDeath
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The crashes and ANRs Android logged on one attached device since the attachment opened, and what
 * they explain.
 *
 * A `logcat -v epoch` child follows the events buffer's `am_crash` / `am_anr` and the crash
 * buffer's `AndroidRuntime` / `DEBUG` / `libc` lines for the attachment's lifetime ([start],
 * [close]). Each `am_crash` / `am_anr` becomes a numbered record ([AppDeath]); the crash-buffer
 * lines of the dying pid that came before it become its stack trace. The child is started again
 * from the last line's time when it exits; lines seen twice are dropped. While it is not running,
 * the questions below first read the same lines once (`logcat -d`), within 2 s.
 *
 * A package is watched from the moment an app call starts it ([watch]) or a command's selector
 * names it ([watchNamed]): watching first catches up on the log, so a death logged before does
 * not count. Only deaths of watched packages since their latest start explain failures
 * ([explain]): a command that failed in a way a dead app explains becomes `AUT_CRASHED` /
 * `AUT_ANR`, naming the app, with the code it replaces as its detail and the record as its
 * `app_death`. Each record explains one failure, and none once a client took it ([acknowledge]):
 * both make it handled. Another app crashing in the background explains nothing; it is still
 * listed ([list]).
 */
internal class AppDeaths(
    private val adb: Adb,
    private val serial: String,
    private val sinceEpochSeconds: Long?,
    private val log: (String) -> Unit = {},
    private val restartDelayMs: Long = RESTART_DELAY_MS,
) {
    private class Record(
        val sequence: Long,
        val kind: AppDeath.Kind,
        val packageName: String,
        val processName: String,
        val pid: Int,
        val atEpochMs: Long,
        val exceptionClass: String,
        val message: String,
        val stackTrace: String,
        val watched: Boolean,
        @Volatile var handled: Boolean = false,
    ) {
        fun proto(): AppDeath =
            AppDeath
                .newBuilder()
                .setSequence(sequence)
                .setKind(kind)
                .setPackageName(packageName)
                .setProcessName(processName)
                .setPid(pid)
                .setAtEpochMs(atEpochMs)
                .setExceptionClass(exceptionClass)
                .setMessage(message)
                .setStackTrace(stackTrace)
                .setWatched(watched)
                .setHandled(handled)
                .build()

        /** `com.shop crashed (pid 23554): java.lang.IllegalStateException: Out of stock`. */
        fun summary(): String =
            if (kind == AppDeath.Kind.KIND_ANR) {
                "$packageName stopped responding (pid $pid): $message"
            } else {
                "$packageName crashed (pid $pid): " + listOf(exceptionClass, message).filter { it.isNotEmpty() }.joinToString(": ")
            }
    }

    private val lock = Any()
    private val records = ArrayDeque<Record>()
    private var sequence = 0L

    /** Packages watched, each with the sequence at its latest start: deaths up to it are old. */
    private val startedAt = HashMap<String, Long>()

    /** Recent lines, to drop the ones a restarted child or a catch-up read sees again. */
    private val seen = LinkedHashSet<String>()

    /** Crash-buffer lines by the pid that logged them (`AndroidRuntime`, `libc`), and tombstones
     * (`DEBUG`) by the crash dumper's pid, with the pid they are about once their `pid:` line came. */
    private val stacks = LinkedHashMap<Int, StringBuilder>()
    private val tombstones = LinkedHashMap<Int, Pair<Int?, StringBuilder>>()
    private var lastLineEpochMs: Long? = null

    /** The latest sequence; waiters ([awaitDeathAfter]) collect it. */
    private val latest = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var follower: Job? = null

    @Volatile private var child: Process? = null

    @Volatile private var following = false

    /** Whether any package is watched: nothing else asks the device anything. */
    val watching: Boolean get() = synchronized(lock) { startedAt.isNotEmpty() }

    /** The largest sequence recorded so far (0 = none). */
    val lastSequence: Long get() = latest.value

    /** When the latest input (a mutating command or an app call) ended, `System.nanoTime()`. */
    @Volatile private var lastInputNanos: Long? = null

    /** Starts following the log; [close] stops it. */
    fun start() {
        synchronized(lock) {
            if (follower == null) follower = scope.launch { follow() }
        }
    }

    /** Set by [close]: ends [awaitRecordAfter]. */
    private val closed = MutableStateFlow(false)

    /** Stops the child and the follower; never throws. */
    fun close() {
        closed.value = true
        scope.cancel()
        child?.destroyForcibly()
    }

    /** From now on [packageName]'s new deaths explain failures; those already logged do not. */
    suspend fun watch(packageName: String) {
        catchUp()
        synchronized(lock) { startedAt[packageName] = sequence }
    }

    /** Watches each of [packageNames] not yet watched, as [watch] does; a watched one keeps its start. */
    suspend fun watchNamed(packageNames: Collection<String>) {
        val fresh = synchronized(lock) { packageNames.filter { it !in startedAt } }
        if (fresh.isEmpty()) return
        catchUp()
        synchronized(lock) { fresh.forEach { startedAt.putIfAbsent(it, sequence) } }
    }

    /** Notes that input reached the device now: [settle] waits from here. */
    fun inputSent() {
        lastInputNanos = System.nanoTime()
    }

    /**
     * Waits until [settleMs] after the latest input ([inputSent]), because Android logs a crash
     * shortly (10–200 ms) after the input that caused it. No wait when that has passed, when no
     * input was sent, or when no package is watched.
     */
    suspend fun settle(settleMs: Long) {
        if (settleMs <= 0 || !watching) return
        val last = lastInputNanos ?: return
        val remainingMs = settleMs - (System.nanoTime() - last) / 1_000_000
        if (remainingMs > 0) delay(remainingMs)
    }

    /** Marks the records with these sequences handled: a client took them, so they explain no later failure. */
    fun acknowledge(sequences: Collection<Long>) {
        val wanted = sequences.toSet()
        synchronized(lock) { records.forEach { if (it.sequence in wanted) it.handled = true } }
    }

    /** The records after [afterSequence], of [packageName] (any of its processes) when given. */
    suspend fun list(
        afterSequence: Long,
        packageName: String?,
    ): List<AppDeath> {
        if (!following) catchUp()
        return synchronized(lock) {
            records.filter { it.sequence > afterSequence && (packageName == null || it.packageName == packageName) }.map { it.proto() }
        }
    }

    /**
     * Suspends until a watched package dies after [afterSequence] (since its latest start) and
     * returns that death's sequence. Only the follower's lines wake it.
     */
    suspend fun awaitDeathAfter(afterSequence: Long): Long {
        var found: Long? = null
        latest.first {
            found = synchronized(lock) { fresh(afterSequence)?.sequence }
            found != null
        }
        return requireNotNull(found)
    }

    /**
     * Suspends until a death (of any app) after [afterSequence] is recorded, at most [timeoutMs].
     * Returns false when the attachment closed meanwhile. While the follower is down it reads the
     * log once a second, so a death still arrives.
     */
    suspend fun awaitRecordAfter(
        afterSequence: Long,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (latest.value <= afterSequence && !closed.value) {
            if (!following) catchUp()
            val leftMs = (deadline - System.nanoTime()) / 1_000_000
            if (leftMs <= 0) break
            withTimeoutOrNull(minOf(leftMs, AWAIT_READ_MS)) {
                combine(latest, closed) { last, ended -> last > afterSequence || ended }.first { it }
            }
        }
        return !closed.value
    }

    /**
     * [result], or when a watched app's death explains its failure, that failure. [endedFor] is the
     * death a wait was cancelled for ([awaitDeathAfter]): its `CANCELLED` is that death too.
     */
    suspend fun explain(
        result: CommandResult,
        endedFor: Long? = null,
    ): CommandResult {
        if (!result.hasError()) return result
        val ended = endedFor != null && result.error.code == ErrorCode.ERR_CANCELLED
        if (!ended && (result.error.code !in EXPLAINED || !watching)) return result
        if (!ended && !following) catchUp()
        val death =
            synchronized(lock) {
                val found = if (ended) records.firstOrNull { it.sequence == endedFor } else fresh(0)
                found?.also { it.handled = true }
            } ?: return result
        val replaced = result.error.code.name.removePrefix("ERR_")
        val original = listOfNotNull(replaced, result.error.takeIf { it.hasDetail() }?.detail, result.error.takeIf { it.hasMessage() }?.message)
        val what = if (ended) "the wait was ended ($replaced)" else "the command reported ${original.joinToString(": ")}"
        return result
            .toBuilder()
            .setError(
                result.error
                    .toBuilder()
                    .setCode(if (death.kind == AppDeath.Kind.KIND_ANR) ErrorCode.ERR_AUT_ANR else ErrorCode.ERR_AUT_CRASHED)
                    .setDetail(replaced)
                    .setMessage("${death.summary()}; $what")
                    .setAppDeath(death.proto()),
            ).build()
    }

    /** The first unhandled death of a watched package after its latest start and after [after]. */
    private fun fresh(after: Long): Record? =
        records.firstOrNull { record ->
            !record.handled && record.sequence > after && startedAt[record.packageName]?.let { record.sequence > it } == true
        }

    private suspend fun follow() {
        var failures = 0
        while (scope.isActive) {
            failures = runInterruptible { followLog(failures) }
            delay((restartDelayMs shl failures.coerceAtMost(5)).coerceAtMost(MAX_RESTART_DELAY_MS))
        }
    }

    /** Keep error handling and cleanup out of the coroutine state machine: GraalVM 21.0.2
     * cannot parse its combined suspension and exception paths. The caller interrupts this
     * blocking read on cancellation; close also destroys the child to unblock its pipe. */
    private fun followLog(previousFailures: Int): Int {
        var failures = previousFailures
        var read = false
        val since = synchronized(lock) { lastLineEpochMs } ?: sinceEpochSeconds?.times(1000)
        try {
            val process = adb.startLogcatEpoch(serial, BUFFERS, since, TAGS)
            child = process
            following = true
            // Catch-up reads and restarted followers can see the same lines; ingest deduplicates.
            process.inputStream.bufferedReader().use { reader ->
                while (scope.isActive) {
                    val line = reader.readLine() ?: break
                    read = true
                    failures = 0
                    ingest(line)
                }
            }
            // A child that ends without a line (device gone, logcat refused) is a failure too.
            if (!read && failures++ == 0) log("crash log of $serial not followed: logcat ended at once")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Let runInterruptible translate an interrupted read into cancellation, not a retry.
            if (!scope.isActive) throw error
            if (failures++ == 0) log("crash log of $serial not followed: ${error.message}")
        } finally {
            following = false
            child?.destroyForcibly()
            child = null
        }
        return failures
    }

    /** Reads what the log holds now, within [READ_TIMEOUT_MS]; a failed read leaves the record as it is. */
    private suspend fun catchUp() {
        val since = sinceEpochSeconds?.times(1000)
        try {
            withTimeoutOrNull(READ_TIMEOUT_MS) { adb.logcatEpoch(serial, BUFFERS, since, TAGS) }?.lineSequence()?.forEach(::ingest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("crash log of $serial unreadable: ${error.message}")
        }
    }

    internal fun ingest(line: String) {
        val parsed = LOG_LINE.matchEntire(line) ?: return
        val (seconds, millis, pidText, tag, text) = parsed.destructured
        val atEpochMs = seconds.toLong() * 1000 + millis.padEnd(3, '0').take(3).toLong()
        val pid = pidText.toInt()
        synchronized(lock) {
            if (!seen.add(line)) return
            if (seen.size > SEEN_LINES) seen.remove(seen.first())
            lastLineEpochMs = maxOf(lastLineEpochMs ?: atEpochMs, atEpochMs)
            when (tag) {
                "AndroidRuntime", "libc" -> {
                    if (text.startsWith("FATAL EXCEPTION") || text.startsWith("Fatal signal")) stacks.remove(pid)
                    bounded(stacks, pid) { StringBuilder() }.appendBounded(text)
                }
                "DEBUG" -> {
                    if (text.startsWith("*** ***")) tombstones.remove(pid)
                    val (about, trace) = bounded(tombstones, pid) { null to StringBuilder() }
                    val target = about ?: TOMBSTONE_PID.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    tombstones[pid] = target to trace.appendBounded(text)
                }
                CRASH_TAG, ANR_TAG -> {
                    val death = parseEvent(tag, text) ?: return
                    val trace =
                        if (death.kind == AppDeath.Kind.KIND_CRASH) {
                            val java = stacks.remove(death.pid)?.toString()
                            val native = tombstones.entries.firstOrNull { it.value.first == death.pid }?.also { tombstones.remove(it.key) }?.value?.second?.toString()
                            listOfNotNull(java, native).joinToString("").trimEnd()
                        } else {
                            ""
                        }
                    sequence += 1
                    records.addLast(
                        Record(
                            sequence = sequence,
                            kind = death.kind,
                            packageName = death.process.substringBefore(':'),
                            processName = death.process,
                            pid = death.pid,
                            atEpochMs = atEpochMs,
                            exceptionClass = death.exceptionClass,
                            message = death.message,
                            stackTrace = trace,
                            watched = death.process.substringBefore(':') in startedAt,
                        ),
                    )
                    if (records.size > MAX_RECORDS) records.removeFirst()
                    latest.value = sequence
                }
            }
        }
    }

    private class Event(
        val kind: AppDeath.Kind,
        val pid: Int,
        val process: String,
        val exceptionClass: String,
        val message: String,
    )

    /**
     * `am_crash: [pid,user,process,flags,exception,message,file,line(,recoverable)]` (the last
     * field since Android 14; a message may hold commas) and `am_anr: [user,pid,process,flags,reason]`:
     * the pid comes first in a crash, second in an ANR (as an API 34 emulator logs them).
     */
    private fun parseEvent(
        tag: String,
        text: String,
    ): Event? {
        val body = text.trim().removePrefix("[").removeSuffix("]")
        val fields = body.split(',')
        if (fields.size < 5) return null
        val pid = fields[if (tag == ANR_TAG) 1 else 0].trim().toIntOrNull() ?: return null
        val process = fields[2].trim().takeIf { it.isNotEmpty() } ?: return null
        if (tag == ANR_TAG) return Event(AppDeath.Kind.KIND_ANR, pid, process, "", fields.drop(4).joinToString(",").trim())
        val tail = if (fields.size >= 9 && fields.takeLast(2).all { it.trim().toIntOrNull() != null }) 3 else 2
        if (fields.size < 5 + tail) return null
        return Event(AppDeath.Kind.KIND_CRASH, pid, process, fields[4].trim(), fields.subList(5, fields.size - tail).joinToString(",").trim())
    }

    private fun <K, V> bounded(
        map: LinkedHashMap<K, V>,
        key: K,
        create: () -> V,
    ): V {
        val value = map.getOrPut(key, create)
        while (map.size > MAX_STACKS) map.remove(map.keys.first())
        return value
    }

    private fun StringBuilder.appendBounded(text: String): StringBuilder {
        if (length + text.length + 1 <= MAX_STACK_CHARS) append(text).append('\n')
        return this
    }

    private companion object {
        const val CRASH_TAG = "am_crash"
        const val ANR_TAG = "am_anr"
        val BUFFERS = listOf("events", "crash")
        val TAGS = listOf(CRASH_TAG, ANR_TAG, "AndroidRuntime", "DEBUG", "libc")
        const val READ_TIMEOUT_MS = 2_000L
        const val RESTART_DELAY_MS = 1_000L

        /** How often [awaitRecordAfter] reads the log while the follower is down. */
        const val AWAIT_READ_MS = 1_000L
        const val MAX_RESTART_DELAY_MS = 30_000L
        const val MAX_RECORDS = 256
        const val MAX_STACKS = 16
        const val MAX_STACK_CHARS = 32 * 1024
        const val SEEN_LINES = 2_048

        /** `1731577334.107  574  5281 I am_crash: [...]`: time, pid, tid, level, tag, text. */
        val LOG_LINE = Regex("""^\s*(\d+)\.(\d+)\s+(\d+)\s+\d+\s+[VDIWEFA]\s+(\S+?)\s*: ?(.*)$""")
        val TOMBSTONE_PID = Regex("""\bpid: (\d+),""")

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
