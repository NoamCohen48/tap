package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.daemon.grpc.DAEMON_VERSION
import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal data class StartupConfiguration(
    val values: Map<String, String>,
    val sources: Map<String, String>,
)

/** Only known, non-secret launcher flags enter this configuration or the Info response. */
internal fun startupConfiguration(
    options: Map<String, String>,
    stateDir: Path,
    environment: (String) -> String? = System::getenv,
): StartupConfiguration {
    val values = linkedMapOf<String, String>()
    val sources = linkedMapOf<String, String>()

    fun option(
        key: String,
        default: String?,
        env: String? = null,
    ) {
        val fromEnv = env?.let(environment)
        val value = options[key] ?: fromEnv ?: default ?: return
        values[key] = value
        sources[key] =
            when {
                key in options -> "command line"
                fromEnv != null -> env!!
                else -> "default"
            }
    }
    option("--state-dir", stateDir.toString(), "TAP_STATE_DIR")
    values["--state-dir"] = stateDir.toString()
    option("--port", "0")
    option("--adb", "adb", "TAP_ADB")
    option("--scrcpy", "scrcpy", "TAP_SCRCPY")
    option("--scrcpy-server", null, "TAP_SCRCPY_SERVER")
    option("--driver-idle-timeout-ms", "0")
    option("--driver-apk", null)
    option("--driver-test-apk", null)
    for (key in listOf("--adb", "--scrcpy")) {
        values[key]?.let { value ->
            if (Path.of(value).parent != null) {
                values[key] =
                    Path
                        .of(value)
                        .toAbsolutePath()
                        .normalize()
                        .toString()
            }
        }
    }
    for (key in listOf("--scrcpy-server", "--driver-apk", "--driver-test-apk")) {
        values[key]?.let {
            values[key] =
                Path
                    .of(it)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
        }
    }
    return StartupConfiguration(values, sources)
}

internal fun configurationJson(
    values: Map<String, String>,
    sources: Map<String, String>,
): JsonObject =
    buildJsonObject {
        values.filterKeys { it in START_OPTIONS }.toSortedMap().forEach { (key, value) ->
            put(
                key.removePrefix("--").replace('-', '_'),
                buildJsonObject {
                    put("value", value)
                    put("source", sources[key] ?: "running daemon")
                },
            )
        }
    }

internal fun configurationReport(
    line: CommandLine,
    stateDir: Path,
): CliReport {
    val local = startupConfiguration(line.options, stateDir)
    val descriptor = DaemonDescriptor.read(stateDir)
    var problem: String? = null
    val running =
        if (descriptor == null) {
            null
        } else {
            try {
                Administration(descriptor).use { admin ->
                    val info = admin.info()
                    if (info.startupOptionsMap.isEmpty()) {
                        problem = "running daemon does not report startup configuration; upgrade it for full inspection"
                        buildJsonObject {
                            put(
                                "adb",
                                buildJsonObject {
                                    put("value", info.adbExecutable)
                                    put("source", "running daemon")
                                },
                            )
                            put(
                                "state_dir",
                                buildJsonObject {
                                    put("value", info.stateDir)
                                    put("source", "running daemon")
                                },
                            )
                        }
                    } else {
                        configurationJson(info.startupOptionsMap, info.startupOptionSourcesMap)
                    }
                }
            } catch (error: Exception) {
                problem = safeFailure(error, stateDir)
                null
            }
        }
    val data =
        buildJsonObject {
            put("cli_version", DAEMON_VERSION)
            put("running", running ?: kotlinx.serialization.json.JsonNull)
            put("next_start", configurationJson(local.values, local.sources))
            put("note", "next_start resolves this invocation's flags/environment; tap start never reconfigures a running daemon")
            problem?.let { put("warning", it) }
        }
    return CliReport(if (descriptor != null && running == null) 1 else 0, data, describe(data))
}

internal fun executablePath(
    executable: String,
    path: String? = System.getenv("PATH"),
): Path? {
    val direct = Path.of(executable)
    val candidates =
        if (direct.isAbsolute || direct.parent != null) {
            listOf(direct)
        } else {
            path.orEmpty().split(File.pathSeparator).take(128).flatMap { directory ->
                val names = if (File.separatorChar == '\\') listOf(executable, "$executable.exe") else listOf(executable)
                names.map { Path.of(directory.ifEmpty { "." }, it) }
            }
        }
    return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toAbsolutePath()
}

/** Setup checks are read-only; the existing doctor owns all device recovery and its --fix gate. */
internal fun doctorReport(
    line: CommandLine,
    stateDir: Path,
): CliReport {
    val health = statusReport(stateDir)
    val local = startupConfiguration(line.options, stateDir)
    val runningAdb = health.data["daemon"]?.let { (it as JsonObject)["adb_executable"] as? JsonPrimitive }?.content
    val adb = line.options["--adb"] ?: runningAdb ?: local.values.getValue("--adb")
    val setup = mutableListOf<String>()
    val resolvedAdb = executablePath(adb)
    setup +=
        if (resolvedAdb == null) "adb: '$adb' not executable; install Android platform-tools or use --adb PATH" else "adb: $resolvedAdb"
    val existingParent = generateSequence(stateDir) { it.parent }.firstOrNull { Files.exists(it) }
    val writable = existingParent != null && Files.isDirectory(existingParent) && Files.isWritable(existingParent)
    setup += "state directory: $stateDir (${if (writable) "writable" else "not writable; check permissions"})"
    val driverAvailable = (health.data["daemon"] as? JsonObject)?.get("driver_available")
    if (driverAvailable != null) setup += "bundled/override driver available in daemon: $driverAvailable"
    val deviceLines = mutableListOf<String>()
    var deviceCode = 1
    if (resolvedAdb != null) {
        try {
            deviceCode =
                runBlocking {
                    doctor(
                        stateDir,
                        Adb(adb),
                        line.options["--serial"],
                        "--fix" in line.options,
                        deviceLines::add,
                    ) { health.text }
                }
        } catch (error: Exception) {
            deviceLines += "device checks failed: ${safeFailure(error, stateDir)}"
        }
    } else {
        deviceLines += "server: ${health.text}"
        deviceLines += "device checks skipped: ADB is unavailable"
    }
    val code = if (health.code == 0 && writable && resolvedAdb != null && deviceCode == 0) 0 else 1
    val data =
        buildJsonObject {
            put("healthy", code == 0)
            put("daemon", health.data)
            put("setup", JsonArray(setup.map(::JsonPrimitive)))
            put("device_checks", JsonArray(deviceLines.map(::JsonPrimitive)))
            put("fix_requested", "--fix" in line.options)
        }
    return CliReport(
        code,
        data,
        (listOf("tap CLI: $DAEMON_VERSION") + setup + deviceLines.ifEmpty { listOf(health.text) }).joinToString("\n"),
    )
}
