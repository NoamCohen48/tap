package io.github.noamcohen48.tap.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Payload bytes per `BLOB_CHUNK` frame, excluding the 20-byte chunk header. */
const val MAX_BLOB_CHUNK_BYTES = 256 * 1024

/** Upper bound for one artifact blob. */
const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024

class BlobChunk(
    val blobId: UUID,
    val index: Int,
    val data: ByteArray,
)

/**
 * `BLOB_CHUNK` layout: 16 raw UUID bytes, a 4-byte unsigned big-endian chunk index, then the
 * chunk bytes (plan §"Blob transfer"). Chunks are contiguous and ordered per blob. The
 * `BLOB_START`/`BLOB_END` payloads are `tap.wire.v1.BlobStart`/`BlobEnd`.
 */
object BlobFrames {
    const val CHUNK_HEADER_BYTES = 20

    fun encodeChunk(
        blobId: UUID,
        index: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(index >= 0) { "Chunk index must not be negative" }
        require(length in 1..MAX_BLOB_CHUNK_BYTES) { "Chunk length $length is out of range" }
        return ByteBuffer
            .allocate(CHUNK_HEADER_BYTES + length)
            .putLong(blobId.mostSignificantBits)
            .putLong(blobId.leastSignificantBits)
            .putInt(index)
            .put(data, offset, length)
            .array()
    }

    fun decodeChunk(payload: ByteArray): BlobChunk {
        if (payload.size <= CHUNK_HEADER_BYTES || payload.size - CHUNK_HEADER_BYTES > MAX_BLOB_CHUNK_BYTES) {
            throw ProtocolException("Invalid blob chunk length: ${payload.size}")
        }
        val buffer = ByteBuffer.wrap(payload)
        val blobId = UUID(buffer.getLong(), buffer.getLong())
        val index = buffer.getInt()
        if (index < 0) throw ProtocolException("Invalid blob chunk index: $index")
        return BlobChunk(blobId, index, payload.copyOfRange(CHUNK_HEADER_BYTES, payload.size))
    }

    /** Lower-case hex SHA-256 of [bytes]. Hand-rolled: `java.util.HexFormat` needs API 34 on Android. */
    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lower-case hex of [digest], the form [sha256Hex] and the blob frames use. */
    fun hex(digest: ByteArray): String {
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(hex)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
