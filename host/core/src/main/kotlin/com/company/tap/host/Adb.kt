package com.company.tap.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/**
 * Marks the raw `adb -s <serial> ...` escape hatch. Product code goes through [Adb]'s typed
 * operations so every command line, its timeout and its output parsing live in one place;
 * only validation and fault-injection tooling (`:host:validation`) opt in to run arbitrary
 * ADB commands.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Raw ADB is for validation tooling; add a typed operation to Adb instead.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RawAdb

/**
 * An ADB child or its output drain survived the bounded reap, so cleanup is unproven. The
 * device may have mutated; the caller must quarantine, never trust or retry blindly. Carries
 * the command and serial for the journal reason and diagnostics.
 */
class AdbReapUncertainException(
    message: String,
    val command: List<String>,
    val serial: String?,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * A command that never started because another serial owns this shared runner's residual
 * capacity. Temporary and non-poisoning: the caller did not mutate (no process was started),
 * so it must neither quarantine nor poison its session — it retries later or on another runner.
 * Carries the attempted serial plus the blocking residual's serial/command for diagnostics.
 * Distinct from [AdbReapUncertainException], which means THIS command started and left an
 * uncertain residual and must poison/quarantine.
 */
class AdbRunnerGatedException(
    message: String,
    val attemptSerial: String?,
    val blockingSerial: String?,
    val blockingCommand: List<String>,
) : IllegalStateException(message)

/**
 * Every ADB interaction of the host, one typed method per command. All device commands are
 * serial-specific (`-s`), bounded by a timeout, and return captured output; the parsing of
 * that output lives here too, so a device-family quirk is fixed once. `open` so tests can
 * substitute a fake.
 */
open class Adb(
    val executable: String = "adb",
) {
    /** Starts an OS process; tests substitute a [FakeProcess] so cancellation and reap are
     * deterministic without real subprocesses. Production default is the real [ProcessBuilder]. */
    internal var processStarter: ProcessStarter =
        ProcessStarter { command ->
            ProcessBuilder(command).redirectErrorStream(true).start()
        }

    private val admissionMutex = Mutex()

    private data class ReapResidual(
        val token: Long,
        val serial: String?,
        val command: List<String>,
        val process: Process,
        val drain: Deferred<String>,
        val executor: java.util.concurrent.ExecutorService,
        val scope: CoroutineScope,
    )

    // Admission state, guarded by [admissionMutex]. `inFlight` holds one token per admitted
    // operation from before process start through proven reap; a reap-uncertain operation keeps
    // its token by moving it to `residuals` until its drain completes and its process is dead.
    // Every token is a monotonic id released exactly once by its owner, so a stale completion can
    // never free a newer holder (no ABA): prune/remove match by token, never by emptiness or index.
    private var nextAdmissionId = 0L
    private val inFlight = HashMap<Long, String?>()
    private val reapResiduals = LinkedHashMap<Long, ReapResidual>()

    /** Test-only probe invoked when a caller enters the admission wait for an in-flight
     * permit, so a test can prove the waiter is parked before cancelling it. Null in production. */
    internal var admissionWaitProbeForTest: (() -> Unit)? = null

    /** Test-only probe invoked synchronously before token bookkeeping (release or residual
     * transfer) acquires [admissionMutex], so a test holding the mutex can prove the attempt is
     * pended before releasing the barrier. Null in production. */
    internal var admissionBookkeepingProbeForTest: (() -> Unit)? = null

    /**
     * Test-only deterministic barrier: holds [admissionMutex] between [entered] and [release],
     * so a test can strand token bookkeeping on the mutex, cancel the owner, then release and
     * prove the bookkeeping still completed. Internal, never part of the supported surface.
     */
    internal suspend fun holdAdmissionForTest(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        admissionMutex.withLock {
            entered.complete(Unit)
            release.await()
        }
    }

    /** Test visibility: whether this runner currently gates new starts on an unreaped drain. */
    internal suspend fun isReapGatedForTest(): Boolean =
        admissionMutex.withLock {
            pruneCompletedResiduals()
            reapResiduals.isNotEmpty() || inFlight.isNotEmpty()
        }

    /** Test visibility: admitted-but-unproven operations plus unresolved residuals. */
    internal suspend fun admissionCountForTest(): Int =
        admissionMutex.withLock {
            pruneCompletedResiduals()
            inFlight.size + reapResiduals.size
        }

    private fun serialOf(command: List<String>): String? = if (command.size >= 3 && command[1] == "-s") command[2] else null

    /** Removes only the residuals whose own drain completed and whose own process is dead. */
    private fun pruneCompletedResiduals() {
        val it = reapResiduals.iterator()
        while (it.hasNext()) {
            val residual = it.next().value
            if (residual.drain.isCompleted && !residual.process.isAlive) {
                runCatching { residual.scope.cancel() }
                residual.executor.shutdownNow()
                it.remove()
            }
        }
    }

    /**
     * Linearized admission: the gate check and the token reservation are one mutex step, so
     * concurrent callers can never both observe an empty gate and both start. A residual-held
     * permit rejects immediately with [AdbRunnerGatedException] (temporary, non-poisoning: the
     * residual may live long, so waiting would hang). An in-flight-held permit waits cancellably
     * and re-checks, so a queued caller cancelled before admission never starts a process; once
     * the holder's reap leaves a residual, waiters observe it and reject rather than pile on.
     */
    private suspend fun admit(attemptSerial: String?): Long {
        while (true) {
            admissionMutex.withLock {
                pruneCompletedResiduals()
                if (reapResiduals.isNotEmpty()) {
                    val blocking = reapResiduals.values.first()
                    throw AdbRunnerGatedException(
                        "ADB runner gated by unreaped drain (${blocking.command.joinToString(" ")}" +
                            " on ${blocking.serial ?: "no serial"}); refusing start for ${attemptSerial ?: "no serial"}",
                        attemptSerial,
                        blocking.serial,
                        blocking.command,
                    )
                }
                if (inFlight.size < ADB_RUNNER_PERMITS) {
                    val token = nextAdmissionId++
                    inFlight[token] = attemptSerial
                    return token
                }
            }
            // Permit held by a live in-flight operation, not a residual: wait cancellably for the
            // holder to release or convert, then re-check. Cancellation propagates before start.
            currentCoroutineContext().ensureActive()
            admissionWaitProbeForTest?.invoke()
            delay(10)
        }
    }

    /**
     * Releases one exact admission token. Runs NonCancellable: the caller is typically already
     * cancelled (local timeout or enclosing deadline) when reap finishes, and a cancellable lock
     * here would skip the decrement, leak the token, and mask the original failure with a
     * bookkeeping [CancellationException]. Never throws, so the primary failure propagates intact.
     */
    private suspend fun releaseAdmission(token: Long) {
        admissionBookkeepingProbeForTest?.invoke()
        withContext(NonCancellable) {
            admissionMutex.withLock { inFlight.remove(token) }
        }
    }

    /**
     * Atomically moves one exact token from admitted to residual. Runs NonCancellable for the
     * same reason as [releaseAdmission]: the transfer must land before any release, even under
     * an already-cancelled caller, so the uncertain operation gates the runner until its own
     * process/drain resolves instead of leaking its permit or masking the reap exception.
     */
    private suspend fun transferToResidual(
        token: Long,
        serial: String?,
        command: List<String>,
        process: Process,
        drain: Deferred<String>,
        executor: java.util.concurrent.ExecutorService,
        scope: CoroutineScope,
    ) {
        admissionBookkeepingProbeForTest?.invoke()
        withContext(NonCancellable) {
            admissionMutex.withLock {
                inFlight.remove(token)
                reapResiduals[token] = ReapResidual(token, serial, command, process, drain, executor, scope)
            }
        }
    }

    data class Result(
        val exitCode: Int,
        val output: String,
    )

    /** What `/proc/<pid>/stat` says about a process: alive with its start token, or gone. */
    sealed class ProcessStat {
        data class Live(
            val startToken: String,
        ) : ProcessStat()

        object Gone : ProcessStat()
    }

    @RawAdb
    suspend fun run(
        serial: String,
        vararg arguments: String,
        timeoutMs: Long = 30_000,
    ): String = exec(serial, *arguments, timeoutMs = timeoutMs)

    @RawAdb
    suspend fun runResult(
        serial: String,
        vararg arguments: String,
        timeoutMs: Long = 30_000,
    ): Result = execResult(serial, *arguments, timeoutMs = timeoutMs)

    /** Runs the command and returns its output; a non-zero exit is an [IllegalStateException]. */
    protected suspend fun exec(
        serial: String,
        vararg arguments: String,
        timeoutMs: Long = 30_000,
    ): String {
        val result = execResult(serial, *arguments, timeoutMs = timeoutMs)
        check(result.exitCode == 0) { "ADB command failed: ${result.output}" }
        return result.output
    }

    protected open suspend fun execResult(
        serial: String,
        vararg arguments: String,
        timeoutMs: Long = 30_000,
    ): Result {
        val (exitCode, output) =
            runAdbProcess(
                listOf(executable, "-s", serial) + arguments,
                timeoutMs,
            ) { "ADB command timed out for $serial: ${arguments.joinToString(" ")}" }
        return Result(exitCode, output.trim())
    }

    /** Serials of devices currently in the `device` state (not offline/unauthorized). */
    open suspend fun devices(timeoutMs: Long = 10_000): List<String> {
        val (exitCode, text) =
            runAdbProcess(listOf(executable, "devices"), timeoutMs) { "adb devices timed out" }
        check(exitCode == 0) { "adb devices failed: $text" }
        return text
            .lineSequence()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" }
            .map { it[0] }
            .toList()
    }

    /**
     * Runs one process with a locally owned deadline and reaps it in all outcomes. The local
     * timeout is a null, never an exception: an outer cancellation propagates as cancellation
     * instead of being converted into the timeout's [IllegalStateException].
     *
     * Admission holds one of [ADB_RUNNER_PERMITS] tokens from before process start through proven
     * reap. The empty-gate check and the reservation are one mutex step, so concurrent starters
     * can never both observe an empty gate; a caller rejected here never started a process and
     * gets the temporary [AdbRunnerGatedException], never the uncertain [AdbReapUncertainException].
     * The output drain lives in a locally owned scope on a dedicated daemon thread, never as a
     * child of the caller's scope: a drain that ignores cancellation and stream closure (a
     * blocking read) therefore cannot structurally block return after [ADB_REAP_TIMEOUT_MS].
     * When process or drain death cannot be proven within the bound, this throws
     * [AdbReapUncertainException] (with any in-flight failure suppressed into it) so the caller
     * quarantines instead of trusting unproven cleanup. The uncertain operation keeps its token as
     * a residual until its own drain completes and its own process is dead, so sequential calls cannot pile up
     * abandoned drains; the executor is shut down on every path and its thread is a daemon, so
     * even the one gated drain cannot hold the JVM open.
     */
    private suspend fun runAdbProcess(
        command: List<String>,
        timeoutMs: Long,
        timeoutMessage: () -> String,
    ): Pair<Int, String> {
        val attemptSerial = serialOf(command)
        val token = admit(attemptSerial)
        var transferred = false
        lateinit var process: Process
        // Install cleanup ownership before returning to the caller's cancellable context. A
        // cancellation while start() is returning cannot discard an already-created child.
        try {
            withContext(NonCancellable) {
                process = withContext(Dispatchers.IO) { processStarter.start(command) }
            }
        } catch (error: Throwable) {
            releaseAdmission(token)
            throw error
        }
        val drainExecutor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "adb-output-drain").apply { isDaemon = true }
            }
        val drainScope = CoroutineScope(SupervisorJob() + drainExecutor.asCoroutineDispatcher())
        val drain = drainScope.async { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            var primary: Throwable? = null
            var outcome: Pair<Int, String>? = null
            try {
                currentCoroutineContext().ensureActive()
                // `Process.waitFor` is not cancellable, so the deadline is a polling loop.
                val exited =
                    withTimeoutOrNull(timeoutMs) {
                        while (process.isAlive) delay(10)
                        true
                    }
                check(exited == true) { timeoutMessage() }
                withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
                val text =
                    withTimeoutOrNull(5_000) { drain.await() }
                        ?: throw IllegalStateException("ADB output drain timed out: ${command.joinToString(" ")}")
                outcome = process.exitValue() to text
            } catch (error: Throwable) {
                primary = error
            }
            try {
                reap(command, process, drain, primary)
            } catch (reapError: AdbReapUncertainException) {
                primary?.let(reapError::addSuppressed)
                transferToResidual(token, serialOf(command), command, process, drain, drainExecutor, drainScope)
                transferred = true
                throw reapError
            } catch (reapError: Throwable) {
                primary?.let(reapError::addSuppressed)
                throw reapError
            }
            if (primary != null) throw primary
            check(outcome != null) { "ADB process produced neither output nor failure" }
            return outcome
        } finally {
            drainScope.cancel()
            drainExecutor.shutdownNow()
            if (!transferred) releaseAdmission(token)
        }
    }

    /** Destroys a child that may still be alive (local timeout or caller cancellation), closes
     * its streams to unblock the drain, and reaps both within one aggregate [ADB_REAP_TIMEOUT_MS]
     * deadline shared by the drain join and the process wait/destroy. Never waits beyond the bound:
     * when process or drain death cannot be proven, throws [AdbReapUncertainException] so the
     * caller quarantines instead of trusting unproven cleanup. The in-flight failure rides in the
     * message (cancellation machinery may drop the suppressed chain on delivery) as well as
     * suppressed. */
    private suspend fun reap(
        command: List<String>,
        process: Process,
        drain: Deferred<String>,
        primary: Throwable?,
    ) {
        runCatching { if (process.isAlive) process.destroy() }
        closeProcessStreams(process)
        drain.cancel()
        val deadlineNanos = System.nanoTime() + ADB_REAP_TIMEOUT_MS * 1_000_000L

        fun remainingMs(): Long = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        withContext(NonCancellable) {
            withTimeoutOrNull(remainingMs()) { drain.join() }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
                val remaining = remainingMs()
                if (remaining > 0) {
                    withContext(Dispatchers.IO) {
                        runCatching { process.waitFor(remaining, TimeUnit.MILLISECONDS) }
                    }
                }
            }
        }
        if (drain.isCompleted && !process.isAlive) return
        val inFlight = primary?.let { "; in-flight failure: $it" } ?: ""
        throw AdbReapUncertainException(
            "ADB process or output drain survived bounded reap " +
                "(${ADB_REAP_TIMEOUT_MS}ms): ${command.joinToString(" ")} " +
                "(processAlive=${process.isAlive}, drainDone=${drain.isCompleted})$inFlight",
            command,
            serialOf(command),
            primary,
        )
    }

    /**
     * Best-effort screen preparation: wakes the display and dismisses an insecure keyguard so
     * the AUT can reach the foreground. A secure lock is not bypassed; the app-visible wait
     * then reports the lock screen package as the last observation.
     */
    open suspend fun wakeAndDismissKeyguard(serial: String) {
        execResult(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", timeoutMs = 10_000)
        execResult(serial, "shell", "wm", "dismiss-keyguard", timeoutMs = 10_000)
    }

    // ---- device identity and kernel state ----------------------------------------------------

    /** The kernel's boot identity; changes exactly when the device reboots. */
    open suspend fun bootId(serial: String): String = exec(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")

    /** Whether a TCP socket is listening on [port] (IPv4 or IPv6), from `/proc/net/tcp*`. */
    open suspend fun isPortListening(
        serial: String,
        port: Int,
    ): Boolean {
        val expectedPort = port.toString(16).uppercase().padStart(4, '0')
        return exec(serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .any { fields -> fields.size > 3 && fields[1].endsWith(":$expectedPort") && fields[3] == "0A" }
    }

    /**
     * Reads `/proc/[pid]/stat`. [ProcessStat.Gone] when the kernel says the process no longer
     * exists; anything else unreadable or malformed is an [IllegalStateException], because a
     * process whose identity cannot be read must never be mistaken for a dead one.
     */
    open suspend fun processStat(
        serial: String,
        pid: Int,
        timeoutMs: Long = 30_000,
    ): ProcessStat {
        val result = execResult(serial, "shell", "cat", "/proc/$pid/stat", timeoutMs = timeoutMs)
        if (result.exitCode != 0) {
            check("No such file" in result.output || "No such process" in result.output) {
                "Unable to observe process $pid on $serial: ${result.output}"
            }
            return ProcessStat.Gone
        }
        // The command name is in parentheses and may itself contain spaces; fields follow it.
        val closingName = result.output.lastIndexOf(')')
        check(closingName >= 0) { "Malformed /proc stat for PID $pid" }
        val fieldsFromState =
            result.output
                .substring(closingName + 1)
                .trim()
                .split(Regex("\\s+"))
        check(fieldsFromState.size > 19) { "Incomplete /proc stat for PID $pid" }
        return ProcessStat.Live(fieldsFromState[19]) // starttime: clock ticks since boot
    }

    // ---- packages and processes --------------------------------------------------------------

    open suspend fun install(
        serial: String,
        apk: Path,
        timeoutMs: Long = 120_000,
    ) {
        exec(serial, "install", "-r", "-t", apk.absolutePathString(), timeoutMs = timeoutMs)
    }

    /** `adb uninstall`; returns the tool's output for the caller's diagnostics. */
    open suspend fun uninstall(
        serial: String,
        packageName: String,
    ): String = exec(serial, "uninstall", packageName)

    /** `pm path` lists at least one APK for the package. */
    open suspend fun isInstalled(
        serial: String,
        packageName: String,
    ): Boolean = exec(serial, "shell", "pm", "path", packageName).lineSequence().any { it.startsWith("package:") }

    /** `pm clear`; returns the output, which says `Success` when it worked. */
    open suspend fun clearData(
        serial: String,
        packageName: String,
    ): String = exec(serial, "shell", "pm", "clear", packageName)

    open suspend fun grantPermission(
        serial: String,
        packageName: String,
        permission: String,
    ) {
        exec(serial, "shell", "pm", "grant", packageName, permission)
    }

    /** `am force-stop`; proves nothing by itself — callers poll [processIds]. */
    open suspend fun forceStop(
        serial: String,
        packageName: String,
    ) {
        exec(serial, "shell", "am", "force-stop", packageName)
    }

    /** `am start -W -n component`; returns the output, which names `Error`/`Exception` on failure. */
    open suspend fun startActivity(
        serial: String,
        component: String,
        timeoutMs: Long,
    ): String = exec(serial, "shell", "am", "start", "-W", "-n", component, timeoutMs = timeoutMs)

    /** The package's MAIN/LAUNCHER activity as `package/activity`, or null when it has none. */
    open suspend fun launcherActivity(
        serial: String,
        packageName: String,
    ): String? =
        exec(
            serial,
            "shell",
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            packageName,
        ).lineSequence().map(String::trim).lastOrNull { it.startsWith("$packageName/") }

    // ---- forwards ----------------------------------------------------------------------------

    open suspend fun forward(
        serial: String,
        devicePort: Int,
    ): Int = exec(serial, "forward", "tcp:0", "tcp:$devicePort").toInt()

    open suspend fun removeForward(
        serial: String,
        hostPort: Int,
    ) {
        exec(serial, "forward", "--remove", "tcp:$hostPort")
    }

    open suspend fun forwards(serial: String): List<Forwarding> =
        exec(serial, "forward", "--list")
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size != 3 || fields[0] != serial) return@mapNotNull null
                val hostPort = fields[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                val devicePort = fields[2].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                Forwarding(serial, hostPort, devicePort)
            }.toList()

    open suspend fun processIds(
        serial: String,
        packageName: String,
    ): List<Int> {
        val result = execResult(serial, "shell", "pidof", packageName)
        if (result.exitCode != 0) {
            check(result.exitCode == 1 && result.output.isBlank()) {
                "Unable to observe process IDs for $serial: ${result.output}"
            }
            return emptyList()
        }
        val tokens = result.output.split(Regex("\\s+")).filter(String::isNotBlank)
        check(tokens.isNotEmpty()) { "pidof succeeded without reporting a PID" }
        return tokens.map { token ->
            requireNotNull(token.toIntOrNull()) { "pidof returned a nonnumeric PID: $token" }
        }
    }

    data class Forwarding(
        val serial: String,
        val hostPort: Int,
        val devicePort: Int,
    )
}

internal fun closeProcessStreams(
    process: Process,
    recordFailure: (Throwable) -> Unit = {},
) {
    try {
        process.outputStream.close()
    } catch (error: Throwable) {
        recordFailure(error)
    }
    try {
        process.inputStream.close()
    } catch (error: Throwable) {
        recordFailure(error)
    }
    try {
        process.errorStream.close()
    } catch (error: Throwable) {
        recordFailure(error)
    }
}

/** Starts an OS process; a `fun interface` so tests can substitute a [FakeProcess]. */
fun interface ProcessStarter {
    fun start(command: List<String>): Process
}

/** The real process starter: the command with merged stderr, exactly as [ProcessBuilder] runs it. */
val DefaultProcessStarter =
    ProcessStarter { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    }

/** Bound for reaping an ADB child and its output drain; never unbounded. */
const val ADB_REAP_TIMEOUT_MS = 2_000L

/**
 * Fixed capacity of one shared [Adb] runner: a single admitted operation plus at most one
 * unresolved residual. The smallest cap consistent with the existing sequential use (one ADB
 * at a time per session; the local matrix drives two serials through one shared runner):
 * correctness first — concurrent starters can never pile up abandoned drains/processes beyond
 * one — at the cost of serializing shared-runner ADB. A caller refused here never started, so it
 * retries later; only a started command that left uncertainty quarantines. Revisit with per-serial
 * runners if the matrix outgrows single-flight throughput.
 */
const val ADB_RUNNER_PERMITS = 1
