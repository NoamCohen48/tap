package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * The soft keyboard: whether one is showing (`device_info`), `hide_keyboard` and
 * `perform_ime_action`. "Showing" is an input-method window on screen, whatever the IME, rather
 * than a known keyboard's resource ids (Maestro) or a `dumpsys input_method` parse (Appium).
 */
internal class KeyboardCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
) {
    val shown: Boolean
        get() {
            val windows = instrumentation.uiAutomation.windows ?: return false
            try {
                return windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } finally {
                @Suppress("DEPRECATION")
                windows.forEach(AccessibilityWindowInfo::recycle)
            }
        }

    /**
     * One Back key, which the IME consumes to hide itself; nothing is sent when no keyboard is
     * showing, so Back never reaches the app (Maestro presses it unconditionally). A keyboard
     * closing on its own between the check and the key still lets Back through; the result is
     * only that the key was injected.
     */
    fun hide(context: CommandContext) {
        context.checkpoint()
        if (!shown) return
        context.markMutationStarted()
        if (!device.pressBack()) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The Back key was not injected")
    }

    /**
     * Accessibility `ACTION_IME_ENTER` on the one matching node: the field runs its configured
     * editor action (Search, Go, Send, Done, …) exactly as the keyboard's action key does. A node
     * that does not offer the action (not an editable field, or one without input focus) is
     * refused before input with `ACTION_REJECTED`. Pressing
     * Enter is not equivalent (`TextView` reports `IME_NULL`), so below API 30, where the action
     * does not exist, the command is refused before input rather than falling back to it.
     */
    fun performImeAction(
        context: CommandContext,
        target: CompiledSelector,
    ) {
        context.checkpoint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw CommandFailure(
                ErrorCode.ERR_UNSUPPORTED,
                ErrorDetail.REQUIRES_API_30,
                "The keyboard action needs API 30 (this device is API ${Build.VERSION.SDK_INT})",
            )
        }
        val element = objects.resolveTarget(target)
        val accepted =
            try {
                val node = element.accessibilityNodeInfo
                val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                // TextView answers true to the action whatever the view, so what the node offers is
                // the signal: an editable field offers it only while it has input focus.
                if (node.actionList.none { it.id == imeEnter.id }) {
                    throw CommandFailure(
                        ErrorCode.ERR_ACTION_REJECTED,
                        message = "The node does not offer the keyboard action (only an editable field with input focus does)",
                    )
                }
                context.markMutationStarted()
                node.performAction(imeEnter.id)
            } finally {
                element.recycle()
            }
        if (!accepted) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The node refused ACTION_IME_ENTER")
    }
}
