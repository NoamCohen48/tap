package com.company.tap.driver

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.company.tap.driver.engine.BlobTransfer
import com.company.tap.driver.engine.CommandContext
import com.company.tap.protocol.Bounds
import com.company.tap.protocol.DeviceInfo
import com.company.tap.protocol.Direction
import com.company.tap.protocol.ElementSnapshot
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.KEYCODE_BACK
import com.company.tap.protocol.KEYCODE_HOME
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

    /** Polls presence on the device until it equals [expected] (`WAIT_VISIBLE` / `WAIT_GONE`). */
    fun waitVisible(context: CommandContext, request: Request, expected: Boolean): Response {
        val selector = requireNotNull(request.selector)
        return pollUntil(context) { objects.hasObject(selector) == expected }
    }

    /** Waits until the named package owns a focused window. */
    fun waitAppVisible(context: CommandContext, request: Request): Response {
        val packageName = requireNotNull(request.packageName)
        return pollUntil(context) { device.findWindow(By.Window.pkg(packageName).focused(true)) != null }
    }

    private inline fun pollUntil(context: CommandContext, condition: () -> Boolean): Response {
        var satisfied: Boolean
        do {
            context.checkCancelled()
            satisfied = condition()
            if (!satisfied && context.remainingMs() > 0) context.sleep(50)
        } while (!satisfied && !context.isExpired())
        return if (satisfied) Response(true, value = true, durationMs = context.elapsed())
        else Response.failure(ErrorCode.WAIT_TIMEOUT, value = false, durationMs = context.elapsed())
    }

    fun deviceInfo(context: CommandContext): Response = Response(
        true,
        durationMs = context.elapsed(),
        deviceInfo = DeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            displayWidth = device.displayWidth,
            displayHeight = device.displayHeight,
            displayRotation = device.displayRotation,
            currentPackage = device.currentPackageName,
        ),
    )

    /** Key injection is a mutation: it passes the gate and is never replayed. */
    fun pressKey(context: CommandContext, request: Request): Response {
        val keyCode = requireNotNull(request.keyCode)
        context.checkpoint()
        context.markMutationStarted()
        val injected = when (keyCode) {
            KEYCODE_BACK -> device.pressBack()
            KEYCODE_HOME -> device.pressHome()
            else -> device.pressKeyCode(keyCode)
        }
        return if (injected) Response(true, value = true, durationMs = context.elapsed())
        else Response.failure(ErrorCode.ACTION_REJECTED, message = "Key $keyCode was not injected", durationMs = context.elapsed())
    }

    /** Reads one element's state at this instant; the object is recycled before returning. */
    fun snapshot(context: CommandContext, request: Request): Response {
        val element = resolveOrFail(context, requireNotNull(request.selector)) { return it }
        val snapshot = try {
            val bounds = element.visibleBounds
            ElementSnapshot(
                className = element.className,
                packageName = element.applicationPackage,
                resourceName = element.resourceName,
                text = element.displayedText(),
                contentDescription = element.contentDescription,
                hint = element.hint,
                bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                checkable = element.isCheckable,
                checked = element.isChecked,
                clickable = element.isClickable,
                enabled = element.isEnabled,
                focusable = element.isFocusable,
                focused = element.isFocused,
                longClickable = element.isLongClickable,
                scrollable = element.isScrollable,
                selected = element.isSelected,
                childCount = element.childCount,
            )
        } finally {
            element.recycle()
        }
        return Response(true, snapshot = snapshot, durationMs = context.elapsed())
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

            val initialText = element.displayedText().orEmpty()
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
            current.displayedText().orEmpty()
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

/**
 * The node's text without a displayed hint. An empty `EditText` reports its hint as `text`
 * (with `isShowingHintText` set) on API 26+, which would make a successful clear look like a
 * mismatch and leak the hint into snapshots.
 */
internal fun UiObject2.displayedText(): String? {
    val node = accessibilityNodeInfo
    return if (node.isShowingHintText) null else node.text?.toString()
}
