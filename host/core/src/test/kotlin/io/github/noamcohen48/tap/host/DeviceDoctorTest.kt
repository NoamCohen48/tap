package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.host.DeviceDoctor.Finding
import io.github.noamcohen48.tap.host.DeviceDoctor.Outcome
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceDoctorTest {
    private val serial = "emulator-5554"

    /** A device with a driver process and two forwards (one Tap's, one not) until they are removed. */
    private fun leftovers(): FakeAdb {
        var driverRunning = true
        val forwards = mutableListOf("$serial tcp:41000 tcp:$DEVICE_PORT", "$serial tcp:5000 tcp:8080")
        return FakeAdb().apply {
            responder = { _, command ->
                when {
                    command.startsWith("shell pidof") -> if (driverRunning) ok("4242") else Adb.Result(1, "")
                    command.startsWith("shell am force-stop") -> ok("").also { driverRunning = false }
                    command == "forward --list" -> ok(forwards.joinToString("\n"))
                    command.startsWith("forward --remove tcp:") -> {
                        val port = command.removePrefix("forward --remove tcp:")
                        forwards.removeIf { it.split(" ")[1] == "tcp:$port" }
                        ok("")
                    }
                    command == "shell cat /proc/sys/kernel/random/boot_id" -> ok("boot-1")
                    else -> null
                }
            }
        }
    }

    private fun journal(root: Path, state: JournalState, reason: String? = null) =
        SessionJournalStore(root, serial).write(
            SessionJournal(state = state, serial = serial, bootId = "boot-1", sessionId = "s", generation = 3, devicePort = DEVICE_PORT, quarantineReason = reason),
        )

    @Test
    fun `a report lists what an ended session left and changes nothing`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            journal(root, JournalState.ACTIVE)
            val adb = leftovers()
            val outcome = DeviceDoctor.check(adb, root, serial, fix = false)
            assertEquals(
                Outcome.Findings(
                    listOf(
                        Finding("a session (generation 3) ended without closing"),
                        Finding("the driver is running with no session"),
                        Finding("port forward tcp:41000 -> tcp:$DEVICE_PORT has no session"),
                    ),
                ),
                outcome,
            )
            assertFalse(adb.calls.any { "force-stop" in it || "--remove" in it }, adb.calls.toString())
            assertEquals(JournalState.ACTIVE, SessionJournalStore(root, serial).read()?.state)
        }

    @Test
    fun `fix runs the attach recovery and removes only Tap's forwards`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            journal(root, JournalState.ACTIVE)
            val adb = leftovers()
            val outcome = DeviceDoctor.check(adb, root, serial, fix = true) as Outcome.Findings
            assertTrue(outcome.findings.all { it.fixed }, outcome.toString())
            assertEquals(JournalState.CLOSED, SessionJournalStore(root, serial).read()?.state)
            assertTrue("$serial: forward --remove tcp:41000" in adb.calls, adb.calls.toString())
            assertFalse(adb.calls.any { "--remove tcp:5000" in it }, adb.calls.toString())
            assertFalse(adb.calls.any { "--remove-all" in it })
            assertEquals(Outcome.Clean, DeviceDoctor.check(adb, root, serial, fix = false))
        }

    @Test
    fun `a quarantined device is reported with its journal and never cleared`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            journal(root, JournalState.QUARANTINED, reason = "LATE_MUTATION")
            val outcome = DeviceDoctor.check(leftovers(), root, serial, fix = true)
            assertEquals(Outcome.Quarantined("LATE_MUTATION", SessionJournalStore(root, serial).file), outcome)
            assertEquals(JournalState.QUARANTINED, SessionJournalStore(root, serial).read()?.state)
        }

    @Test
    fun `a device another session holds is skipped untouched`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            val adb = leftovers()
            SessionJournalStore(root, serial).acquireLease(0).use {
                assertEquals(Outcome.InUse, DeviceDoctor.check(adb, root, serial, fix = true))
            }
            assertTrue(adb.calls.isEmpty(), adb.calls.toString())
        }
}
