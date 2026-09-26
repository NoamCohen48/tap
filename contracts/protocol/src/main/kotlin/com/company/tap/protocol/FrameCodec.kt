package com.company.tap.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * TAP1 framing: a fixed 20-byte big-endian header (magic, framing version, type, flags,
 * request ID, payload length) followed by the payload. Each direction reads the header with one
 * `readFully` and writes a whole frame with one `write`, so a frame never leaves as a train of
 * small segments (Nagle plus delayed ACK over adb forwarding) and never interleaves.
 */
object FrameCodec {
    private const val MAGIC = 0x54415031
    const val HEADER_BYTES = 4 + 1 + 1 + 2 + 8 + 4

    /** Reads one frame; [EOFException] on a clean close before (or truncation inside) a frame. */
    fun read(input: InputStream): Frame {
        val source = DataInputStream(input)
        val header = ByteArray(HEADER_BYTES)
        source.readFully(header)
        val fields = ByteBuffer.wrap(header)
        if (fields.int != MAGIC) throw ProtocolException("Invalid frame magic")

        val framingVersion = fields.get()
        if (framingVersion != FRAMING_VERSION) {
            throw ProtocolException("Unsupported framing version: $framingVersion")
        }

        val type = FrameType.fromWireValue(fields.get())
        val flags = fields.short.toInt() and 0xFFFF
        if (flags != 0) throw ProtocolException("Unsupported frame flags: $flags")

        val requestId = fields.long
        val length = fields.int
        if (length !in 0..MAX_CONTROL_PAYLOAD) {
            throw ProtocolException("Invalid payload length: $length")
        }

        return Frame(type, requestId, ByteArray(length).also(source::readFully))
    }

    /** Encodes [frame] into one buffer and hands it to [output] in a single write. */
    fun write(output: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_CONTROL_PAYLOAD) { "Payload is too large" }
        val bytes = ByteBuffer.allocate(HEADER_BYTES + frame.payload.size)
            .putInt(MAGIC)
            .put(FRAMING_VERSION)
            .put(frame.type.wireValue)
            .putShort(0)
            .putLong(frame.requestId)
            .putInt(frame.payload.size)
            .put(frame.payload)
            .array()
        output.write(bytes)
        output.flush()
    }
}
