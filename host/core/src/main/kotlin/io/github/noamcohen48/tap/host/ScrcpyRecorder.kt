package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A failed or unsupported external capture, never a driver command failure. */
class RecordingException(message: String, cause: Throwable? = null) : TapHostException(message, cause)

/** An encoded, bounded recording. `format` is opus (audio), mp4 (video), or mkv (both). */
data class RecordedMedia(val bytes: ByteArray, val format: String)

/**
 * One optional scrcpy process per attached device, shared by audio-only, video-only and combined
 * captures. The daemon closes it before releasing the device lease. The driver command executor
 * never encodes frames; ADB and the shell-side scrcpy server are launched only on request.
 */
class ScrcpyRecorder(
    private val serial: String,
    private val stateDir: Path,
    private val executable: String = "scrcpy",
    /** Tap's adb, exported to scrcpy as `ADB`: a different adb on `PATH` would restart the shared adb server. */
    private val adb: String? = null,
    private val launch: (List<String>, Path) -> Process = { command, log -> scrcpyProcess(command, log, adb).start() },
) {
    private data class Active(
        val process: Process,
        val directory: Path,
        val file: Path,
        val log: Path,
        val format: String,
        val maxBytes: Long,
    )

    private val mutex = Mutex()
    private var active: Active? = null
    private var closed = false

    /**
     * Start one bounded capture. Video-only works on Android 21+; scrcpy audio requires 11+,
     * while playback with device-side duplication requires 13+. Both tracks share one muxer.
     */
    suspend fun start(video: Boolean, audioSource: String?, maxSeconds: Int) = mutex.withLock {
        if (closed) throw RecordingException("Recorder for $serial is closed")
        if (active != null) throw RecordingException("Recording already started on $serial")
        if (!video && audioSource == null) throw RecordingException("At least one recording track is required")
        if (audioSource != null && audioSource !in setOf("output", "playback", "mic")) {
            throw RecordingException("Unsupported audio source: $audioSource")
        }
        if (maxSeconds !in 1..(if (video) MAX_VIDEO_SECONDS else MAX_AUDIO_SECONDS)) {
            throw RecordingException("max_seconds is outside the recording limit")
        }
        val format = when {
            !video -> "opus"
            audioSource == null -> "mp4"
            else -> "mkv"
        }
        val maximum = if (video) MAX_VIDEO_BYTES else MAX_AUDIO_BYTES
        // A cancelled dispatcher handoff must not lose an allocated directory or child process.
        val directory = withContext(NonCancellable + Dispatchers.IO) {
            Files.createTempDirectory(Files.createDirectories(stateDir.resolve("recordings")), "tap-recording-")
        }
        val file = directory.resolve("recording.$format")
        val log = directory.resolve("scrcpy.log")
        val command = buildList {
            addAll(listOf(executable, "-s", serial, "--no-control", "--no-window", "--no-playback"))
            if (!video) add("--no-video") else addAll(listOf("--video-bit-rate=2M", "--max-size=1024", "--max-fps=15"))
            if (audioSource == null) add("--no-audio") else {
                addAll(listOf("--require-audio", "--audio-source=$audioSource", "--audio-codec=opus"))
                if (audioSource == "playback") add("--audio-dup")
            }
            add("--time-limit=$maxSeconds")
            add("--record=$file")
        }
        try {
            val process = withContext(NonCancellable + Dispatchers.IO) { launch(command, log) }
            active = Active(process, directory, file, log, format, maximum)
            delay(500)
            if (!process.isAlive && process.exitValue() != 0) {
                throw RecordingException("scrcpy recording failed on $serial: ${logTail(log)}")
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { active?.let { reap(it.process) } } finally { active = null; clean(directory, file) }
            }
            if (error is CancellationException) throw error
            throw if (error is RecordingException) error else RecordingException("Cannot start scrcpy recording on $serial: ${error.message}", error)
        }
    }

    /** Stop the capture and return its encoded bytes and file format. */
    suspend fun stop(): RecordedMedia = mutex.withLock {
        val current = active ?: throw RecordingException("No recording on $serial")
        active = null
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                // Cancellation after Stop cannot abandon a live child or a temporary file.
                val exit = reap(current.process)
                if (exit != 0) throw RecordingException("scrcpy exited $exit on $serial: ${logTail(current.log)}")
                if (!Files.exists(current.file)) throw RecordingException("scrcpy produced no recording on $serial: ${logTail(current.log)}")
                val size = Files.size(current.file)
                if (size == 0L || size > current.maxBytes) throw RecordingException("Invalid recording size $size on $serial")
                RecordedMedia(Files.readAllBytes(current.file), current.format)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { clean(current.directory, current.file) }
        }
    }

    /** Idempotent teardown on detach, owner loss, and daemon shutdown. */
    suspend fun close() = mutex.withLock {
        closed = true
        val current = active
        active = null
        if (current != null) withContext(NonCancellable + Dispatchers.IO) {
            try { reap(current.process) } finally { clean(current.directory, current.file) }
        }
    }

    private fun reap(process: Process): Int {
        if (process.isAlive) process.destroy() // scrcpy finalizes its muxer on SIGTERM
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw RecordingException("scrcpy could not be reaped on $serial")
            throw RecordingException("scrcpy did not stop cleanly on $serial")
        }
        return process.exitValue()
    }

    private fun clean(directory: Path, file: Path) {
        Files.deleteIfExists(file)
        Files.deleteIfExists(directory.resolve("scrcpy.log"))
        Files.deleteIfExists(directory)
    }

    private fun logTail(log: Path): String =
        if (Files.exists(log)) Files.readString(log).takeLast(1_024).replace('\n', ' ') else "no log"

    companion object {
        const val MAX_AUDIO_SECONDS = 60
        const val MAX_VIDEO_SECONDS = 30
        const val MAX_AUDIO_BYTES = 3L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 16L * 1024 * 1024
    }
}

internal fun scrcpyProcess(command: List<String>, log: Path, adb: String?): ProcessBuilder =
    ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).also { builder ->
        if (adb != null) builder.environment()["ADB"] = adb
    }
