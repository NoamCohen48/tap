package com.company.tap.host

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
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
     * instead of being converted into the timeout's [IllegalStateException]. The child is
     * destroyed, its streams closed to unblock the drain, and the drain joined boundedly, so no
     * path waits unboundedly on a blocking drain.
     */
    private suspend fun runAdbProcess(
        command: List<String>,
        timeoutMs: Long,
        timeoutMessage: () -> String,
    ): Pair<Int, String> =
        coroutineScope {
            lateinit var process: Process
            // Install cleanup ownership before returning to the caller's cancellable context. A
            // cancellation while start() is returning cannot discard an already-created child.
            withContext(NonCancellable) {
                process = withContext(Dispatchers.IO) { processStarter.start(command) }
            }
            val drain = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
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
                process.exitValue() to text
            } finally {
                reap(process, drain)
            }
        }

    /** Destroys a child that may still be alive (local timeout or caller cancellation), closes
     * its streams to unblock the drain, and reaps both with a bound. Never throws. */
    private suspend fun reap(
        process: Process,
        drain: Deferred<String>,
    ) {
        runCatching { if (process.isAlive) process.destroy() }
        closeProcessStreams(process)
        drain.cancel()
        withContext(NonCancellable) {
            withTimeoutOrNull(ADB_REAP_TIMEOUT_MS) { drain.join() }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
                withContext(Dispatchers.IO) {
                    runCatching { process.waitFor(ADB_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                }
            }
        }
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
