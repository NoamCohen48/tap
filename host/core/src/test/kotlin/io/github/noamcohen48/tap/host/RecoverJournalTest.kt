package io.github.noamcohen48.tap.host

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every [recoverJournal] branch against a [FakeAdb]: what it proves, clears or quarantines. */
class RecoverJournalTest {
    private val serial = "emulator-5554"
    private val root = Files.createTempDirectory("tap-recover-test")
    private val store = SessionJournalStore(root, serial)

    /** A device with no driver process; forwards listed by [forwards], boot IDs from [boots]. */
    private fun adb(
        forwards: () -> String = { "" },
        boots: Iterator<String> = generateSequence { "boot-1" }.iterator(),
    ) = FakeAdb(
        mapOf(
            "shell am force-stop $DRIVER_PACKAGE" to ok(""),
            "shell pidof $DRIVER_PACKAGE" to Adb.Result(1, ""),
        ),
    ).apply {
        responder = { _, command ->
            when {
                command == "shell cat /proc/sys/kernel/random/boot_id" -> ok(boots.next())
                command == "forward --list" -> ok(forwards())
                command.startsWith("forward --remove ") -> ok("")
                else -> null
            }
        }
    }

    private fun record(
        state: JournalState,
        hostPort: Int? = null,
        devicePort: Int = DEVICE_PORT,
        bootId: String = "boot-1",
    ) = SessionJournal(
        state = state,
        serial = serial,
        bootId = bootId,
        sessionId = "session",
        generation = 4,
        devicePort = devicePort,
        hostPort = hostPort,
    )

    @Test
    fun `no journal only proves the driver is stopped`() =
        runBlocking {
            val adb = adb()
            assertNull(recoverJournal(adb, serial, "boot-1", store))
            assertEquals(listOf("$serial: shell am force-stop $DRIVER_PACKAGE", "$serial: shell pidof $DRIVER_PACKAGE"), adb.calls)
        }

    @Test
    fun `a closed journal is returned after stopping the driver, forwards untouched`() =
        runBlocking {
            store.write(record(JournalState.CLOSED, hostPort = 40000))
            val adb = adb()
            assertEquals(JournalState.CLOSED, recoverJournal(adb, serial, "boot-1", store)?.state)
            assertTrue(adb.calls.none { "forward" in it }, "${adb.calls}")
        }

    @Test
    fun `an active journal removes its exact forward and closes`() =
        runBlocking {
            store.write(record(JournalState.ACTIVE, hostPort = 40000))
            var removed = false
            val adb = adb(forwards = { if (removed) "" else "$serial tcp:40000 tcp:$DEVICE_PORT\n$serial tcp:40001 tcp:9999" })
            adb.responder.let { base ->
                adb.responder = { s, command ->
                    if (command == "forward --remove tcp:40000") removed = true
                    base?.invoke(s, command)
                }
            }
            assertEquals(JournalState.CLOSED, recoverJournal(adb, serial, "boot-1", store)?.state)
            assertEquals(JournalState.CLOSED, store.read()?.state)
            assertEquals(1, adb.calls.count { it.endsWith("forward --remove tcp:40000") })
            assertTrue(adb.calls.none { it.endsWith("tcp:40001") }, "another forward is never removed")
        }

    @Test
    fun `an active journal without a host port removes the forwards to its device port only`() =
        runBlocking {
            store.write(record(JournalState.READY))
            val removed = mutableSetOf<String>()
            val adb =
                adb(forwards = {
                    listOf("$serial tcp:40000 tcp:$DEVICE_PORT", "$serial tcp:40001 tcp:9999", "other tcp:40002 tcp:$DEVICE_PORT")
                        .filter { line -> removed.none { line.contains(" tcp:$it ") } }
                        .joinToString("\n")
                })
            adb.responder.let { base ->
                adb.responder = { s, command ->
                    if (command.startsWith("forward --remove tcp:")) removed += command.removePrefix("forward --remove tcp:")
                    base?.invoke(s, command)
                }
            }
            recoverJournal(adb, serial, "boot-1", store)
            assertEquals(setOf("40000"), removed)
        }

    @Test
    fun `a forward the journal names that targets another port quarantines`() =
        runBlocking {
            store.write(record(JournalState.ACTIVE, hostPort = 40000))
            val adb = adb(forwards = { "$serial tcp:40000 tcp:9999" })
            assertFailsWith<DeviceQuarantinedException> { recoverJournal(adb, serial, "boot-1", store) }
            assertTrue(adb.calls.none { "forward --remove" in it })
        }

    @Test
    fun `an active journal from another boot or a reboot during recovery quarantines`() =
        runBlocking {
            store.write(record(JournalState.ACTIVE, bootId = "boot-0"))
            assertFailsWith<DeviceQuarantinedException> { recoverJournal(adb(), serial, "boot-1", store) }
            assertEquals(JournalState.QUARANTINED, store.read()?.state)

            val other = SessionJournalStore(Files.createTempDirectory("tap-recover-reboot"), serial)
            other.write(record(JournalState.ACTIVE))
            val rebooting = adb(boots = listOf("boot-2").iterator())
            assertFailsWith<DeviceQuarantinedException> { recoverJournal(rebooting, serial, "boot-1", other) }
            assertEquals(JournalState.QUARANTINED, other.read()?.state)
        }

    @Test
    fun `a device port outside the reserved range quarantines`() =
        runBlocking {
            store.write(record(JournalState.ACTIVE, devicePort = 5555))
            assertFailsWith<DeviceQuarantinedException> { recoverJournal(adb(), serial, "boot-1", store) }
            assertEquals(JournalState.QUARANTINED, store.read()?.state)
        }

    @Test
    fun `a quarantined journal stays quarantined unless reset recovery is allowed`() =
        runBlocking {
            store.write(
                record(JournalState.QUARANTINED).copy(quarantineReason = LATE_MUTATION_QUARANTINE, resetRequired = true),
            )
            val refused = assertFailsWith<DeviceQuarantinedException> { recoverJournal(adb(), serial, "boot-1", store) }
            assertTrue(refused.message!!.endsWith(": $LATE_MUTATION_QUARANTINE"), refused.message)
            assertEquals(JournalState.QUARANTINED, recoverJournal(adb(), serial, "boot-1", store, allowResetRecovery = true)?.state)
        }

    @Test
    fun `a corrupt journal is preserved and replaced by a quarantine`() =
        runBlocking {
            store.write(record(JournalState.CLOSED))
            val journal = Files.list(root).use { files -> files.filter { it.toString().endsWith(".json") }.findFirst().get() }
            Files.writeString(journal, "{ not json")
            assertFailsWith<CorruptJournalException> { recoverJournal(adb(), serial, "boot-1", store) }
            assertEquals(JournalState.QUARANTINED, store.read()?.state)
            assertTrue(Files.list(root).use { files -> files.anyMatch { ".corrupt-" in it.fileName.toString() } })
        }
}
