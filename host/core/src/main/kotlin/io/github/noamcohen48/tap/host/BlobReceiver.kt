package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.MAX_ARTIFACT_BYTES
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import java.io.ByteArrayOutputStream

/**
 * Host-side reassembly of one request's blob. Every frame is checked against the announced
 * identity, order, length, and SHA-256; the first violation sticks as [failureDetail] and the
 * bytes are discarded, so a caller never sees a partial or corrupted artifact.
 */
internal class BlobReceiver(private val start: BlobStart) {
    private val buffer = ByteArrayOutputStream()
    private var nextIndex = 0
    var failureDetail: String? = null
        private set
    var complete = false
        private set

    /** The verified bytes, only once [complete] and without failure. */
    val bytes: ByteArray? get() = if (complete && failureDetail == null) buffer.toByteArray() else null

    init {
        if (start.totalLength !in 0..MAX_ARTIFACT_BYTES) fail(ErrorDetail.ARTIFACT_TOO_LARGE)
    }

    fun chunk(payload: ByteArray) {
        if (failureDetail != null) return
        val chunk = try {
            BlobFrames.decodeChunk(payload)
        } catch (_: Exception) {
            return fail(ErrorDetail.BLOB_OUT_OF_ORDER)
        }
        when {
            complete || chunk.blobId.toString() != start.blobId -> fail(ErrorDetail.BLOB_UNEXPECTED)
            chunk.index != nextIndex -> fail(ErrorDetail.BLOB_OUT_OF_ORDER)
            buffer.size().toLong() + chunk.data.size > start.totalLength -> fail(ErrorDetail.BLOB_LENGTH_MISMATCH)
            else -> {
                buffer.write(chunk.data)
                nextIndex += 1
            }
        }
    }

    fun end(end: BlobEnd) {
        if (failureDetail != null) return
        when {
            complete || end.blobId != start.blobId -> fail(ErrorDetail.BLOB_UNEXPECTED)
            end.byteCount != start.totalLength || buffer.size().toLong() != start.totalLength ->
                fail(ErrorDetail.BLOB_LENGTH_MISMATCH)
            end.sha256 != start.sha256 || BlobFrames.sha256Hex(buffer.toByteArray()) != start.sha256 ->
                fail(ErrorDetail.BLOB_CHECKSUM_MISMATCH)
            else -> complete = true
        }
    }

    private fun fail(detail: String) {
        failureDetail = detail
        buffer.reset()
    }
}
