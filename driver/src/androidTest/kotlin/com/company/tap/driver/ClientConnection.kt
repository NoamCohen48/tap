package com.company.tap.driver

import android.os.SystemClock
import com.company.tap.driver.engine.CommandPipeline
import com.company.tap.driver.engine.Outbound
import com.company.tap.driver.engine.PipelineListener
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.MAX_CONTROL_PAYLOAD
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reader lane for one authenticated connection. The calling thread only reads frames and
 * feeds the [CommandPipeline]; UI work runs on the pipeline's executor and responses leave on
 * its writer, so `CANCEL`, `PING`, and transport closure are observed while a command runs.
 */
internal class ClientConnection(
    private val socket: Socket,
    private val engine: DriverCommandEngine,
    private val faults: FaultController,
    private val sessionId: String,
    private val generation: Long,
    private val uninterruptibleGraceMs: Long,
    private val onPoisoned: (reason: String) -> Unit,
) : PipelineListener {
    private val json = Json { ignoreUnknownKeys = true }
    private val transportEnded = AtomicBoolean(false)
    private var highestRequestId = 0L
    private val pipeline = CommandPipeline(
        clock = SystemClock::elapsedRealtime,
        sink = ::writeOutbound,
        listener = this,
        uninterruptibleGraceMs = uninterruptibleGraceMs,
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
                }
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
        FrameType.CANCEL -> {
            require(frame.requestId > 0) { "CANCEL requires a request ID" }
            pipeline.cancel(frame.requestId)
            true
        }
        FrameType.PING -> {
            require(frame.requestId == 0L) { "PING is a connection frame" }
            pipeline.pong(0)
            true
        }
        FrameType.HELLO, FrameType.CHALLENGE, FrameType.AUTH, FrameType.AUTH_RESULT,
        FrameType.RESPONSE, FrameType.PONG ->
            error("Illegal frame ${frame.type} after authentication")
    }

    private fun handleRequest(frame: Frame): Boolean {
        val request = json.decodeFromString<Request>(frame.payload.decodeToString())
        if (frame.requestId <= highestRequestId) {
            writeResponse(frame.requestId, Response(false, errorCode = "DUPLICATE_OR_STALE", durationMs = 0))
            return !transportEnded.get()
        }
        if (faults.inject(FaultPoint.BEFORE_ACCEPTANCE, request, frame.requestId)) {
            dropConnection()
            return false
        }
        highestRequestId = frame.requestId
        if (faults.inject(FaultPoint.AFTER_ACCEPTANCE, request, frame.requestId)) {
            dropConnection()
            return false
        }
        val timeoutMs = request.timeoutMs.coerceIn(0, com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS)
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
        when (message) {
            is Outbound.TerminalResponse -> writeResponse(message.requestId, message.response)
            is Outbound.Pong -> FrameCodec.write(socket.getOutputStream(), Frame(FrameType.PONG, 0, byteArrayOf()))
        }
    }

    private fun writeResponse(requestId: Long, response: Response) {
        var payload = json.encodeToString(response).encodeToByteArray()
        if (payload.size > MAX_CONTROL_PAYLOAD) {
            payload = json.encodeToString(
                Response(
                    ok = false,
                    errorCode = "PAYLOAD_TOO_LARGE",
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
}
