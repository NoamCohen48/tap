package io.github.noamcohen48.tap.protocol

import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobFramesTest {
    private val blobId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    @Test
    fun chunkRoundTripsASliceOfTheBlob() {
        val bytes = ByteArray(100) { it.toByte() }
        val payload = BlobFrames.encodeChunk(blobId, 3, bytes, 10, 5)

        assertEquals(BlobFrames.CHUNK_HEADER_BYTES + 5, payload.size)
        val chunk = BlobFrames.decodeChunk(payload)
        assertEquals(blobId, chunk.blobId)
        assertEquals(3, chunk.index)
        assertContentEquals(byteArrayOf(10, 11, 12, 13, 14), chunk.data)
    }

    @Test
    fun chunkHeaderIsRawUuidThenBigEndianIndex() {
        val payload = BlobFrames.encodeChunk(blobId, 0x01020304, byteArrayOf(7), 0, 1)
        assertContentEquals(
            byteArrayOf(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
                1, 2, 3, 4, 7,
            ),
            payload,
        )
    }

    @Test
    fun encodingEnforcesTheChunkLimits() {
        val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES + 1)
        BlobFrames.encodeChunk(blobId, 0, bytes, 0, MAX_BLOB_CHUNK_BYTES)
        assertFailsWith<IllegalArgumentException> { BlobFrames.encodeChunk(blobId, 0, bytes, 0, MAX_BLOB_CHUNK_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { BlobFrames.encodeChunk(blobId, 0, bytes, 0, 0) }
        assertFailsWith<IllegalArgumentException> { BlobFrames.encodeChunk(blobId, -1, bytes, 0, 1) }
    }

    @Test
    fun decodingRejectsMalformedChunks() {
        assertFailsWith<ProtocolException> { BlobFrames.decodeChunk(ByteArray(BlobFrames.CHUNK_HEADER_BYTES)) } // no data
        assertFailsWith<ProtocolException> { BlobFrames.decodeChunk(ByteArray(5)) } // short header
        assertFailsWith<ProtocolException> {
            BlobFrames.decodeChunk(ByteArray(BlobFrames.CHUNK_HEADER_BYTES + MAX_BLOB_CHUNK_BYTES + 1))
        }
        val negative = BlobFrames.encodeChunk(blobId, 0, byteArrayOf(1), 0, 1).also { ByteBuffer.wrap(it).putInt(16, -1) }
        assertFailsWith<ProtocolException> { BlobFrames.decodeChunk(negative) }
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BlobFrames.sha256Hex("abc".encodeToByteArray()))
    }
}
