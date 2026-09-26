package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.protocol.BlobEnd
import io.github.noamcohen48.tap.protocol.BlobStart
import io.github.noamcohen48.tap.protocol.Response

/** Messages the writer lane delivers to the transport, in order, on a single thread. */
sealed interface Outbound {
    data class TerminalResponse(val requestId: Long, val response: Response) : Outbound
    data class Pong(val requestId: Long) : Outbound
    data class BlobStartFrame(val requestId: Long, val start: BlobStart) : Outbound

    /** [payload] is the complete `BLOB_CHUNK` payload (header included). */
    class BlobChunkFrame(val requestId: Long, val payload: ByteArray) : Outbound
    data class BlobEndFrame(val requestId: Long, val end: BlobEnd) : Outbound

    /**
     * Orderly driver-initiated close after a protocol violation: the connection-level `CLOSE`
     * frame carrying [reason]. Nothing is written after it.
     */
    data class Close(val reason: String) : Outbound
}

fun interface OutboundSink {
    /** Called only on the writer thread. Throwing marks the transport as failed. */
    fun write(message: Outbound)
}

interface PipelineListener {
    /** The pipeline stopped trusting the executor; the owner must close the listener and exit. */
    fun onPoisoned(reason: String)

    /** The writer lane failed; the owner must treat the transport as lost. */
    fun onWriteFailed(error: Throwable)
}
