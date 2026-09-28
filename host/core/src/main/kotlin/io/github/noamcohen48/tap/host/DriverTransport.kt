package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.ProtocolException
import io.github.noamcohen48.tap.protocol.op
import io.github.noamcohen48.tap.protocol.parsePayload
import io.github.noamcohen48.tap.protocol.render
import io.github.noamcohen48.tap.protocol.targetSelector
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Response
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
    private val generation: Long,
    private val serial: String?,
    private val timeoutProvider: (Int) -> Int,
    private val hooks: TransportHooks,
    private val pendingFactory: (Long, Request, Long) -> DriverClient.PendingCommand,
) {
    private val mutex = Mutex()
    private val pingMutex = Mutex()
    private val poisonLock = Any()
    private val pending = ConcurrentHashMap<Long, DriverClient.PendingCommand>()
    private val pongs = Channel<Long>(Channel.UNLIMITED)
    private var nextRequestId = 1L

    @Volatile private var poisoned = false

    @Volatile private var closed = false

    /** When the last frame finished writing; the heartbeat pings only after an idle interval. */
    @Volatile var lastWriteNanos: Long = System.nanoTime()
        private set

    val isPoisoned: Boolean get() = poisoned

    /** What poisoned the transport first; null while healthy. */
    @Volatile var poisonCause: Throwable? = null
        private set

    /** Transmits [request], which already carries its session envelope, under the next request ID. */
    suspend fun submit(
        request: Request,
        admission: () -> Unit,
    ): DriverClient.PendingCommand =
        mutex.withLock {
            admission()
            ensureUsable(request)
            transmit(nextRequestId++, request, request.timeoutMs, request.toByteArray())
        }

    /**
     * Validation only ([ValidationTransport]): transmits [payload] under the caller's [requestId]
     * instead of the next allocated one. [request] only classifies the pending entry.
     */
    suspend fun submitWithExplicitId(
        requestId: Long,
        request: Request,
        timeoutMs: Long,
        payload: ByteArray,
        admission: () -> Unit,
    ): DriverClient.PendingCommand =
        mutex.withLock {
            admission()
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            transmit(requestId, request, timeoutMs, payload)
        }

    private fun ensureUsable(request: Request) {
        if (!poisoned && !closed) return
        throw CommandTransportException(
            ErrorCode.ERR_TRANSPORT_LOST,
            request.op,
            -1,
            generation,
            TransmissionState.NOT_WRITTEN,
            IllegalStateException("Driver connection is closed or poisoned"),
            serial,
            request.targetSelector?.render(),
            request.timeoutMs,
        )
    }

    private suspend fun transmit(
        requestId: Long,
        request: Request,
        timeoutMs: Long,
        payload: ByteArray,
    ): DriverClient.PendingCommand {
        val pendingCommand = pendingFactory(requestId, request, timeoutMs)
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
            val pingBudgetMs = timeoutMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            while (pongs.tryReceive().isSuccess) Unit
            mutex.withLock {
                admission()
                if (poisoned || closed) {
                    throw CommandTransportException(
                        ErrorCode.ERR_TRANSPORT_LOST,
                        "ping",
                        0,
                        generation,
                        TransmissionState.NOT_WRITTEN,
                        IllegalStateException("Driver connection is closed or poisoned"),
                        serial,
                    )
                }
                try {
                    writeFrame(Frame(FrameType.PING, 0, byteArrayOf()), remainingTimeoutMs(pingBudgetMs))
                } catch (error: Throwable) {
                    poison(error)
                    throw error
                }
            }
            val pong = withTimeoutOrNull(remainingTimeoutMs(pingBudgetMs).toLong()) { pongs.receive() }
            if (pong == null) {
                val timeout = SocketTimeoutException("No PONG within $timeoutMs ms")
                poison(timeout)
                throw timeout
            }
            (System.nanoTime() - started) / 1_000_000L
        }

    /** Reads until close or poison. A frame the protocol does not allow here is a
     * [ProtocolException] and poisons the connection like any transport failure. */
    fun readFrames() {
        try {
            while (!poisoned && !closed) {
                val frame = FrameCodec.read(socket.getInputStream())
                when (frame.type) {
                    FrameType.RESPONSE -> {
                        val command =
                            pending.remove(frame.requestId)
                                ?: throw ProtocolException("Response for unknown request ${frame.requestId}")
                        command.complete(parsePayload("RESPONSE", frame.payload, Response::parseFrom))
                    }

                    FrameType.PONG -> {
                        if (frame.requestId != 0L) throw ProtocolException("PONG must use request ID 0")
                        pongs.trySend(System.nanoTime())
                    }

                    FrameType.BLOB_START -> {
                        val command = pendingFor(frame)
                        val start = parsePayload("BLOB_START", frame.payload, BlobStart::parseFrom)
                        if (command.blob != null) throw ProtocolException("Second BLOB_START for request ${frame.requestId}")
                        command.blob = BlobReceiver(start)
                    }

                    FrameType.BLOB_CHUNK -> {
                        pendingFor(frame).blob?.chunk(frame.payload)
                            ?: throw ProtocolException("BLOB_CHUNK before BLOB_START for request ${frame.requestId}")
                    }

                    FrameType.BLOB_END -> {
                        pendingFor(frame).blob?.end(parsePayload("BLOB_END", frame.payload, BlobEnd::parseFrom))
                            ?: throw ProtocolException("BLOB_END before BLOB_START for request ${frame.requestId}")
                    }

                    FrameType.CLOSE -> {
                        // The driver ends the connection after a protocol violation; the reason
                        // becomes the poison cause so in-flight commands report why.
                        val reason = frame.payload.decodeToString().ifEmpty { "no reason given" }
                        throw ProtocolException("Driver closed the connection: $reason")
                    }

                    else -> {
                        throw ProtocolException("Unexpected ${frame.type} frame from driver")
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed) poison(error)
        }
    }

    private fun pendingFor(frame: Frame): DriverClient.PendingCommand =
        pending[frame.requestId] ?: throw ProtocolException("${frame.type} for unknown request ${frame.requestId}")

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

    /** Validation only ([ValidationTransport]): poisons a healthy transport as if it had failed. */
    fun disconnectForValidation() {
        synchronized(poisonLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
        }
        poison(IllegalStateException("Disconnected for validation"))
    }

    /** [poison], except on a client that was already closed deliberately. */
    fun poisonUnlessClosed(cause: Throwable) {
        if (!closed) poison(cause)
    }

    fun poison(cause: Throwable) {
        synchronized(poisonLock) {
            if (poisoned) return
            poisonCause = cause
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

    fun remainingTimeoutMs(maximumMs: Int): Int = timeoutProvider(maximumMs)

    suspend fun writeFrame(
        frame: Frame,
        timeoutMs: Int,
        markStarted: () -> Unit = {},
    ) {
        val started = AtomicBoolean(false)
        val writer =
            scope.async(Dispatchers.IO) {
                hooks.beforePhysicalWrite()
                markStarted()
                started.set(true)
                try {
                    hooks.physicalWrite(frame) { FrameCodec.write(socket.getOutputStream(), it) }
                } finally {
                    hooks.afterPhysicalWrite()
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
            lastWriteNanos = System.nanoTime()
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

/** Bound for reaping a writer child after its socket was closed. */
const val WRITE_REAP_TIMEOUT_MS = 2_000L
