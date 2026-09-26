package com.company.tap.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * `<state-dir>/daemon.json`: how clients find and authenticate to the running daemon. The
 * [token] is a per-instance bearer secret, so the file is owner-only (0600) and every RPC must
 * present it (`authorization: Bearer <token>`).
 */
data class DaemonDescriptor(
    val port: Int,
    val pid: Long,
    val token: String,
    val daemonVersion: String,
    val adb: String,
) {
    fun toJson(): String =
        buildJsonObject {
            put("port", port)
            put("pid", pid)
            put("token", token)
            put("daemonVersion", daemonVersion)
            put("adb", adb)
        }.toString()

    /** Atomically replaces [path] with this descriptor, owner read/write only. */
    fun write(path: Path) {
        val temp = Files.createTempFile(path.parent, "daemon", ".json.tmp")
        try {
            restrictToOwner(temp)
            Files.write(temp, toJson().toByteArray(StandardCharsets.UTF_8))
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val FILE_NAME = "daemon.json"

        fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        fun parse(text: String): DaemonDescriptor? =
            runCatching {
                val json = Json.parseToJsonElement(text) as JsonObject
                DaemonDescriptor(
                    port = json.getValue("port").jsonPrimitive.long.toInt(),
                    pid = json.getValue("pid").jsonPrimitive.long,
                    token = json.getValue("token").jsonPrimitive.content,
                    daemonVersion = json["daemonVersion"]?.jsonPrimitive?.content.orEmpty(),
                    adb = json["adb"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.getOrNull()

        /** The descriptor in [stateDir], or null when it is missing or unreadable. */
        fun read(stateDir: Path): DaemonDescriptor? =
            stateDir
                .resolve(FILE_NAME)
                .takeIf(Files::exists)
                ?.let { runCatching { Files.readString(it) }.getOrNull() }
                ?.let(::parse)

        /** Deletes the descriptor in [stateDir] only if it still carries [token] (a newer daemon's stays). */
        fun deleteIfOwned(
            stateDir: Path,
            token: String,
        ) {
            if (read(stateDir)?.token == token) Files.deleteIfExists(stateDir.resolve(FILE_NAME))
        }
    }
}

/** Owner-only permissions where the file system supports POSIX attributes. */
internal fun restrictToOwner(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
}

/**
 * `<state-dir>/daemon.lock`, held exclusively for the whole life of `tap serve`, so two daemons
 * can never share a state dir (and its journals). The OS releases it when the process dies.
 */
class DaemonLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val FILE_NAME = "daemon.lock"

        /** The lock, or null when another process holds it. */
        fun tryAcquire(stateDir: Path): DaemonLock? {
            val path = stateDir.resolve(FILE_NAME)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            restrictToOwner(path)
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) {
                channel.close()
                return null
            }
            return DaemonLock(channel, lock)
        }
    }
}
