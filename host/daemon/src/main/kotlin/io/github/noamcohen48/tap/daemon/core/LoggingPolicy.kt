package io.github.noamcohen48.tap.daemon.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** Global disk retention limits. Protected recordings bypass rolling limits, never hard limits. */
internal data class LoggingPolicy(
    val maxAgeMs: Long = 3_600_000,
    val maxBytes: Long = 256L * 1024 * 1024,
    val hardBytes: Long = 1024L * 1024 * 1024,
    val minFreeBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(maxAgeMs > 0) { "max-age must be positive" }
        require(maxBytes > 0 && hardBytes >= maxBytes) { "max-size must be positive and no larger than hard-size" }
        require(minFreeBytes >= 0) { "min-free must not be negative" }
    }

    fun properties(): Properties =
        Properties().apply {
            setProperty("max_age_ms", maxAgeMs.toString())
            setProperty("max_bytes", maxBytes.toString())
            setProperty("hard_bytes", hardBytes.toString())
            setProperty("min_free_bytes", minFreeBytes.toString())
        }

    companion object {
        const val FILE = "logging-policy.properties"

        fun read(stateDir: Path): LoggingPolicy {
            val file = stateDir.resolve(FILE)
            if (!Files.exists(file, NOFOLLOW_LINKS)) return LoggingPolicy()
            privateFile(file)
            val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
            require(
                p.stringPropertyNames() == setOf("max_age_ms", "max_bytes", "hard_bytes", "min_free_bytes"),
            ) { "invalid logging policy fields" }
            return LoggingPolicy(
                p.getProperty("max_age_ms").toLong(),
                p.getProperty("max_bytes").toLong(),
                p.getProperty("hard_bytes").toLong(),
                p.getProperty("min_free_bytes").toLong(),
            )
        }

        fun write(
            stateDir: Path,
            policy: LoggingPolicy,
        ) {
            Files.createDirectories(stateDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(
                Files.isDirectory(stateDir, NOFOLLOW_LINKS) &&
                    Files.getOwner(stateDir, NOFOLLOW_LINKS) == Files.getOwner(Path.of(System.getProperty("user.home"))),
            ) { "state directory must be a real directory owned by this user" }
            val permissions = Files.getPosixFilePermissions(stateDir, NOFOLLOW_LINKS)
            require(
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE !in permissions &&
                    java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE !in permissions,
            ) { "state directory must not be writable by other users" }
            atomicProperties(stateDir.resolve(FILE), policy.properties())
        }
    }
}

/** Private, real paths only; do not follow user-replaced archive/config symlinks. */
internal fun privateDirectory(path: Path) {
    if (!Files.exists(
            path,
            NOFOLLOW_LINKS,
        )
    ) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }
    require(
        Files.isDirectory(path, NOFOLLOW_LINKS) &&
            Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(Path.of(System.getProperty("user.home"))),
    ) { "not an owner-controlled directory: $path" }
    require(
        Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none {
            it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_")
        },
    ) { "directory must be owner-only: $path" }
}

internal fun privateFile(path: Path) {
    require(
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(Path.of(System.getProperty("user.home"))),
    ) { "not an owner-controlled file: $path" }
    require(
        Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none {
            it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_")
        },
    ) { "file must be owner-only: $path" }
}

internal fun atomicProperties(
    path: Path,
    properties: Properties,
) = atomicPrivateFile(path) { properties.store(it, null) }

internal fun atomicPrivateFile(
    path: Path,
    write: (java.io.OutputStream) -> Unit,
) {
    if (Files.exists(path, NOFOLLOW_LINKS)) privateFile(path)
    val temp =
        Files.createTempFile(
            path.parent,
            ".pending-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
    try {
        java.io.FileOutputStream(temp.toFile()).use { out ->
            write(out)
            out.fd.sync()
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Commit the rename as well as the file contents before acknowledging a retention boundary.
        java.nio.channels.FileChannel
            .open(path.parent, java.nio.file.StandardOpenOption.READ)
            .use { it.force(true) }
    } finally {
        Files.deleteIfExists(temp)
    }
}
