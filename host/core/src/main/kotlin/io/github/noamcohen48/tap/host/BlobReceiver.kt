package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.MAX_ARTIFACT_BYTES
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import java.security.MessageDigest

/**
 * Host-side reassembly of one request's blob. Every frame is checked against the announced
 * identity, order, length, and SHA-256; the first violation sticks as [failureDetail] and the
 * bytes are discarded, so a caller never sees a partial or corrupted artifact. Chunks are copied
 * once, into a buffer of the announced size, and hashed as they arrive.
 */
internal class BlobReceiver(private val start: BlobStart) {
    private var buffer: ByteArray? = null
    private var received = 0
    private val digest = MessageDigest.getInstance("SHA-256")
    private var nextIndex = 0
    var failureDetail: String? = null
        private set
    var complete = false
        private set

    /** The verified bytes, only once [complete] and without failure. Not copied: callers hand it on. */
    val bytes: ByteArray? get() = if (complete && failureDetail == null) buffer else null

    init {
        if (start.totalLength !in 0..MAX_ARTIFACT_BYTES) {
            fail(ErrorDetail.ARTIFACT_TOO_LARGE)
        } else {
            buffer = ByteArray(start.totalLength.toInt())
        }
    }

    fun chunk(payload: ByteArray) {
        val target = buffer
        if (failureDetail != null || target == null) return
        val chunk = try {
            BlobFrames.decodeChunk(payload)
        } catch (_: Exception) {
            return fail(ErrorDetail.BLOB_MALFORMED)
        }
        when {
            complete || chunk.blobId.toString() != start.blobId -> fail(ErrorDetail.BLOB_UNEXPECTED)
            chunk.index != nextIndex -> fail(ErrorDetail.BLOB_OUT_OF_ORDER)
            received.toLong() + chunk.data.size > start.totalLength -> fail(ErrorDetail.BLOB_LENGTH_MISMATCH)
            else -> {
                chunk.data.copyInto(target, received)
                digest.update(chunk.data)
                received += chunk.data.size
                nextIndex += 1
            }
        }
    }

    fun end(end: BlobEnd) {
        if (failureDetail != null) return
        when {
            complete || end.blobId != start.blobId -> fail(ErrorDetail.BLOB_UNEXPECTED)
            end.byteCount != start.totalLength || received.toLong() != start.totalLength ->
                fail(ErrorDetail.BLOB_LENGTH_MISMATCH)
            end.sha256 != start.sha256 || BlobFrames.hex(digest.digest()) != start.sha256 ->
                fail(ErrorDetail.BLOB_CHECKSUM_MISMATCH)
            else -> complete = true
        }
    }

    private fun fail(detail: String) {
        failureDetail = detail
        buffer = null
    }
}
