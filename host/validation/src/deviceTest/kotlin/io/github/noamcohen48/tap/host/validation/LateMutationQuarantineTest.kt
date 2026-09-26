package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.CommandTransportException
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.host.LATE_MUTATION_QUARANTINE
import io.github.noamcohen48.tap.host.ResetRecoveryAction
import io.github.noamcohen48.tap.host.SessionJournalStore
import io.github.noamcohen48.tap.host.TransmissionState
import io.github.noamcohen48.tap.host.observeProcess
import io.github.noamcohen48.tap.host.processStartToken
import io.github.noamcohen48.tap.host.recoverJournal
import io.github.noamcohen48.tap.host.resetRecoveryAction
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

private const val DRIVER_INSTRUMENTATION = "$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"

/**
 * A mutation the driver cannot interrupt (`LATE_UNINTERRUPTIBLE`) outlives its connection: the
 * host reports `INDETERMINATE`, quarantines the device with `resetRequired`, refuses same-boot
 * reuse, and only an explicit **reboot** plus AUT data reset clears it; a post-reset session
 * then proves the late mutation never landed.
 *
 * Reboots the device, so it is tagged `reboot`: excluded by default and run only with
 * `-Ptap.reboot=true`. Never run it against shared devices without asking.
 */
@DeviceTest
@Tag("reboot")
class LateMutationQuarantineTest {
    @OnEachDevice
    fun `late uninterruptible mutation quarantines until an explicit reboot reset`(serial: String) {
        assumeTrue(Devices.allowReboot) { "Reboot scenarios need -Ptap.reboot=true" }
        val scenarioDeadline = System.nanoTime() + 600_000_000_000L

        // A reset an earlier run left pending must finish before a new scenario starts.
        deviceTest(serial, allowResetRecovery = true) { device ->
            val prior = device.priorJournal
            if (prior?.state == JournalState.QUARANTINED && prior.resetRequired) {
                resetQuarantinedDevice(device.adb, serial, device.bootId, device.store, scenarioDeadline)
            }
        }

        var hypotheticalMutationAtNanos = Long.MAX_VALUE
        deviceTest(serial) { device ->
            val adb = device.adb
            device.forceStopFixture()
            device.wakeAndDismissKeyguard()
            device.launchFixture("MainActivity")
            val fixtureProcess = observeProcess(adb, serial, FIXTURE_PACKAGE)
            val workDeadline = minOf(System.nanoTime() + 180_000_000_000L, scenarioDeadline)
            val session = device.startSession(TransportFaultPoint.LATE_UNINTERRUPTIBLE, deadlineNanos = workDeadline)
            val faultButton = Selectors.androidResource(FIXTURE_PACKAGE, "fault_button")
            var lateWorkDelegated = false
            var cleanupStarted = false
            var primaryError: Throwable? = null

            fun quarantineForReset() {
                session.journal =
                    session.journal.copy(
                        state = JournalState.QUARANTINED,
                        quarantineReason = LATE_MUTATION_QUARANTINE,
                        resetRequired = true,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                device.store.write(session.journal)
            }

            try {
                check(session.client.send(Commands.waitVisible(Selectors.text("Fault taps: 0")), timeoutMs = 10_000).ok)
                val failure = runCatching { session.client.send(Commands.tap(faultButton), timeoutMs = 5_000) }.exceptionOrNull()
                hypotheticalMutationAtNanos = System.nanoTime() + 15_000_000_000L
                val delegationMarker = "point=${TransportFaultPoint.LATE_UNINTERRUPTIBLE.name} phase=WORK_DELEGATED"
                lateWorkDelegated = session.awaitMarker(delegationMarker, remainingTimeoutMs(scenarioDeadline, 5_000))
                check(lateWorkDelegated) { "Driver did not prove delegation of late mutation work" }
                check(failure is CommandTransportException && failure.code == ErrorCode.ERR_INDETERMINATE) {
                    "Late mutation did not produce INDETERMINATE: $failure"
                }
                check(failure.transmissionState == TransmissionState.WRITTEN)
                check(session.running.process.isAlive) { "Blocked instrumentation exited before cleanup" }
                val oldPid = requireNotNull(session.journal.driverPid)
                check(oldPid in adb.processIds(serial, DRIVER_PACKAGE))
                check(processStartToken(adb, serial, oldPid) == session.journal.driverStartToken)
                check(observeProcess(adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT changed before late-work cleanup" }

                quarantineForReset()
                cleanupStarted = true
                session.cleanup(terminalState = JournalState.QUARANTINED, expectForceStop = true)
                check(session.journal.resetRequired)
                check(session.journal.quarantineReason == LATE_MUTATION_QUARANTINE)
                val cleanForwards = adb.forwards(serial).toSet()
                val refusal = runCatching { recoverJournal(adb, serial, device.bootId, device.store) }.exceptionOrNull()
                check(refusal != null && device.store.read()?.state == JournalState.QUARANTINED) {
                    "Same-boot reuse was not refused after late mutation"
                }
                check(adb.forwards(serial).toSet() == cleanForwards)
                check(adb.processIds(serial, DRIVER_PACKAGE).isEmpty())
            } catch (error: Throwable) {
                primaryError = error
                println("Driver output on $serial before the failure:\n${session.outputTail()}")
            } finally {
                if (!cleanupStarted) {
                    val quarantineFailure = runCatching { quarantineForReset() }.exceptionOrNull()
                    val cleanupFailure = runCatching { session.cleanup(terminalState = JournalState.QUARANTINED) }.exceptionOrNull()
                    listOfNotNull(quarantineFailure, cleanupFailure).forEach { failure ->
                        if (primaryError == null) primaryError = failure else primaryError?.addSuppressed(failure)
                    }
                }
                if (lateWorkDelegated || device.store.read()?.resetRequired == true) {
                    runCatching { resetQuarantinedDevice(adb, serial, device.bootId, device.store, scenarioDeadline) }
                        .exceptionOrNull()
                        ?.let { if (primaryError == null) primaryError = it else primaryError?.addSuppressed(it) }
                }
            }
            primaryError?.let { throw it }
        }
        check(System.nanoTime() < scenarioDeadline) { "Late mutation reset scenario timed out" }

        // A fresh hold on the rebooted device: new boot identity, recovered CLOSED journal.
        deviceTest(serial) { device ->
            val adb = device.adb
            adb.install(serial, Devices.driverApk, remainingTimeoutMs(scenarioDeadline, 120_000))
            adb.install(serial, Devices.driverTestApk, remainingTimeoutMs(scenarioDeadline, 120_000))
            waitForPostResetPackageReadiness(adb, serial, device.apiLevel, scenarioDeadline)
            val registrationDeadline = minOf(System.nanoTime() + 30_000_000_000L, scenarioDeadline)
            while (System.nanoTime() < registrationDeadline && !instrumentationRegistered(adb, serial, registrationDeadline)) {
                delayWithinDeadline(registrationDeadline, 250)
            }
            check(instrumentationRegistered(adb, serial, scenarioDeadline)) {
                "Driver instrumentation was not registered after reset reinstall"
            }
            device.launchFixture("MainActivity")
            val remainingDelayMs = ((hypotheticalMutationAtNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (remainingDelayMs > 0) delayWithinDeadline(scenarioDeadline, remainingDelayMs + 500)

            val verification = startPostResetVerificationSession(device, scenarioDeadline)
            try {
                check(verification.client.send(Commands.waitVisible(Selectors.text("Fault taps: 0")), timeoutMs = 10_000).ok) {
                    "Late mutation survived the mandatory reset"
                }
            } catch (error: Throwable) {
                runCatching { verification.cleanup() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            verification.cleanup()
        }
    }

    private suspend fun instrumentationRegistered(
        adb: Adb,
        serial: String,
        deadlineNanos: Long,
    ): Boolean =
        adb
            .run(serial, "shell", "pm", "list", "instrumentation", timeoutMs = remainingTimeoutMs(deadlineNanos, 5_000))
            .contains(DRIVER_INSTRUMENTATION)

    /** Up to three post-reset startups; a failed one must be a safely quarantined, reset-free startup. */
    private suspend fun startPostResetVerificationSession(
        device: DeviceHarness,
        scenarioDeadline: Long,
    ): FaultSession {
        val adb = device.adb
        var lastFailure: Throwable? = null
        repeat(3) {
            check(System.nanoTime() < scenarioDeadline) { "Post-reset verification timed out" }
            val generation = device.nextGeneration()
            try {
                return device.startSession(
                    deadlineNanos = minOf(System.nanoTime() + 120_000_000_000L, scenarioDeadline),
                    generation = generation,
                )
            } catch (error: Throwable) {
                lastFailure?.addSuppressed(error)
                lastFailure = lastFailure ?: error
                val failed = requireNotNull(device.store.read())
                check(failed.state == JournalState.QUARANTINED && !failed.resetRequired && failed.generation == generation) {
                    "Post-reset startup failure was not safely quarantined"
                }
                check(adb.processIds(device.serial, DRIVER_PACKAGE).isEmpty())
                val remainingForwards = adb.forwards(device.serial)
                check(
                    if (failed.hostPort != null) {
                        remainingForwards.none { it.hostPort == failed.hostPort && it.devicePort == failed.devicePort }
                    } else {
                        remainingForwards.none { it.devicePort == failed.devicePort }
                    },
                ) { "Post-reset startup cleanup left an owned forwarding rule" }
                device.store.write(failed.copy(state = JournalState.CLOSED, updatedAtEpochMs = System.currentTimeMillis()))
                delayWithinDeadline(scenarioDeadline, 5_000)
            }
        }
        throw IllegalStateException("Driver did not restart after explicit reset", lastFailure)
    }

    private suspend fun waitForPostResetPackageReadiness(
        adb: Adb,
        serial: String,
        apiLevel: Int,
        deadlineNanos: Long,
    ) {
        if (apiLevel >= 31) {
            val idle = adb.runResult(serial, "shell", "am", "wait-for-broadcast-idle", timeoutMs = remainingTimeoutMs(deadlineNanos, 120_000))
            check(idle.exitCode == 0) { "Android did not reach broadcast-idle after reset: ${idle.output}" }
        } else {
            delayWithinDeadline(deadlineNanos, 30_000)
        }
        check(adb.run(serial, "shell", "pm", "path", DRIVER_PACKAGE, timeoutMs = remainingTimeoutMs(deadlineNanos, 30_000)).startsWith("package:")) {
            "Driver package was not available after reset reinstall"
        }
        check(
            adb.run(serial, "shell", "pm", "path", "$DRIVER_PACKAGE.test", timeoutMs = remainingTimeoutMs(deadlineNanos, 30_000)).startsWith("package:"),
        ) { "Driver test package was not available after reset reinstall" }
    }

    /**
     * Reboots (or completes an already started reboot of) a device quarantined for a late
     * mutation, waits for a new boot identity with the driver instrumentation registered, proves
     * no driver or owned forward survived, clears the AUT's data and journals `CLOSED` under the
     * new boot.
     */
    private suspend fun resetQuarantinedDevice(
        adb: Adb,
        serial: String,
        currentBootId: String,
        store: SessionJournalStore,
        scenarioDeadline: Long,
    ) {
        var record = requireNotNull(store.read())
        check(record.state == JournalState.QUARANTINED)
        check(record.resetRequired && record.quarantineReason == LATE_MUTATION_QUARANTINE)
        val resetAction = resetRecoveryAction(record, currentBootId)
        val resetOriginBootId = record.resetStartedBootId ?: record.bootId
        if (record.resetStartedBootId == null) {
            record = record.copy(resetStartedBootId = resetOriginBootId, updatedAtEpochMs = System.currentTimeMillis())
            store.write(record)
        }
        if (resetAction == ResetRecoveryAction.REBOOT) {
            adb.runResult(serial, "reboot", timeoutMs = remainingTimeoutMs(scenarioDeadline, 10_000))
        }

        val deadline = minOf(System.nanoTime() + 240_000_000_000L, scenarioDeadline)
        var newBootId: String? = null
        while (System.nanoTime() < deadline) {
            suspend fun probe(vararg arguments: String) =
                runCatching { adb.run(serial, "shell", *arguments, timeoutMs = remainingTimeoutMs(deadline, 5_000)) }.getOrNull()
            val candidate = probe("cat", "/proc/sys/kernel/random/boot_id")
            val completed = probe("getprop", "sys.boot_completed")
            val bootAnimation = probe("getprop", "init.svc.bootanim")
            val instrumentationReady = probe("pm", "list", "instrumentation")?.contains(DRIVER_INSTRUMENTATION) == true
            if (!candidate.isNullOrBlank() && candidate != resetOriginBootId && completed == "1" && bootAnimation == "stopped" && instrumentationReady) {
                newBootId = candidate
                break
            }
            delayWithinDeadline(deadline, 500)
        }
        val observedBootId = requireNotNull(newBootId) { "Device did not complete reboot with a new boot identity" }
        delayWithinDeadline(scenarioDeadline, 15_000)
        adb.run(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", timeoutMs = remainingTimeoutMs(scenarioDeadline, 30_000))
        adb.run(serial, "shell", "wm", "dismiss-keyguard", timeoutMs = remainingTimeoutMs(scenarioDeadline, 30_000))
        check(adb.processIds(serial, DRIVER_PACKAGE).isEmpty()) { "Driver process existed after explicit reboot reset" }
        val remainingForwards = adb.forwards(serial)
        check(
            if (record.hostPort != null) {
                remainingForwards.none { it.hostPort == record.hostPort && it.devicePort == record.devicePort }
            } else {
                remainingForwards.none { it.devicePort == record.devicePort }
            },
        ) { "Quarantined forward survived explicit reset" }
        check(adb.run(serial, "shell", "pm", "clear", FIXTURE_PACKAGE) == "Success") {
            "AUT data reset did not report success after reboot"
        }
        adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
        awaitAbsent(adb, serial, FIXTURE_PACKAGE)
        store.write(
            record.copy(
                state = JournalState.CLOSED,
                bootId = observedBootId,
                quarantineReason = null,
                resetRequired = false,
                resetStartedBootId = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun awaitAbsent(
        adb: Adb,
        serial: String,
        packageName: String,
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var consecutiveAbsentSamples = 0
        while (System.nanoTime() < deadline) {
            if (adb.processIds(serial, packageName).isEmpty()) {
                if (++consecutiveAbsentSamples == 3) return
            } else {
                consecutiveAbsentSamples = 0
            }
            delay(250)
        }
        error("$packageName process remained present after force-stop")
    }

    private fun remainingTimeoutMs(
        deadlineNanos: Long,
        maximumMs: Long,
    ): Long {
        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
        check(remainingMs > 0) { "Late mutation reset scenario timed out" }
        return minOf(maximumMs, remainingMs).coerceAtLeast(1L)
    }

    private suspend fun delayWithinDeadline(
        deadlineNanos: Long,
        requestedMs: Long,
    ) {
        val timeoutMs = remainingTimeoutMs(deadlineNanos, requestedMs)
        delay(timeoutMs)
        check(timeoutMs == requestedMs) { "Late mutation reset scenario timed out" }
    }
}
