package io.github.noamcohen48.tap.sdk

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AppDataHeader
import io.github.noamcohen48.tap.api.v1.AppDataPart
import io.github.noamcohen48.tap.api.v1.AppDataServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.DeleteFileRequest
import io.github.noamcohen48.tap.api.v1.ExportDatabaseRequest
import io.github.noamcohen48.tap.api.v1.GetCapabilitiesRequest
import io.github.noamcohen48.tap.api.v1.ImportDatabaseRequest
import io.github.noamcohen48.tap.api.v1.ListFilesRequest
import io.github.noamcohen48.tap.api.v1.ReadFileRequest
import io.github.noamcohen48.tap.api.v1.WriteFileRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Private-data refusal/failure. [detail] is a stable token (APP_RUNNING, SYMLINK, EXISTS, …). */
class AppDataException(
    val detail: String,
    val serial: String,
    message: String,
    cause: Throwable? = null,
) : TapException(message, cause)

/** Actual run-as capability for the current Android user, not just a debug-build flag. */
data class AppDataAccess(
    val available: Boolean,
    val reason: String? = null,
)

/** An immediate private-directory child. Symlinks may be listed but are never followed. */
data class PrivateFileEntry(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
)

/**
 * Opaque SQLite base file plus persistent WAL/rollback journal; never rebuildable SHM.
 * Needs the app's schema, keys and migrations. No SQL/decryption. At most 64 MiB together.
 */
data class DatabaseBundle(
    val name: String,
    val database: ByteArray,
    val wal: ByteArray? = null,
    val journal: ByteArray? = null,
)

/**
 * Debuggable-app credential-encrypted private data. Obtain from [App.data]. Except
 * [capabilities], access needs all app-UID processes stopped, including secondary/native/shared
 * UID processes. Call [App.forceStop] explicitly: no implicit stop/relaunch or restore on detach.
 * Do not concurrently drive the app through UI, notifications, another ADB client or external
 * actor: this is not an OS filesystem lock. Paths are relative UTF-8, without traversal,
 * symlink/control characters or reserved `.tap-private-` components. Transfers are binary-safe,
 * bounded to 64 MiB, and omitted (including paths) from event records. Never retry a lost mutation
 * blindly. These calls observe `tapTest`'s coroutine-bound lifetime like other [App] calls.
 */
class AppData internal constructor(
    private val app: App,
) {
    private val target: AppTarget get() =
        AppTarget
            .newBuilder()
            .setClientConnectionId(app.device.ownerConnection.id)
            .setAttachedDeviceId(app.device.attachedDeviceId)
            .setPackageName(app.packageName)
            .build()

    private suspend fun <T> call(
        timeout: Duration?,
        block: suspend (AppDataServiceGrpcKt.AppDataServiceCoroutineStub) -> T,
    ): T {
        ensureTapBound("App.data.${app.packageName}")
        return app.device.admitted("App.data.${app.packageName}") {
            mapped(app.device.serial) {
                val stub =
                    app.device.ownerConnection.client.appData.withDeadlineAfter(
                        ((timeout ?: app.device.timeouts.lifecycle) + 60.seconds).inWholeMilliseconds,
                        TimeUnit.MILLISECONDS,
                    )
                block(stub)
            }
        }
    }

    /** Probe actual run-as access, safe while the app runs. No root fallback. */
    suspend fun capabilities(): AppDataAccess =
        call(null) {
            val result = it.getCapabilities(GetCapabilitiesRequest.newBuilder().setApp(target).build())
            AppDataAccess(result.available, result.reason.takeIf { _ -> result.hasReason() })
        }

    /** List immediate children, at most 1024. An empty [path] lists the private root. */
    suspend fun list(path: String = ""): List<PrivateFileEntry> =
        call(null) {
            it
                .listFiles(
                    ListFilesRequest
                        .newBuilder()
                        .setApp(target)
                        .setPath(path)
                        .build(),
                ).entriesList
                .map { e ->
                    PrivateFileEntry(e.name, e.sizeBytes, e.isDirectory, e.isSymlink)
                }
        }

    /** Read one regular file, at most 64 MiB, with server-side size/SHA-256 verification. */
    suspend fun read(
        path: String,
        timeout: Duration? = null,
    ): ByteArray =
        call(timeout) {
            receive(
                it
                    .readFile(
                        ReadFileRequest
                            .newBuilder()
                            .setApp(target)
                            .setPath(path)
                            .build(),
                    ).map { it.part },
                listOf(path),
                path,
            ).getValue(path)
        }

    /** Create parents and write bounded bytes. Refuse an existing file unless [overwrite]. */
    suspend fun write(
        path: String,
        data: ByteArray,
        overwrite: Boolean = false,
        timeout: Duration? = null,
    ) {
        require(data.size.toLong() <= MAX_BYTES) { "Private-data file exceeds 64 MiB" }
        call(timeout) {
            it.writeFile(
                parts(listOf(path to data), overwrite).map { part ->
                    WriteFileRequest.newBuilder().setPart(part).build()
                },
            )
        }
    }

    /** Delete one regular file only. No recursive deletion, root/directory deletion or restore. */
    suspend fun delete(path: String) {
        call(null) {
            it.deleteFile(
                DeleteFileRequest
                    .newBuilder()
                    .setApp(target)
                    .setPath(path)
                    .build(),
            )
        }
    }

    /** Snapshot the database and existing WAL/journal, at most 64 MiB total; never discard WAL. */
    suspend fun exportDatabase(
        name: String,
        timeout: Duration? = null,
    ): DatabaseBundle =
        call(timeout) {
            val base = "databases/$name"
            val files =
                receive(
                    it
                        .exportDatabase(
                            ExportDatabaseRequest
                                .newBuilder()
                                .setApp(target)
                                .setName(name)
                                .build(),
                        ).map { it.part },
                    listOf(base, "$base-wal", "$base-journal"),
                    base,
                )
            DatabaseBundle(name, files.getValue(base), files["$base-wal"], files["$base-journal"])
        }

    /**
     * Import the bundle, optionally under another [name]. Stage all bytes before replacement;
     * discard old sidecars including SHM. Refuse existing files unless [overwrite]. Multi-file
     * replacement is not an OS transaction: after interrupted commit, do not launch or repeat
     * blindly. Inspect/repair the retained private backup or explicitly clear data first.
     */
    suspend fun importDatabase(
        bundle: DatabaseBundle,
        name: String = bundle.name,
        overwrite: Boolean = false,
        timeout: Duration? = null,
    ) {
        val base = "databases/$name"
        val files =
            buildList {
                add(base to bundle.database)
                bundle.wal?.let { add("$base-wal" to it) }
                bundle.journal?.let { add("$base-journal" to it) }
            }
        require(
            bundle.database.isNotEmpty() && files.sumOf {
                it.second.size.toLong()
            } <= MAX_BYTES,
        ) { "Database bundle must be nonempty and at most 64 MiB" }
        call(timeout) {
            it.importDatabase(
                parts(files, overwrite).map { part ->
                    ImportDatabaseRequest.newBuilder().setPart(part).build()
                },
            )
        }
    }

    private fun parts(
        files: List<Pair<String, ByteArray>>,
        overwrite: Boolean,
    ): Flow<AppDataPart> =
        flow {
            for ((path, data) in files) {
                emit(
                    AppDataPart
                        .newBuilder()
                        .setHeader(
                            AppDataHeader
                                .newBuilder()
                                .setApp(target)
                                .setPath(path)
                                .setSizeBytes(data.size.toLong())
                                .setOverwrite(overwrite),
                        ).build(),
                )
                var offset = 0
                while (offset < data.size) {
                    val size = minOf(CHUNK_BYTES, data.size - offset)
                    emit(AppDataPart.newBuilder().setChunk(ByteString.copyFrom(data, offset, size)).build())
                    offset += size
                }
            }
        }

    private suspend fun receive(
        parts: Flow<AppDataPart>,
        allowed: List<String>,
        required: String,
    ): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArrayOutputStream>()
        var current: ByteArrayOutputStream? = null
        var expected = 0L
        var declared = 0L
        parts.collect { part ->
            when (part.partCase) {
                AppDataPart.PartCase.HEADER -> {
                    check(current == null || current!!.size().toLong() == expected) { "Private-data response ended before size_bytes" }
                    val h = part.header
                    check(h.app == target && h.path in allowed && h.path !in files) { "Unexpected private-data response header" }
                    check(current != null || h.path == required) { "Required private-data file must be first" }
                    check(h.sizeBytes in 0..MAX_BYTES && declared + h.sizeBytes <= MAX_BYTES) { "Private-data response exceeds 64 MiB" }
                    declared += h.sizeBytes
                    expected = h.sizeBytes
                    current = ByteArrayOutputStream().also { files[h.path] = it }
                }

                AppDataPart.PartCase.CHUNK -> {
                    val out = checkNotNull(current) { "Private-data response requires a header" }
                    check(part.chunk.size() <= CHUNK_BYTES && out.size().toLong() + part.chunk.size() <= expected) {
                        "Private-data response exceeds declared size or chunk limit"
                    }
                    part.chunk.writeTo(out)
                }

                else -> {
                    error("Private-data response part must be set")
                }
            }
        }
        check(current?.size()?.toLong() == expected) { "Incomplete private-data response" }
        return files.mapValues { it.value.toByteArray() }
    }

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        const val CHUNK_BYTES = 1024 * 1024
    }
}
