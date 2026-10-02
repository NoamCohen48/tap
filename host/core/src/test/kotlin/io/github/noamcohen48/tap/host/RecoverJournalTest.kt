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
            assertEquals(listOf("$serial: shell dumpsys notification", "$serial: shell am force-stop $DRIVER_PACKAGE", "$serial: shell pidof $DRIVER_PACKAGE"), adb.calls)
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

    /** [device] answers the notification commands; the driver process runs while its listener is bound. */
    private fun listenerAdb(device: FakeDeviceState) =
        adb().apply {
            responder.let { base ->
                responder = { s, command ->
                    when (command) {
                        "shell pidof $DRIVER_PACKAGE" -> if (device.driverListenerBound) ok("4242") else Adb.Result(1, "")
                        else -> device.answer(command) ?: base?.invoke(s, command)
                    }
                }
            }
        }

    @Test
    fun `a listener bound with its access given is unbound before the driver stops`() =
        runBlocking {
            // A server that died while a session had given notification access.
            val device = FakeDeviceState(mapOf(StateKey.DriverNotificationListener.id to "allowed")).apply { driverListenerBound = true }
            val adb = listenerAdb(device)
            assertNull(recoverJournal(adb, serial, "boot-1", store))
            assertEquals("disallowed", device.values[StateKey.DriverNotificationListener.id])
            val disallow = adb.calls.indexOf("$serial: shell cmd notification disallow_listener $DRIVER_NOTIFICATION_LISTENER")
            assertTrue(disallow in 0 until adb.calls.indexOf("$serial: shell am force-stop $DRIVER_PACKAGE"), "${adb.calls}")
            assertTrue(adb.calls.none { "allow_listener" in it && "disallow" !in it }, "${adb.calls}")
        }

    @Test
    fun `a listener Android bound again without access is given it and has it taken back to unbind`() =
        runBlocking {
            // Android 10 rebinds a killed listener whatever its access; only a change unbinds it.
            val device = FakeDeviceState(mapOf(StateKey.DriverNotificationListener.id to "disallowed")).apply { driverListenerBound = true }
            val adb = listenerAdb(device)
            assertNull(recoverJournal(adb, serial, "boot-1", store))
            assertEquals("disallowed", device.values[StateKey.DriverNotificationListener.id])
            assertEquals(listOf("${StateKey.DriverNotificationListener.id}=allowed", "${StateKey.DriverNotificationListener.id}=disallowed"), device.writes)
        }

    @Test
    fun `a listener that stays bound fails the stop instead of leaving the driver to come back`() =
        runBlocking {
            val device = FakeDeviceState(mapOf(StateKey.DriverNotificationListener.id to "allowed")).apply { driverListenerBound = true }
            device.stuck += StateKey.DriverNotificationListener.id
            val adb = listenerAdb(device)
            assertFailsWith<DeviceSettingException> { recoverJournal(adb, serial, "boot-1", store) }
            assertTrue(adb.calls.none { "force-stop" in it }, "${adb.calls}")
        }

    @Test
    fun `state a dead session changed is restored and cleared from the journal`() =
        runBlocking {
            val device = FakeDeviceState(mapOf(StateKey.FONT_SCALE to "1.3", StateKey.Density.id to "320"))
            val adb = adb().apply { responder.let { base -> responder = { s, command -> device.answer(command) ?: base?.invoke(s, command) } } }
            val prior = record(JournalState.CLOSED).copy(savedState = listOf(SavedState(StateKey.FONT_SCALE, "1.1"), SavedState(StateKey.Density.id, null)))
            store.write(prior)
            val restored = restorePriorState(adb, serial, store, prior)
            assertEquals(mapOf(StateKey.FONT_SCALE to "1.1", StateKey.Density.id to null), device.values)
            assertEquals(emptyList(), restored.savedState)
            assertEquals(emptyList(), store.read()?.savedState)
            assertEquals(JournalState.CLOSED, store.read()?.state)
        }

    @Test
    fun `state that cannot be restored quarantines and keeps the record`() =
        runBlocking {
            val device = FakeDeviceState(mapOf(StateKey.FONT_SCALE to "1.3"))
            device.stuck += StateKey.FONT_SCALE
            val adb = adb().apply { responder.let { base -> responder = { s, command -> device.answer(command) ?: base?.invoke(s, command) } } }
            val prior = record(JournalState.CLOSED).copy(savedState = listOf(SavedState(StateKey.FONT_SCALE, "1.1")))
            store.write(prior)
            assertFailsWith<DeviceQuarantinedException> { restorePriorState(adb, serial, store, prior) }
            val record = store.read()
            assertEquals(JournalState.QUARANTINED, record?.state)
            assertTrue(record?.quarantineReason?.startsWith("DEVICE_STATE_RESTORE_FAILED") == true, record?.quarantineReason)
            assertEquals(prior.savedState, record?.savedState)
        }
}
