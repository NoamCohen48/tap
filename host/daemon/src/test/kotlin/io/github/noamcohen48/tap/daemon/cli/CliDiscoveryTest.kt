package io.github.noamcohen48.tap.daemon.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliDiscoveryTest {
    @Test
    fun `root help is a complete start discover interact walkthrough`() {
        for (args in listOf(emptyList(), listOf("--help"), listOf("-h"), listOf("help"))) {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            assertEquals(0, runCli(args, out::add, err::add))
            assertTrue(err.isEmpty())
            val text = out.single()
            for (example in listOf(
                "tap start",
                "tap devices",
                "tap sessions",
                "tap-agent attach SERIAL",
                "tap-agent snapshot",
                "uv tool install tap-agent",
            )) {
                assertTrue(text.contains(example), example)
            }
            assertFalse(text.contains("tap agent "))
        }
    }

    @Test
    fun `every command has focused help before checking required arguments`() {
        val missing = Files.createTempDirectory("tap-help").resolve("must-not-exist")
        for (command in COMMAND_HELP.keys) {
            val words = command.split(' ')
            for (args in listOf(words + "--help", words + "-h", listOf("help") + words)) {
                val out = mutableListOf<String>()
                val err = mutableListOf<String>()
                assertEquals(0, runCli(listOf("--state-dir", missing.toString()) + args, out::add, err::add), args.toString())
                assertTrue(err.isEmpty())
                assertTrue(out.single().contains("Usage: tap $command"))
                assertTrue(out.single().contains("Examples:"))
            }
        }
        assertFalse(Files.exists(missing))
    }

    @Test
    fun `usage errors use stderr and preserve strict option validation`() {
        for (args in listOf(
            listOf("not-a-command"),
            listOf("start", "--port", "invalid"),
            listOf("sessions", "close", "id"),
            listOf("devices", "--force"),
            listOf("logs", "--tail", "-1"),
            listOf("status", "--interval", "2"),
            listOf("status", "--watch", "--interval", "NaN"),
            listOf("diagnostics"),
            listOf("completion", "sh"),
            listOf("sessions", "close", "id", "--force", "--reason", "new\nline"),
        )) {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            assertEquals(2, runCli(args, out::add, err::add), args.toString())
            assertTrue(out.isEmpty())
            assertTrue(err.single().startsWith("error:"))
        }
        assertEquals(
            CommandLine("devices show", mapOf("--state-dir" to "/tmp/test", "--json" to "true"), listOf("phone")),
            parseCommandLine(listOf("--state-dir=/tmp/test", "devices", "show", "phone", "--json")),
        )
        assertEquals(CommandLine("version"), parseCommandLine(listOf("--version")))
    }

    @Test
    fun `no daemon fails actionably without creating state or launching another executable`() {
        val state = Files.createTempDirectory("tap-offline").resolve("missing")
        for (command in listOf("status", "devices", "sessions")) {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            assertEquals(1, runCli(listOf(command, "--state-dir", state.toString(), "--json"), out::add, err::add))
            assertTrue(err.isEmpty())
            assertTrue(out.single().contains("tap start"))
            Json.parseToJsonElement(out.single())
        }
        assertFalse(Files.exists(state))
    }

    @Test
    fun `startup sources honor explicit flags environment defaults and exclude unknown values`() {
        val state = Files.createTempDirectory("tap-config")
        val env = mapOf("TAP_ADB" to "environment-adb", "TAP_SCRCPY" to "environment-scrcpy", "TAP_TOKEN" to "not-for-output")
        val config = startupConfiguration(mapOf("--adb" to "explicit-adb", "--token" to "secret"), state, env::get)
        assertEquals("explicit-adb", config.values["--adb"])
        assertEquals("command line", config.sources["--adb"])
        assertEquals("environment-scrcpy", config.values["--scrcpy"])
        assertEquals("TAP_SCRCPY", config.sources["--scrcpy"])
        assertEquals("default", config.sources["--port"])
        assertFalse(configurationJson(config.values, config.sources).toString().contains("secret"))
        assertFalse(configurationJson(config.values, config.sources).toString().contains("not-for-output"))
        assertEquals(null, executablePath("does-not-exist", state.toString()))
    }

    @Test
    fun `log tails are bounded and following handles append truncation replacement and UTF8`() {
        val state = Files.createTempDirectory("tap-logs")
        val log = state.resolve("daemon.log")
        Files.writeString(log, "one\ntwo\nthree\n")
        assertEquals("two\nthree", tailLog(log, 2))
        assertEquals("", tailLog(log, 0))
        val follower = LogFollower(log, 1)
        assertEquals(listOf("three"), follower.poll())
        assertTrue(follower.poll().isEmpty())
        val unicode = "שלום".toByteArray()
        Files.write(log, unicode.copyOfRange(0, 1), APPEND)
        assertTrue(follower.poll().isEmpty())
        Files.write(log, unicode.copyOfRange(1, unicode.size) + byteArrayOf(10), APPEND)
        assertEquals(listOf("שלום"), follower.poll())
        Files.writeString(log, "new\n")
        assertEquals(listOf("new"), follower.poll())
        Files.move(log, state.resolve("daemon.log.1"))
        assertTrue(follower.poll().isEmpty())
        Files.writeString(log, "replacement\n")
        assertEquals(listOf("replacement"), follower.poll())
        Files.writeString(log, "x".repeat(2_000_000) + "\nlast\n")
        assertEquals("last", tailLog(log, 100))
    }

    @Test
    fun `offline diagnostics work do not overwrite and exclude arbitrary log secrets`() {
        val state = Files.createTempDirectory("tap-bundle")
        val zip = state.resolve("bundle.zip")
        Files.writeString(state.resolve("daemon.log"), "arbitrary secret\n")
        val line = CommandLine("diagnostics", mapOf("--out" to zip.toString()))
        assertEquals(0, exportDiagnostics(line, state).code)
        ZipFile(zip.toFile()).use { archive ->
            val status = archive.getInputStream(archive.getEntry("status.json")).bufferedReader().use { it.readText() }
            assertTrue(status.contains("stopped"))
            val log = archive.getInputStream(archive.getEntry("lifecycle.log")).bufferedReader().use { it.readText() }
            assertFalse(log.contains("arbitrary secret"))
        }
        val content = Files.readAllBytes(zip)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { exportDiagnostics(line, state) }
        assertTrue(content.contentEquals(Files.readAllBytes(zip)))
        assertEquals("Bearer [REDACTED]", redact("Bearer private-value"))
        assertEquals("token=[REDACTED] password=[REDACTED]", redact("token=abc password=def"))
    }

    @Test
    fun `doctor explains missing setup and keeps failure output machine readable`() {
        val state = Files.createTempDirectory("tap-doctor")
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        assertEquals(
            1,
            runCli(
                listOf("doctor", "--state-dir", state.toString(), "--adb", state.resolve("no-adb").toString(), "--json"),
                out::add,
                err::add,
            ),
        )
        assertTrue(err.isEmpty())
        assertTrue(out.single().contains("install Android platform-tools"))
        assertTrue(Json.parseToJsonElement(out.single()) is JsonObject)
    }

    @Test
    fun `bash completion has nested command and option context without executing tap`() {
        val file = Files.createTempFile("tap-completion", ".bash")
        Files.writeString(file, completionScript("bash"))
        val process =
            ProcessBuilder(
                "bash",
                "-c",
                """
                source "$file"
                COMP_WORDS=(tap sessions ''); COMP_CWORD=2; _tap_complete
                printf '%s\n' "${'$'}{COMPREPLY[@]}"
                """.trimIndent(),
            ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(output.contains("show"))
        assertTrue(output.contains("close"))
        assertTrue(output.contains("--json"))
        for (shell in listOf("bash", "zsh", "fish")) assertFalse(completionScript(shell).contains("tap-agent"))
    }
}
