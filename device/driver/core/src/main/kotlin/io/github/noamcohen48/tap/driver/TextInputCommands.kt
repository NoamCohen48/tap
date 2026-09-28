package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * `set_text`, `clear_text` and `type_text`. The driver assumes nothing about how the app reacts
 * to input: it resolves exactly one node, acts on that node, and reports only what Android
 * returned for the action or key events. It never reads the field back or resolves the
 * selector again, because what the app does with the text (reformat, truncate, reject, copy
 * it elsewhere) is for the test to assert.
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
    ) = performSetText(context, target, command.text)

    fun clearText(
        context: CommandContext,
        target: CompiledSelector,
    ) = performSetText(context, target, "")

    /**
     * Accessibility `ACTION_SET_TEXT` on the resolved node. `ACTION_REJECTED` when the node
     * refuses the action (not editable, disabled, a view that does not implement it).
     */
    private fun performSetText(
        context: CommandContext,
        target: CompiledSelector,
        text: String,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        val accepted =
            try {
                val node = element.accessibilityNodeInfo
                val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                context.markMutationStarted()
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } finally {
                element.recycle()
            }
        if (!accepted) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The node refused ACTION_SET_TEXT")
    }

    /**
     * Clicks the resolved node, lets the UI settle (bounded; never fails the command), then
     * injects [TypeText.getText] as key events wherever input focus is. Reports whether every
     * event was accepted; where the characters landed is for the test to assert.
     */
    fun typeText(
        context: CommandContext,
        command: TypeText,
        target: CompiledSelector,
    ) {
        context.checkpoint()
        val events =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(command.text.toCharArray())
                ?: throw CommandFailure(
                    ErrorCode.ERR_INVALID_REQUEST,
                    detail = ErrorDetail.UNSUPPORTED_CHARACTERS,
                    message = "Text cannot be represented as Android key events",
                )
        val element = objects.resolveTarget(target)
        try {
            if (context.isExpired()) throw CommandFailure(ErrorCode.ERR_DEADLINE_EXCEEDED)
            // The click is the first injected input; everything after it is definitive.
            context.markMutationStarted()
            element.click()
        } finally {
            element.recycle()
        }
        device.waitForIdle(minOf(SETTLE_WAIT_MS, context.remainingMs()))
        if (context.isExpired()) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.DEADLINE_AFTER_FOCUS)
        }
        injectKeys(context, events)
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
                val now = SystemClock.uptimeMillis()
                val downTime = pressedKeys.downTimeFor(event.action, event.keyCode, now)
                val injected = instrumentation.uiAutomation.injectInputEvent(event.at(downTime, now), false)
                pressedKeys.record(event.action, event.keyCode, downTime, injected)
                if (!injected) {
                    rejected = true
                    break
                }
            }
        } finally {
            released =
                pressedKeys.releaseAll { keyCode, downTime ->
                    val release = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
                    instrumentation.uiAutomation.injectInputEvent(release, false)
                }
        }
        if (!released) throw CommandFailure(ErrorCode.ERR_INDETERMINATE, detail = ErrorDetail.KEY_RELEASE_FAILED)
        if (deadlineExpired) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.PARTIAL_INPUT)
        if (rejected) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED)
    }

    /** This event re-stamped for injection now: [downTime] of its press, [eventTime] now. */
    private fun KeyEvent.at(
        downTime: Long,
        eventTime: Long,
    ): KeyEvent = KeyEvent(downTime, eventTime, action, keyCode, repeatCount, metaState, deviceId, scanCode, flags, source)

    private companion object {
        /** Bound for letting the UI settle between the click and the first key event. */
        const val SETTLE_WAIT_MS = 3_000L
    }
}
