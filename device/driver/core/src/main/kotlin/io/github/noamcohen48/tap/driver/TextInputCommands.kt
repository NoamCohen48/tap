package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * `set_text`, `clear_text` and `type_text`: resolve an editable target, pass the gate, input,
 * then verify the effect with [TextVerifier] before reporting success.
 */
internal class TextInputCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
) {
    fun setText(
        context: CommandContext,
        command: SetText,
        target: CompiledSelector,
    ) = editText(context, target, expected = command.text) { element, expected ->
        element.text = expected
    }

    fun clearText(
        context: CommandContext,
        target: CompiledSelector,
    ) = editText(context, target, expected = "") { element, _ -> element.clear() }

    /** Accessibility `ACTION_SET_TEXT` shape: resolve, require editable, gate, set, verify. */
    private inline fun editText(
        context: CommandContext,
        target: CompiledSelector,
        expected: String,
        mutate: (UiObject2, String) -> Unit,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        try {
            if (!element.accessibilityNodeInfo.isEditable) throw CommandFailure(ErrorCode.ERR_NOT_INTERACTABLE)
            context.markMutationStarted()
            mutate(element, expected)
        } finally {
            element.recycle()
        }
        val verificationDeadline = minOf(context.deadlineMs, context.nowMs() + TextVerifier.EDIT_VERIFY_MS)
        if (!awaitText(context, target, expected, verificationDeadline)) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.TEXT_MISMATCH)
        }
    }

    fun typeText(
        context: CommandContext,
        command: TypeText,
        target: CompiledSelector,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        val deadline = context.deadlineMs
        try {
            if (!element.accessibilityNodeInfo.isEditable) throw CommandFailure(ErrorCode.ERR_NOT_INTERACTABLE)
            if (context.isExpired()) throw CommandFailure(ErrorCode.ERR_DEADLINE_EXCEEDED)

            val text = command.text
            val events =
                KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                    ?: throw CommandFailure(
                        ErrorCode.ERR_INVALID_REQUEST,
                        detail = ErrorDetail.UNSUPPORTED_CHARACTERS,
                        message = "Text cannot be represented as Android key events",
                    )

            val initialText = element.displayedText().orEmpty()
            // The focusing click is the first injected input; everything after it is definitive.
            context.markMutationStarted()
            element.click()
            while (!isFocused(target)) {
                val remaining = deadline - context.nowMs()
                if (remaining <= 0) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.FOCUS_TIMEOUT)
                Thread.sleep(minOf(TextVerifier.POLL_MS, remaining))
            }

            if (context.isExpired()) throw deadlineAfterFocus()
            device.waitForIdle(minOf(FOCUS_IDLE_WAIT_MS, context.remainingMs()))
            val revalidation = objects.resolve(target)
            val revalidated = revalidation.element ?: throw staleTarget(revalidation)
            val stillFocused =
                try {
                    revalidated.isFocused
                } finally {
                    revalidated.recycle()
                }
            if (!stillFocused) throw CommandFailure(ErrorCode.ERR_STALE_DURING_COMMAND, detail = ErrorDetail.FOCUS_LOST)
            if (context.isExpired()) throw deadlineAfterFocus()

            injectKeys(context, events)

            val expected = TextVerifier.expectedAfterTyping(initialText, text)
            if (!awaitText(context, target, expected, deadline)) {
                throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.TEXT_MISMATCH)
            }
        } finally {
            element.recycle()
        }
    }

    /**
     * Injects [events] in order until the deadline or a rejection, then releases every key
     * still down. A key that cannot be released is `INDETERMINATE`: the device may be left
     * with a stuck key.
     */
    private fun injectKeys(
        context: CommandContext,
        events: Array<KeyEvent>,
    ) {
        val pressedKeys = PressedKeys()
        var rejected = false
        var deadlineExpired = false
        var released: Boolean
        try {
            for (event in events) {
                if (context.isExpired()) {
                    deadlineExpired = true
                    break
                }
                val timedEvent = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), event.repeatCount)
                val injected = instrumentation.uiAutomation.injectInputEvent(timedEvent, false)
                pressedKeys.record(event.action, event.keyCode, injected)
                if (!injected) {
                    rejected = true
                    break
                }
            }
        } finally {
            released =
                pressedKeys.releaseAll { keyCode ->
                    instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode), false)
                }
        }
        if (!released) throw CommandFailure(ErrorCode.ERR_INDETERMINATE, detail = ErrorDetail.KEY_RELEASE_FAILED)
        if (deadlineExpired) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.PARTIAL_INPUT)
        if (rejected) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED)
    }

    private fun awaitText(
        context: CommandContext,
        target: CompiledSelector,
        expected: String,
        deadlineMs: Long,
    ): Boolean =
        TextVerifier.awaitText(
            expected,
            deadlineMs,
            now = context::nowMs,
            sleep = Thread::sleep,
            read = { currentText(target) },
        )

    /** The target's shown text now, or null when it does not resolve. */
    private fun currentText(target: CompiledSelector): String? {
        val current = objects.resolve(target).element ?: return null
        return try {
            current.displayedText().orEmpty()
        } finally {
            current.recycle()
        }
    }

    private fun isFocused(target: CompiledSelector): Boolean {
        val current = objects.resolve(target).element ?: return false
        return try {
            current.isFocused
        } finally {
            current.recycle()
        }
    }

    private fun deadlineAfterFocus(): CommandFailure =
        CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.DEADLINE_AFTER_FOCUS)

    private companion object {
        /** Bound for the idle wait between focusing and typing. */
        const val FOCUS_IDLE_WAIT_MS = 3_000L
    }
}
