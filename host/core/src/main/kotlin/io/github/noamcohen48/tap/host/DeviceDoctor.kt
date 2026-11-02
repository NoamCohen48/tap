package io.github.noamcohen48.tap.host

import java.nio.file.Path

/**
 * `tap doctor` for one device: what a session that is no longer running left behind, and with
 * [fix] the same recovery an attach would run, done now instead of at the next attach.
 *
 * Only a device nobody holds is examined: the per-serial lock is taken without waiting, so a
 * live session (any daemon, any process) is reported as in use and never touched. A quarantined
 * device is reported, never cleared: that stays a person's decision.
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

        /** Out of service until a person removes [journal] after checking the device. */
        data class Quarantined(
            val reason: String,
            val journal: Path,
        ) : Outcome
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
        if (record?.state == JournalState.QUARANTINED) {
            return Outcome.Quarantined(record.quarantineReason ?: "no reason recorded", store.file)
        }
        val findings = mutableListOf<String>()
        if (record != null && record.state != JournalState.CLOSED) findings += "a session (generation ${record.generation}) ended without closing"
        if (record != null && record.savedState.isNotEmpty()) findings += "device settings a session changed were not restored (${record.savedState.size})"
        if (adb.processIds(serial, DRIVER_PACKAGE).isNotEmpty()) findings += "the driver is running with no session"
        val forwards = adb.forwards(serial).filter { it.devicePort in DEVICE_PORT_RANGE }
        forwards.forEach { findings += "port forward tcp:${it.hostPort} -> tcp:${it.devicePort} has no session" }
        if (findings.isEmpty()) return Outcome.Clean
        if (!fix) return Outcome.Findings(findings.map { Finding(it) })
        try {
            // The recovery an attach runs: stop the old driver, remove the journal's forwards,
            // close the journal, put back what it saved; it quarantines what it cannot prove.
            val recovered = recoverJournal(adb, serial, adb.bootId(serial), store)
            recovered?.let { restorePriorState(adb, serial, store, it) }
            forceStopDriverAndVerify(adb, serial)
            adb
                .forwards(serial)
                .filter { it.devicePort in DEVICE_PORT_RANGE }
                .forEach { removeExactForward(adb, serial, it.hostPort, it.devicePort) }
        } catch (quarantined: DeviceQuarantinedException) {
            val reason = runCatching { store.read()?.quarantineReason }.getOrNull() ?: quarantined.message ?: "recovery failed"
            return Outcome.Quarantined(reason, store.file)
        }
        return Outcome.Findings(findings.map { Finding(it, fixed = true) })
    }
}
