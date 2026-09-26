package io.github.noamcohen48.tap.driver

import android.os.SystemClock
import io.github.noamcohen48.tap.driver.engine.CommandPipeline
import io.github.noamcohen48.tap.driver.engine.Outbound
import io.github.noamcohen48.tap.driver.engine.PipelineListener
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.MAX_CONTROL_PAYLOAD
import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.ProtocolException
import io.github.noamcohen48.tap.protocol.ProtocolJson
import io.github.noamcohen48.tap.protocol.RequestDecoder
import io.github.noamcohen48.tap.protocol.Response
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString

/**
 * Reader lane for one authenticated connection. The calling thread only reads frames and
 * feeds the [CommandPipeline]; UI work runs on the pipeline's executor and every outbound frame
 * (responses, rejections, `CLOSE`) leaves on its writer, so `CANCEL`, `PING`, and transport
 * closure are observed while a command runs and frames never interleave on the socket.
 *
 * A protocol violation (a reused or stale request ID, a malformed or illegal control frame)
 * ends the connection in order: a `CLOSE` frame with the reason is queued behind the frames
 * already on the writer, then the socket is closed.
 */
internal class ClientConnection(
    private val socket: Socket,
    private val engine: DriverCommandEngine,
    private val faults: FaultController,
    private val sessionId: String,
    private val generation: Long,
    private val uninterruptibleGraceMs: Long,
    private val heartbeatTimeoutMs: Long,
    private val onPoisoned: (reason: String) -> Unit,
) : PipelineListener {
    private val json = ProtocolJson.codec
    private val transportEnded = AtomicBoolean(false)
    private var highestRequestId = 0L
    private val pipeline = CommandPipeline(
        clock = SystemClock::elapsedRealtime,
        sink = ::writeOutbound,
        listener = this,
        uninterruptibleGraceMs = uninterruptibleGraceMs,
        heartbeatTimeoutMs = heartbeatTimeoutMs,
    )

    val isPoisoned: Boolean get() = pipeline.isPoisoned

    fun run() {
        pipeline.start()
        try {
            while (!transportEnded.get()) {
                val frame = try {
                    FrameCodec.read(socket.getInputStream())
                } catch (_: EOFException) {
                    return
                } catch (malformed: ProtocolException) {
                    protocolViolation(malformed.message ?: "Malformed frame")
                    return
                }
                pipeline.heartbeat()
                if (!handle(frame)) return
            }
        } finally {
            endTransport()
            pipeline.discardQueued()
            // A blocked executor is late work; the watchdog decides whether the process dies.
            while (!pipeline.awaitTermination(1_000)) {
                // keep waiting; the watchdog listener handles kill/quarantine policy
            }
        }
    }

    private fun handle(frame: Frame): Boolean = when (frame.type) {
        FrameType.CLOSE -> false
        FrameType.REQUEST -> handleRequest(frame)
        FrameType.CANCEL -> when {
            frame.requestId <= 0 -> protocolViolation("CANCEL requires a positive request ID, got ${frame.requestId}")
            frame.payload.isNotEmpty() -> protocolViolation("CANCEL carries no payload")
            else -> {
                pipeline.cancel(frame.requestId)
                true
            }
        }
        FrameType.PING -> when {
            frame.requestId != 0L -> protocolViolation("PING is a connection frame, got request ID ${frame.requestId}")
            frame.payload.isNotEmpty() -> protocolViolation("PING carries no payload")
            else -> {
                pipeline.pong(0)
                true
            }
        }
        FrameType.HELLO, FrameType.CHALLENGE, FrameType.AUTH, FrameType.AUTH_RESULT,
        FrameType.RESPONSE, FrameType.PONG, FrameType.BLOB_START, FrameType.BLOB_CHUNK, FrameType.BLOB_END ->
            protocolViolation("Illegal frame ${frame.type} after authentication")
    }

    private fun handleRequest(frame: Frame): Boolean {
        if (frame.requestId <= highestRequestId) {
            // Answering with the reused ID could complete the host's real pending command with
            // this rejection, so a duplicate or stale ID ends the connection instead.
            return protocolViolation(
                "${ErrorCode.DUPLICATE_OR_STALE}: request ID ${frame.requestId} is at or below the watermark $highestRequestId",
            )
        }
        val request = when (val decoded = RequestDecoder.decode(frame.payload.decodeToString())) {
            is RequestDecoder.Outcome.Decoded -> decoded.request
            is RequestDecoder.Outcome.Rejected -> {
                // A malformed request still consumes its ID: the watermark only ever moves forward.
                highestRequestId = frame.requestId
                pipeline.respond(frame.requestId, Response.failure(decoded.code, message = decoded.message, durationMs = 0))
                return !transportEnded.get()
            }
        }
        if (faults.inject(FaultPoint.BEFORE_ACCEPTANCE, request.command, frame.requestId, generation)) {
            dropConnection()
            return false
        }
        highestRequestId = frame.requestId
        if (faults.inject(FaultPoint.AFTER_ACCEPTANCE, request.command, frame.requestId, generation)) {
            dropConnection()
            return false
        }
        val timeoutMs = request.timeoutMs.coerceIn(0, io.github.noamcohen48.tap.protocol.MAX_REQUEST_TIMEOUT_MS)
        pipeline.submit(frame.requestId, timeoutMs) { context ->
            try {
                engine.execute(context, socket, request, sessionId, generation)
            } catch (loss: InjectedTransportLoss) {
                dropConnection()
                endTransport()
                throw loss
            }
        }
        return true
    }

    private fun writeOutbound(message: Outbound) {
        if (transportEnded.get()) return
        val output = socket.getOutputStream()
        when (message) {
            is Outbound.TerminalResponse -> writeResponse(message.requestId, message.response)
            is Outbound.Pong -> FrameCodec.write(output, Frame(FrameType.PONG, 0, byteArrayOf()))
            is Outbound.BlobStartFrame -> FrameCodec.write(
                output,
                Frame(FrameType.BLOB_START, message.requestId, json.encodeToString(message.start).encodeToByteArray()),
            )
            is Outbound.BlobChunkFrame ->
                FrameCodec.write(output, Frame(FrameType.BLOB_CHUNK, message.requestId, message.payload))
            is Outbound.BlobEndFrame -> FrameCodec.write(
                output,
                Frame(FrameType.BLOB_END, message.requestId, json.encodeToString(message.end).encodeToByteArray()),
            )
            is Outbound.Close ->
                FrameCodec.write(output, Frame(FrameType.CLOSE, 0, message.reason.take(MAX_CLOSE_REASON_CHARS).encodeToByteArray()))
        }
    }

    /**
     * Ends the connection after a protocol violation: `CLOSE` with [reason] goes out on the
     * writer lane (bounded wait), then [run]'s cleanup closes the socket. Always returns false
     * so the read loop stops.
     */
    private fun protocolViolation(reason: String): Boolean {
        pipeline.close(reason, CLOSE_FLUSH_TIMEOUT_MS)
        return false
    }

    private fun writeResponse(requestId: Long, response: Response) {
        var payload = json.encodeToString(response).encodeToByteArray()
        if (payload.size > MAX_CONTROL_PAYLOAD) {
            payload = json.encodeToString(
                Response.failure(
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    message = "Response exceeded $MAX_CONTROL_PAYLOAD bytes",
                    durationMs = response.durationMs,
                )
            ).encodeToByteArray()
        }
        FrameCodec.write(socket.getOutputStream(), Frame(FrameType.RESPONSE, requestId, payload))
    }

    override fun onPoisoned(reason: String) {
        onPoisoned.invoke(reason)
    }

    override fun onWriteFailed(error: Throwable) {
        endTransport()
    }

    private fun endTransport() {
        if (transportEnded.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }

    private fun dropConnection() {
        runCatching { socket.setSoLinger(true, 0) }
    }

    private companion object {
        const val CLOSE_FLUSH_TIMEOUT_MS = 2_000L
        const val MAX_CLOSE_REASON_CHARS = 512
    }
}
