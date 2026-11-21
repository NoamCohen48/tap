package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.AppDeath
import io.github.noamcohen48.tap.api.v1.HistoryDeath
import io.github.noamcohen48.tap.api.v1.HistoryEntry
import io.github.noamcohen48.tap.api.v1.HistoryIssueKind
import io.github.noamcohen48.tap.api.v1.HistorySelection
import io.github.noamcohen48.tap.api.v1.LogAction
import io.github.noamcohen48.tap.api.v1.LogLine
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.api.v1.ReadHistoryRequest
import io.github.noamcohen48.tap.api.v1.ReadHistoryResponse
import io.github.noamcohen48.tap.daemon.storage.StoredEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryReaderTest {
    @TempDir lateinit var root: Path
    private var now = 10_000L
    private val store by lazy { RetainedLogs(root, { now }, { Long.MAX_VALUE }) }
    private val reader by lazy { HistoryReader(store, root, { now }) }

    private fun policy(age: Long = 3_600_000) = LoggingPolicy.write(root, LoggingPolicy(age, 1_000_000, 2_000_000, 0))

    private fun RetainedLogs.Archive.add(
        at: Long,
        build: StoredEntry.Builder.() -> Unit,
    ) {
        now = at
        store.append(this, StoredEntry.newBuilder().setCursor(cursor + 1).setHostTimeMs(at).apply(build).build())
    }

    private fun RetainedLogs.Archive.line(
        at: Long,
        text: String,
    ) = add(at) { setLine(LogLine.newBuilder().setMessage(text)) }

    private fun RetainedLogs.Archive.action(
        at: Long,
        step: String,
    ) = add(at) { setAction(LogAction.newBuilder().setOperation("tap").setStep(step)) }

    private fun RetainedLogs.Archive.call(
        startedAt: Long,
        endedAt: Long,
        step: String,
    ) = add(endedAt) { setEvent(LoggedEvent.newBuilder().setAtEpochMs(startedAt).setSerial("emu").setStep(step)) }

    private fun RetainedLogs.Archive.death(
        at: Long,
        name: String,
    ) = add(at) { setDeath(HistoryDeath.newBuilder().setSerial("emu").setDeath(AppDeath.newBuilder().setProcessName(name))) }

    private fun read(build: ReadHistoryRequest.Builder.() -> Unit): ReadHistoryResponse =
        runBlocking { reader.read(ReadHistoryRequest.newBuilder().apply(build).build()) }

    private fun text(entry: HistoryEntry): String =
        when {
            entry.hasLine() -> entry.line.message
            entry.hasMarker() -> "marker:${entry.marker}"
            entry.hasAction() -> "action:${entry.action.step}"
            entry.hasCall() -> "call:${entry.call.step}@${entry.receivedAtMs}"
            entry.hasDeath() -> "death:${entry.death.death.processName}"
            else -> "gap:${entry.gap}"
        }

    private fun id() = UUID.randomUUID().toString()

    @Test fun `a marker bound takes each device's newest marker across its attachments`() {
        policy()
        val old = store.open(id(), id(), "emu")
        old.add(1_000) { setMarker("checkout") }
        old.line(1_100, "old run")
        store.close(old)
        val current = store.open(id(), id(), "emu")
        current.line(4_000, "before")
        current.add(5_000) { setMarker("checkout") }
        current.line(5_100, "paid")
        val texts = read { fromMarker = "checkout"; includeLogs = true }.entriesList.map(::text)
        assertEquals(listOf("marker:checkout", "paid"), texts)
    }

    @Test fun `a step read keeps its own runs, their deaths and the read's bounds`() {
        policy()
        val owner = id()
        val calls = store.open(owner, owner, "", events = true)
        val device = store.open(id(), owner, "emu")
        device.action(1_000, "a")
        device.line(1_050, "inside a")
        device.action(1_090, "a") // the driver writes a start and an end action per command
        calls.call(1_000, 1_100, "a")
        device.action(2_000, "b")
        device.line(2_050, "inside b")
        calls.call(2_000, 2_100, "b")
        calls.death(2_500, "during b")
        device.action(3_000, "a")
        calls.death(3_050, "during a")
        calls.call(3_000, 3_100, "a")
        calls.death(9_000, "much later")
        now = 10_000

        val all = read { step = "a"; includeLogs = true }.entriesList.map(::text)
        assertEquals(listOf("action:a", "inside a", "action:a", "call:a@1100", "action:a", "death:during a", "call:a@3100"), all)
        val bounded = read { step = "a"; includeLogs = true; fromTimeMs = 2_900 }.entriesList.map(::text)
        assertEquals(listOf("action:a", "death:during a", "call:a@3100"), bounded)
    }

    @Test fun `a bounded read that starts before what retention kept reports it, an unbounded one does not`() {
        policy(age = 1_000)
        val device = store.open(id(), id(), "emu")
        device.line(10_000, "removed")
        device.line(11_500, "kept")
        now = 11_600
        store.maintain()
        val bounded = read { fromTimeMs = 9_000; includeLogs = true }
        assertEquals(listOf("kept"), bounded.entriesList.map(::text))
        assertEquals(listOf(HistoryIssueKind.HISTORY_ISSUE_KIND_EXPIRED), bounded.issuesList.map { it.kind })
        assertTrue(read { includeLogs = true }.issuesList.isEmpty())
        assertTrue(read { fromTimeMs = 11_000; includeLogs = true }.issuesList.isEmpty())
    }

    @Test fun `entries past the retention age in a kept file are reported with their count`() {
        policy(age = 1_000)
        val device = store.open(id(), id(), "emu")
        device.line(11_000, "old")
        device.line(11_100, "older than the age")
        device.line(11_800, "kept")
        now = 12_150
        val response = read { includeLogs = true }
        assertEquals(listOf("kept"), response.entriesList.map(::text))
        val expired = response.issuesList.single()
        assertEquals(HistoryIssueKind.HISTORY_ISSUE_KIND_EXPIRED, expired.kind)
        assertEquals(2, expired.missingEntries)
    }

    @Test fun `a recording starts on a degraded source and names it in its issues`() {
        policy()
        val owner = id()
        val calls = store.open(owner, owner, "", events = true)
        val device = store.open(id(), owner, "emu")
        store.close(device, "logcat stopped")
        val recording = store.start("r", owner, listOf(device.id, calls.id))
        assertEquals(listOf(device.id to "logcat stopped"), store.issues(recording))
        val response = read { selection = HistorySelection.newBuilder().setRecordingId(recording.id).build() }
        assertEquals(listOf(device.id), response.issuesList.map { it.sourceId })
        assertTrue(response.sourcesList.all { it.issuesCount == 0 })
    }

    @Test fun `a closed connection's open recordings end so they can be deleted`() {
        policy()
        val owner = id()
        val calls = store.open(owner, owner, "", events = true)
        val recording = store.start("r", owner, listOf(calls.id))
        store.endConnection(owner)
        assertTrue(store.get(recording.id).hasStoppedAtMs())
        store.delete(recording.id)
        assertTrue(store.list().isEmpty())
    }
}
