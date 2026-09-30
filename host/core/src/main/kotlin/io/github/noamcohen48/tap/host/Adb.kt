package io.github.noamcohen48.tap.host

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
) : TapHostException(message, cause)

/**
 * A command that never started because its serial's lane (or, when every global slot is held
 * by unresolved residuals, the whole runner) is gated by an unreaped residual. Temporary and
 * non-poisoning: the caller did not mutate (no process was started), so it must neither
 * quarantine nor poison its session — it retries later.
 * Carries the attempted serial plus the blocking residual's serial/command for diagnostics.
 * Distinct from [AdbReapUncertainException], which means THIS command started and left an
 * uncertain residual and must poison/quarantine.
 */
class AdbRunnerGatedException(
    message: String,
    val attemptSerial: String?,
    val blockingSerial: String?,
    val blockingCommand: List<String>,
) : TapHostException(message)

/**
 * Every ADB interaction of the host, one typed method per command. All device commands are
 * serial-specific (`-s`), bounded by a timeout, and return captured output; the parsing of
 * that output lives here too, so a device-family quirk is fixed once. `open` so tests can
 * substitute a fake; [processStarter] starts each ADB child (tests pass a [FakeProcess] starter so
 * cancellation and reap are deterministic without real subprocesses).
 */
open class Adb internal constructor(
    val executable: String,
    private val processStarter: ProcessStarter,
    private val hooks: AdbHooks,
) {
    constructor(
        executable: String = "adb",
        processStarter: ProcessStarter = DefaultProcessStarter,
    ) : this(executable, processStarter, AdbHooks.None)

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
    // operation (with its serial lane; null for serial-less commands such as `adb devices`) from
    // before process start through proven reap; a reap-uncertain operation keeps its token by
    // moving it to `residuals` until its drain completes and its process is dead. Every token is
    // a monotonic id released exactly once by its owner, so a stale completion can never free a
    // newer holder (no ABA): prune/remove match by token, never by emptiness or index.
    private var nextAdmissionId = 0L
    private val inFlight = HashMap<Long, String?>()
    private val reapResiduals = LinkedHashMap<Long, ReapResidual>()

    /** Completed and replaced (under [admissionMutex]) whenever an in-flight token is released
     * or becomes a residual, so a waiter that saw a full lane suspends instead of polling. A
     * waiter captures it in the same critical section that saw the lane full: no lost wake-up. */
    private var admissionChanged = CompletableDeferred<Unit>()

    private fun signalAdmissionChanged() {
        admissionChanged.complete(Unit)
        admissionChanged = CompletableDeferred()
    }

    /** Admitted-but-unproven operations plus unresolved residuals; zero means no lane is
     * gated. A read-only snapshot for diagnostics and tests. */
    internal suspend fun admittedTokenCount(): Int =
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
     * concurrent callers can never both observe a free slot and both start. Capacity is per
     * serial lane ([ADB_PERMITS_PER_SERIAL]: one device's commands never overlap, and one
     * device's residual never blocks another) under a global cap ([ADB_GLOBAL_PERMITS]) that
     * counts in-flight and residual tokens alike, so abandoned processes/drains stay bounded.
     *
     * A residual in the caller's own lane rejects immediately with [AdbRunnerGatedException]
     * (temporary, non-poisoning: the residual may live long, so waiting would hang), as does a
     * global cap held entirely by residuals. A slot held by a live in-flight operation waits
     * cancellably for its release or conversion and re-checks, so a queued caller cancelled
     * before admission never starts a process; once the holder's reap leaves a residual,
     * waiters in that lane observe it and reject rather than pile on.
     */
    private suspend fun admit(attemptSerial: String?): Long {
        while (true) {
            val changed =
                admissionMutex.withLock {
                    pruneCompletedResiduals()
                    reapResiduals.values.firstOrNull { it.serial == attemptSerial }?.let { throw gated(attemptSerial, it) }
                    val laneBusy = inFlight.values.count { it == attemptSerial } >= ADB_PERMITS_PER_SERIAL
                    val globalBusy = inFlight.size + reapResiduals.size >= ADB_GLOBAL_PERMITS
                    if (!laneBusy && !globalBusy) {
                        val token = nextAdmissionId++
                        inFlight[token] = attemptSerial
                        return token
                    }
                    if (!laneBusy && inFlight.isEmpty()) throw gated(attemptSerial, reapResiduals.values.first())
                    admissionChanged
                }
            // A slot is held by a live in-flight operation, not a residual: suspend until some
            // holder releases or converts, then re-check. Cancellation propagates before start.
            currentCoroutineContext().ensureActive()
            hooks.onAdmissionWait()
            changed.await()
        }
    }

    private fun gated(
        attemptSerial: String?,
        blocking: ReapResidual,
    ) = AdbRunnerGatedException(
        "ADB runner gated by unreaped drain (${blocking.command.joinToString(" ")}" +
            " on ${blocking.serial ?: "no serial"}); refusing start for ${attemptSerial ?: "no serial"}",
        attemptSerial,
        blocking.serial,
        blocking.command,
    )

    /**
     * Releases one exact admission token. Runs NonCancellable: the caller is typically already
     * cancelled (local timeout or enclosing deadline) when reap finishes, and a cancellable lock
     * here would skip the decrement, leak the token, and mask the original failure with a
     * bookkeeping [CancellationException]. Never throws, so the primary failure propagates intact.
     */
    private suspend fun releaseAdmission(token: Long) {
        withContext(NonCancellable) {
            hooks.beforeAdmissionBookkeeping()
            admissionMutex.withLock {
                inFlight.remove(token)
                signalAdmissionChanged()
            }
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
        withContext(NonCancellable) {
            hooks.beforeAdmissionBookkeeping()
            admissionMutex.withLock {
                inFlight.remove(token)
                reapResiduals[token] = ReapResidual(token, serial, command, process, drain, executor, scope)
                signalAdmissionChanged()
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

    /** Runs the command and returns its output; a non-zero exit is an [AdbCommandException]. */
    protected suspend fun exec(
        serial: String,
        vararg arguments: String,
        timeoutMs: Long = 30_000,
    ): String {
        val result = execResult(serial, *arguments, timeoutMs = timeoutMs)
        if (result.exitCode != 0) throw AdbCommandException(serial, arguments.toList(), result.exitCode, result.output)
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
    open suspend fun devices(timeoutMs: Long = 10_000): List<String> =
        deviceStates(timeoutMs).filter { it.state == AdbDeviceState.ONLINE }.map { it.serial }

    /** Every device `adb devices` lists, whatever its state, so a caller can tell an unplugged
     * device from one that waits for USB authorization or has gone offline. */
    open suspend fun deviceStates(timeoutMs: Long = 10_000): List<AdbDevice> {
        val (exitCode, text) =
            runAdbProcess(listOf(executable, "devices"), timeoutMs) { "adb devices timed out" }
        if (exitCode != 0) throw AdbCommandException(null, listOf("devices"), exitCode, text)
        return parseAdbDevices(text)
    }

    /**
     * Runs one process with a locally owned deadline and reaps it in all outcomes. The local
     * timeout is a null, never an exception: an outer cancellation propagates as cancellation
     * instead of being converted into the timeout's [AdbTimeoutException].
     *
     * Admission holds one token of its serial lane ([ADB_PERMITS_PER_SERIAL], within
     * [ADB_GLOBAL_PERMITS]) from before process start through proven reap. The empty-gate check and the reservation are one mutex step, so concurrent starters
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
                if (exited != true) throw AdbTimeoutException(serialOf(command), command, timeoutMessage())
                withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
                val text =
                    withTimeoutOrNull(5_000) { drain.await() }
                        ?: throw AdbTimeoutException(serialOf(command), command, "ADB output drain timed out: ${command.joinToString(" ")}")
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
        val command = listOf("shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")
        val result = execResult(serial, *command.toTypedArray())
        val tables = result.output.lineSequence().map(String::trim)
        // A kernel without IPv6 has no tcp6: `cat` still prints tcp and exits non-zero. Only
        // output with no socket table at all is a failure.
        if (result.exitCode != 0 && tables.none { it.startsWith("sl ") }) {
            throw AdbCommandException(serial, command, result.exitCode, result.output)
        }
        return tables
            .map { it.split(WHITESPACE) }
            .any { fields -> fields.size > 3 && fields[1].endsWith(":$expectedPort") && fields[3] == "0A" }
    }

    /**
     * Reads `/proc/[pid]/stat`. [ProcessStat.Gone] when the kernel says the process no longer
     * exists; anything else unreadable or malformed is an [AdbCommandException], because a
     * process whose identity cannot be read must never be mistaken for a dead one.
     */
    open suspend fun processStat(
        serial: String,
        pid: Int,
        timeoutMs: Long = 30_000,
    ): ProcessStat {
        val command = listOf("shell", "cat", "/proc/$pid/stat")
        val result = execResult(serial, *command.toTypedArray(), timeoutMs = timeoutMs)
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
            return ProcessStat.Gone
        }

        fun malformed(what: String) = AdbCommandException(serial, command, null, result.output, "$what /proc stat for PID $pid on $serial")
        // The command name is in parentheses and may itself contain spaces; fields follow it.
        val closingName = result.output.lastIndexOf(')')
        if (closingName < 0) throw malformed("Malformed")
        val fieldsFromState =
            result.output
                .substring(closingName + 1)
                .trim()
                .split(WHITESPACE)
        if (fieldsFromState.size <= 19) throw malformed("Incomplete")
        return ProcessStat.Live(fieldsFromState[19]) // starttime: clock ticks since boot
    }

    // ---- packages and processes --------------------------------------------------------------
    //
    // `adb shell` joins its arguments into one command line that the device `sh` re-parses, so
    // every caller-supplied value (package, component, permission) goes through [shellQuote].
    // `install`/`uninstall` are adb client commands with a real argv and need no quoting.

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
    ): Boolean = exec(serial, "shell", "pm", "path", shellQuote(packageName)).lineSequence().any { it.startsWith("package:") }

    /**
     * The installed version of [packageName] from `dumpsys package`, or null when the package
     * is not installed. Bounded by [timeoutMs]. A package that is listed but whose section carries
     * no `versionCode` is an [AdbCommandException]: its build cannot be told, which must never
     * read as "not installed".
     */
    open suspend fun installedPackage(
        serial: String,
        packageName: String,
        timeoutMs: Long = 15_000,
    ): InstalledPackage? {
        val command = listOf("shell", "dumpsys", "package", shellQuote(packageName))
        val output = exec(serial, *command.toTypedArray(), timeoutMs = timeoutMs)
        return try {
            parseDumpsysPackage(output, packageName)
        } catch (malformed: IllegalArgumentException) {
            throw AdbCommandException(serial, command, null, output, "${malformed.message} on $serial")
        }
    }

    /** `pm clear`; returns the output, which says `Success` when it worked. */
    open suspend fun clearData(
        serial: String,
        packageName: String,
    ): String = exec(serial, "shell", "pm", "clear", shellQuote(packageName))

    open suspend fun grantPermission(
        serial: String,
        packageName: String,
        permission: String,
    ) {
        exec(serial, "shell", "pm", "grant", shellQuote(packageName), shellQuote(permission))
    }

    /** Whether `dumpsys package` lists [permission] as `granted=true` for [packageName]. */
    open suspend fun isPermissionGranted(
        serial: String,
        packageName: String,
        permission: String,
    ): Boolean =
        exec(serial, "shell", "dumpsys", "package", shellQuote(packageName))
            .lineSequence()
            .any { it.trim().startsWith("$permission: granted=true") }

    /** `am force-stop`; proves nothing by itself — callers poll [processIds]. */
    open suspend fun forceStop(
        serial: String,
        packageName: String,
    ) {
        exec(serial, "shell", "am", "force-stop", shellQuote(packageName))
    }

    /**
     * Whether any activity of [packageName] is still in the activity manager's hierarchy
     * (`dumpsys activity activities`, its `* Hist #n: ActivityRecord{… u0 <package>/…}` lines),
     * including one that is only exiting. The process can be gone while one still is.
     */
    open suspend fun hasActivities(
        serial: String,
        packageName: String,
    ): Boolean {
        val record = Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{\S+ u\d+ ${Regex.escape(packageName)}/""")
        return exec(serial, "shell", "dumpsys", "activity", "activities").lineSequence().any { record.containsMatchIn(it) }
    }

    /** `am start -W -n component`; returns the raw output (`AmStartOutput` reads its `Status:`/`Error:` lines). */
    open suspend fun startActivity(
        serial: String,
        component: String,
        timeoutMs: Long,
    ): String = exec(serial, "shell", "am", "start", "-W", "-n", shellQuote(component), timeoutMs = timeoutMs)

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
            shellQuote(packageName),
        ).lineSequence().map(String::trim).lastOrNull { it.startsWith("$packageName/") }

    // ---- forwards ----------------------------------------------------------------------------

    open suspend fun forward(
        serial: String,
        devicePort: Int,
    ): Int {
        val output = exec(serial, "forward", "tcp:0", "tcp:$devicePort")
        return output.toIntOrNull()
            ?: throw AdbCommandException(serial, listOf("forward", "tcp:0", "tcp:$devicePort"), null, output, "adb forward on $serial did not report a host port: $output")
    }

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
                val fields = line.trim().split(WHITESPACE)
                if (fields.size != 3 || fields[0] != serial) return@mapNotNull null
                val hostPort = fields[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                val devicePort = fields[2].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                Forwarding(serial, hostPort, devicePort)
            }.toList()

    open suspend fun processIds(
        serial: String,
        packageName: String,
    ): List<Int> {
        val command = listOf("shell", "pidof", shellQuote(packageName))
        val result = execResult(serial, *command.toTypedArray())
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
        val tokens = result.output.split(WHITESPACE).filter(String::isNotBlank)
        if (tokens.isEmpty()) throw AdbCommandException(serial, command, null, result.output, "pidof succeeded without reporting a PID on $serial")
        return tokens.map { token ->
            token.toIntOrNull()
                ?: throw AdbCommandException(serial, command, null, result.output, "pidof returned a nonnumeric PID on $serial: $token")
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
 * Admitted operations (in flight or left as an unresolved residual) per serial lane of one
 * shared [Adb] runner. One: a session issues its ADB work sequentially, so a device's commands
 * never overlap and one device can pile up at most one abandoned drain/process. Serial-less
 * commands (`adb devices`) form their own lane.
 */
const val ADB_PERMITS_PER_SERIAL = 1

/**
 * Global cap on tokens (in flight plus residual) across all lanes of one runner: devices run
 * their ADB traffic in parallel up to this many, and abandoned processes/drains stay bounded
 * however many devices misbehave. A caller refused here never started, so it retries later;
 * only a started command that left uncertainty quarantines.
 */
const val ADB_GLOBAL_PERMITS = 4

/** One row of `adb devices`: [rawState] is the tool's own word (`device`, `offline`,
 * `unauthorized`, `recovery`, `no permissions (...)`, ...), [state] its classification. */
data class AdbDevice(
    val serial: String,
    val state: AdbDeviceState,
    val rawState: String,
)

enum class AdbDeviceState {
    /** `device`: connected, authorized and usable. */
    ONLINE,

    /** `offline`: known to adb but not responding (booting, cable, adbd restart). */
    OFFLINE,

    /** `unauthorized`: the device has not accepted this host's USB debugging key. */
    UNAUTHORIZED,

    /** Anything else (`recovery`, `sideload`, `bootloader`, `authorizing`, `no permissions`, ...). */
    OTHER,
}

/** Parses `adb devices` output. Status lines from a starting adb server (`* daemon ...`) and the
 * header are skipped; the state is everything after the serial, since some (`no permissions
 * (...)`) contain spaces. */
internal fun parseAdbDevices(text: String): List<AdbDevice> =
    text
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("List of devices") }
        .mapNotNull { line ->
            val fields = line.split(WHITESPACE, limit = 2)
            if (fields.size < 2) return@mapNotNull null
            val raw = fields[1].trim()
            val state =
                when (raw) {
                    "device" -> AdbDeviceState.ONLINE
                    "offline" -> AdbDeviceState.OFFLINE
                    "unauthorized" -> AdbDeviceState.UNAUTHORIZED
                    else -> AdbDeviceState.OTHER
                }
            AdbDevice(fields[0], state, raw)
        }.toList()

/** An installed package's build: `versionName` (null when the APK declares none, like an
 * instrumentation APK AGP builds) and `versionCode` (the long version code). */
data class InstalledPackage(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
)

/**
 * Reads the `Package [name] (hash):` section of `dumpsys package name` (layout shared by API 26
 * through 34+; later sections such as `Hidden system packages:` repeat the header for an updated
 * system app, and the first — the live package — wins). Null when no section names the package
 * (not installed; newer builds may also say `Unable to find package`). A section without a
 * `versionCode=` line is an [IllegalArgumentException].
 */
internal fun parseDumpsysPackage(
    output: String,
    packageName: String,
): InstalledPackage? {
    val lines = output.lines()
    val header = "Package [$packageName] ("
    val start = lines.indexOfFirst { it.trimStart().startsWith(header) }
    if (start < 0) return null
    val indent = lines[start].indexOfFirst { !it.isWhitespace() }
    var versionCode: Long? = null
    var versionName: String? = null
    for (line in lines.drop(start + 1)) {
        if (line.isBlank()) continue
        // The section ends at the next line indented no deeper than its own header.
        if (line.indexOfFirst { !it.isWhitespace() } <= indent) break
        val trimmed = line.trim()
        if (versionCode == null) {
            DUMPSYS_VERSION_CODE.find(trimmed)?.let { versionCode = it.groupValues[1].toLong() }
        }
        if (versionName == null && trimmed.startsWith("versionName=")) {
            versionName = trimmed.removePrefix("versionName=").takeUnless { it.isEmpty() || it == "null" }
        }
    }
    val code = requireNotNull(versionCode) { "dumpsys package $packageName listed the package without a versionCode" }
    return InstalledPackage(packageName, versionName, code)
}

private val WHITESPACE = Regex("\\s+")

private val DUMPSYS_VERSION_CODE = Regex("""^versionCode=(\d+)""")

private val SHELL_SAFE = Regex("[A-Za-z0-9_@%+=:,./-]+")

/**
 * Quotes one argument for the device shell that `adb shell` hands its joined command line to
 * (POSIX `sh`, like Python's `shlex.quote`): tokens made only of safe characters pass through
 * unchanged, anything else is single-quoted with embedded `'` spelled `'\''`.
 */
fun shellQuote(token: String): String =
    if (SHELL_SAFE.matches(token)) token else "'" + token.replace("'", "'\\''") + "'"
