package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** A refused/failed private-data operation; never contains transferred file bytes. */
class AppDataException(
    message: String,
    val detail: String = "IO_ERROR",
    val serial: String? = null,
) : TapHostException(message)

/** Actual run-as access, not merely the package's debug flag. */
data class AppDataAccess(
    val available: Boolean,
    val reason: String? = null,
)

/** One immediate private-directory child. Symlinks may be listed but never followed. */
data class PrivateDataEntry(
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
)

/** An owner-controlled local spool file, not a path on the Android device. */
data class PrivateDataFile(
    val name: String,
    val path: Path,
    val sizeBytes: Long,
)

/**
 * Stopped-app private files via run-as. No SQL, root, implicit stop/relaunch, or app-data restore
 * on detach. The session journal owns only shell staging files, never the app's changed data.
 * Explicit lifecycle changes share the device's mutex. External starts/other ADB actors are not
 * excluded by an OS lock; callers must not concurrently drive the app through UI/notifications.
 */
class AppPrivateData internal constructor(
    private val app: AppLifecycle,
) {
    private val session get() = app.session
    private val adb get() = session.adb
    private val serial get() = session.serial

    suspend fun capabilities(): AppDataAccess =
        session.guardAdb {
            val user = adb.appDataUser(serial)
            val result = adb.runAs(serial, app.packageName, user, "id -u")
            if (result.exitCode == 0 && result.output.toIntOrNull()?.let { it >= 10_000 } == true) {
                AppDataAccess(true)
            } else {
                AppDataAccess(false, "run-as is unavailable: install a debuggable app for the current Android user; no root fallback")
            }
        }

    suspend fun list(path: String = ""): List<PrivateDataEntry> {
        validatePath(path, allowRoot = true)
        return stopped { user ->
            val output = script(user, LIST, listOf(path.ifEmpty { "." }))
            val fields = output.split('\u0000')
            if (fields.lastOrNull() != "" || (fields.size - 1) % 3 != 0) fail("IO_ERROR")
            fields
                .dropLast(1)
                .chunked(3)
                .map { (kind, size, name) ->
                    validatePath(name, allowReserved = true)
                    if ('/' in name || kind !in listOf("f", "d", "l")) fail("IO_ERROR")
                    PrivateDataEntry(name, size.toLongOrNull()?.takeIf { it >= 0 } ?: fail("IO_ERROR"), kind == "d", kind == "l")
                }.sortedBy { it.name }
        }
    }

    /** Read to an owner-controlled local file, after bounding size and verifying SHA-256. */
    suspend fun read(
        path: String,
        destination: Path,
    ): Long {
        validatePath(path)
        return stopped { user -> readUnlocked(user, path, destination, info(user, path)) }
    }

    /** Create missing parent directories; replace a regular file only with explicit [overwrite]. */
    suspend fun write(
        path: String,
        source: Path,
        overwrite: Boolean = false,
    ) {
        validatePath(path)
        checkSource(source)
        stopped { user -> writeUnlocked(user, path, source, overwrite) }
    }

    /** Delete one regular file. No directory/root/recursive deletion. */
    suspend fun delete(path: String) {
        validatePath(path)
        stopped { user -> script(user, DELETE, listOf(path)) }
    }

    /** Snapshot base database and existing WAL/rollback journal; SHM is rebuildable, not content. */
    suspend fun exportDatabase(
        name: String,
        directory: Path,
    ): List<PrivateDataFile> {
        validateDatabaseName(name)
        return stopped { user ->
            var total = 0L
            buildList {
                for (suffix in listOf("", "-wal", "-journal")) {
                    val path = "databases/$name$suffix"
                    val exists = script(user, EXISTS, listOf(path)) == "1"
                    if (!exists && suffix.isNotEmpty()) continue
                    val info = info(user, path)
                    total += info.first
                    if (total > MAX_BYTES) fail("LIMIT")
                    val local = directory.resolve(name + suffix)
                    readUnlocked(user, path, local, info)
                    add(PrivateDataFile(name + suffix, local, info.first))
                }
            }
        }
    }

    /**
     * Stage all bytes before touching existing database files; replace the selected bundle and
     * discard old sidecars, including SHM. Normal script failure rolls back. Process/device loss
     * during multi-file commit is indeterminate: do not launch or repeat blindly; inspect/repair
     * or explicitly clear data. A leftover private backup is preserved, not silently overwritten.
     */
    suspend fun importDatabase(
        name: String,
        files: List<PrivateDataFile>,
        overwrite: Boolean = false,
    ) {
        validateDatabaseName(name)
        val allowed = listOf(name, "$name-wal", "$name-journal")
        input(
            files.isNotEmpty() && files.first().name == name && files.size <= 3,
        ) { "Database must be first, followed by optional WAL/journal" }
        input(
            files
                .map {
                    it.name
                }.distinct()
                .size == files.size && files.all { it.name in allowed },
        ) { "Invalid or duplicate database bundle entry" }
        input(
            files.sumOf {
                it.sizeBytes
            } in 1..MAX_BYTES && files.first().sizeBytes > 0,
        ) { "Database bundle must be nonempty and at most $MAX_BYTES bytes" }
        files.forEach {
            checkSource(it.path)
            input(Files.size(it.path) == it.sizeBytes) { "Database entry size mismatch" }
        }
        stopped { user ->
            val token = UUID.randomUUID().toString()
            val stage = "databases/.tap-private-stage-$token"
            val backup = "databases/.tap-private-backup-$token"
            script(user, PREPARE_DATABASE, listOf(name, stage, backup, if (overwrite) "1" else "0"))
            try {
                files.forEach { writeUnlocked(user, "$stage/${it.name}", it.path, false) }
                script(
                    user,
                    COMMIT_DATABASE,
                    listOf(
                        name,
                        stage,
                        backup,
                        if (files.any { it.name == "$name-wal" }) "1" else "0",
                        if (files.any { it.name == "$name-journal" }) "1" else "0",
                    ),
                )
            } finally {
                // Stage is ours; backup may be the last recoverable copy after interruption.
                cleanup { script(user, REMOVE_STAGE, listOf(stage)) }
            }
        }
    }

    private suspend fun <T> stopped(block: suspend (Int) -> T): T =
        session.guardAdb {
            session.appDataMutex.withLock {
                val user = adb.appDataUser(serial)
                val uid = script(user, "id -u").toIntOrNull()?.takeIf { it >= 10_000 } ?: fail("RUN_AS_UNAVAILABLE")
                checkStopped(uid)
                val result = block(user)
                checkStopped(uid)
                result
            }
        }

    private suspend fun checkStopped(uid: Int) {
        if (adb.appDataProcesses(serial, uid).isNotEmpty()) fail("APP_RUNNING")
    }

    private suspend fun info(
        user: Int,
        path: String,
    ): Pair<Long, String> {
        val lines = script(user, INFO, listOf(path)).lines()
        val size = lines.firstOrNull()?.toLongOrNull()?.takeIf { it in 0..MAX_BYTES } ?: fail("LIMIT")
        val hash = lines.getOrNull(1)?.substringBefore(' ')?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: fail("IO_ERROR")
        return size to hash
    }

    private suspend fun readUnlocked(
        user: Int,
        path: String,
        destination: Path,
        expected: Pair<Long, String>,
    ): Long {
        transfer { remote ->
            script(user, READ, listOf(path), output = remote)
            val stat = adb.fileInfo(serial, remote)
            if (stat == null || !stat.regular || stat.sizeBytes != expected.first) fail("INCONSISTENT_SNAPSHOT")
            adb.pull(serial, remote, destination)
            if (Files.size(destination) != expected.first || digest(destination) != expected.second) fail("INCONSISTENT_SNAPSHOT")
        }
        return expected.first
    }

    private suspend fun writeUnlocked(
        user: Int,
        path: String,
        source: Path,
        overwrite: Boolean,
    ) {
        val local = withContext(Dispatchers.IO) { Files.createTempFile("tap-private-", ".bin") }
        try {
            // adb push sends source permissions: never push a caller's potentially world-readable file.
            withContext(Dispatchers.IO) {
                Files.newInputStream(source).use { input ->
                    Files.newOutputStream(local).use { output ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        var remaining = MAX_BYTES + 1
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
            val size = Files.size(local)
            if (size > MAX_BYTES) fail("LIMIT")
            val hash = digest(local)
            transfer { remote ->
                adb.createAppDataTransfer(serial, remote)
                adb.push(serial, local, remote)
                script(
                    user,
                    WRITE,
                    listOf(path, UUID.randomUUID().toString(), size.toString(), hash, if (overwrite) "1" else "0"),
                    input = remote,
                )
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(local) }
        }
    }

    private suspend fun <T> transfer(block: suspend (String) -> T): T {
        val path = "/data/local/tmp/tap-private-${UUID.randomUUID()}.bin"
        if (adb.fileInfo(serial, path) != null) fail("EXISTS")
        session.captureBeforeChange(listOf(StateKey.DeviceFile(path).id))
        try {
            return block(path)
        } finally {
            cleanup { adb.removeAppDataTransfer(serial, path) }
        }
    }

    private suspend fun cleanup(block: suspend () -> Unit) =
        withContext(NonCancellable) {
            // A failure still reaches guardAdb's reap-uncertain tracking; journal cleanup can retry
            // shell-file deletion on detach/recovery. Never mask a mutation's original outcome.
            runCatching { session.guardAdb { block() } }.onFailure {
                System.err.println("[tap] private-data staging cleanup deferred; no file contents logged")
            }
            Unit
        }

    private suspend fun script(
        user: Int,
        text: String,
        arguments: List<String> = emptyList(),
        input: String? = null,
        output: String? = null,
    ): String {
        val result = adb.runAs(serial, app.packageName, user, text, arguments, input, output)
        if (result.exitCode != 0) {
            fail(
                when (result.exitCode) {
                    65 -> "SYMLINK"
                    66 -> "NOT_FOUND"
                    67 -> "NOT_REGULAR"
                    68 -> "EXISTS"
                    69 -> "LIMIT"
                    71 -> "INCOMPLETE_IMPORT"
                    else -> if (text == "id -u") "RUN_AS_UNAVAILABLE" else "IO_ERROR"
                },
            )
        }
        return result.output
    }

    private fun fail(detail: String): Nothing =
        throw AppDataException(
            "Private app data: $detail. Stop the app explicitly before access; after interrupted import, repair or clear data before launch",
            detail,
            serial,
        )

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        const val CHUNK_BYTES = 1024 * 1024

        fun validatePath(
            path: String,
            allowRoot: Boolean = false,
            allowReserved: Boolean = false,
        ) {
            input(
                (allowRoot && path.isEmpty()) || (
                    path.isNotEmpty() && !path.startsWith('/') && path.toByteArray().size <= 1024 &&
                        path.none { it.isISOControl() || it == '\\' || it == '\uFFFD' } &&
                        path.split('/').all {
                            it.isNotEmpty() && it != "." && it != ".." &&
                                (allowReserved || !it.startsWith(".tap-private-"))
                        }
                ),
            ) {
                "Expected a normalized relative private-data path, without traversal, control characters or reserved staging names"
            }
        }

        fun validateDatabaseName(name: String) {
            validatePath(name)
            input('/' !in name && !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal")) {
                "Expected a database basename, not a path or sidecar"
            }
        }

        private inline fun input(
            valid: Boolean,
            message: () -> String,
        ) {
            if (!valid) throw InvalidCommandException(ErrorCode.ERR_INVALID_REQUEST, null, message())
        }

        private fun checkSource(path: Path) {
            input(Files.isRegularFile(path) && Files.size(path) in 0..MAX_BYTES) {
                "Private-data source must be a regular file of at most $MAX_BYTES bytes"
            }
        }

        private suspend fun digest(path: Path): String =
            withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                HexFormat.of().formatHex(digest.digest())
            }

        private val COMMON =
            $$"""
            set -eu
            umask 077
            safe() {
                old_ifs=$IFS; IFS=/; set -f; prefix=.
                for component in $1; do
                    prefix="$prefix/$component"
                    [ ! -L "$prefix" ] || exit 65
                done
                IFS=$old_ifs; set +f
            }
            regular() {
                safe "$1"
                [ -e "./$1" ] || exit 66
                [ -f "./$1" ] || exit 67
            }
            """.trimIndent() + "\n"
        private val EXISTS =
            COMMON +
                $$"""
            safe "$1"
            if [ -e "./$1" ]; then printf 1; else printf 0; fi
                """.trimIndent()
        private val INFO =
            COMMON +
                $$"""
            regular "$1"
            size=$(stat -c %s "./$1")
            [ "$size" -le $$MAX_BYTES ] || exit 69
            printf '%s\n' "$size"
            sha256sum "./$1"
                """.trimIndent()
        private val READ =
            COMMON +
                $$"""
            regular "$1"
            head -c $${MAX_BYTES + 1} "./$1"
                """.trimIndent()
        private val LIST =
            COMMON +
                $$"""
            safe "$1"
            dir="./$1"
            [ -d "$dir" ] || exit 67
            count=0
            for file in "$dir"/* "$dir"/.[!.]* "$dir"/..?*; do
                [ -e "$file" ] || [ -L "$file" ] || continue
                count=$((count + 1)); [ "$count" -le 1024 ] || exit 69
                size=0
                if [ -L "$file" ]; then kind=l
                elif [ -d "$file" ]; then kind=d
                elif [ -f "$file" ]; then kind=f; size=$(stat -c %s "$file")
                else continue
                fi
                printf '%s\000%s\000%s\000' "$kind" "$size" "${file##*/}"
            done
                """.trimIndent()
        private val DELETE =
            COMMON +
                $$"""
            regular "$1"
            rm "./$1"
                """.trimIndent()
        private val WRITE =
            COMMON +
                $$"""
            safe "$1"
            path="./$1"; parent=${path%/*}; temp="$parent/.tap-private-write-$2"
            if [ -e "$path" ]; then
                [ -f "$path" ] || exit 67
                [ "$5" = 1 ] || exit 68
            fi
            mkdir -p "$parent"
            trap 'rm -f "$temp"' EXIT HUP INT TERM
            (set -C; head -c $${MAX_BYTES + 1} > "$temp")
            [ "$(stat -c %s "$temp")" = "$3" ] || exit 69
            actual=$(sha256sum "$temp"); actual=${actual%% *}
            [ "$actual" = "$4" ] || exit 70
            safe "$1"
            [ ! -e "$path" ] || [ -f "$path" ] || exit 67
            if [ "$5" = 1 ]; then mv -f -T "$temp" "$path"
            else
                # run_as_app SELinux denies hard links on production Android. Use toybox's
                # no-clobber rename and check whether it actually consumed the staged file.
                mv -n -T "$temp" "$path"
                [ ! -e "$temp" ] || exit 68
            fi
                """.trimIndent()
        private val PREPARE_DATABASE =
            COMMON +
                $$"""
            safe databases
            for leftover in ./databases/.tap-private-backup-*; do
                [ ! -e "$leftover" ] && [ ! -L "$leftover" ] || exit 71
            done
            for suffix in '' -wal -journal -shm; do
                safe "databases/$1$suffix"
                if [ -e "./databases/$1$suffix" ]; then
                    [ -f "./databases/$1$suffix" ] || exit 67
                    [ "$4" = 1 ] || exit 68
                fi
            done
            mkdir -p ./databases
            mkdir -m 700 "./$2"
                """.trimIndent()
        private val COMMIT_DATABASE =
            COMMON +
                $$"""
            name=$1; stage="./$2"; backup="./$3"; committed=0
            safe databases; safe "$2"; safe "$3"
            regular "$2/$name"
            if [ "$4" = 1 ]; then regular "$2/$name-wal"; fi
            if [ "$5" = 1 ]; then regular "$2/$name-journal"; fi
            mkdir -m 700 "$backup"
            rollback() {
                if [ "$committed" = 0 ]; then
                    for suffix in '' -wal -journal -shm; do
                        key="main$suffix"; dest="./databases/$name$suffix"
                        if [ -f "$backup/$key" ]; then mv -f "$backup/$key" "$dest" || return 1
                        elif [ -f "$backup/absent$key" ]; then rm -f "$dest" || return 1
                        fi
                    done
                fi
                rm -rf "$backup"
            }
            trap 'rollback' EXIT
            trap 'exit 70' HUP INT TERM
            for suffix in '' -wal -journal -shm; do
                key="main$suffix"; dest="./databases/$name$suffix"
                safe "databases/$name$suffix"
                if [ -e "$dest" ]; then
                    [ -f "$dest" ] || exit 67
                    mv "$dest" "$backup/$key"
                else : > "$backup/absent$key"
                fi
            done
            mv "$stage/$name" "./databases/$name"
            if [ "$4" = 1 ]; then mv "$stage/$name-wal" "./databases/$name-wal"; fi
            if [ "$5" = 1 ]; then mv "$stage/$name-journal" "./databases/$name-journal"; fi
            committed=1
                """.trimIndent()
        private val REMOVE_STAGE =
            COMMON +
                $$"""
            safe "$1"
            rm -rf "./$1"
                """.trimIndent()
    }
}
