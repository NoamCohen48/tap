package com.company.tap.driver

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.company.tap.driver.engine.BlobTransfer
import com.company.tap.driver.engine.CommandContext
import com.company.tap.protocol.Direction
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.MAX_ARTIFACT_BYTES
import com.company.tap.protocol.MAX_CONTROL_PAYLOAD
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

internal class UiAutomationCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
    private val faults: FaultController,
) {
    fun tap(context: CommandContext, socket: Socket, request: Request): Response =
        gesture(context, request) { element ->
            faults.injectLateUninterruptible(socket, request, context.requestId)
            element.click()
            faults.holdAfterMutation(request, context.requestId)
            if (faults.inject(FaultPoint.AFTER_MUTATION, request, context.requestId)) {
                throw InjectedTransportLoss()
            }
            true
        }

    fun longTap(context: CommandContext, request: Request): Response =
        gesture(context, request) { element ->
            element.longClick()
            true
        }

    /** Finger gesture across the element; the value is always true once injected. */
    fun swipe(context: CommandContext, request: Request): Response =
        gesture(context, request) { element ->
            element.swipe(direction(request), percent(request))
            true
        }

    /** One scroll segment of a container. The value reports whether the content moved. */
    fun scroll(context: CommandContext, request: Request): Response =
        gesture(context, request, interactable = UiObject2::isScrollable) { element ->
            element.scroll(direction(request), percent(request))
        }

    /**
     * Shared shape of every single-target gesture: checkpoint, resolve exactly one target,
     * pass the mutation gate, act, recycle. Everything after the gate is definitive.
     */
    private inline fun gesture(
        context: CommandContext,
        request: Request,
        interactable: (UiObject2) -> Boolean = { true },
        action: (UiObject2) -> Boolean,
    ): Response {
        context.checkpoint()
        val element = resolveOrFail(context, requireNotNull(request.selector)) { return it }
        val value = try {
            if (!interactable(element)) {
                return Response.failure(ErrorCode.NOT_INTERACTABLE, durationMs = context.elapsed())
            }
            // Atomically refuses on cancel, deadline, or a poisoned session; otherwise
            // cancellation is ignored from here on and the gesture result is definitive.
            context.markMutationStarted()
            action(element)
        } finally {
            element.recycle()
        }
        return Response(true, value = value, durationMs = context.elapsed())
    }

    fun waitVisible(context: CommandContext, request: Request): Response {
        require(request.timeoutMs >= 0) { "timeoutMs must not be negative" }
        val selector = requireNotNull(request.selector)
        var found: Boolean
        do {
            context.checkCancelled()
            found = objects.hasObject(selector)
            if (!found && context.remainingMs() > 0) context.sleep(50)
        } while (!found && !context.isExpired())
        return if (found) Response(true, value = true, durationMs = context.elapsed())
        else Response.failure(ErrorCode.WAIT_TIMEOUT, value = false, durationMs = context.elapsed())
    }

    fun dumpHierarchy(started: Long): Response {
        val hierarchy = try {
            LimitedOutputStream(MAX_HIERARCHY_BYTES).use { output ->
                device.dumpWindowHierarchy(output)
                output.content()
            }
        } catch (_: OutputLimitExceeded) {
            return Response.failure(
                ErrorCode.PAYLOAD_TOO_LARGE,
                message = "Hierarchy exceeded $MAX_HIERARCHY_BYTES bytes",
                durationMs = elapsed(started),
            )
        }
        return Response(true, text = hierarchy, durationMs = elapsed(started))
    }

    /**
     * PNG screenshot streamed as a blob ahead of the response. Pure query: an aborted transfer
     * reports `CANCELLED`/`DEADLINE_EXCEEDED`, never a partial artifact.
     */
    fun screenshot(context: CommandContext): Response {
        context.checkpoint()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: return Response.failure(
                ErrorCode.ARTIFACT_TRANSFER_FAILED,
                detail = ErrorDetail.CAPTURE_FAILED,
                durationMs = context.elapsed(),
            )
        val width = bitmap.width
        val height = bitmap.height
        val png = try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        if (png.size > MAX_ARTIFACT_BYTES) {
            return Response.failure(
                ErrorCode.ARTIFACT_TRANSFER_FAILED,
                detail = ErrorDetail.ARTIFACT_TOO_LARGE,
                durationMs = context.elapsed(),
            )
        }
        context.checkpoint()
        val (blob, outcome) = context.transferBlob("image/png", png)
        return when (outcome) {
            BlobTransfer.Outcome.COMPLETED -> Response(
                true,
                durationMs = context.elapsed(),
                artifact = blob.artifactInfo(width, height),
            )
            BlobTransfer.Outcome.CANCELLED -> Response.failure(ErrorCode.CANCELLED, durationMs = context.elapsed())
            BlobTransfer.Outcome.DEADLINE_EXCEEDED ->
                Response.failure(ErrorCode.DEADLINE_EXCEEDED, durationMs = context.elapsed())
            BlobTransfer.Outcome.WRITE_FAILED -> Response.failure(
                ErrorCode.ARTIFACT_TRANSFER_FAILED,
                detail = ErrorDetail.BLOB_INCOMPLETE,
                durationMs = context.elapsed(),
            )
        }
    }

    fun setText(context: CommandContext, request: Request): Response =
        editText(context, request, expected = requireNotNull(request.inputText)) { element, expected ->
            element.text = expected
        }

    fun clearText(context: CommandContext, request: Request): Response =
        editText(context, request, expected = "") { element, _ -> element.clear() }

    /** Accessibility `ACTION_SET_TEXT` shape: resolve, require editable, gate, set, verify. */
    private inline fun editText(
        context: CommandContext,
        request: Request,
        expected: String,
        mutate: (UiObject2, String) -> Unit,
    ): Response {
        val selector = requireNotNull(request.selector)
        context.checkpoint()
        val element = resolveOrFail(context, selector) { return it }
        try {
            if (!element.accessibilityNodeInfo.isEditable) {
                return Response.failure(ErrorCode.NOT_INTERACTABLE, durationMs = context.elapsed())
            }
            context.markMutationStarted()
            mutate(element, expected)
        } finally {
            element.recycle()
        }
        val verificationDeadline = minOf(context.deadlineMs, SystemClock.elapsedRealtime() + 1_000)
        var changed: Boolean
        do {
            changed = currentText(selector) == expected
            if (!changed) Thread.sleep(25)
        } while (!changed && SystemClock.elapsedRealtime() < verificationDeadline)
        return if (changed) Response(true, value = true, durationMs = context.elapsed())
        else Response.failure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.TEXT_MISMATCH, durationMs = context.elapsed())
    }

    fun typeText(context: CommandContext, request: Request): Response {
        val selector = requireNotNull(request.selector)
        context.checkpoint()
        val element = resolveOrFail(context, selector) { return it }
        val deadline = context.deadlineMs
        try {
            if (!element.accessibilityNodeInfo.isEditable) {
                return Response.failure(ErrorCode.NOT_INTERACTABLE, durationMs = context.elapsed())
            }
            if (context.isExpired()) {
                return Response.failure(ErrorCode.DEADLINE_EXCEEDED, durationMs = context.elapsed())
            }

            val text = requireNotNull(request.inputText)
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?: return Response.failure(
                    ErrorCode.INVALID_REQUEST,
                    detail = ErrorDetail.UNSUPPORTED_CHARACTERS,
                    message = "Text cannot be represented as Android key events",
                    durationMs = context.elapsed(),
                )

            val initialNode = element.accessibilityNodeInfo
            val initialText = if (initialNode.isShowingHintText) "" else initialNode.text?.toString().orEmpty()
            // The focusing click is the first injected input; everything after it is definitive.
            context.markMutationStarted()
            element.click()
            while (!isFocused(selector)) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    return Response.failure(
                        ErrorCode.ACTION_REJECTED,
                        detail = ErrorDetail.FOCUS_TIMEOUT,
                        durationMs = context.elapsed(),
                    )
                }
                Thread.sleep(minOf(25, remaining))
            }

            if (context.isExpired()) return deadlineAfterFocus(context)
            device.waitForIdle(minOf(3_000L, context.remainingMs()))
            val revalidation = objects.resolve(selector)
            val revalidated = revalidation.element ?: return staleTarget(revalidation, context)
            val stillFocused = try {
                revalidated.isFocused
            } finally {
                revalidated.recycle()
            }
            if (!stillFocused) {
                return Response.failure(
                    ErrorCode.STALE_DURING_COMMAND,
                    detail = ErrorDetail.FOCUS_LOST,
                    durationMs = context.elapsed(),
                )
            }
            if (context.isExpired()) return deadlineAfterFocus(context)

            val pressedKeys = mutableSetOf<Int>()
            var rejected = false
            var cleanupFailed = false
            var deadlineExpired = false
            try {
                for (event in events) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        deadlineExpired = true
                        break
                    }
                    val timedEvent = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), event.repeatCount)
                    val injected = instrumentation.uiAutomation.injectInputEvent(timedEvent, false)
                    if (event.action == KeyEvent.ACTION_DOWN && injected) pressedKeys += event.keyCode
                    if (event.action == KeyEvent.ACTION_UP && injected) pressedKeys -= event.keyCode
                    if (!injected) {
                        rejected = true
                        break
                    }
                }
            } finally {
                pressedKeys.forEach { keyCode ->
                    var released = false
                    repeat(3) {
                        if (!released) {
                            released = instrumentation.uiAutomation.injectInputEvent(
                                KeyEvent(KeyEvent.ACTION_UP, keyCode),
                                false,
                            )
                        }
                    }
                    if (!released) cleanupFailed = true
                }
            }
            if (cleanupFailed) {
                return Response.failure(
                    ErrorCode.INDETERMINATE,
                    detail = ErrorDetail.KEY_RELEASE_FAILED,
                    durationMs = context.elapsed(),
                )
            }
            if (deadlineExpired) {
                return Response.failure(
                    ErrorCode.ACTION_REJECTED,
                    detail = ErrorDetail.PARTIAL_INPUT,
                    durationMs = context.elapsed(),
                )
            }
            if (rejected) {
                return Response.failure(ErrorCode.ACTION_REJECTED, durationMs = context.elapsed())
            }

            do {
                if (currentText(selector) == initialText + text) {
                    return Response(true, value = true, durationMs = context.elapsed())
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) Thread.sleep(minOf(25, remaining))
            } while (SystemClock.elapsedRealtime() < deadline)
            return Response.failure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.TEXT_MISMATCH, durationMs = context.elapsed())
        } finally {
            element.recycle()
        }
    }

    fun scrollUntil(context: CommandContext, request: Request): Response {
        require(request.timeoutMs >= 0) { "timeoutMs must not be negative" }
        val target = requireNotNull(request.selector)
        val containerSelector = requireNotNull(request.containerSelector)
        val direction = direction(request)
        val percent = percent(request)
        var noProgressAttempts = 0

        repeat(request.maxScrolls) {
            context.checkCancelled()
            if (context.isExpired()) return waitTimeout(context)
            val resolved = objects.resolve(containerSelector)
            val scrollable = resolved.element ?: return Response.failure(
                requireNotNull(resolved.errorCode),
                message = "Scroll container was not uniquely resolved",
                durationMs = context.elapsed(),
            )
            val before = try {
                if (objects.containerHasObject(scrollable, target)) {
                    return Response(true, value = true, durationMs = context.elapsed())
                }
                val fingerprint = visibleFingerprint(scrollable)
                if (context.isExpired()) return waitTimeout(context)
                // The first gesture makes the command definitive; later cancels are ignored.
                context.markMutationStarted()
                scrollable.scroll(direction, percent)
                fingerprint
            } finally {
                scrollable.recycle()
            }
            val remaining = context.remainingMs()
            if (remaining <= 0) return waitTimeout(context)
            Thread.sleep(minOf(100, remaining))
            val afterResolution = objects.resolve(containerSelector)
            val after = afterResolution.element ?: return staleTarget(
                afterResolution,
                context,
                "Scroll container was not uniquely resolved after scrolling",
            )
            val (found, afterFingerprint) = try {
                objects.containerHasObject(after, target) to visibleFingerprint(after)
            } finally {
                after.recycle()
            }
            if (found) return Response(true, value = true, durationMs = context.elapsed())
            noProgressAttempts = if (afterFingerprint == before) noProgressAttempts + 1 else 0
            if (noProgressAttempts >= 2) {
                return Response.failure(
                    ErrorCode.NOT_FOUND,
                    detail = ErrorDetail.END_REACHED,
                    value = false,
                    durationMs = context.elapsed(),
                )
            }
        }

        if (context.isExpired()) return waitTimeout(context)
        val finalResolution = objects.resolve(containerSelector)
        val finalContainer = finalResolution.element ?: return staleTarget(
            finalResolution,
            context,
            "Scroll container was not uniquely resolved after the final attempt",
        )
        val found = try {
            objects.containerHasObject(finalContainer, target)
        } finally {
            finalContainer.recycle()
        }
        return if (found) Response(true, value = true, durationMs = context.elapsed())
        else Response.failure(
            ErrorCode.NOT_FOUND,
            detail = ErrorDetail.MAX_SCROLLS,
            value = false,
            durationMs = context.elapsed(),
        )
    }

    /** Pre-mutation resolution; `NOT_FOUND`/`AMBIGUOUS` here still promise no input. */
    private inline fun resolveOrFail(
        context: CommandContext,
        selector: Selector,
        fail: (Response) -> Nothing,
    ): UiObject2 {
        val resolved = objects.resolve(selector)
        return resolved.element
            ?: fail(Response.failure(requireNotNull(resolved.errorCode), durationMs = context.elapsed()))
    }

    private fun currentText(selector: Selector): String? {
        val current = objects.resolve(selector).element ?: return null
        return try {
            current.text.orEmpty()
        } finally {
            current.recycle()
        }
    }

    private fun isFocused(selector: Selector): Boolean {
        val current = objects.resolve(selector).element ?: return false
        return try {
            current.isFocused
        } finally {
            current.recycle()
        }
    }

    private fun direction(request: Request): androidx.test.uiautomator.Direction =
        when (request.direction ?: Direction.DOWN) {
            Direction.UP -> androidx.test.uiautomator.Direction.UP
            Direction.DOWN -> androidx.test.uiautomator.Direction.DOWN
            Direction.LEFT -> androidx.test.uiautomator.Direction.LEFT
            Direction.RIGHT -> androidx.test.uiautomator.Direction.RIGHT
        }

    private fun percent(request: Request): Float = request.distancePercent / 100f

    private fun waitTimeout(context: CommandContext): Response =
        Response.failure(ErrorCode.WAIT_TIMEOUT, value = false, durationMs = context.elapsed())

    private fun deadlineAfterFocus(context: CommandContext): Response = Response.failure(
        ErrorCode.ACTION_REJECTED,
        detail = ErrorDetail.DEADLINE_AFTER_FOCUS,
        durationMs = context.elapsed(),
    )

    /**
     * A target that resolved before the mutation but not after it. `NOT_FOUND`/`AMBIGUOUS`
     * promise no mutation, so post-mutation cardinality failures report
     * `STALE_DURING_COMMAND` with the cardinality as detail.
     */
    private fun staleTarget(
        resolution: UiObjectAccess.Resolution,
        context: CommandContext,
        message: String? = null,
    ): Response = Response.failure(
        ErrorCode.STALE_DURING_COMMAND,
        detail = when (requireNotNull(resolution.errorCode)) {
            ErrorCode.AMBIGUOUS -> ErrorDetail.TARGET_AMBIGUOUS
            else -> ErrorDetail.TARGET_GONE
        },
        message = message,
        durationMs = context.elapsed(),
    )

    private fun visibleFingerprint(root: UiObject2): String = buildString {
        fun appendNode(node: UiObject2, depth: Int) {
            if (depth > 32) return
            append(node.className).append('|')
            append(node.resourceName).append('|')
            append(node.text).append('|')
            append(node.contentDescription).append(';')
            node.children.forEach { child ->
                try {
                    appendNode(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        appendNode(root, 0)
    }
}

internal fun CommandContext.elapsed(): Long = nowMs() - acceptedAtMs

private const val MAX_HIERARCHY_BYTES = (MAX_CONTROL_PAYLOAD - 1_024) / 2

private class LimitedOutputStream(private val limit: Int) : OutputStream() {
    private val output = ByteArrayOutputStream()

    override fun write(value: Int) {
        ensureCapacity(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
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

private class OutputLimitExceeded : IOException()
