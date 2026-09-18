package com.company.tap.driver

import android.app.Instrumentation
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SelectorKind
import com.company.tap.protocol.TargetScope
import com.company.tap.protocol.MAX_CONTROL_PAYLOAD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

internal class UiObjectAccess(
    private val device: UiDevice,
    private val expectedAut: String,
) {
    data class UniqueResolution(val element: UiObject2?, val errorCode: String?)

    fun selector(selector: Selector): BySelector {
        val compiled = when (selector.kind) {
            SelectorKind.TEXT -> By.text(selector.value)
            SelectorKind.RAW_RESOURCE -> By.res(selector.value)
            SelectorKind.ANDROID_RESOURCE -> By.res(requireNotNull(selector.packageName), selector.value)
        }
        return compiled.pkg(scopePackage(selector))
    }

    fun scopePackage(selector: Selector): String = when (selector.scope) {
        TargetScope.AUT -> {
            if (selector.kind == SelectorKind.ANDROID_RESOURCE) {
                require(selector.packageName == expectedAut) {
                    "AUT resource package does not match expected AUT"
                }
            }
            expectedAut
        }
        TargetScope.SYSTEM -> requireNotNull(selector.scopePackage)
    }

    fun resolveUnique(selector: Selector): UniqueResolution {
        val elements = findObjects(selector)
        if (elements.isEmpty()) return UniqueResolution(null, "NOT_FOUND")
        if (elements.size > 1) {
            elements.forEach(UiObject2::recycle)
            return UniqueResolution(null, "AMBIGUOUS")
        }
        return UniqueResolution(elements.single(), null)
    }

    fun findObject(selector: Selector): UiObject2? = try {
        device.findWindow(By.Window.pkg(scopePackage(selector)).focused(true))
            ?.findObject(selector(selector))
    } catch (_: StaleObjectException) {
        null
    }

    fun hasObject(selector: Selector): Boolean = try {
        device.findWindow(By.Window.pkg(scopePackage(selector)).focused(true))
            ?.hasObject(selector(selector)) == true
    } catch (_: StaleObjectException) {
        false
    }

    private fun findObjects(selector: Selector): List<UiObject2> {
        val focusedWindow = device.findWindow(By.Window.pkg(scopePackage(selector)).focused(true))
            ?: return emptyList()
        val candidates = device.findObjects(selector(selector))
        val matches = mutableListOf<UiObject2>()
        try {
            candidates.forEach { candidate ->
                if (candidate.accessibilityNodeInfo.windowId == focusedWindow.id) {
                    matches += candidate
                } else {
                    candidate.recycle()
                }
            }
            return matches
        } catch (error: Throwable) {
            candidates.forEach { runCatching { it.recycle() } }
            throw error
        }
    }
}

internal class UiAutomationCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
    private val faults: FaultController,
) {
    fun tap(socket: Socket, request: Request, requestId: Long, started: Long): Response {
        val requestSelector = requireNotNull(request.selector)
        val resolved = objects.resolveUnique(requestSelector)
        val element = resolved.element ?: return Response(
            false,
            errorCode = requireNotNull(resolved.errorCode),
            durationMs = elapsed(started),
        )
        try {
            if (android.os.SystemClock.elapsedRealtime() >= started + request.timeoutMs) {
                return Response(false, errorCode = "DEADLINE_EXCEEDED", durationMs = elapsed(started))
            }
            faults.injectLateUninterruptible(socket, request, requestId)
            element.click()
            if (faults.inject(FaultPoint.AFTER_MUTATION, request, requestId)) {
                throw InjectedTransportLoss()
            }
        } finally {
            element.recycle()
        }
        return Response(true, value = true, durationMs = elapsed(started))
    }

    fun waitVisible(request: Request, started: Long): Response {
        require(request.timeoutMs >= 0) { "timeoutMs must not be negative" }
        val deadline = started + request.timeoutMs
        var found = false
        do {
            found = objects.hasObject(requireNotNull(request.selector))
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (!found && remaining > 0) Thread.sleep(minOf(50, remaining))
        } while (!found && android.os.SystemClock.elapsedRealtime() < deadline)
        return if (found) Response(true, value = true, durationMs = elapsed(started))
        else Response(false, value = false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
    }

    fun dumpHierarchy(started: Long): Response {
        val hierarchy = try {
            LimitedOutputStream(MAX_HIERARCHY_BYTES).use { output ->
                device.dumpWindowHierarchy(output)
                output.content()
            }
        } catch (_: OutputLimitExceeded) {
            return Response(
                false,
                errorCode = "PAYLOAD_TOO_LARGE",
                message = "Hierarchy exceeded $MAX_HIERARCHY_BYTES bytes",
                durationMs = elapsed(started),
            )
        }
        return Response(true, text = hierarchy, durationMs = elapsed(started))
    }

    fun setText(request: Request, started: Long): Response {
        val requestSelector = requireNotNull(request.selector)
        val resolved = objects.resolveUnique(requestSelector)
        val element = resolved.element ?: return Response(
            false,
            errorCode = requireNotNull(resolved.errorCode),
            durationMs = elapsed(started),
        )
        try {
            if (!element.accessibilityNodeInfo.isEditable) {
                return Response(false, errorCode = "NOT_INTERACTABLE", durationMs = elapsed(started))
            }
            if (android.os.SystemClock.elapsedRealtime() >= started + request.timeoutMs) {
                return Response(false, errorCode = "DEADLINE_EXCEEDED", durationMs = elapsed(started))
            }
            val expected = requireNotNull(request.inputText)
            element.text = expected
        } finally {
            element.recycle()
        }
        val expected = requireNotNull(request.inputText)
        val verificationDeadline = minOf(
            started + request.timeoutMs,
            android.os.SystemClock.elapsedRealtime() + 1_000,
        )
        var changed: Boolean
        do {
            val current = objects.findObject(requestSelector)
            changed = try {
                current?.text.orEmpty() == expected
            } finally {
                current?.recycle()
            }
            if (!changed) Thread.sleep(25)
        } while (!changed && android.os.SystemClock.elapsedRealtime() < verificationDeadline)
        return if (changed) Response(true, value = true, durationMs = elapsed(started))
        else Response(false, errorCode = "ACTION_REJECTED", durationMs = elapsed(started))
    }

    fun typeText(request: Request, started: Long): Response {
        val requestSelector = requireNotNull(request.selector)
        val resolved = objects.resolveUnique(requestSelector)
        val element = resolved.element ?: return Response(
            false,
            errorCode = requireNotNull(resolved.errorCode),
            durationMs = elapsed(started),
        )
        val deadline = started + request.timeoutMs
        try {
            if (!element.accessibilityNodeInfo.isEditable) {
                return Response(false, errorCode = "NOT_INTERACTABLE", durationMs = elapsed(started))
            }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                return Response(false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
            }

            val text = requireNotNull(request.inputText)
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?: return Response(
                    false,
                    errorCode = "UNSUPPORTED_CHARACTERS",
                    message = "Text cannot be represented as Android key events",
                    durationMs = elapsed(started),
                )

            val initialNode = element.accessibilityNodeInfo
            val initialText = if (initialNode.isShowingHintText) "" else initialNode.text?.toString().orEmpty()
            element.click()
            while (true) {
                val focused = objects.findObject(requestSelector)
                val hasFocus = try {
                    focused?.isFocused == true
                } finally {
                    focused?.recycle()
                }
                if (hasFocus) break
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    return Response(false, errorCode = "FOCUS_TIMEOUT", durationMs = elapsed(started))
                }
                Thread.sleep(minOf(25, remaining))
            }

            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                return Response(false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
            }
            device.waitForIdle(minOf(3_000L, deadline - android.os.SystemClock.elapsedRealtime()))
            val revalidation = objects.resolveUnique(requestSelector)
            val revalidated = revalidation.element ?: return Response(
                false,
                errorCode = requireNotNull(revalidation.errorCode),
                durationMs = elapsed(started),
            )
            val stillFocused = try {
                revalidated.isFocused
            } finally {
                revalidated.recycle()
            }
            if (!stillFocused) {
                return Response(false, errorCode = "FOCUS_LOST", durationMs = elapsed(started))
            }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                return Response(false, errorCode = "DEADLINE_EXCEEDED", durationMs = elapsed(started))
            }
            val pressedKeys = mutableSetOf<Int>()
            var rejected = false
            var cleanupFailed = false
            var deadlineExpired = false
            try {
                for (event in events) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        deadlineExpired = true
                        break
                    }
                    val timedEvent = KeyEvent.changeTimeRepeat(
                        event,
                        android.os.SystemClock.uptimeMillis(),
                        event.repeatCount,
                    )
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
                return Response(false, errorCode = "INPUT_STATE_UNCERTAIN", durationMs = elapsed(started))
            }
            if (deadlineExpired) {
                return Response(false, errorCode = "DEADLINE_EXCEEDED", durationMs = elapsed(started))
            }
            if (rejected) {
                return Response(false, errorCode = "ACTION_REJECTED", durationMs = elapsed(started))
            }

            val verificationDeadline = deadline
            do {
                val current = objects.findObject(requestSelector)
                val complete = try {
                    current?.text.orEmpty() == initialText + text
                } finally {
                    current?.recycle()
                }
                if (complete) {
                    return Response(true, value = true, durationMs = elapsed(started))
                }
                val remaining = verificationDeadline - android.os.SystemClock.elapsedRealtime()
                if (remaining > 0) Thread.sleep(minOf(25, remaining))
            } while (android.os.SystemClock.elapsedRealtime() < verificationDeadline)
            return Response(false, errorCode = "ACTION_REJECTED", durationMs = elapsed(started))
        } finally {
            element.recycle()
        }
    }

    fun scrollUntil(request: Request, started: Long): Response {
        require(request.timeoutMs >= 0) { "timeoutMs must not be negative" }
        require(request.maxScrolls in 1..100) { "maxScrolls must be between 1 and 100" }
        val target = objects.selector(requireNotNull(request.selector))
        val containerSelector = requireNotNull(request.containerSelector)
        val deadline = started + request.timeoutMs
        var noProgressAttempts = 0

        repeat(request.maxScrolls) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                return Response(false, value = false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
            }
            val resolved = objects.resolveUnique(containerSelector)
            val scrollable = resolved.element ?: return Response(
                false,
                errorCode = requireNotNull(resolved.errorCode),
                message = "Scroll container was not uniquely resolved",
                durationMs = elapsed(started),
            )
            val before = try {
                if (scrollable.hasObject(target)) {
                    return Response(true, value = true, durationMs = elapsed(started))
                }
                val fingerprint = visibleFingerprint(scrollable)
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    return Response(false, value = false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
                }
                scrollable.scroll(Direction.DOWN, 0.8f)
                fingerprint
            } finally {
                scrollable.recycle()
            }
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                return Response(false, value = false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
            }
            Thread.sleep(minOf(100, remaining))
            val afterResolution = objects.resolveUnique(containerSelector)
            val after = afterResolution.element ?: return Response(
                false,
                errorCode = requireNotNull(afterResolution.errorCode),
                message = "Scroll container was not uniquely resolved after scrolling",
                durationMs = elapsed(started),
            )
            val (found, afterFingerprint) = try {
                after.hasObject(target) to visibleFingerprint(after)
            } finally {
                after.recycle()
            }
            if (found) {
                return Response(true, value = true, durationMs = elapsed(started))
            }
            noProgressAttempts = if (afterFingerprint == before) noProgressAttempts + 1 else 0
            if (noProgressAttempts >= 2) {
                return Response(false, value = false, errorCode = "END_REACHED", durationMs = elapsed(started))
            }
        }

        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
            return Response(false, value = false, errorCode = "WAIT_TIMEOUT", durationMs = elapsed(started))
        }
        val finalResolution = objects.resolveUnique(containerSelector)
        val finalContainer = finalResolution.element ?: return Response(
            false,
            errorCode = requireNotNull(finalResolution.errorCode),
            message = "Scroll container was not uniquely resolved after the final attempt",
            durationMs = elapsed(started),
        )
        val found = try {
            finalContainer.hasObject(target)
        } finally {
            finalContainer.recycle()
        }
        return if (found) Response(true, value = true, durationMs = elapsed(started))
        else Response(false, value = false, errorCode = "MAX_SCROLLS", durationMs = elapsed(started))
    }

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
