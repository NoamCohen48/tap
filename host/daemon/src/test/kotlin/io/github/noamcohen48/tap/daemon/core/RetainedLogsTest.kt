package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogLine
import io.github.noamcohen48.tap.host.LogcatLine
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetainedLogsTest {
    @TempDir lateinit var root: Path
    private var now = 10_000L

    private fun store(free: Long = Long.MAX_VALUE) = RetainedLogs(root, { now }, { free })

    private fun policy(
        age: Long = 1_000,
        size: Long = 1_000_000,
        hard: Long = 2_000_000,
        free: Long = 0,
    ) = LoggingPolicy.write(root, LoggingPolicy(age, size, hard, free))

    private fun append(
        store: RetainedLogs,
        archive: RetainedLogs.Archive,
        cursor: Long,
        message: String = "entry-$cursor",
    ) = store.append(
        archive,
        LogEntry
            .newBuilder()
            .setCursor(cursor)
            .setHostTimeMs(now)
            .setLine(LogLine.newBuilder().setMessage(message))
            .build(),
    )

    @Test fun `default policy is one hour 256 MiB with a hard budget and disk reserve`() {
        assertEquals(LoggingPolicy(3_600_000, 268_435_456, 1_073_741_824, 268_435_456), LoggingPolicy.read(root))
        assertFailsWith<IllegalArgumentException> { LoggingPolicy(maxBytes = 200, hardBytes = 100) }
        assertFailsWith<IllegalArgumentException> { LoggingPolicy(maxAgeMs = 0) }
    }

    @Test fun `redacted entries survive detach and a new daemon instance`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "device")
        val log = DeviceLog(retained = store, archive = a)
        log.line(LogcatLine(100, 10001, 1, 1, 'I', "App", "password=secret-value"))
        log.close()
        val next = store()
        val saved = next.entries(next.archive(a.id, "owner"))
        assertEquals("password=<redacted>", saved.single().line.message)
        Files.walk(root).use { files ->
            files.filter(Files::isRegularFile).forEach { assertFalse(String(Files.readAllBytes(it)).contains("secret-value")) }
        }
        assertFailsWith<NotOwnerException> { next.archive(a.id, "another-owner") }
    }

    @Test fun `late redaction rewrites previously persisted segments`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "device")
        val log = DeviceLog(retained = store, archive = a)
        log.line(LogcatLine(100, 10001, 1, 1, 'I', "App", "custom confidential-value"))
        log.redactor.literal("confidential-value")
        assertEquals(
            "custom <redacted>",
            store
                .entries(a)
                .single()
                .line.message,
        )
        log.close()
        Files.walk(root).use { files ->
            files.filter(Files::isRegularFile).forEach { assertFalse(String(Files.readAllBytes(it)).contains("confidential-value")) }
        }
    }

    @Test fun `rolling age eviction does not evict a protected recording`() {
        policy()
        val store = store()
        val ordinary = store.open(UUID.randomUUID().toString(), "owner", "ordinary")
        val pinned = store.open(UUID.randomUUID().toString(), "owner", "pinned")
        append(store, pinned, 1)
        store.start("checkout", "owner", listOf(pinned.id))
        append(store, pinned, 2)
        append(store, ordinary, 1)
        store.stop("checkout")
        now += 2_000
        assertTrue(store.entries(ordinary).isEmpty())
        assertEquals(listOf(2L), store.entries(pinned, "checkout").map { it.cursor })
        store.delete("checkout")
        assertTrue(store.entries(pinned).isEmpty())
    }

    @Test fun `policy changes apply live and byte limits are global across devices`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        val b = store.open(UUID.randomUUID().toString(), "owner", "b")
        append(store, a, 1, "a".repeat(100))
        now++
        append(store, b, 1, "b".repeat(100))
        policy(size = 150)
        assertTrue(store.entries(a).isEmpty())
        assertEquals(1, store.entries(b).size)
    }

    @Test fun `recording start does not restart capture and stop freezes its boundary`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        append(store, a, 1)
        assertEquals(
            1,
            store
                .start("r", "owner", listOf(a.id))
                .rangesList
                .single()
                .afterCursor,
        )
        append(store, a, 2)
        val stopped = store.stop("r")
        assertTrue(stopped.complete)
        append(store, a, 3)
        val repeated = store.stop("r")
        assertEquals(stopped.toBuilder().clearRetainedBytes().build(), repeated.toBuilder().clearRetainedBytes().build())
        assertTrue(stopped.retainedBytes > 0)
        assertTrue(repeated.retainedBytes >= stopped.retainedBytes) // Shared segment tails can grow after stop.
        assertEquals(listOf(2L), store.entries(a, "r").map { it.cursor })
        assertEquals(listOf(1L, 2L, 3L), store.entries(a).map { it.cursor })
    }

    @Test fun `hard disk limit reports incomplete capture without evicting pinned evidence`() {
        policy(size = 150, hard = 6000)
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        store.start("r", "owner", listOf(a.id))
        append(store, a, 1, "a".repeat(1000))
        append(store, a, 2, "b".repeat(1000))
        assertTrue(a.error.contains("hard storage"))
        assertEquals(1, store.entries(a, "r").size)
        assertFalse(store.stop("r").complete)
        assertTrue(
            store
                .list()
                .single()
                .captureError
                .isNotEmpty(),
        )
    }

    @Test fun `free space reserve refuses recording start`() {
        policy(free = 100)
        val store = store(free = 99)
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        assertFailsWith<LogRecordingStateException> { store.start("r", "owner", listOf(a.id)) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun `restart finalizes open recordings as incomplete and keeps protected data`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        store.start("r", "owner", listOf(a.id))
        append(store, a, 1)
        now += 2_000
        val restarted = store()
        val recording = restarted.list().single()
        assertTrue(recording.hasStoppedAtMs())
        assertFalse(recording.complete)
        assertTrue(recording.captureError.contains("restart"))
        assertEquals(1, restarted.entries(restarted.archive(a.id, "owner"), "r").size)
    }

    @Test fun `a torn tail keeps committed evidence and marks the recording incomplete`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        store.start("r", "owner", listOf(a.id))
        append(store, a, 1)
        java.io
            .FileOutputStream(
                a.segments
                    .single()
                    .path
                    .toFile(),
                true,
            ).use { it.write(byteArrayOf(0, 0, 1)) }
        val recovered = store()
        assertEquals(listOf(1L), recovered.entries(recovered.archive(a.id, "owner"), "r").map { it.cursor })
        assertFalse(recovered.list().single().complete)
    }

    @Test fun `a torn first frame leaves no undeletable archive or startup failure`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        atomicPrivateFile(a.directory.resolve("00000000000000000001.pb")) { it.write(byteArrayOf(0, 0, 1)) }
        store.close(a)
        val recovered = store()
        assertTrue(recovered.entries(recovered.archive(a.id, "owner")).isEmpty())
        now += 2_000
        recovered.maintain()
        assertFalse(Files.exists(a.directory))
        assertTrue(store().archives("").isEmpty())
    }

    @Test fun `corruption of a later segment downgrades an already stopped range`() {
        policy(age = 1_000_000)
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        append(store, a, 1000)
        store.start("r", "owner", listOf(a.id))
        now += 60_000
        append(store, a, 1001)
        assertTrue(store.stop("r").complete)
        store.close(a)
        java.io
            .RandomAccessFile(
                a.segments
                    .last()
                    .path
                    .toFile(),
                "rw",
            ).use {
                it.setLength(2)
                it.fd.sync()
            }
        val recovered = store()
        assertFalse(recovered.list().single().complete)
        assertFalse(recovered.stop("r").complete)
        assertTrue(recovered.stop("r").captureError.isNotEmpty())
    }

    @Test fun `markers are not acknowledged as durable after storage exhaustion`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        val log = DeviceLog(retained = store, archive = a)
        policy(size = 1, hard = 1)
        assertFailsWith<DaemonPreconditionException> { log.mark("not-retained") }
        assertTrue(store.entries(a).isEmpty())
    }

    @Test fun `read snapshots hold files without copying all history and release eviction`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        append(store, a, 1)
        val snapshot = store.snapshot(a)
        now += 2_000
        store.maintain()
        assertEquals(listOf(1L), snapshot.entries().map { it.cursor }.toList())
        snapshot.close()
        assertTrue(store.entries(a).isEmpty())
    }

    @Test fun `policy age hides old unprotected entries sharing a protected segment`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        append(store, a, 1)
        store.start("r", "owner", listOf(a.id))
        append(store, a, 2)
        store.stop("r")
        now += 2_000
        assertEquals(listOf(2L), store.entries(a).map { it.cursor })
    }

    @Test fun `later storage failures do not invalidate an already stopped range`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        store.start("r", "owner", listOf(a.id))
        append(store, a, 1)
        store.stop("r")
        policy(size = 1, hard = 1)
        append(store, a, 2)
        assertTrue(store.list().single().complete)
        assertEquals("", store.captureError(a, "r"))
    }

    @Test fun `capture gaps prevent a protected range from claiming complete evidence`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        val log = DeviceLog(retained = store, archive = a)
        store.start("r", "owner", listOf(a.id))
        log.interrupted("device disconnected")
        assertFalse(store.stop("r").complete)
    }

    @Test fun `names cannot escape storage and active recordings cannot be deleted`() {
        policy()
        val store = store()
        val a = store.open(UUID.randomUUID().toString(), "owner", "a")
        assertFailsWith<IllegalArgumentException> { store.start("../outside", "owner", listOf(a.id)) }
        store.start("r", "owner", listOf(a.id))
        assertFailsWith<LogRecordingStateException> { store.start("r", "owner", listOf(a.id)) }
        assertFailsWith<LogRecordingStateException> { store.delete("r") }
    }
}
