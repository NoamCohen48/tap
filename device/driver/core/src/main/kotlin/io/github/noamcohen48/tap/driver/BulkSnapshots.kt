package io.github.noamcohen48.tap.driver

import com.google.protobuf.CodedOutputStream
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ElementSnapshotList
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.MAX_SNAPSHOT_LIST_BYTES
import io.github.noamcohen48.tap.protocol.MAX_SNAPSHOT_MATCHES

/** Owns every resolved object until the whole value-only result is built, including on failure. */
internal fun <T> bulkSnapshots(
    elements: List<T>,
    snapshot: (T) -> ElementSnapshot,
    recycle: (T) -> Unit,
    checkpoint: () -> Unit,
): ElementSnapshotList {
    try {
        checkpoint()
        if (elements.size > MAX_SNAPSHOT_MATCHES) {
            throw CommandFailure(ErrorCode.ERR_PAYLOAD_TOO_LARGE, message = "Bulk read exceeded $MAX_SNAPSHOT_MATCHES matches")
        }
        val result = ElementSnapshotList.newBuilder()
        var bytes = 0L
        for (element in elements) {
            checkpoint()
            val value = snapshot(element)
            bytes += CodedOutputStream.computeMessageSize(1, value)
            if (bytes > MAX_SNAPSHOT_LIST_BYTES) {
                throw CommandFailure(ErrorCode.ERR_PAYLOAD_TOO_LARGE, message = "Bulk read exceeded $MAX_SNAPSHOT_LIST_BYTES bytes")
            }
            result.addElements(value)
        }
        checkpoint()
        return result.build()
    } finally {
        elements.forEach(recycle)
    }
}
