package com.company.tap.driver.engine

import com.company.tap.protocol.ArtifactInfo
import com.company.tap.protocol.BlobEnd
import com.company.tap.protocol.BlobFrames
import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.MAX_BLOB_CHUNK_BYTES
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * One artifact blob queued for the writer lane. The executor blocks in [await] while the
 * writer streams it; between chunks the writer honors cancellation and the request deadline,
 * so a `CANCEL` stops an active blob at the next chunk boundary (plan §"Blob transfer").
 */
class BlobTransfer internal constructor(
    internal val command: Command,
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

    fun start(): BlobStart = BlobStart(blobId.toString(), mediaType, byteCount, sha256)

    fun end(): BlobEnd = BlobEnd(blobId.toString(), byteCount, sha256)

    fun chunkPayload(index: Int): ByteArray {
        val offset = index * MAX_BLOB_CHUNK_BYTES
        val length = minOf(MAX_BLOB_CHUNK_BYTES, bytes.size - offset)
        return BlobFrames.encodeChunk(blobId, index, bytes, offset, length)
    }

    fun artifactInfo(width: Int? = null, height: Int? = null): ArtifactInfo =
        ArtifactInfo(blobId.toString(), mediaType, byteCount, sha256, width, height)

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
