package com.company.tap.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object FrameCodec {
    private const val MAGIC = 0x54415031

    fun read(input: InputStream): Frame {
        val source = DataInputStream(input)
        val magic = try {
            source.readInt()
        } catch (error: EOFException) {
            throw error
        }
        if (magic != MAGIC) throw ProtocolException("Invalid frame magic")

        val framingVersion = source.readByte()
        if (framingVersion != FRAMING_VERSION) {
            throw ProtocolException("Unsupported framing version: $framingVersion")
        }

        val type = FrameType.fromWireValue(source.readByte())
        val flags = source.readUnsignedShort()
        if (flags != 0) throw ProtocolException("Unsupported frame flags: $flags")

        val requestId = source.readLong()
        val length = source.readInt()
        if (length !in 0..MAX_CONTROL_PAYLOAD) {
            throw ProtocolException("Invalid payload length: $length")
        }

        return Frame(type, requestId, ByteArray(length).also(source::readFully))
    }

    fun write(output: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_CONTROL_PAYLOAD) { "Payload is too large" }
        val sink = DataOutputStream(output)
        sink.writeInt(MAGIC)
        sink.writeByte(FRAMING_VERSION.toInt())
        sink.writeByte(frame.type.wireValue.toInt())
        sink.writeShort(0)
        sink.writeLong(frame.requestId)
        sink.writeInt(frame.payload.size)
        sink.write(frame.payload)
        sink.flush()
    }
}
