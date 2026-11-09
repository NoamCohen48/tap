package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.host.DeviceDoctor.Finding
import io.github.noamcohen48.tap.host.DeviceDoctor.Outcome
import io.github.noamcohen48.tap.host.DeviceDoctor.UnquarantineOutcome
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
            assertEquals(Outcome.Quarantined("LATE_MUTATION"), outcome)
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

    @Test
    fun `fix retries a quarantine over settings that did not read back and lifts it`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            SessionJournalStore(root, serial).write(
                SessionJournal(
                    state = JournalState.QUARANTINED, serial = serial, bootId = "boot-1", sessionId = "s", generation = 3, devicePort = DEVICE_PORT,
                    quarantineReason = "DEVICE_STATE_RESTORE_FAILED: font scale", restoreRetryable = true,
                    savedState = listOf(SavedState(StateKey.FONT_SCALE, "1.1")),
                ),
            )
            val device = FakeDeviceState(mapOf(StateKey.FONT_SCALE to "1.3"))
            val adb = leftovers().apply { responder.let { base -> responder = { s, command -> device.answer(command) ?: base?.invoke(s, command) } } }
            val report = DeviceDoctor.check(adb, root, serial, fix = false) as Outcome.Findings
            assertEquals(Finding("quarantined until its device settings read back (DEVICE_STATE_RESTORE_FAILED: font scale)"), report.findings.first())
            val fixed = DeviceDoctor.check(adb, root, serial, fix = true) as Outcome.Findings
            assertTrue(fixed.findings.all { it.fixed }, fixed.toString())
            assertEquals("1.1", device.values[StateKey.FONT_SCALE])
            assertEquals(JournalState.CLOSED, SessionJournalStore(root, serial).read()?.state)
        }

    @Test
    fun `unquarantine retries the cleanup, closes the journal and says what is still off`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            SessionJournalStore(root, serial).write(
                SessionJournal(
                    state = JournalState.QUARANTINED, serial = serial, bootId = "boot-0", sessionId = "s", generation = 3, devicePort = DEVICE_PORT,
                    quarantineReason = "SESSION_CLEANUP_UNCERTAIN: driver survived",
                    savedState = listOf(SavedState(StateKey.FONT_SCALE, "1.1"), SavedState(StateKey.Density.id, null)),
                ),
            )
            val device = FakeDeviceState(mapOf(StateKey.FONT_SCALE to "1.3", StateKey.Density.id to "320"))
            device.stuck += StateKey.FONT_SCALE
            val adb = leftovers().apply { responder.let { base -> responder = { s, command -> device.answer(command) ?: base?.invoke(s, command) } } }
            val outcome = DeviceDoctor.unquarantine(adb, root, serial) as UnquarantineOutcome.Unquarantined
            assertEquals("SESSION_CLEANUP_UNCERTAIN: driver survived", outcome.reason)
            assertEquals(listOf("stopped the driver", "removed 1 port forward(s)"), outcome.cleaned)
            assertEquals(1, outcome.remaining.size, outcome.toString())
            assertTrue(outcome.remaining.single().startsWith("device settings a session changed were not restored"), outcome.toString())
            assertFalse(adb.calls.any { "--remove tcp:5000" in it }, adb.calls.toString())
            val record = SessionJournalStore(root, serial).read()!!
            assertEquals(JournalState.CLOSED, record.state)
            assertEquals("boot-1", record.bootId)
            assertEquals(3, record.generation)
            assertTrue(record.savedState.isEmpty() && record.quarantineReason == null, record.toString())
            assertEquals(UnquarantineOutcome.NotQuarantined, DeviceDoctor.unquarantine(adb, root, serial))
        }

    @Test
    fun `unquarantine keeps an unreadable journal beside a closed one`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            val store = SessionJournalStore(root, serial)
            Files.writeString(store.file, "{ not json")
            val outcome = DeviceDoctor.unquarantine(leftovers(), root, serial) as UnquarantineOutcome.Unquarantined
            assertEquals("the session journal is unreadable", outcome.reason)
            assertEquals(JournalState.CLOSED, store.read()?.state)
            assertTrue(Files.list(root).use { files -> files.anyMatch { ".corrupt-" in it.fileName.toString() } })
        }

    @Test
    fun `unquarantine leaves a held or healthy device alone`(): Unit =
        runBlocking {
            val root = Files.createTempDirectory("tap-doctor")
            journal(root, JournalState.QUARANTINED, reason = "why")
            val adb = leftovers()
            SessionJournalStore(root, serial).acquireLease(0).use {
                assertEquals(UnquarantineOutcome.InUse, DeviceDoctor.unquarantine(adb, root, serial))
            }
            assertTrue(adb.calls.isEmpty(), adb.calls.toString())
            assertEquals(JournalState.QUARANTINED, SessionJournalStore(root, serial).read()?.state)
            journal(root, JournalState.CLOSED)
            assertEquals(UnquarantineOutcome.NotQuarantined, DeviceDoctor.unquarantine(adb, root, serial))
            assertTrue(adb.calls.isEmpty(), adb.calls.toString())
        }
}
