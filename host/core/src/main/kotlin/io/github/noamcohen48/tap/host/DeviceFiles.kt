package io.github.noamcohen48.tap.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Files on the device: push, pull, and media added to the gallery. The bytes travel as local
 * files of the host ([Path]s the server wrote from a client's stream, never a client's path), and
 * every file a session creates is captured as absent first, so detach removes it again (journaled,
 * like every saved state). A session never overwrites a file it did not create: the device keeps
 * what it had.
 */
class DeviceFiles internal constructor(
    private val session: DeviceSession,
) {
    private val adb get() = session.adb
    private val serial get() = session.serial

    /**
     * Copies [source] to [devicePath] (absolute; its directory must exist) and reads the size
     * back. A file already there is refused unless this session pushed it. Removed on detach.
     */
    suspend fun push(
        source: Path,
        devicePath: String,
    ) {
        val path = checkDevicePath(devicePath)
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        session.guardAdb {
            if (!adb.isDirectory(serial, parent)) throw DeviceFileException(serial, "$parent is not a directory on $serial")
        }
        write(source, path, StateKey.DeviceFile(path))
    }

    /** Copies the regular file at [devicePath] into [target] (at most [MAX_FILE_BYTES]). */
    suspend fun pull(
        devicePath: String,
        target: Path,
    ) {
        val path = checkDevicePath(devicePath)
        session.guardAdb {
            val info = adb.fileInfo(serial, path) ?: throw DeviceFileException(serial, "No file at $path on $serial")
            if (!info.regular) throw DeviceFileException(serial, "$path on $serial is not a regular file")
            if (info.sizeBytes > MAX_FILE_BYTES) throw DeviceFileException(serial, "$path on $serial is ${info.sizeBytes} bytes; at most $MAX_FILE_BYTES are pulled")
            adb.pull(serial, path, target)
            val pulled = Files.size(target)
            if (pulled != info.sizeBytes) throw DeviceFileException(serial, "Pulled $pulled of ${info.sizeBytes} bytes of $path from $serial")
        }
    }

    /**
     * Adds [source] to the gallery as [fileName] (a photo or a video, by its extension): written to
     * `Pictures/Tap/` or `Movies/Tap/` on shared storage and indexed by the media scanner, so
     * gallery apps and photo pickers list it. Returns the device path. Removed (and dropped from
     * the index) on detach.
     */
    suspend fun addMedia(
        source: Path,
        fileName: String,
    ): String {
        val folder = mediaFolder(fileName)
        val directory = "$SHARED_STORAGE/$folder"
        val path = "$directory/$fileName"
        session.guardAdb { adb.makeDirectories(serial, directory) }
        write(source, path, StateKey.DeviceFile(path, media = true))
        session.guardAdb {
            adb.scanMedia(serial, path)
            if (!adb.mediaIndexed(serial, folder, fileName)) throw DeviceFileException(serial, "The media scanner did not index $path on $serial")
        }
        return path
    }

    private suspend fun write(
        source: Path,
        path: String,
        key: StateKey.DeviceFile,
    ) {
        val size = Files.size(source)
        require(size <= MAX_FILE_BYTES) { "at most $MAX_FILE_BYTES bytes, not $size" }
        session.guardAdb {
            val existing = adb.fileInfo(serial, path)
            if (existing != null && !session.hasCaptured(key.id)) {
                throw DeviceFileException(serial, "$path already exists on $serial; Tap writes only files it creates, and removes them on detach")
            }
        }
        // Journaled as absent before anything is written: a dead server's next attach removes it.
        session.captureBeforeChange(listOf(key.id))
        session.guardAdb {
            adb.push(serial, source, path)
            val info = adb.fileInfo(serial, path)
            if (info?.sizeBytes != size) throw DeviceFileException(serial, "$path on $serial reads back ${info?.sizeBytes} bytes; $size were pushed")
        }
    }

    companion object {
        /** Largest file pushed, pulled or added as media. */
        const val MAX_FILE_BYTES = 512L shl 20
        const val MAX_DEVICE_PATH_CHARS = 1024

        /** Where shell (and every app with storage access) sees shared storage. */
        const val SHARED_STORAGE = "/sdcard"

        private val MEDIA_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,126}")

        /** Extensions the gallery indexes as photos (`Pictures/Tap`) or videos (`Movies/Tap`). */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
        val VIDEO_EXTENSIONS = setOf("mp4", "3gp", "webm", "mkv", "mov")

        /** [path] if it is an absolute device path Tap writes and reads; IllegalArgumentException otherwise. */
        fun checkDevicePath(path: String): String {
            require(path.startsWith("/")) { "the device path must be absolute, not $path" }
            require(path.length <= MAX_DEVICE_PATH_CHARS) { "the device path must be at most $MAX_DEVICE_PATH_CHARS chars" }
            require(path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "the device path has a control character" }
            require(!path.endsWith("/")) { "the device path names a directory: $path" }
            require(path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }) { "the device path must be normalised: $path" }
            return path
        }

        /** `Pictures/Tap` or `Movies/Tap` for [fileName]; IllegalArgumentException for a name or type the gallery does not take. */
        fun mediaFolder(fileName: String): String {
            require(MEDIA_NAME.matches(fileName)) { "a media file name is 1-127 letters, digits, '.', '_', '-' or spaces, not '$fileName'" }
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                in IMAGE_EXTENSIONS -> "Pictures/Tap"
                in VIDEO_EXTENSIONS -> "Movies/Tap"
                else -> throw IllegalArgumentException(
                    "'$fileName' is not a photo (${IMAGE_EXTENSIONS.joinToString()}) or video (${VIDEO_EXTENSIONS.joinToString()})",
                )
            }
        }
    }
}
