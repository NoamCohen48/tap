package io.github.noamcohen48.tap.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameCodecTest {
    @Test
    fun roundTripsEveryFrameType() {
        FrameType.entries.forEachIndexed { index, type ->
            val expected = Frame(type, index * 1_000_000_007L, "payload $index".encodeToByteArray())
            val actual = FrameCodec.read(ByteArrayInputStream(encode(expected)))

            assertEquals(expected.type, actual.type)
            assertEquals(expected.requestId, actual.requestId)
            assertContentEquals(expected.payload, actual.payload)
        }
    }

    @Test
    fun readsConsecutiveFramesFromOneStream() {
        val bytes = encode(Frame(FrameType.PING, 0, byteArrayOf())) + encode(Frame(FrameType.CANCEL, 7, byteArrayOf()))
        val input = ByteArrayInputStream(bytes)

        assertEquals(FrameType.PING, FrameCodec.read(input).type)
        assertEquals(7L, FrameCodec.read(input).requestId)
        assertFailsWith<EOFException> { FrameCodec.read(input) }
    }

    @Test
    fun writesTheWholeFrameInOneWriteWithTheWireLayout() {
        val writes = mutableListOf<ByteArray>()
        val sink =
            object : OutputStream() {
                override fun write(b: Int) = error("single-byte write")

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    writes += b.copyOfRange(off, off + len)
                }
            }
        FrameCodec.write(sink, Frame(FrameType.PING, 0x0102030405060708, byteArrayOf(9)))

        assertEquals(1, writes.size, "one write per frame")
        assertContentEquals(
            byteArrayOf(0x54, 0x41, 0x50, 0x31, 1, 9, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 1, 9),
            writes.single(),
        )
        assertEquals(20, FrameCodec.HEADER_BYTES)
    }

    @Test
    fun acceptsTheLargestControlPayloadAndRejectsOneMore() {
        val largest = Frame(FrameType.RESPONSE, 1, ByteArray(MAX_CONTROL_PAYLOAD) { it.toByte() })
        assertContentEquals(largest.payload, FrameCodec.read(ByteArrayInputStream(encode(largest))).payload)

        assertFailsWith<IllegalArgumentException> {
            FrameCodec.write(ByteArrayOutputStream(), Frame(FrameType.RESPONSE, 1, ByteArray(MAX_CONTROL_PAYLOAD + 1)))
        }
        // A header announcing MAX_CONTROL_PAYLOAD + 1 bytes is refused before any payload is read.
        val oversized = encode(Frame(FrameType.RESPONSE, 1, byteArrayOf())).also { header ->
            java.nio.ByteBuffer.wrap(header).putInt(16, MAX_CONTROL_PAYLOAD + 1)
        }
        assertFailsWith<ProtocolException> { FrameCodec.read(ByteArrayInputStream(oversized)) }
    }

    @Test
    fun rejectsMalformedFrames() {
        val good = encode(Frame(FrameType.PING, 0, byteArrayOf()))

        fun mutated(
            index: Int,
            value: Int,
        ) = good.copyOf().also { it[index] = value.toByte() }

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
    fun frameTypesHaveStableWireValues() {
        assertEquals((1..13).map(Int::toByte), FrameType.entries.map(FrameType::wireValue))
        assertEquals(FrameType.BLOB_END, FrameType.fromWireValue(13))
        assertFailsWith<ProtocolException> { FrameType.fromWireValue(0) }
        assertFailsWith<ProtocolException> { FrameType.fromWireValue(14) }
    }

    private fun encode(frame: Frame): ByteArray = ByteArrayOutputStream().also { FrameCodec.write(it, frame) }.toByteArray()
}
