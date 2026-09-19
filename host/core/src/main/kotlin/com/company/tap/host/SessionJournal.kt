package com.company.tap.host

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun acquireLease(): AutoCloseable {
        val channel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val lock = channel.tryLock()
            ?: run {
                channel.close()
                error("Device already leased: $encodedSerial")
            }
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                lock.release()
                channel.close()
            }
        }
    }

    fun read(): SessionJournal? {
        if (!Files.exists(journalPath)) return null
        return try {
            json.decodeFromString<SessionJournal>(Files.readString(journalPath)).also(::validate)
        } catch (error: Throwable) {
            throw IllegalStateException("Corrupt session journal: $journalPath", error)
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
