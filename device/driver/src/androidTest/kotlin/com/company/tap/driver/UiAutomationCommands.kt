package com.company.tap.driver

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.company.tap.driver.engine.BlobTransfer
import com.company.tap.driver.engine.CommandContext
import com.company.tap.protocol.Bounds
import com.company.tap.protocol.ArtifactResult
import com.company.tap.protocol.ClearText
import com.company.tap.protocol.CommandFailure
import com.company.tap.protocol.DeviceInfoResult
import com.company.tap.protocol.Done
import com.company.tap.protocol.LongTap
import com.company.tap.protocol.Moved
import com.company.tap.protocol.PressKey
import com.company.tap.protocol.Scroll
import com.company.tap.protocol.ScrollUntil
import com.company.tap.protocol.SetText
import com.company.tap.protocol.Snapshot
import com.company.tap.protocol.StabilitySignal
import com.company.tap.protocol.SnapshotResult
import com.company.tap.protocol.Swipe
import com.company.tap.protocol.Tap
import com.company.tap.protocol.TextResult
import com.company.tap.protocol.TypeText
import com.company.tap.protocol.WaitAppVisible
import com.company.tap.protocol.WaitScreenStable
import com.company.tap.protocol.DeviceInfo
import com.company.tap.protocol.Direction
import com.company.tap.protocol.ElementSnapshot
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.KEYCODE_BACK
import com.company.tap.protocol.KEYCODE_HOME
import com.company.tap.protocol.MAX_ARTIFACT_BYTES
import com.company.tap.protocol.MAX_CONTROL_PAYLOAD
import com.company.tap.protocol.Selector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeoutException

internal class UiAutomationCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
    private val faults: FaultController,
) {
    fun tap(context: CommandContext, socket: Socket, command: Tap, generation: Long): Done =
        gesture(context, command.selector) { element ->
            faults.injectLateUninterruptible(socket, command, context.requestId, generation)
            element.click()
            faults.holdAfterMutation(command, context.requestId, generation)
            if (faults.inject(FaultPoint.AFTER_MUTATION, command, context.requestId, generation)) {
                throw InjectedTransportLoss()
            }
            Done
        }

    fun longTap(context: CommandContext, command: LongTap): Done =
        gesture(context, command.selector) { element ->
            element.longClick()
            Done
        }

    /** Finger gesture across the element; always reports moved once injected. */
    fun swipe(context: CommandContext, command: Swipe): Moved =
        gesture(context, command.selector) { element ->
            element.swipe(direction(command.direction), percent(command.distancePercent))
            Moved(true)
        }

    /** One scroll segment of a container; reports whether the content moved. */
    fun scroll(context: CommandContext, command: Scroll): Moved =
        gesture(context, command.selector, interactable = UiObject2::isScrollable) { element ->
            Moved(element.scroll(direction(command.direction), percent(command.distancePercent)))
        }

    /**
     * Shared shape of every single-target gesture: checkpoint, resolve exactly one target,
     * pass the mutation gate, act, recycle. Everything after the gate is definitive.
     */
    private inline fun <R> gesture(
        context: CommandContext,
        selector: Selector,
        interactable: (UiObject2) -> Boolean = { true },
        action: (UiObject2) -> R,
    ): R {
        context.checkpoint()
        val element = resolve(selector)
        try {
            if (!interactable(element)) throw CommandFailure(ErrorCode.NOT_INTERACTABLE)
            // Atomically refuses on cancel, deadline, or a poisoned session; otherwise
            // cancellation is ignored from here on and the gesture result is definitive.
            context.markMutationStarted()
            return action(element)
        } finally {
            element.recycle()
        }
    }

    /** Polls presence on the device until it equals [expected] ([WaitVisible] / [WaitGone]). */
    fun waitVisible(context: CommandContext, selector: Selector, expected: Boolean): Done =
        pollUntil(context) { objects.hasObject(selector) == expected }

    /** Waits until the named package owns a focused window. */
    fun waitAppVisible(context: CommandContext, command: WaitAppVisible): Done =
        pollUntil(context) { device.findWindow(By.Window.pkg(command.packageName).focused(true)) != null }

    /**
     * [WaitScreenStable]: succeeds once the package's focused window has been quiet for
     * `stableForMs` — no `WINDOW_CONTENT_CHANGED` events from it, an unchanged accessibility
     * tree fingerprint and (down-sampled) pixels. This is an explicit wait a test asks for;
     * no other command settles implicitly. Reads raw accessibility windows so a running
     * animation cannot stall the sampler in UiAutomator's idle wait.
     */
    fun waitScreenStable(context: CommandContext, command: WaitScreenStable): Done {
        val packageName = command.packageName
        val stableFor = command.stableForMs
        val signal = command.signal
        var reference: ScreenSample? = null
        var stableSince = 0L
        var windowSeen = false
        while (true) {
            context.checkCancelled()
            val now = context.nowMs()
            val sample = sampleScreen(packageName, signal)
            when {
                sample == null -> reference = null
                reference == null || sample.changedFrom(reference) -> {
                    windowSeen = true
                    reference = sample
                    stableSince = now
                }
                now - stableSince >= stableFor -> return Done
            }
            val remaining = context.remainingMs()
            if (remaining <= 0) break
            val slice = minOf(remaining, if (reference == null) 50L else 100L)
            // Event-driven early exit: a content or state change from the package restarts the
            // quiet period. UiAutomation directly, not UiDevice.waitForWindowUpdate, which first
            // runs the implicit idle wait a changing screen can never satisfy.
            val before = context.nowMs()
            if (awaitWindowEvent(packageName, slice)) reference = null
            val waited = context.nowMs() - before
            if (waited < slice) context.sleep(slice - waited)
        }
        throw CommandFailure(
            ErrorCode.WAIT_TIMEOUT,
            detail = if (windowSeen) ErrorDetail.SCREEN_CHANGING else ErrorDetail.APP_NOT_VISIBLE,
        )
    }

    private fun awaitWindowEvent(packageName: String, timeoutMs: Long): Boolean = try {
        instrumentation.uiAutomation.executeAndWaitForEvent(
            {},
            { event ->
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
                    event.packageName?.toString() == packageName
            },
            timeoutMs,
        )
        true
    } catch (timeout: TimeoutException) {
        false
    }

    private class ScreenSample(val treeHash: Long, val pixels: IntArray) {
        fun changedFrom(other: ScreenSample): Boolean {
            if (treeHash != other.treeHash) return true
            if (pixels.size != other.pixels.size) return true
            if (pixels.isEmpty()) return false
            var differing = 0
            for (i in pixels.indices) if (pixels[i] != other.pixels[i]) differing++
            return differing > pixels.size * PIXEL_DIFF_THRESHOLD
        }
    }

    /** The focused application window of [packageName], or null when it has none right now. */
    private fun sampleScreen(packageName: String, signal: StabilitySignal): ScreenSample? {
        val windows = instrumentation.uiAutomation.windows ?: return null
        val window = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused &&
                runCatching { it.root?.packageName?.toString() }.getOrNull() == packageName
        } ?: return null
        val root = window.root ?: return null
        val bounds = Rect().also(window::getBoundsInScreen)
        val treeHash = try {
            if (signal == StabilitySignal.PIXELS) 0L else fingerprint(root)
        } finally {
            runCatching { root.recycle() }
        }
        val pixels = if (signal == StabilitySignal.TREE) IntArray(0) else samplePixels(bounds)
        return ScreenSample(treeHash, pixels)
    }

    private fun fingerprint(root: AccessibilityNodeInfo): Long {
        var hash = 1_469_598_103_934_665_603L
        var nodes = 0
        val bounds = Rect()
        fun mix(value: Any?) {
            hash = (hash xor (value?.hashCode() ?: 0).toLong()) * 1_099_511_628_211L
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 64 || ++nodes > MAX_FINGERPRINT_NODES) return
            node.getBoundsInScreen(bounds)
            mix(node.className); mix(node.viewIdResourceName); mix(node.text?.toString()); mix(node.contentDescription?.toString())
            mix(bounds.left); mix(bounds.top); mix(bounds.right); mix(bounds.bottom)
            mix(node.isVisibleToUser); mix(node.isEnabled); mix(node.isChecked); mix(node.isSelected); mix(node.isFocused)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }
        visit(root, 0)
        return hash
    }

    /** A coarse grid of quantised pixels inside [bounds]; empty when the capture fails. */
    private fun samplePixels(bounds: Rect): IntArray {
        val captured = instrumentation.uiAutomation.takeScreenshot() ?: return IntArray(0)
        val bitmap = if (captured.config == Bitmap.Config.HARDWARE) {
            captured.copy(Bitmap.Config.ARGB_8888, false).also { captured.recycle() }
        } else {
            captured
        }
        try {
            val area = Rect(bounds).apply { intersect(0, 0, bitmap.width, bitmap.height) }
            if (area.isEmpty) return IntArray(0)
            val result = IntArray(PIXEL_GRID_COLUMNS * PIXEL_GRID_ROWS)
            var i = 0
            for (row in 0 until PIXEL_GRID_ROWS) {
                val y = area.top + (area.height() * (2 * row + 1)) / (2 * PIXEL_GRID_ROWS)
                for (column in 0 until PIXEL_GRID_COLUMNS) {
                    val x = area.left + (area.width() * (2 * column + 1)) / (2 * PIXEL_GRID_COLUMNS)
                    // Drop the low bits of each channel so compression/dithering noise does not register.
                    result[i++] = bitmap.getPixel(x, y) and 0x00F0F0F0
                }
            }
            return result
        } finally {
            bitmap.recycle()
        }
    }

    private inline fun pollUntil(context: CommandContext, condition: () -> Boolean): Done {
        var satisfied: Boolean
        do {
            context.checkCancelled()
            satisfied = condition()
            if (!satisfied && context.remainingMs() > 0) context.sleep(50)
        } while (!satisfied && !context.isExpired())
        if (!satisfied) throw CommandFailure(ErrorCode.WAIT_TIMEOUT)
        return Done
    }

    fun deviceInfo(): DeviceInfoResult = DeviceInfoResult(
        DeviceInfo(
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
    fun pressKey(context: CommandContext, command: PressKey): Done {
        val keyCode = command.keyCode
        context.checkpoint()
        context.markMutationStarted()
        val injected = when (keyCode) {
            KEYCODE_BACK -> device.pressBack()
            KEYCODE_HOME -> device.pressHome()
            else -> device.pressKeyCode(keyCode)
        }
        if (!injected) throw CommandFailure(ErrorCode.ACTION_REJECTED, message = "Key $keyCode was not injected")
        return Done
    }

    /** Reads one element's state at this instant; the object is recycled before returning. */
    fun snapshot(command: Snapshot): SnapshotResult {
        val element = resolve(command.selector)
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
        return SnapshotResult(snapshot)
    }

    fun dumpHierarchy(): TextResult {
        val hierarchy = try {
            LimitedOutputStream(MAX_HIERARCHY_BYTES).use { output ->
                device.dumpWindowHierarchy(output)
                output.content()
            }
        } catch (_: OutputLimitExceeded) {
            throw CommandFailure(ErrorCode.PAYLOAD_TOO_LARGE, message = "Hierarchy exceeded $MAX_HIERARCHY_BYTES bytes")
        }
        return TextResult(hierarchy)
    }

    /**
     * PNG screenshot streamed as a blob ahead of the response. Pure query: an aborted transfer
     * reports `CANCELLED`/`DEADLINE_EXCEEDED`, never a partial artifact.
     */
    fun screenshot(context: CommandContext): ArtifactResult {
        context.checkpoint()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: throw CommandFailure(ErrorCode.ARTIFACT_TRANSFER_FAILED, detail = ErrorDetail.CAPTURE_FAILED)
        val width = bitmap.width
        val height = bitmap.height
        val png = try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        if (png.size > MAX_ARTIFACT_BYTES) {
            throw CommandFailure(ErrorCode.ARTIFACT_TRANSFER_FAILED, detail = ErrorDetail.ARTIFACT_TOO_LARGE)
        }
        context.checkpoint()
        val (blob, outcome) = context.transferBlob("image/png", png)
        return when (outcome) {
            BlobTransfer.Outcome.COMPLETED -> ArtifactResult(blob.artifactInfo(width, height))
            BlobTransfer.Outcome.CANCELLED -> throw CommandFailure(ErrorCode.CANCELLED)
            BlobTransfer.Outcome.DEADLINE_EXCEEDED -> throw CommandFailure(ErrorCode.DEADLINE_EXCEEDED)
            BlobTransfer.Outcome.WRITE_FAILED ->
                throw CommandFailure(ErrorCode.ARTIFACT_TRANSFER_FAILED, detail = ErrorDetail.BLOB_INCOMPLETE)
        }
    }

    fun setText(context: CommandContext, command: SetText): Done =
        editText(context, command.selector, expected = command.text) { element, expected ->
            element.text = expected
        }

    fun clearText(context: CommandContext, command: ClearText): Done =
        editText(context, command.selector, expected = "") { element, _ -> element.clear() }

    /** Accessibility `ACTION_SET_TEXT` shape: resolve, require editable, gate, set, verify. */
    private inline fun editText(
        context: CommandContext,
        selector: Selector,
        expected: String,
        mutate: (UiObject2, String) -> Unit,
    ): Done {
        context.checkpoint()
        val element = resolve(selector)
        try {
            if (!element.accessibilityNodeInfo.isEditable) throw CommandFailure(ErrorCode.NOT_INTERACTABLE)
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
        if (!changed) throw CommandFailure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.TEXT_MISMATCH)
        return Done
    }

    fun typeText(context: CommandContext, command: TypeText): Done {
        val selector = command.selector
        context.checkpoint()
        val element = resolve(selector)
        val deadline = context.deadlineMs
        try {
            if (!element.accessibilityNodeInfo.isEditable) throw CommandFailure(ErrorCode.NOT_INTERACTABLE)
            if (context.isExpired()) throw CommandFailure(ErrorCode.DEADLINE_EXCEEDED)

            val text = command.text
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?: throw CommandFailure(
                    ErrorCode.INVALID_REQUEST,
                    detail = ErrorDetail.UNSUPPORTED_CHARACTERS,
                    message = "Text cannot be represented as Android key events",
                )

            val initialText = element.displayedText().orEmpty()
            // The focusing click is the first injected input; everything after it is definitive.
            context.markMutationStarted()
            element.click()
            while (!isFocused(selector)) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw CommandFailure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.FOCUS_TIMEOUT)
                Thread.sleep(minOf(25, remaining))
            }

            if (context.isExpired()) throw deadlineAfterFocus()
            device.waitForIdle(minOf(3_000L, context.remainingMs()))
            val revalidation = objects.resolve(selector)
            val revalidated = revalidation.element ?: throw staleTarget(revalidation)
            val stillFocused = try {
                revalidated.isFocused
            } finally {
                revalidated.recycle()
            }
            if (!stillFocused) throw CommandFailure(ErrorCode.STALE_DURING_COMMAND, detail = ErrorDetail.FOCUS_LOST)
            if (context.isExpired()) throw deadlineAfterFocus()

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
            if (cleanupFailed) throw CommandFailure(ErrorCode.INDETERMINATE, detail = ErrorDetail.KEY_RELEASE_FAILED)
            if (deadlineExpired) throw CommandFailure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.PARTIAL_INPUT)
            if (rejected) throw CommandFailure(ErrorCode.ACTION_REJECTED)

            do {
                if (currentText(selector) == initialText + text) return Done
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) Thread.sleep(minOf(25, remaining))
            } while (SystemClock.elapsedRealtime() < deadline)
            throw CommandFailure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.TEXT_MISMATCH)
        } finally {
            element.recycle()
        }
    }

    fun scrollUntil(context: CommandContext, command: ScrollUntil): Done {
        val target = command.selector
        val containerSelector = command.container
        val direction = direction(command.direction)
        val percent = percent(command.distancePercent)
        var noProgressAttempts = 0

        repeat(command.maxScrolls) {
            context.checkCancelled()
            if (context.isExpired()) throw waitTimeout()
            val resolved = objects.resolve(containerSelector)
            val scrollable = resolved.element ?: throw if (context.mutationStarted) {
                // Once a gesture was sent, losing the container is a stale target, never a
                // "nothing happened" NOT_FOUND/AMBIGUOUS.
                staleTarget(resolved, "Scroll container was not uniquely resolved after scrolling")
            } else {
                CommandFailure(requireNotNull(resolved.errorCode), message = "Scroll container was not uniquely resolved")
            }
            val before = try {
                if (objects.containerHasObject(scrollable, target)) return Done
                val fingerprint = visibleFingerprint(scrollable)
                if (context.isExpired()) throw waitTimeout()
                // Gate once, before the first gesture: from then on the command is definitive,
                // later cancels are ignored and every exit reports that input was sent (the
                // pipeline rewrites non-mutating codes to INDETERMINATE).
                if (!context.mutationStarted) context.markMutationStarted()
                scrollable.scroll(direction, percent)
                fingerprint
            } finally {
                scrollable.recycle()
            }
            val remaining = context.remainingMs()
            if (remaining <= 0) throw waitTimeout()
            Thread.sleep(minOf(100, remaining))
            val afterResolution = objects.resolve(containerSelector)
            val after = afterResolution.element
                ?: throw staleTarget(afterResolution, "Scroll container was not uniquely resolved after scrolling")
            val (found, afterFingerprint) = try {
                objects.containerHasObject(after, target) to visibleFingerprint(after)
            } finally {
                after.recycle()
            }
            if (found) return Done
            noProgressAttempts = if (afterFingerprint == before) noProgressAttempts + 1 else 0
            if (noProgressAttempts >= 2) throw CommandFailure(ErrorCode.NOT_FOUND, detail = ErrorDetail.END_REACHED)
        }

        if (context.isExpired()) throw waitTimeout()
        val finalResolution = objects.resolve(containerSelector)
        val finalContainer = finalResolution.element
            ?: throw staleTarget(finalResolution, "Scroll container was not uniquely resolved after the final attempt")
        val found = try {
            objects.containerHasObject(finalContainer, target)
        } finally {
            finalContainer.recycle()
        }
        if (!found) throw CommandFailure(ErrorCode.NOT_FOUND, detail = ErrorDetail.MAX_SCROLLS)
        return Done
    }

    /** Pre-mutation resolution; `NOT_FOUND`/`AMBIGUOUS` here still promise no input. */
    private fun resolve(selector: Selector): UiObject2 {
        val resolved = objects.resolve(selector)
        return resolved.element ?: throw CommandFailure(requireNotNull(resolved.errorCode))
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

    private fun direction(direction: Direction): androidx.test.uiautomator.Direction = when (direction) {
        Direction.UP -> androidx.test.uiautomator.Direction.UP
        Direction.DOWN -> androidx.test.uiautomator.Direction.DOWN
        Direction.LEFT -> androidx.test.uiautomator.Direction.LEFT
        Direction.RIGHT -> androidx.test.uiautomator.Direction.RIGHT
    }

    private fun percent(distancePercent: Int): Float = distancePercent / 100f

    private fun waitTimeout(): CommandFailure = CommandFailure(ErrorCode.WAIT_TIMEOUT)

    private fun deadlineAfterFocus(): CommandFailure =
        CommandFailure(ErrorCode.ACTION_REJECTED, detail = ErrorDetail.DEADLINE_AFTER_FOCUS)

    /**
     * A target that resolved before the mutation but not after it. `NOT_FOUND`/`AMBIGUOUS`
     * promise no mutation, so post-mutation cardinality failures report
     * `STALE_DURING_COMMAND` with the cardinality as detail.
     */
    private fun staleTarget(resolution: UiObjectAccess.Resolution, message: String? = null): CommandFailure =
        CommandFailure(
            ErrorCode.STALE_DURING_COMMAND,
            detail = when (requireNotNull(resolution.errorCode)) {
                ErrorCode.AMBIGUOUS -> ErrorDetail.TARGET_AMBIGUOUS
                else -> ErrorDetail.TARGET_GONE
            },
            message = message,
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

// WAIT_SCREEN_STABLE sampling: a 48x96 grid (4 608 points) with a 0.5 % tolerance, as in
// Maestro's screen-static check, so a blinking caret or the status-bar clock do not count.
private const val PIXEL_GRID_COLUMNS = 48
private const val PIXEL_GRID_ROWS = 96
private const val PIXEL_DIFF_THRESHOLD = 0.005
private const val MAX_FINGERPRINT_NODES = 4_000

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
