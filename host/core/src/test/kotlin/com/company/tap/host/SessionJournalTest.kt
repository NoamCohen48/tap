package com.company.tap.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionJournalTest {
    @Test
    fun persistsAndRejectsGenerationRegression() {
        val root = Files.createTempDirectory("tap-journal-test")
        val store = SessionJournalStore(root, "serial")
        val record = record(generation = 2)
        store.write(record)

        assertEquals(record, store.read())
        assertFailsWith<IllegalArgumentException> {
            store.write(record(generation = 1))
        }
    }

    @Test
    fun sessionReplacementRequiresNewGeneration() {
        val root = Files.createTempDirectory("tap-journal-identity-test")
        val store = SessionJournalStore(root, "serial")
        store.write(record(generation = 2, sessionId = "first"))

        assertFailsWith<IllegalArgumentException> {
            store.write(record(generation = 2, sessionId = "second"))
        }
    }

    @Test
    fun preservesCorruptEvidenceAndAtomicallyQuarantines() {
        val root = Files.createTempDirectory("tap-corrupt-journal-test")
        val store = SessionJournalStore(root, "serial")
        store.write(record(generation = 1))
        val journal = Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.findFirst().orElseThrow()
        }
        Files.writeString(journal, "not-json")

        assertFailsWith<IllegalStateException> { store.read() }
        val evidence = store.preserveCorrupt()
        val quarantined = record(generation = 0).copy(state = JournalState.QUARANTINED)
        store.replaceCorruptWith(quarantined)

        assertEquals(quarantined, store.read())
        check(evidence != null && Files.readString(evidence) == "not-json")
    }

    @Test
    fun resetRecoveryStartsAndRetriesOnTheQuarantinedBoot() {
        val quarantined = resetRecord()
        assertEquals(ResetRecoveryAction.REBOOT, resetRecoveryAction(quarantined, "boot"))
        assertEquals(
            ResetRecoveryAction.REBOOT,
            resetRecoveryAction(quarantined.copy(resetStartedBootId = "boot"), "boot"),
        )
    }

    @Test
    fun resetRecoveryCompletesOnlyAfterDurablyStartedBootChange() {
        val quarantined = resetRecord()
        assertFailsWith<IllegalArgumentException> {
            resetRecoveryAction(quarantined, "new-boot")
        }
        assertEquals(
            ResetRecoveryAction.COMPLETE,
            resetRecoveryAction(quarantined.copy(resetStartedBootId = "boot"), "new-boot"),
        )
    }

    @Test
    fun rejectsResetOriginDifferentFromQuarantinedBoot() {
        val root = Files.createTempDirectory("tap-reset-origin-test")
        val store = SessionJournalStore(root, "serial")

        assertFailsWith<IllegalArgumentException> {
            store.write(resetRecord().copy(resetStartedBootId = "other-boot"))
        }
    }

    private fun resetRecord() = record(generation = 3).copy(
        state = JournalState.QUARANTINED,
        quarantineReason = "UNINTERRUPTIBLE_MUTATION_RESET_REQUIRED",
        resetRequired = true,
    )

    private fun record(generation: Long, sessionId: String = "session-$generation") = SessionJournal(
        state = JournalState.CLOSED,
        serial = "serial",
        bootId = "boot",
        sessionId = sessionId,
        generation = generation,
        devicePort = 27183,
    )
}
