package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AdbDevice
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.host.FakeAdb
import io.github.noamcohen48.tap.host.ok
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {
    @Test
    fun `options are validated per command`() {
        assertEquals(
            CommandLine("start", mapOf("--port" to "0", "--state-dir" to "/tmp/tap", "--adb" to "/opt/adb")),
            parseCommandLine(listOf("start", "--port", "0", "--state-dir", "/tmp/tap", "--adb", "/opt/adb")),
        )
        assertEquals(
            CommandLine("serve", mapOf("--scrcpy" to "/opt/scrcpy")),
            parseCommandLine(listOf("serve", "--scrcpy", "/opt/scrcpy")),
        )
        assertEquals(CommandLine("version", emptyMap()), parseCommandLine(listOf("version")))
        assertEquals(CommandLine("stop", mapOf("--state-dir" to "d")), parseCommandLine(listOf("stop", "--state-dir", "d")))

        fun refused(vararg args: String): String? = assertFailsWith<UsageException> { parseCommandLine(args.toList()) }.message
        assertEquals(null, refused())
        assertEquals("unknown command 'launch'", refused("launch"))
        assertEquals("option --port is not valid for `tap status`", refused("status", "--port", "1"))
        assertEquals("--port must be an integer in 0..65535, got '70000'", refused("serve", "--port", "70000"))
        assertEquals("--port must be an integer in 0..65535, got 'x'", refused("serve", "--port", "x"))
        assertEquals("--driver-apk and --driver-test-apk go together", refused("start", "--driver-apk", "a.apk"))
        assertEquals("option --state-dir needs a value", refused("stop", "--state-dir"))
        assertEquals("option --state-dir needs a value", refused("stop", "--state-dir", "--adb", "x"))
        assertEquals("unexpected argument 'extra'", refused("stop", "extra"))
        assertEquals("option --state-dir given twice", refused("stop", "--state-dir", "a", "--state-dir", "b"))
        assertEquals(
            CommandLine("doctor", mapOf("--fix" to "true", "--serial" to "emulator-5554")),
            parseCommandLine(listOf("doctor", "--fix", "--serial", "emulator-5554")),
        )
        assertEquals("option --fix is not valid for `tap stop`", refused("stop", "--fix"))
    }

    @Test
    fun `doctor reports each device and fails while something is left`(): Unit =
        runBlocking {
            val stateDir = Files.createTempDirectory("tap-doctor-cli")
            val adb =
                FakeAdb().apply {
                    responder = { serial, command ->
                        when {
                            serial == "emulator-5554" && command.startsWith("shell pidof") -> ok("4242")
                            command.startsWith("shell pidof") -> Adb.Result(1, "")
                            command == "forward --list" -> ok("")
                            else -> null
                        }
                    }
                }
            val listed =
                object : Adb("fake-adb") {
                    override suspend fun deviceStates(timeoutMs: Long) =
                        listOf(
                            AdbDevice("emulator-5554", AdbDeviceState.ONLINE, "device"),
                            AdbDevice("phone", AdbDeviceState.ONLINE, "device"),
                            AdbDevice("85e49002", AdbDeviceState.UNAUTHORIZED, "unauthorized"),
                        )

                    override suspend fun execResult(
                        serial: String,
                        vararg arguments: String,
                        timeoutMs: Long,
                    ): Result = adb.responder!!(serial, arguments.joinToString(" ")) ?: error("unexpected ${arguments.toList()}")
                }
            val lines = mutableListOf<String>()
            assertEquals(1, doctor(stateDir, listed, serial = null, fix = false, out = { lines += it }, server = { "no daemon" }))
            assertEquals(
                listOf(
                    "server: no daemon",
                    "emulator-5554:",
                    "  - the driver is running with no session",
                    "phone: clean",
                    "85e49002: unauthorized; accept the USB debugging prompt on the device",
                    "run `tap doctor --fix` to clean up what ended sessions left",
                ),
                lines,
            )
            lines.clear()
            assertEquals(1, doctor(stateDir, listed, serial = "gone", fix = false, out = { lines += it }, server = { "no daemon" }))
            assertEquals("gone: not listed by `adb devices`", lines.last())
        }

    @Test
    fun `status and stop without a daemon`() {
        val stateDir = Files.createTempDirectory("tap-cli")
        val lines = mutableListOf<String>()
        assertEquals(1, status(stateDir, lines::add))
        assertTrue(lines.single().startsWith("no daemon (no readable "), lines.single())
        lines.clear()
        assertEquals(0, stop(stateDir, lines::add))
        assertTrue(lines.single().startsWith("no daemon running"), lines.single())
    }

    @Test
    fun `a descriptor nobody answers is reported by status and removed by stop without signalling`() {
        val stateDir = Files.createTempDirectory("tap-cli-stale")
        val deadPort = ServerSocket(0).use { it.localPort }
        // Our own pid: stop must never signal it, since the port does not prove the daemon.
        val descriptor = DaemonDescriptor(deadPort, ProcessHandle.current().pid(), DaemonDescriptor.newToken(), "0.0.0", "adb")
        descriptor.write(stateDir.resolve(DaemonDescriptor.FILE_NAME))

        val lines = mutableListOf<String>()
        assertEquals(1, status(stateDir, lines::add))
        assertTrue(lines.single().startsWith("not responding: daemon pid ${descriptor.pid}"), lines.single())
        assertTrue(Files.exists(stateDir.resolve(DaemonDescriptor.FILE_NAME)), "status never removes the descriptor")

        lines.clear()
        assertEquals(0, stop(stateDir, lines::add))
        assertTrue(lines.single().startsWith("stale descriptor (daemon pid ${descriptor.pid} does not answer)"), lines.single())
        assertFalse(Files.exists(stateDir.resolve(DaemonDescriptor.FILE_NAME)))
    }
}
