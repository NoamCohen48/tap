package io.github.noamcohen48.tap.daemon.cli

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val LOG_BYTES = 1_048_576

/** A bounded tail, including an unterminated last line. Never reads the whole log into memory. */
internal fun tailLog(
    path: Path,
    lines: Int,
): String {
    if (lines == 0) return ""
    return RandomAccessFile(path.toFile(), "r").use { file ->
        val end = file.length()
        val start = (end - LOG_BYTES).coerceAtLeast(0L)
        file.seek(start)
        val bytes = ByteArray((end - start).toInt())
        file.readFully(bytes)
        val text = bytes.toString(Charsets.UTF_8).let { if (start == 0L) it else it.substringAfter('\n', "") }
        text
            .removeSuffix("\n")
            .split('\n')
            .takeLast(lines)
            .joinToString("\n")
    }
}

/** Follows complete UTF-8 lines with bounded buffers, including replacement and truncation. */
internal class LogFollower(
    private val path: Path,
    private val initialTail: Int,
) {
    private var initialized = false
    private var key: Any? = null
    private var offset = 0L
    private var pending = byteArrayOf()

    fun poll(): List<String> =
        try {
            pollExisting()
        } catch (_: java.nio.file.NoSuchFileException) {
            emptyList()
        } catch (_: java.io.FileNotFoundException) {
            // Rotation can remove the path between stat and open; retry on the next poll.
            if (Files.exists(path)) throw IllegalStateException("cannot read daemon log $path")
            emptyList()
        }

    private fun pollExisting(): List<String> {
        if (!Files.exists(path)) return emptyList()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        val replaced = initialized && (key != attributes.fileKey() || attributes.size() < offset)
        if (!initialized) {
            initialized = true
            key = attributes.fileKey()
            return RandomAccessFile(path.toFile(), "r").use { file ->
                val end = file.length()
                val start = (end - LOG_BYTES).coerceAtLeast(0L)
                file.seek(start)
                val bytes = ByteArray((end - start).toInt())
                file.readFully(bytes)
                offset = file.filePointer
                val trimmed =
                    if (start ==
                        0L
                    ) {
                        bytes
                    } else {
                        bytes
                            .drop(
                                bytes.indexOfFirst { it == 10.toByte() }.let {
                                    if (it <
                                        0
                                    ) {
                                        bytes.size
                                    } else {
                                        it + 1
                                    }
                                },
                            ).toByteArray()
                    }
                val complete = decodeLines(trimmed)
                complete.takeLast(initialTail)
            }
        }
        if (replaced) {
            offset = 0
            pending = byteArrayOf()
            key = attributes.fileKey()
        }
        return RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < offset) {
                offset = 0
                pending = byteArrayOf()
            }
            file.seek(offset)
            val bytes = ByteArray(minOf(LOG_BYTES.toLong(), file.length() - offset).toInt())
            file.readFully(bytes)
            offset = file.filePointer
            decodeLines(bytes)
        }
    }

    private fun decodeLines(bytes: ByteArray): List<String> {
        val all = pending + bytes
        val lastNewline = all.indexOfLast { it == 10.toByte() }
        val lines =
            if (lastNewline <
                0
            ) {
                emptyList()
            } else {
                all
                    .copyOfRange(0, lastNewline)
                    .toString(Charsets.UTF_8)
                    .split('\n')
                    .map { it.removeSuffix("\r") }
            }
        pending = all.copyOfRange(lastNewline + 1, all.size)
        if (pending.size > 65_536) {
            pending = byteArrayOf()
            return lines + "[log line exceeded 64 KiB; truncated]"
        }
        return lines
    }
}

internal fun printLogs(
    line: CommandLine,
    stateDir: Path,
    out: (String) -> Unit,
    keepGoing: () -> Boolean = { !Thread.currentThread().isInterrupted },
    pause: (Long) -> Unit = Thread::sleep,
): Int {
    val path = stateDir.resolve("daemon.log")
    val count = line.options["--tail"]?.toInt() ?: 100
    if ("--follow" !in line.options) {
        check(Files.isRegularFile(path)) { "no daemon log at $path; tap start captures output here, tap serve writes to its terminal" }
        val text = tailLog(path, count)
        if (text.isNotEmpty()) out(text)
        return 0
    }
    val follower = LogFollower(path, count)
    while (keepGoing()) {
        follower.poll().forEach(out)
        pause(250)
    }
    return 0
}
