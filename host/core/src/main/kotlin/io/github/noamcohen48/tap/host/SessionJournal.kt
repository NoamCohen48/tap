package io.github.noamcohen48.tap.host

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay

@Serializable
data class SessionJournal(
    val version: Int = 1,
    val state: JournalState,
    val serial: String,
    val bootId: String,
    val sessionId: String,
    val generation: Long,
    val devicePort: Int,
    val hostPort: Int? = null,
    val driverPid: Int? = null,
    val driverStartToken: String? = null,
    val driverInstanceId: String? = null,
    val quarantineReason: String? = null,
    val resetRequired: Boolean = false,
    val resetStartedBootId: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
enum class JournalState {
    CREATING,
    ACTIVE,
    READY,
    BROKEN,
    QUARANTINED,
    CLOSED,
}

/** The per-serial lock was still held by another session at the deadline. */
class DeviceBusyException(val serial: String, val waitedMs: Long) :
    TapHostException(if (waitedMs > 0) "Device $serial is in use by another session (waited ${waitedMs}ms)" else "Device $serial is in use by another session")

private const val LEASE_POLL_MS = 200L

class SessionJournalStore(root: Path, private val serial: String) {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }
    private val encodedSerial = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(serial.encodeToByteArray())
    private val directory = root.toAbsolutePath()
    private val journalPath = directory.resolve("$encodedSerial.json")
    private val lockPath = directory.resolve("$encodedSerial.lock")

    init {
        Files.createDirectories(directory)
    }

    /**
     * Takes the exclusive per-serial lock (`<serial>.lock`), waiting up to [timeoutMs] for another
     * holder — in this or any other process — to let go. This lock is what makes the journal's
     * read-modify-write (generation bump, forward cleanup, quarantine) safe; the OS releases it
     * if the holder dies. Throws [DeviceBusyException] when it is still held at the deadline.
     */
    suspend fun acquireLease(timeoutMs: Long = 0): AutoCloseable {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        while (true) {
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (held: OverlappingFileLockException) {
                null // another thread of this JVM holds it
            }
            if (lock != null) {
                recordHolder(channel, ProcessHandle.current().pid().toString())
                var closed = false
                return AutoCloseable {
                    if (!closed) {
                        closed = true
                        runCatching { recordHolder(channel, "") }
                        lock.release()
                        channel.close()
                    }
                }
            }
            channel.close()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw DeviceBusyException(serial, timeoutMs)
            delay(minOf(LEASE_POLL_MS, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)))
        }
    }

    /**
     * Advisory: whether a live process recorded itself as this serial's lock holder. It reads the
     * holder PID the lock file carries instead of taking the lock, so it never makes a concurrent
     * [acquireLease] fail; the answer can be stale by the time the caller acts on it. A holder that
     * died without releasing left a PID that is no longer alive, which reads as free.
     */
    fun isLeased(): Boolean {
        val pid = runCatching { Files.readString(lockPath).trim().toLongOrNull() }.getOrNull() ?: return false
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private fun recordHolder(channel: FileChannel, holder: String) {
        channel.truncate(0)
        channel.write(java.nio.ByteBuffer.wrap(holder.encodeToByteArray()), 0)
    }

    /** The current record, null when there is none; unreadable or invalid content is a
     * [CorruptJournalException] (recovery then preserves it and quarantines). */
    fun read(): SessionJournal? {
        if (!Files.exists(journalPath)) return null
        return try {
            json.decodeFromString<SessionJournal>(Files.readString(journalPath)).also(::validate)
        } catch (error: Exception) {
            throw CorruptJournalException(serial, "Corrupt session journal: $journalPath", error)
        }
    }

    fun preserveCorrupt(): Path? {
        if (!Files.exists(journalPath)) return null
        val destination = directory.resolve("$encodedSerial.corrupt-${System.currentTimeMillis()}.json")
        Files.copy(journalPath, destination)
        FileChannel.open(destination, StandardOpenOption.READ).use { it.force(true) }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        return destination
    }

    fun write(record: SessionJournal) {
        validate(record)
        if (Files.exists(journalPath)) {
            val previous = read()
            require(previous == null || record.generation >= previous.generation) {
                "Session generation regressed"
            }
            require(
                previous == null || record.generation != previous.generation ||
                    record.sessionId == previous.sessionId
            ) { "Session identity changed without advancing generation ${record.generation}" }
        }
        writeAtomically(record)
    }

    fun replaceCorruptWith(record: SessionJournal) {
        validate(record)
        writeAtomically(record)
    }

    private fun validate(record: SessionJournal) {
        require(record.version == 1)
        require(record.serial == serial)
        require(record.bootId.isNotBlank())
        require(record.sessionId.isNotBlank())
        require(record.generation >= 0)
        require(record.devicePort in 1..65535)
        require(record.hostPort == null || record.hostPort in 1..65535)
        require(!record.resetRequired || record.state == JournalState.QUARANTINED)
        require(!record.resetRequired || !record.quarantineReason.isNullOrBlank())
        require(record.resetStartedBootId == null || record.resetRequired)
        require(record.resetStartedBootId == null || record.resetStartedBootId == record.bootId)
    }

    private fun writeAtomically(record: SessionJournal) {
        val temporary = Files.createTempFile(directory, "$encodedSerial-", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val bytes = json.encodeToString(record).encodeToByteArray()
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(
                temporary,
                journalPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
