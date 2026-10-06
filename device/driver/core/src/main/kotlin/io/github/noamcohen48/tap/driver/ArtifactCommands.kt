package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.graphics.Bitmap
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.driver.engine.BlobTransfer
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.MAX_ARTIFACT_BYTES
import io.github.noamcohen48.tap.protocol.MAX_CONTROL_PAYLOAD
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Diagnostic artifacts: the screenshot blob and the (diagnostic-only) hierarchy dump. */
internal class ArtifactCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
) {
    fun dumpHierarchy(): String =
        try {
            LimitedOutputStream(MAX_HIERARCHY_BYTES).use { output ->
                HierarchyDump.write(device, output)
                output.content()
            }
        } catch (_: OutputLimitExceeded) {
            throw CommandFailure(ErrorCode.ERR_PAYLOAD_TOO_LARGE, message = "Hierarchy exceeded $MAX_HIERARCHY_BYTES bytes")
        }

    /**
     * PNG screenshot streamed as a blob ahead of the response. Pure query: an aborted transfer
     * reports `CANCELLED`/`DEADLINE_EXCEEDED`, never a partial artifact.
     */
    fun screenshot(context: CommandContext): ArtifactInfo {
        context.checkpoint()
        val bitmap =
            instrumentation.uiAutomation.takeScreenshot()
                ?: throw CommandFailure(ErrorCode.ERR_ARTIFACT_TRANSFER_FAILED, detail = ErrorDetail.CAPTURE_FAILED)
        val width = bitmap.width
        val height = bitmap.height
        val png =
            try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        if (png.size > MAX_ARTIFACT_BYTES) {
            throw CommandFailure(ErrorCode.ERR_ARTIFACT_TRANSFER_FAILED, detail = ErrorDetail.ARTIFACT_TOO_LARGE)
        }
        context.checkpoint()
        val (blob, outcome) = context.transferBlob("image/png", png)
        return when (outcome) {
            BlobTransfer.Outcome.COMPLETED -> blob.artifactInfo(width, height)
            BlobTransfer.Outcome.CANCELLED -> throw CommandFailure(ErrorCode.ERR_CANCELLED)
            BlobTransfer.Outcome.DEADLINE_EXCEEDED -> throw CommandFailure(ErrorCode.ERR_DEADLINE_EXCEEDED)
            BlobTransfer.Outcome.WRITE_FAILED ->
                throw CommandFailure(ErrorCode.ERR_ARTIFACT_TRANSFER_FAILED, detail = ErrorDetail.BLOB_INCOMPLETE)
        }
    }

    private companion object {
        /** The dump travels as a UTF-8 string inside one control frame, with headroom for the envelope. */
        const val MAX_HIERARCHY_BYTES = (MAX_CONTROL_PAYLOAD - 1_024) / 2
    }
}

/** An in-memory stream that refuses to grow past [limit] bytes ([OutputLimitExceeded]). */
internal class LimitedOutputStream(
    private val limit: Int,
) : OutputStream() {
    private val output = ByteArrayOutputStream()

    override fun write(value: Int) {
        ensureCapacity(1)
        output.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        ensureCapacity(length)
        output.write(bytes, offset, length)
    }

    fun content(): String = output.toString(Charsets.UTF_8.name())

    private fun ensureCapacity(additionalBytes: Int) {
        if (additionalBytes < 0 || output.size() > limit - additionalBytes) {
            throw OutputLimitExceeded()
        }
    }
}

internal class OutputLimitExceeded : IOException()
