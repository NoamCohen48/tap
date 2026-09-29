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

/** A failed or unsupported external audio capture, never a driver command failure. */
class AudioRecordingException(message: String, cause: Throwable? = null) : TapHostException(message, cause)

/**
 * One optional audio capture per attached device. The daemon owns this object and closes it
 * before releasing the device session. scrcpy's own server runs as shell; the Tap driver never
 * acquires a privileged audio permission or spends its command lane recording sound.
 *
 * The executable is external (on PATH, or supplied by the daemon), and scrcpy itself invokes
 * ADB with an explicit serial. Nothing is installed or started until [start] is requested.
 */
class ScrcpyAudioRecorder(
    private val serial: String,
    private val stateDir: Path,
    private val executable: String = "scrcpy",
    private val launch: (List<String>, Path) -> Process = { command, log ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
    },
) {
    private data class Active(val process: Process, val directory: Path, val file: Path, val log: Path)

    private val mutex = Mutex()
    private var active: Active? = null
    private var closed = false

    /** Android 11+ for output/mic; playback with duplication requires Android 13+. */
    suspend fun start(source: String, maxSeconds: Int) = mutex.withLock {
        if (closed) throw AudioRecordingException("Audio recorder for $serial is closed")
        if (active != null) throw AudioRecordingException("Audio recording already started on $serial")
        if (source !in setOf("output", "playback", "mic")) throw AudioRecordingException("Unsupported audio source: $source")
        if (maxSeconds !in 1..60) throw AudioRecordingException("max_seconds must be in 1..60")
        // NonCancellable only around allocation/launch: cancellation at a dispatcher handoff
        // must not lose the directory or process before its owner can enter the cleanup path.
        val directory = withContext(NonCancellable + Dispatchers.IO) {
            Files.createTempDirectory(Files.createDirectories(stateDir.resolve("recordings")), "tap-audio-")
        }
        val file = directory.resolve("recording.opus")
        val log = directory.resolve("scrcpy.log")
        val command = buildList {
            addAll(listOf(executable, "-s", serial, "--no-video", "--no-control", "--no-window", "--no-playback"))
            addAll(listOf("--require-audio", "--audio-source=$source", "--audio-codec=opus", "--time-limit=$maxSeconds"))
            if (source == "playback") add("--audio-dup")
            add("--record=$file")
        }
        try {
            val process = withContext(NonCancellable + Dispatchers.IO) { launch(command, log) }
            active = Active(process, directory, file, log)
            // scrcpy may fail at once (missing executable, device not supported, or audio not
            // initialized). Do not claim to have started a recording that has already failed.
            delay(500)
            if (!process.isAlive && process.exitValue() != 0) {
                throw AudioRecordingException("scrcpy audio failed on $serial: ${logTail(log)}")
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { active?.let { reap(it.process) } } finally { active = null; clean(directory) }
            }
            if (error is CancellationException) throw error
            throw if (error is AudioRecordingException) error else AudioRecordingException("Cannot start scrcpy audio on $serial", error)
        }
    }

    /** Stop early (or collect a time-limited capture), validate, and return at most 3 MiB. */
    suspend fun stop(): ByteArray = mutex.withLock {
        val current = active ?: throw AudioRecordingException("No audio recording on $serial")
        active = null
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                // Cancellation after Stop must still terminate the child before deleting its file.
                val exit = reap(current.process)
                if (exit != 0) throw AudioRecordingException("scrcpy audio exited $exit on $serial: ${logTail(current.log)}")
                if (!Files.exists(current.file)) throw AudioRecordingException("scrcpy produced no audio on $serial: ${logTail(current.log)}")
                val size = Files.size(current.file)
                if (size == 0L || size > MAX_AUDIO_BYTES) throw AudioRecordingException("Invalid audio artifact size $size on $serial")
                Files.readAllBytes(current.file)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { clean(current.directory) }
        }
    }

    /** Idempotent, bounded teardown on detach, owner loss and daemon shutdown. */
    suspend fun close() = mutex.withLock {
        closed = true
        val current = active
        active = null
        if (current != null) withContext(NonCancellable + Dispatchers.IO) {
            try { reap(current.process) } finally { clean(current.directory) }
        }
    }

    private fun reap(process: Process): Int {
        if (process.isAlive) process.destroy() // scrcpy finalizes its muxer on SIGTERM
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw AudioRecordingException("scrcpy could not be reaped on $serial")
            throw AudioRecordingException("scrcpy did not stop cleanly on $serial")
        }
        return process.exitValue()
    }

    private fun clean(directory: Path) {
        Files.deleteIfExists(directory.resolve("recording.opus"))
        Files.deleteIfExists(directory.resolve("scrcpy.log"))
        Files.deleteIfExists(directory)
    }

    private fun logTail(log: Path): String =
        if (Files.exists(log)) Files.readString(log).takeLast(1_024).replace('\n', ' ') else "no log"

    companion object {
        const val MAX_AUDIO_BYTES = 3L * 1024 * 1024
    }
}
