package com.company.tap.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameCodecTest {
    @Test
    fun roundTripsFrame() {
        val expected = Frame(FrameType.REQUEST, 42, "payload".encodeToByteArray())
        val bytes = ByteArrayOutputStream().also { FrameCodec.write(it, expected) }.toByteArray()
        val actual = FrameCodec.read(ByteArrayInputStream(bytes))

        assertEquals(expected.type, actual.type)
        assertEquals(expected.requestId, actual.requestId)
        assertContentEquals(expected.payload, actual.payload)
    }

    @Test
    fun writesTheWholeFrameInOneWriteWithTheWireLayout() {
        val writes = mutableListOf<ByteArray>()
        val sink = object : OutputStream() {
            override fun write(b: Int) = error("single-byte write")
            override fun write(b: ByteArray, off: Int, len: Int) { writes += b.copyOfRange(off, off + len) }
        }
        FrameCodec.write(sink, Frame(FrameType.PING, 0x0102030405060708, byteArrayOf(9)))

        assertEquals(1, writes.size, "one write per frame")
        assertContentEquals(
            byteArrayOf(0x54, 0x41, 0x50, 0x31, 1, 9, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 9),
            writes.single(),
        )
    }

    @Test
    fun rejectsMalformedFrames() {
        val good = ByteArrayOutputStream().also { FrameCodec.write(it, Frame(FrameType.PING, 0, byteArrayOf())) }.toByteArray()
        fun mutated(index: Int, value: Int) = good.copyOf().also { it[index] = value.toByte() }

        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(mutated(0, 0))) } // magic
        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(mutated(4, 2))) } // framing version
        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(mutated(5, 99))) } // frame type
        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(mutated(7, 1))) } // flags
        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(mutated(16, 0x80))) } // negative length
        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(mutated(17, 0x7F))) } // oversized length
        assertFailsWith<EOFException> { FrameCodec.read(ByteArrayInputStream(good.copyOf(10))) } // truncated header
        assertFailsWith<EOFException> { FrameCodec.read(ByteArrayInputStream(mutated(19, 4))) } // truncated payload
    }

    @Test
    fun authenticatesBothSidesWithDifferentDomainMacs() {
        val secret = ByteArray(32) { it.toByte() }
        val transcript = ProtocolAuthentication.transcript(
            "hello".encodeToByteArray(),
            "challenge".encodeToByteArray(),
            "negotiation".encodeToByteArray(),
        )
        val hostMac = ProtocolAuthentication.hostMac(secret, transcript)
        val driverMac = ProtocolAuthentication.driverMac(secret, transcript)

        assertFalse(hostMac == driverMac)
        assertTrue(ProtocolAuthentication.constantTimeEquals(hostMac, hostMac))
        assertFalse(ProtocolAuthentication.constantTimeEquals(hostMac, driverMac))
    }
}
