package com.company.tap.driver

import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.MAX_CONTROL_PAYLOAD
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ClientConnection(
    private val socket: Socket,
    private val engine: DriverCommandEngine,
    private val faults: FaultController,
    private val sessionId: String,
    private val generation: Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inbound = ArrayBlockingQueue<InboundEvent>(1)
    private val transportEnded = AtomicBoolean(false)
    private var highestRequestId = 0L

    fun run() {
        val reader = Thread(::readFrames, "tap-driver-socket-reader").apply {
            isDaemon = true
            start()
        }
        try {
            while (true) {
                when (val event = inbound.take()) {
                    EndOfStream -> return
                    is ReadFailed -> throw event.error
                    is FrameReceived -> if (!handle(event.frame)) return
                }
            }
        } finally {
            transportEnded.set(true)
            runCatching { socket.close() }
            reader.interrupt()
            reader.join(1_000)
            check(!reader.isAlive) { "Socket reader survived connection teardown" }
        }
    }

    private fun readFrames() {
        try {
            while (!transportEnded.get()) {
                inbound.put(FrameReceived(FrameCodec.read(socket.getInputStream())))
            }
        } catch (_: EOFException) {
            transportEnded.set(true)
            offerTerminal(EndOfStream)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            transportEnded.set(true)
            offerTerminal(ReadFailed(error))
        }
    }

    private fun offerTerminal(event: InboundEvent) {
        try {
            inbound.put(event)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handle(frame: Frame): Boolean {
        if (frame.type == FrameType.CLOSE) return false
        require(frame.type == FrameType.REQUEST) { "Expected REQUEST" }
        val request = json.decodeFromString<Request>(frame.payload.decodeToString())
        if (frame.requestId <= highestRequestId) {
            if (!transportEnded.get()) {
                writeResponse(
                    frame.requestId,
                    Response(false, errorCode = "DUPLICATE_OR_STALE", durationMs = 0),
                )
            }
            return !transportEnded.get()
        }
        if (transportEnded.get()) return false
        if (faults.inject(FaultPoint.BEFORE_ACCEPTANCE, request, frame.requestId)) {
            dropConnection()
            return false
        }
        highestRequestId = frame.requestId
        if (faults.inject(FaultPoint.AFTER_ACCEPTANCE, request, frame.requestId)) {
            dropConnection()
            return false
        }

        val response = try {
            engine.execute(socket, request, frame.requestId, sessionId, generation)
        } catch (_: InjectedTransportLoss) {
            dropConnection()
            return false
        }
        if (transportEnded.get()) return false
        writeResponse(frame.requestId, response)
        return true
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

    private fun dropConnection() {
        socket.setSoLinger(true, 0)
    }
}

private sealed interface InboundEvent
private data class FrameReceived(val frame: Frame) : InboundEvent
private data class ReadFailed(val error: Throwable) : InboundEvent
private data object EndOfStream : InboundEvent
