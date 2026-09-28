package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.DEVICE_PORT
import io.github.noamcohen48.tap.host.DEVICE_PORT_RANGE
import io.github.noamcohen48.tap.host.DRIVER_TEST_RUNNER
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.host.SessionJournal
import io.github.noamcohen48.tap.host.SessionJournalStore
import io.github.noamcohen48.tap.host.processStartToken
import io.github.noamcohen48.tap.host.recoverJournal
import io.github.noamcohen48.tap.host.removeExactForward
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Journal recovery against a real device: a `CREATING` record's stale forward, an orphaned
 * `ACTIVE` driver left by a vanished host, and a changed boot identity. An unrelated sentinel
 * forward proves recovery only ever removes forwards the journal owns.
 */
@DeviceTest
class RecoveryTest {
    @OnEachDevice
    fun `CREATING recovery removes only the stale device-port forward`(serial: String) =
        deviceTest(serial) { device ->
            withSentinelForward(device) { unrelatedForwards ->
                val devicePort = DEVICE_PORT_RANGE.first
                device.store.write(
                    SessionJournal(
                        state = JournalState.CREATING,
                        serial = serial,
                        bootId = device.bootId,
                        sessionId = UUID.randomUUID().toString(),
                        generation = device.nextGeneration(),
                        devicePort = devicePort,
                    ),
                )
                val staleHostPort = device.adb.forward(serial, devicePort)
                val recovered = requireNotNull(recoverJournal(device.adb, serial, device.bootId, device.store))
                check(recovered.state == JournalState.CLOSED) { "CREATING recovery journaled ${recovered.state}" }
                check(device.adb.forwards(serial).none { it.hostPort == staleHostPort }) {
                    "CREATING recovery retained the stale device-port forward"
                }
                check(device.adb.forwards(serial).toSet() == unrelatedForwards) {
                    "CREATING recovery modified unrelated forwarding rules"
                }
            }
        }

    @OnEachDevice
    fun `an orphaned ACTIVE driver is stopped and its forward removed`(serial: String) =
        deviceTest(serial) { device ->
            withSentinelForward(device) { unrelatedForwards ->
                val orphanProcess = seedOrphanSession(device)
                try {
                    device.reacquireLease()
                    val recovered = requireNotNull(recoverJournal(device.adb, serial, device.bootId, device.store))
                    check(recovered.state == JournalState.CLOSED) { "Orphan recovery journaled ${recovered.state}" }
                    check(orphanProcess.waitFor(10, TimeUnit.SECONDS)) { "Orphan instrumentation child did not terminate" }
                    check(device.adb.forwards(serial).toSet() == unrelatedForwards) {
                        "Orphan recovery modified unrelated forwarding rules"
                    }
                } finally {
                    if (orphanProcess.isAlive) orphanProcess.destroyForcibly()
                }
            }
        }

    @OnEachDevice
    fun `a changed boot identity is durably quarantined and keeps unowned forwards`(serial: String) =
        deviceTest(serial) { device ->
            // A scratch journal root: the quarantine must not land in the real one.
            val root = Files.createTempDirectory("tap-boot-journal")
            val store = SessionJournalStore(root, serial)
            val devicePort = DEVICE_PORT_RANGE.first
            var hostPort: Int? = null
            try {
                store.acquireLease().use {
                    hostPort = device.adb.forward(serial, devicePort)
                    store.write(
                        SessionJournal(
                            state = JournalState.ACTIVE,
                            serial = serial,
                            bootId = "changed-${device.bootId}",
                            sessionId = UUID.randomUUID().toString(),
                            generation = 1,
                            devicePort = devicePort,
                            hostPort = hostPort,
                        ),
                    )
                    val failure = runCatching { recoverJournal(device.adb, serial, device.bootId, store) }.exceptionOrNull()
                    check(failure != null) { "Changed boot identity was accepted" }
                    check(store.read()?.state == JournalState.QUARANTINED) { "Changed boot identity was not durably quarantined" }
                    check(device.adb.forwards(serial).any { it.hostPort == hostPort && it.devicePort == devicePort }) {
                        "Changed-boot recovery removed a forward without matching ownership identity"
                    }
                }
            } finally {
                hostPort?.let { removeExactForward(device.adb, serial, it, devicePort) }
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    /** Runs [block] with a forward no journal owns; [block] gets the forwards recovery must keep. */
    private suspend fun withSentinelForward(
        device: DeviceHarness,
        block: suspend (Set<io.github.noamcohen48.tap.host.Adb.Forwarding>) -> Unit,
    ) {
        val sentinelDevicePort = DEVICE_PORT_RANGE.last + 1_000
        val sentinelHostPort = device.adb.forward(device.serial, sentinelDevicePort)
        try {
            block(device.adb.forwards(device.serial).toSet())
        } finally {
            removeExactForward(device.adb, device.serial, sentinelHostPort, sentinelDevicePort)
        }
    }

    /** Starts a driver outside any host session and journals it `ACTIVE`, as a crashed host leaves it. */
    private suspend fun seedOrphanSession(device: DeviceHarness): Process {
        val serial = device.serial
        val sessionId = UUID.randomUUID().toString()
        val generation = device.nextGeneration()
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        var record =
            SessionJournal(
                state = JournalState.CREATING,
                serial = serial,
                bootId = device.bootId,
                sessionId = sessionId,
                generation = generation,
                devicePort = DEVICE_PORT,
            )
        device.store.write(record)
        val process =
            ProcessBuilder(
                device.adb.executable,
                "-s",
                serial,
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                "io.github.noamcohen48.tap.driver.TapDriverServerTest",
                "-e",
                "tapSession",
                sessionId,
                "-e",
                "tapGeneration",
                generation.toString(),
                "-e",
                "tapSecret",
                encodedSecret,
                "-e",
                "tapPort",
                DEVICE_PORT.toString(),
                "-e",
                "tapAutPackage",
                FIXTURE_PACKAGE,
                "-e",
                "tapSyncAuthority",
                SYNC_AUTHORITY,
                DRIVER_TEST_RUNNER,
            ).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        val driverPid = device.waitForDriverPid()
        val hostPort = device.adb.forward(serial, DEVICE_PORT)
        record =
            record.copy(
                state = JournalState.ACTIVE,
                hostPort = hostPort,
                driverPid = driverPid,
                driverStartToken = processStartToken(device.adb, serial, driverPid),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        device.store.write(record)
        return process
    }
}
