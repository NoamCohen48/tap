package com.company.tap.host

import com.company.tap.protocol.BlobEnd
import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.Command
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Targeted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns request admission, request-ID allocation, serialized frame writes, pending-call routing,
 * heartbeat frames and terminal transport failure. No caller writes to the socket outside this
 * object after authentication.
 */
internal class DriverTransport(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val json: Json,
    private val generation: Long,
    private val serial: String?,
    private val timeoutProvider: (Int) -> Int,
    private val pendingFactory: (Long, Command, Long) -> DriverClient.PendingCommand,
) {
    private val mutex = Mutex()
    private val pingMutex = Mutex()
    private val poisonLock = Any()
    private val pending = ConcurrentHashMap<Long, DriverClient.PendingCommand>()
    private val pongs = Channel<Long>(Channel.UNLIMITED)
    private var nextRequestId = 1L

    @Volatile private var poisoned = false

    @Volatile private var closed = false

    @Volatile var lastWriteNanos: Long = System.nanoTime()
        private set

    internal var beforePhysicalWrite: (suspend () -> Unit)? = null
    internal var afterPhysicalWrite: (() -> Unit)? = null
    internal var beforeMarkWritten: (() -> Unit)? = null
    internal var afterTerminalResponse: (() -> Unit)? = null
    internal var afterAwaitCancel: (() -> Unit)? = null
    internal var frameSink: FrameSink = FrameSink { frame -> FrameCodec.write(socket.getOutputStream(), frame) }

    val isPoisoned: Boolean get() = poisoned

    suspend fun submit(
        request: Request,
        admission: () -> Unit,
    ): DriverClient.PendingCommand =
        mutex.withLock {
            admission()
            ensureUsable(request.command, request.timeoutMs)
            transmit(nextRequestId++, request.command, request.timeoutMs, json.encodeToString(request).encodeToByteArray())
        }

    suspend fun submitValidation(
        requestId: Long,
        request: Request,
        admission: () -> Unit,
    ): DriverClient.PendingCommand =
        mutex.withLock {
            admission()
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            transmit(requestId, request.command, request.timeoutMs, json.encodeToString(request).encodeToByteArray())
        }

    suspend fun submitRawValidation(
        requestId: Long,
        command: Command,
        timeoutMs: Long,
        payload: ByteArray,
        admission: () -> Unit,
    ): DriverClient.PendingCommand =
        mutex.withLock {
            admission()
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            transmit(requestId, command, timeoutMs, payload)
        }

    private fun ensureUsable(
        command: Command,
        timeoutMs: Long,
    ) {
        if (!poisoned && !closed) return
        throw CommandTransportException(
            ErrorCode.TRANSPORT_LOST,
            command.op,
            -1,
            generation,
            TransmissionState.NOT_WRITTEN,
            IllegalStateException("Driver connection is closed or poisoned"),
            serial,
            (command as? Targeted)?.selector?.render(),
            timeoutMs,
        )
    }

    private suspend fun transmit(
        requestId: Long,
        command: Command,
        timeoutMs: Long,
        payload: ByteArray,
    ): DriverClient.PendingCommand {
        val pendingCommand = pendingFactory(requestId, command, timeoutMs)
        check(pending.putIfAbsent(requestId, pendingCommand) == null) { "Request $requestId is already pending" }
        nextRequestId = maxOf(nextRequestId, Math.addExact(requestId, 1L))
        pendingCommand.beginWriting()
        try {
            writeFrame(Frame(FrameType.REQUEST, requestId, payload), remainingTimeoutMs(10_000)) {
                pendingCommand.writeStarted = true
            }
            pendingCommand.markWritten()
        } catch (cancelled: CancellationException) {
            if (!pendingCommand.writeStarted) {
                pending.remove(requestId, pendingCommand)
                pendingCommand.resetNotWritten()
                throw cancelled
            }
            poison(cancelled)
            throw pendingCommand.transportFailure(cancelled)
        } catch (error: Throwable) {
            if (!pendingCommand.writeStarted) {
                pending.remove(requestId, pendingCommand)
                pendingCommand.resetNotWritten()
            }
            poison(error)
            throw pendingCommand.transportFailure(error)
        }
        return pendingCommand
    }

    fun queueCancel(command: DriverClient.PendingCommand) {
        scope.launch {
            mutex.withLock {
                if (poisoned || closed || command.isDone || command.transmissionState != TransmissionState.WRITTEN) {
                    return@withLock
                }
                try {
                    writeFrame(Frame(FrameType.CANCEL, command.requestId, byteArrayOf()), remainingTimeoutMs(5_000))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    poison(error)
                }
            }
        }
    }

    suspend fun ping(
        timeoutMs: Long,
        admission: () -> Unit,
    ): Long =
        pingMutex.withLock {
            val started = System.nanoTime()
            while (pongs.tryReceive().isSuccess) Unit
            mutex.withLock {
                admission()
                check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
                try {
                    writeFrame(Frame(FrameType.PING, 0, byteArrayOf()), remainingTimeoutMs(timeoutMs.toInt()))
                } catch (error: Throwable) {
                    poison(error)
                    throw error
                }
            }
            val pong = withTimeoutOrNull(remainingTimeoutMs(timeoutMs.toInt()).toLong()) { pongs.receive() }
            if (pong == null) {
                val timeout = SocketTimeoutException("No PONG within $timeoutMs ms")
                poison(timeout)
                throw timeout
            }
            (System.nanoTime() - started) / 1_000_000L
        }

    fun readFrames() {
        try {
            while (!poisoned && !closed) {
                val frame = FrameCodec.read(socket.getInputStream())
                when (frame.type) {
                    FrameType.RESPONSE -> {
                        val command =
                            pending.remove(frame.requestId)
                                ?: throw IllegalStateException("Response for unknown request ${frame.requestId}")
                        command.complete(json.decodeFromString<Response>(frame.payload.decodeToString()))
                    }

                    FrameType.PONG -> {
                        check(frame.requestId == 0L) { "PONG must use request ID 0" }
                        pongs.trySend(System.nanoTime())
                    }

                    FrameType.BLOB_START -> {
                        val command = pendingFor(frame)
                        val start = json.decodeFromString<BlobStart>(frame.payload.decodeToString())
                        check(command.blob == null) { "Second BLOB_START for request ${frame.requestId}" }
                        command.blob = BlobReceiver(start)
                    }

                    FrameType.BLOB_CHUNK -> {
                        pendingFor(frame).blob?.chunk(frame.payload)
                            ?: throw IllegalStateException("BLOB_CHUNK before BLOB_START for request ${frame.requestId}")
                    }

                    FrameType.BLOB_END -> {
                        pendingFor(frame).blob?.end(json.decodeFromString<BlobEnd>(frame.payload.decodeToString()))
                            ?: throw IllegalStateException("BLOB_END before BLOB_START for request ${frame.requestId}")
                    }

                    else -> {
                        throw IllegalStateException("Unexpected ${frame.type} frame from driver")
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed) poison(error)
        }
    }

    private fun pendingFor(frame: Frame): DriverClient.PendingCommand =
        pending[frame.requestId] ?: throw IllegalStateException("${frame.type} for unknown request ${frame.requestId}")

    suspend fun close() {
        if (closed) return
        withContext(NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                if (!poisoned) {
                    runCatching { writeFrame(Frame(FrameType.CLOSE, 0, byteArrayOf()), remainingTimeoutMs(5_000)) }
                }
                runCatching { socket.close() }
            }
            failPending(IllegalStateException("Driver connection closed"))
        }
    }

    fun disconnectForValidation() {
        synchronized(poisonLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
        }
        poison(IllegalStateException("Disconnected for validation"))
    }

    fun poison(cause: Throwable) {
        synchronized(poisonLock) {
            if (poisoned) return
            poisoned = true
            runCatching { socket.close() }
        }
        failPending(cause)
    }

    private fun failPending(cause: Throwable) {
        val commands = pending.values.toList()
        pending.clear()
        commands.forEach { it.fail(cause) }
    }

    fun isPending(command: DriverClient.PendingCommand): Boolean = pending[command.requestId] === command

    suspend fun withLock(block: suspend () -> Unit) = mutex.withLock { block() }

    suspend fun nextRequestIdForTest(): Long = mutex.withLock { nextRequestId }

    fun remainingTimeoutMs(maximumMs: Int): Int = timeoutProvider(maximumMs)

    suspend fun writeFrame(
        frame: Frame,
        timeoutMs: Int,
        markStarted: () -> Unit = {},
    ) {
        lastWriteNanos = System.nanoTime()
        val started = AtomicBoolean(false)
        val writer =
            scope.async(Dispatchers.IO) {
                beforePhysicalWrite?.invoke()
                markStarted()
                started.set(true)
                try {
                    frameSink.write(frame)
                } finally {
                    afterPhysicalWrite?.invoke()
                }
            }
        try {
            val completed =
                withTimeoutOrNull(timeoutMs.toLong()) {
                    writer.await()
                    true
                }
            if (completed != true) {
                unblockAndReap(writer)
                throw SocketTimeoutException("Socket write exceeded $timeoutMs ms")
            }
        } catch (cancelled: CancellationException) {
            if (!started.get()) {
                writer.cancel()
                withContext(NonCancellable) {
                    withTimeoutOrNull(WRITE_REAP_TIMEOUT_MS) { writer.join() }
                }
                if (!started.get()) throw cancelled
            }
            unblockAndReap(writer)
            throw cancelled
        }
    }

    private suspend fun unblockAndReap(writer: Deferred<Unit>) {
        writer.cancel()
        runCatching { socket.close() }
        withContext(NonCancellable) {
            withTimeoutOrNull(WRITE_REAP_TIMEOUT_MS) { writer.join() }
        }
        writer.cancel()
    }
}

/** A physical frame write; suspends so tests can gate it. */
internal fun interface FrameSink {
    suspend fun write(frame: Frame)
}

/** Bound for reaping a writer child after its socket was closed. */
const val WRITE_REAP_TIMEOUT_MS = 2_000L
