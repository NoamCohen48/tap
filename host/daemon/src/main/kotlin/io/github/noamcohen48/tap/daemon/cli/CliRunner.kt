package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.daemon.grpc.DAEMON_VERSION
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant

/** Executable boundary: usage and operational failures have different streams and exit codes. */
internal fun runCli(
    args: List<String>,
    out: (String) -> Unit = ::println,
    err: (String) -> Unit = System.err::println,
    keepGoing: () -> Boolean = { !Thread.currentThread().isInterrupted },
    pause: (Long) -> Unit = Thread::sleep,
): Int {
    val line =
        try {
            parseCommandLine(args)
        } catch (invalid: UsageException) {
            err(terminalSafe("error: ${invalid.message}\n\n${helpText(invalid.command)}"))
            return 2
        }
    if (line.help) {
        out(helpText(line.command))
        return 0
    }
    if (line.command == "version") {
        out("tap daemon $DAEMON_VERSION")
        return 0
    }
    if (line.command == "completion") {
        out(completionScript(line.arguments.single()))
        return 0
    }
    val stateDir =
        try {
            Path.of(line.options["--state-dir"] ?: defaultStateDir()).toAbsolutePath().normalize()
        } catch (error: Exception) {
            err("invalid state directory: ${terminalSafe(error.message.orEmpty())}")
            return 2
        }
    val json = "--json" in line.options
    val watching = "--watch" in line.options

    fun emit(report: CliReport) {
        if (json) {
            val data =
                if (watching) {
                    JsonObject(report.data + ("observed_at" to kotlinx.serialization.json.JsonPrimitive(Instant.now().toString())))
                } else {
                    report.data
                }
            out(data.toString())
        } else {
            out(terminalSafe((if (watching) "[${Instant.now()}]\n" else "") + report.text))
        }
    }
    return try {
        if (line.command.startsWith("config logging-policy ")) {
            out(terminalSafe(loggingPolicyConfiguration(line, stateDir)))
            return 0
        }
        if (line.command == "logs") return printLogs(line, stateDir, { out(terminalSafe(it)) }, keepGoing, pause)
        if (line.command == "start") {
            start(line.options, stateDir)
            return 0
        }
        if (line.command == "serve") {
            serve(line.options, stateDir)
            return 0
        }
        if (line.command == "stop") return stop(stateDir) { out(terminalSafe(it)) }
        if (line.command == "restart") return restartDaemon(line, stateDir, out)
        if (line.command == "unquarantine") {
            return runBlocking {
                unquarantine(stateDir, Adb(line.options["--adb"] ?: System.getenv("TAP_ADB") ?: "adb"), line.options.getValue("--serial")) {
                    out(terminalSafe(it))
                }
            }
        }
        do {
            val report =
                try {
                    when (line.command) {
                        "status" -> statusReport(stateDir)
                        "config show" -> configurationReport(line, stateDir)
                        "doctor" -> doctorReport(line, stateDir)
                        "diagnostics" -> exportDiagnostics(line, stateDir)
                        else -> withAdministration(stateDir) { discoveryReport(line, it) }
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) throw error
                    failureReport(error, stateDir, line.command)
                }
            if (!json && report.code != 0 && !watching) err(terminalSafe(report.text)) else emit(report)
            if (!watching) return report.code
            if (!keepGoing()) break
            pause(((line.options["--interval"]?.toDouble() ?: 2.0) * 1000).toLong())
        } while (keepGoing())
        0
    } catch (invalid: UsageException) {
        err(terminalSafe("error: ${invalid.message}\n\n${helpText(invalid.command)}"))
        2
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        130
    } catch (error: Exception) {
        val report = failureReport(error, stateDir, line.command)
        if (json) emit(report) else err(terminalSafe(report.text))
        1
    }
}

internal fun safeFailure(
    error: Exception,
    stateDir: Path,
): String {
    val text =
        if (error is StatusRuntimeException) {
            when (error.status.code) {
                Status.Code.UNAVAILABLE -> "daemon unavailable; run tap status, tap logs or tap start"
                Status.Code.UNAUTHENTICATED -> "daemon rejected its descriptor token; run tap status / tap doctor; check --state-dir"
                Status.Code.DEADLINE_EXCEEDED -> "daemon request timed out; inspect tap status and tap logs"
                else -> "${error.status.code}: ${error.status.description.orEmpty()}"
            }
        } else {
            error.message ?: error.javaClass.simpleName
        }
    return redact(text, DaemonDescriptor.read(stateDir)?.token)
}

private fun failureReport(
    error: Exception,
    stateDir: Path,
    command: String,
): CliReport {
    val message =
        safeFailure(error, stateDir) +
            if (command == "sessions close" && error is StatusRuntimeException) {
                "; close outcome is not confirmed: inspect tap sessions and tap doctor before deciding what to do next"
            } else {
                ""
            }
    return CliReport(
        1,
        buildJsonObject {
            put("error", message)
            put("command", command)
        },
        "error: $message",
    )
}

/** Preflight guard, not an ownership lock: coordinate restart with other local clients. */
internal fun restartDaemon(
    line: CommandLine,
    stateDir: Path,
    out: (String) -> Unit,
    stopDaemon: (Path, (String) -> Unit) -> Int = ::stop,
    startDaemon: (Map<String, String>, Path) -> Unit = { options, state -> start(options, state, inheritVideoEnvironment = false) },
): Int {
    val (descriptor, info) =
        withAdministration(stateDir) { admin ->
            val info = admin.info()
            if ("--force" !in line.options) {
                admin.requireCompatible()
                check(
                    admin.listSessions().isEmpty(),
                ) { "active sessions would be interrupted; inspect tap sessions and pass --force to confirm" }
            }
            admin.descriptor to info
        }
    check(info.startupOptionsMap.isNotEmpty()) {
        "daemon does not report startup settings; use explicit tap stop then tap start with the required options"
    }
    val options = info.startupOptionsMap.filterKeys { it in START_OPTIONS } + line.options.filterKeys { it in START_OPTIONS }
    // Validate inherited settings before stopping anything, including paired APK overrides.
    parseCommandLine(listOf("start") + options.flatMap { (key, value) -> listOf(key, value) })
    check(DaemonDescriptor.read(stateDir)?.token == descriptor.token) { "daemon changed during restart preflight; nothing stopped" }
    val code = stopDaemon(stateDir) { out(terminalSafe(it)) }
    if (code != 0) return code
    // Explicit overrides win; no environment changes silently replace a running daemon's settings.
    startDaemon(options, stateDir)
    return 0
}
