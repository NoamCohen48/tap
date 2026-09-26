package io.github.noamcohen48.tap.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

/** Payload bytes per `BLOB_CHUNK` frame, excluding the 20-byte chunk header. */
const val MAX_BLOB_CHUNK_BYTES = 256 * 1024

/** Upper bound for one artifact blob. */
const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024

/** `BLOB_START` payload; the frame's request ID is the owning request. */
@Serializable
data class BlobStart(
    val blobId: String,
    val mediaType: String,
    val totalLength: Long,
    val sha256: String,
)

/** `BLOB_END` payload; repeats the identity so a truncated stream is detectable. */
@Serializable
data class BlobEnd(
    val blobId: String,
    val byteCount: Long,
    val sha256: String,
)

/** Artifact metadata carried in the terminal [Response] of an artifact-producing request. */
@Serializable
data class ArtifactInfo(
    val blobId: String,
    val mediaType: String,
    val byteCount: Long,
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
)

data class BlobChunk(val blobId: UUID, val index: Int, val data: ByteArray)

/**
 * `BLOB_CHUNK` layout: 16 raw UUID bytes, a 4-byte unsigned big-endian chunk index, then the
 * chunk bytes (plan §"Blob transfer"). Chunks are contiguous and ordered per blob.
 */
object BlobFrames {
    const val CHUNK_HEADER_BYTES = 20

    fun encodeChunk(blobId: UUID, index: Int, data: ByteArray, offset: Int, length: Int): ByteArray {
        require(index >= 0) { "Chunk index must not be negative" }
        require(length in 1..MAX_BLOB_CHUNK_BYTES) { "Chunk length $length is out of range" }
        return ByteBuffer.allocate(CHUNK_HEADER_BYTES + length)
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

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
