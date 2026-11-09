package io.github.noamcohen48.tap.host

import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * `tap doctor` for one device: what a session that is no longer running left behind, and with
 * [fix] the same recovery an attach would run, done now instead of at the next attach.
 * [unquarantine] puts a quarantined device back in service.
 *
 * Only a device nobody holds is examined: the per-serial lock is taken without waiting, so a
 * live session (any daemon, any process) is reported as in use and never touched. A quarantined
 * device is reported, never cleared by [check]: clearing it is [unquarantine], a person's (or
 * their agent's) explicit decision.
 */
object DeviceDoctor {
    /** One finding. [fixed] is true when [check] ran with `fix` and cleaned it up. */
    data class Finding(
        val problem: String,
        val fixed: Boolean = false,
    )

    sealed interface Outcome {
        /** Nothing left behind. */
        data object Clean : Outcome

        /** Another session holds the device; nothing was examined. */
        data object InUse : Outcome

        /** Leftovers, each fixed or not. */
        data class Findings(
            val findings: List<Finding>,
        ) : Outcome

        /** Out of service until [unquarantine] puts it back. */
        data class Quarantined(
            val reason: String,
        ) : Outcome
    }

    sealed interface UnquarantineOutcome {
        /** Another session holds the device; nothing was touched. */
        data object InUse : UnquarantineOutcome

        /** The device was not quarantined; nothing was touched. */
        data object NotQuarantined : UnquarantineOutcome

        /**
         * Back in service. [reason] is why it had been quarantined, [cleaned] what the cleanup
         * retry put right, [remaining] what it could not, which the device now keeps.
         */
        data class Unquarantined(
            val reason: String,
            val cleaned: List<String>,
            val remaining: List<String>,
        ) : UnquarantineOutcome
    }

    suspend fun check(
        adb: Adb,
        journalRoot: Path,
        serial: String,
        fix: Boolean,
    ): Outcome {
        val store = SessionJournalStore(journalRoot, serial)
        val lease =
            try {
                store.acquireLease(0)
            } catch (busy: DeviceBusyException) {
                return Outcome.InUse
            }
        return lease.use { examine(adb, store, serial, fix) }
    }

    private suspend fun examine(
        adb: Adb,
        store: SessionJournalStore,
        serial: String,
        fix: Boolean,
    ): Outcome {
        val record =
            try {
                store.read()
            } catch (corrupt: CorruptJournalException) {
                if (!fix) return Outcome.Findings(listOf(Finding("the session journal is unreadable (attach would quarantine it)")))
                null
            }
        if (record?.state == JournalState.QUARANTINED && !record.restoreRetryable) {
            return Outcome.Quarantined(record.quarantineReason ?: NO_REASON)
        }
        // A session inspecting a quarantined device ended without closing: still quarantined.
        record?.inspectedQuarantine?.let { return Outcome.Quarantined(it) }
        val findings = mutableListOf<String>()
        if (record?.restoreRetryable == true) findings += "quarantined until its device settings read back (${record.quarantineReason})"
        if (record != null && record.state != JournalState.CLOSED && record.state != JournalState.QUARANTINED) findings += "a session (generation ${record.generation}) ended without closing"
        if (record != null && record.savedState.isNotEmpty()) findings += "device settings a session changed were not restored (${record.savedState.size})"
        if (adb.processIds(serial, DRIVER_PACKAGE).isNotEmpty()) findings += "the driver is running with no session"
        val forwards = adb.forwards(serial).filter { it.devicePort in DEVICE_PORT_RANGE }
        forwards.forEach { findings += "port forward tcp:${it.hostPort} -> tcp:${it.devicePort} has no session" }
        if (findings.isEmpty()) return Outcome.Clean
        if (!fix) return Outcome.Findings(findings.map { Finding(it) })
        try {
            // The recovery an attach runs: stop the old driver, remove the journal's forwards,
            // close the journal, put back what it saved (again, for a retryable quarantine); it
            // quarantines what it cannot prove.
            val recovered = recoverJournal(adb, serial, adb.bootId(serial), store)
            recovered?.let { if (it.state == JournalState.QUARANTINED) retryRestore(adb, serial, store, it) else restorePriorState(adb, serial, store, it) }
            forceStopDriverAndVerify(adb, serial)
            adb
                .forwards(serial)
                .filter { it.devicePort in DEVICE_PORT_RANGE }
                .forEach { removeExactForward(adb, serial, it.hostPort, it.devicePort) }
        } catch (quarantined: DeviceQuarantinedException) {
            val reason = runCatching { store.read()?.quarantineReason }.getOrNull() ?: quarantined.message ?: "recovery failed"
            return Outcome.Quarantined(reason)
        }
        return Outcome.Findings(findings.map { Finding(it, fixed = true) })
    }

    /**
     * Puts a quarantined device back in service, for a person who has checked it (or an agent
     * acting for one). It retries the cleanup a session could not prove: stops the driver,
     * removes Tap's port forwards and writes back the settings the journal still holds. Then it
     * closes the journal, whatever the retry managed: [UnquarantineOutcome.Unquarantined] says
     * what is still off. An unreadable journal is kept beside it (`.corrupt-<ms>.json`) first.
     *
     * Nothing changes while another session holds the device, or when it is not quarantined.
     * The device must answer ADB: its boot identity is read before anything is cleared.
     */
    suspend fun unquarantine(
        adb: Adb,
        journalRoot: Path,
        serial: String,
    ): UnquarantineOutcome {
        val store = SessionJournalStore(journalRoot, serial)
        val lease =
            try {
                store.acquireLease(0)
            } catch (busy: DeviceBusyException) {
                return UnquarantineOutcome.InUse
            }
        return lease.use { release(adb, store, serial) }
    }

    private suspend fun release(
        adb: Adb,
        store: SessionJournalStore,
        serial: String,
    ): UnquarantineOutcome {
        // An unreadable journal keeps the device out of service just as a quarantine does.
        val (record, corrupt) =
            try {
                store.read() to false
            } catch (unreadable: CorruptJournalException) {
                null to true
            }
        if (!corrupt && record?.state != JournalState.QUARANTINED && record?.inspectedQuarantine == null) return UnquarantineOutcome.NotQuarantined
        val reason = record?.let { it.quarantineReason ?: it.inspectedQuarantine ?: NO_REASON } ?: "the session journal is unreadable"
        val bootId = adb.bootId(serial)
        val cleaned = mutableListOf<String>()
        val remaining = mutableListOf<String>()
        attempt(remaining, "the driver could not be stopped") {
            forceStopDriverAndVerify(adb, serial)
            cleaned += "stopped the driver"
        }
        attempt(remaining, "Tap's port forwards could not be removed") {
            val forwards = adb.forwards(serial).filter { it.devicePort in DEVICE_PORT_RANGE }
            forwards.forEach { removeExactForward(adb, serial, it.hostPort, it.devicePort) }
            if (forwards.isNotEmpty()) cleaned += "removed ${forwards.size} port forward(s)"
        }
        val saved = record?.savedState.orEmpty()
        if (saved.isNotEmpty()) {
            attempt(remaining, "device settings a session changed were not restored") {
                adb.restoreState(serial, saved)
                cleaned += "restored ${saved.size} device setting(s)"
            }
        }
        val closed =
            SessionJournal(
                state = JournalState.CLOSED,
                serial = serial,
                bootId = bootId,
                sessionId = record?.sessionId ?: UUID.randomUUID().toString(),
                generation = record?.generation ?: 0,
                devicePort = record?.devicePort ?: DEVICE_PORT,
            )
        if (corrupt) {
            store.preserveCorrupt()
            store.replaceCorruptWith(closed)
        } else {
            store.write(closed)
        }
        return UnquarantineOutcome.Unquarantined(reason, cleaned, remaining)
    }

    /** Runs one cleanup step; a failure is recorded in [remaining] (with its message), not thrown. */
    private inline fun attempt(
        remaining: MutableList<String>,
        what: String,
        step: () -> Unit,
    ) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            remaining += error.message?.let { "$what: $it" } ?: what
        }
    }

    private const val NO_REASON = "no reason recorded"
}
