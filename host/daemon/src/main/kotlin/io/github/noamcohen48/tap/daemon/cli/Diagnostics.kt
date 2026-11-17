package io.github.noamcohen48.tap.daemon.cli

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Defence in depth for known bearer tokens and conventional credential-shaped log fields. */
internal fun redact(
    text: String,
    token: String? = null,
): String {
    var result = if (token.isNullOrEmpty()) text else text.replace(token, "[REDACTED]")
    result = Regex("(?i)Bearer\\s+[^\\s\"',}]+").replace(result, "Bearer [REDACTED]")
    result =
        Regex("(?i)((?:token|password|secret|api[_-]?key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]+").replace(result) {
            "${it.groupValues[1]}[REDACTED]"
        }
    return Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@").replace(result, "$1[REDACTED]@")
}

/** No descriptor, environment dump, journal, screenshot, command payload or arbitrary log is copied. */
internal fun exportDiagnostics(
    line: CommandLine,
    stateDir: Path,
): CliReport {
    val output = Path.of(line.options.getValue("--out")).toAbsolutePath().normalize()
    check(output != stateDir.resolve("daemon.log") && output != stateDir.resolve(DaemonDescriptor.FILE_NAME)) {
        "diagnostics output must not replace daemon state"
    }
    val token = DaemonDescriptor.read(stateDir)?.token
    val entries = linkedMapOf<String, String>()
    entries["README.txt"] =
        """
        Tap diagnostics: allowlisted configuration, health and read-only discovery.
        Logs contain only recognized lifecycle timing lines from the last 200 log lines (up to 1 MiB).
        Arbitrary logs, environment values, daemon.json, session journals and device content are excluded.
        Bearer credentials are redacted. Device serials, client names and local paths remain:
        review this archive before sharing it. No devices were attached or sessions renewed.
        """.trimIndent()
    entries["status.json"] = statusReport(stateDir).data.toString()
    entries["config.json"] = configurationReport(line, stateDir).data.toString()
    for (command in listOf("devices", "sessions")) {
        entries["$command.json"] =
            try {
                withAdministration(stateDir) { discoveryReport(CommandLine(command), it).data.toString() }
            } catch (error: Exception) {
                buildJsonObject { put("error", safeFailure(error, stateDir)) }.toString()
            }
    }
    val log = stateDir.resolve("daemon.log")
    entries["lifecycle.log"] =
        if (Files.isRegularFile(log)) {
            // Deliberately do not copy free-form exception, connection-name or client payload lines.
            val timing = Regex("^\\[tap] lifecycle serial=[A-Za-z0-9_.:-]+ stage=[a-z_]+ outcome=[a-z_]+ elapsed_ms=[0-9]+$")
            tailLog(log, 200).lineSequence().filter { timing.matches(it) }.joinToString("\n")
        } else {
            "No captured daemon log (tap serve writes to its terminal)."
        }
    val attributes =
        if (Files.getFileStore(output.parent).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            emptyArray()
        }
    // CREATE_NEW is atomic and refuses existing files and symlinks, including an unrelated user's file.
    val channel = Files.newByteChannel(output, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *attributes)
    try {
        ZipOutputStream(
            java.nio.channels.Channels
                .newOutputStream(channel),
        ).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(redact(content, token).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    } catch (error: Exception) {
        channel.close()
        Files.deleteIfExists(output)
        throw error
    }
    return CliReport(
        0,
        buildJsonObject {
            put("path", output.toString())
        },
        "wrote diagnostics to $output; review client names, device serials and local paths before sharing",
    )
}
