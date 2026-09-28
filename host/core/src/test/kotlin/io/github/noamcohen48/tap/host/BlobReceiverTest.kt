package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.MAX_ARTIFACT_BYTES
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobReceiverTest {
    private val id = UUID.randomUUID()
    private val data = Random(7).nextBytes(10_000)
    private val sha = BlobFrames.sha256Hex(data)
    private val start = BlobStart.newBuilder().setBlobId(id.toString()).setTotalLength(data.size.toLong()).setSha256(sha).build()
    private val end = BlobEnd.newBuilder().setBlobId(id.toString()).setByteCount(data.size.toLong()).setSha256(sha).build()

    private fun chunk(index: Int, from: Int, to: Int, blobId: UUID = id) =
        BlobFrames.encodeChunk(blobId, index, data, from, to - from)

    @Test
    fun reassemblesAndVerifiesChunks() {
        val receiver = BlobReceiver(start)
        receiver.chunk(chunk(0, 0, 4_000))
        receiver.chunk(chunk(1, 4_000, 10_000))
        assertNull(receiver.bytes)
        receiver.end(end)
        assertTrue(receiver.complete)
        assertNull(receiver.failureDetail)
        assertContentEquals(data, receiver.bytes)
    }

    @Test
    fun anEmptyBlobIsComplete() {
        val empty = BlobFrames.sha256Hex(ByteArray(0))
        val receiver = BlobReceiver(start.toBuilder().setTotalLength(0).setSha256(empty).build())
        receiver.end(end.toBuilder().setByteCount(0).setSha256(empty).build())
        assertContentEquals(ByteArray(0), receiver.bytes)
    }

    @Test
    fun theFirstViolationSticksAndDiscardsTheBytes() {
        fun failure(frames: BlobReceiver.() -> Unit): String? =
            BlobReceiver(start).apply(frames).also { assertNull(it.bytes) }.failureDetail

        assertEquals(ErrorDetail.BLOB_MALFORMED, failure { chunk(ByteArray(3)) })
        assertEquals(ErrorDetail.BLOB_OUT_OF_ORDER, failure { chunk(chunk(1, 0, 10_000)) })
        assertEquals(ErrorDetail.BLOB_UNEXPECTED, failure { chunk(chunk(0, 0, 10_000, UUID.randomUUID())) })
        assertEquals(
            ErrorDetail.BLOB_LENGTH_MISMATCH,
            failure {
                chunk(chunk(0, 0, 10_000))
                chunk(chunk(1, 0, 1))
            },
        )
        assertEquals(
            ErrorDetail.BLOB_LENGTH_MISMATCH,
            failure {
                chunk(chunk(0, 0, 9_999))
                end(end)
            },
        )
        val corrupt = data.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertEquals(
            ErrorDetail.BLOB_CHECKSUM_MISMATCH,
            failure {
                chunk(BlobFrames.encodeChunk(id, 0, corrupt, 0, corrupt.size))
                end(end)
            },
        )
        assertEquals(
            ErrorDetail.BLOB_MALFORMED,
            failure {
                chunk(ByteArray(3))
                chunk(chunk(0, 0, 10_000))
                end(end)
            },
        )
        assertEquals(
            ErrorDetail.ARTIFACT_TOO_LARGE,
            BlobReceiver(start.toBuilder().setTotalLength(MAX_ARTIFACT_BYTES + 1).build()).failureDetail,
        )
    }
}
