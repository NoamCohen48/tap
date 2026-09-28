package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.MAX_BLOB_CHUNK_BYTES
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * One artifact blob queued for the writer lane. The executor blocks in [await] while the
 * writer streams it; between chunks the writer honors cancellation and the request deadline,
 * so a `CANCEL` stops an active blob at the next chunk boundary (plan §"Blob transfer").
 */
class BlobTransfer internal constructor(
    internal val command: PendingCommand,
    val mediaType: String,
    private val bytes: ByteArray,
) {
    enum class Outcome { COMPLETED, CANCELLED, DEADLINE_EXCEEDED, WRITE_FAILED }

    val blobId: UUID = UUID.randomUUID()
    val sha256: String = BlobFrames.sha256Hex(bytes)
    val byteCount: Long get() = bytes.size.toLong()
    val chunkCount: Int get() = (bytes.size + MAX_BLOB_CHUNK_BYTES - 1) / MAX_BLOB_CHUNK_BYTES

    private val done = CountDownLatch(1)
    @Volatile private var outcome: Outcome? = null

    fun start(): BlobStart =
        BlobStart.newBuilder()
            .setBlobId(blobId.toString())
            .setMediaType(mediaType)
            .setTotalLength(byteCount)
            .setSha256(sha256)
            .build()

    fun end(): BlobEnd = BlobEnd.newBuilder().setBlobId(blobId.toString()).setByteCount(byteCount).setSha256(sha256).build()

    fun chunkPayload(index: Int): ByteArray {
        val offset = index * MAX_BLOB_CHUNK_BYTES
        val length = minOf(MAX_BLOB_CHUNK_BYTES, bytes.size - offset)
        return BlobFrames.encodeChunk(blobId, index, bytes, offset, length)
    }

    fun artifactInfo(width: Int? = null, height: Int? = null): ArtifactInfo =
        ArtifactInfo.newBuilder()
            .setBlobId(blobId.toString())
            .setMediaType(mediaType)
            .setByteCount(byteCount)
            .setSha256(sha256)
            .apply {
                width?.let { setWidth(it) }
                height?.let { setHeight(it) }
            }
            .build()

    /** Blocks the executor until the writer finished, aborted, or failed. */
    fun await(): Outcome {
        done.await()
        return requireNotNull(outcome)
    }

    internal fun finish(result: Outcome) {
        outcome = result
        done.countDown()
    }
}
