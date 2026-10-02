package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Full packet receipt on the host, not device capture time. Compare only within [clockId]. */
data class VideoSample(
    val packet: VideoPacket,
    val receivedMonotonicNs: Long,
    val receivedEpochMs: Long,
    val clockId: String,
)

/** A passive, closeable producer. [interrupt] must unblock even an unchanged-screen socket read. */
interface VideoSource {
    suspend fun run(receive: suspend (VideoSample) -> Unit)

    fun interrupt()
}

/**
 * Optional scrcpy 4.1 server, video only, on a private socket/file namespace. No driver command,
 * attachment, audio, controls, screen wake or AUT lifecycle. Never uses scrcpy's shared JAR path.
 * The caller serializes producer lifetimes; cleanup finishes before another producer can start.
 */
class ScrcpyVideoSource(
    private val adb: Adb,
    private val serial: String,
    private val server: Path,
) : VideoSource {
    private val scid = "%08x".format(SecureRandom().nextInt() and Int.MAX_VALUE)
    private val interrupted = AtomicBoolean(false)
    private val socketLock = Any()
    private var socket: Socket? = null
    private val pid = AtomicInteger()
    private val reportedPid = CompletableDeferred<Int>()
    private val log = StringBuilder()

    override fun interrupt() {
        interrupted.set(true)
        synchronized(socketLock) { runCatching { socket?.close() } }
    }

    private fun alive() {
        if (interrupted.get()) throw VideoException("Video reader closed")
    }

    override suspend fun run(receive: suspend (VideoSample) -> Unit) =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(server)) throw VideoException("scrcpy 4.1 server not found: $server (set TAP_VIDEO_SERVER)")
            var port: Int? = null
            var process: Process? = null
            val drains = ArrayList<Thread>()
            var primaryFailure: Throwable? = null
            try {
                alive()
                adb.pushVideoServer(serial, server, scid)
                withContext(NonCancellable) {
                    alive()
                    port = adb.forwardVideoSocket(serial, scid)
                    alive()
                    process = adb.startVideoServer(serial, scid)
                    val child = process
                    listOf(child.inputStream, child.errorStream).forEach { stream ->
                        drains +=
                            thread(isDaemon = true, name = "tap-video-log-$scid") {
                                try {
                                    stream.use {
                                        val bytes = ByteArray(1024)
                                        while (true) {
                                            val n = it.read(bytes)
                                            if (n < 0) break
                                            synchronized(log) {
                                                log.append(String(bytes, 0, n, Charsets.UTF_8))
                                                Regex("(?m)^TAP_VIDEO_PID=([0-9]+)\\r?\\n")
                                                    .find(log)
                                                    ?.groupValues
                                                    ?.get(1)
                                                    ?.toIntOrNull()
                                                    ?.takeIf { value -> value > 0 }
                                                    ?.let { value ->
                                                        if (pid.compareAndSet(0, value)) reportedPid.complete(value)
                                                    }
                                                if (log.length > 4096) log.delete(0, log.length - 4096)
                                            }
                                        }
                                    }
                                } catch (_: java.io.IOException) {
                                    // closing the child closes its drains
                                }
                            }
                    }
                }
                // A fast socket must not race the PID drain: never publish before we can target cleanup.
                withTimeout(5000) { reportedPid.await() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var connected: Socket? = null
                while (connected == null && System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    alive()
                    if (process?.isAlive != true) throw VideoException("Video capture exited on $serial: ${logTail()}")
                    val candidate = Socket()
                    synchronized(socketLock) {
                        alive()
                        socket = candidate
                    }
                    try {
                        candidate.connect(InetSocketAddress("127.0.0.1", port!!), 500)
                        candidate.soTimeout = 500
                        if (candidate.getInputStream().read() == 0) connected = candidate
                    } catch (_: java.io.IOException) {
                        // adb forward may be ready before the device listener
                    }
                    if (connected == null) {
                        candidate.close()
                        delay(50)
                    }
                }
                val transport = connected ?: throw VideoException("Video capture startup timed out on $serial: ${logTail()}")
                transport.soTimeout = 10_000
                val input = DataInputStream(transport.getInputStream())
                input.readFully(ByteArray(64)) // device-name metadata, not an app identity
                val protocol = ScrcpyVideoProtocol(input)
                var ready = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    alive()
                    val packet = protocol.next()
                    val sample = VideoSample(packet, MediaClock.nowNs(), System.currentTimeMillis(), MediaClock.id)
                    if (!ready && packet is VideoPacket.Data && !packet.configuration && packet.key) {
                        ready = true
                        transport.soTimeout = 0 // idle is normal; interrupt() closes this read on cancellation
                    }
                    receive(sample)
                }
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                try {
                    withContext(NonCancellable) {
                        interrupt()
                        try {
                            // The host adb child alone is not proof its remote process exited. Target its reported PID.
                            if (process != null) {
                                val remotePid =
                                    pid.get().takeIf { it > 0 }
                                        ?: withTimeoutOrNull(1000) { reportedPid.await() }
                                        ?: throw VideoException("Video child PID was not reported; remote cleanup is unproven on $serial")
                                adb.stopVideoServer(serial, scid, remotePid)
                            }
                        } finally {
                            val child = process
                            if (child != null) {
                                child.destroy()
                                if (!child.waitFor(2, TimeUnit.SECONDS)) {
                                    child.destroyForcibly()
                                    if (!child.waitFor(
                                            2,
                                            TimeUnit.SECONDS,
                                        )
                                    ) {
                                        throw VideoException("Video ADB child could not be reaped on $serial")
                                    }
                                }
                                child.inputStream.close()
                                child.errorStream.close()
                                drains.forEach { it.join(1000) }
                            }
                            try {
                                port?.let { adb.removeForward(serial, it) }
                            } finally {
                                adb.deleteVideoServer(serial, scid)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    throw VideoCleanupException("Video cleanup unproven on $serial ($scid)", error).also { failure ->
                        primaryFailure?.let(failure::addSuppressed)
                    }
                }
            }
        }

    private fun logTail(): String = synchronized(log) { log.toString().takeLast(1024).replace('\n', ' ') }
}
