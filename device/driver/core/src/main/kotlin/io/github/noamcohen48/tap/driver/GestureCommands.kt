package io.github.noamcohen48.tap.driver

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.DEFAULT_GESTURE_PERCENT

/** Single-target gestures (`tap`, `long_tap`, `swipe`, `scroll`), `press_key` and `open_system_panel`. */
internal class GestureCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
    private val faults: FaultHooks,
) {
    /** Clicks the one matching node, whatever its state: what the app does with it is for the test. */
    fun tap(
        context: CommandContext,
        command: Tap,
        target: CompiledSelector,
    ) = gesture(context, target) { element ->
        faults.beforeTapClick(command, context.requestId)
        element.click()
        faults.afterTapClick(command, context.requestId)
    }

    /** Long-clicks the one matching node, as for [tap]. */
    fun longTap(
        context: CommandContext,
        target: CompiledSelector,
    ) = gesture(context, target) { element ->
        element.longClick()
    }

    /** Finger gesture across the element. */
    fun swipe(
        context: CommandContext,
        command: Swipe,
        target: CompiledSelector,
    ) = gesture(context, target) { element ->
        val distance = if (command.hasDistancePercent()) command.distancePercent else DEFAULT_GESTURE_PERCENT
        element.swipe(uiDirection(command.direction), fraction(distance))
    }

    /**
     * One scroll gesture across the element, whatever its state. UiAutomator's own "can scroll
     * further" guess is not reported: whether content moved is for the test to observe.
     */
    fun scroll(
        context: CommandContext,
        command: Scroll,
        target: CompiledSelector,
    ) = gesture(context, target) { element ->
        val distance = if (command.hasDistancePercent()) command.distancePercent else DEFAULT_GESTURE_PERCENT
        element.scroll(uiDirection(command.direction), fraction(distance))
        Unit
    }

    /**
     * Key injection is a mutation: it passes the gate and is never replayed. Every key, BACK and
     * HOME included, is a plain key code press: `UiDevice.pressBack`/`pressHome` wait for idle
     * first and report false when no window-content event follows within a second, which is
     * effect verification (a slow device got `ACTION_REJECTED` for a Back that happened). The
     * result is only whether the key events were injected; the test waits for the effect.
     */
    fun pressKey(
        context: CommandContext,
        command: PressKey,
    ) {
        val keyCode = command.keyCode
        context.checkpoint()
        context.markMutationStarted()
        val injected = device.pressKeyCode(keyCode)
        if (!injected) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "Key $keyCode was not injected")
    }

    /**
     * Opens the notification shade or quick settings with the accessibility global action, as
     * `UiDevice.openNotification`/`openQuickSettings` do but without their `waitForIdle` first.
     * The result is only whether the system accepted the action; the test waits for the panel.
     */
    fun openSystemPanel(
        context: CommandContext,
        command: OpenSystemPanel,
    ) {
        val action =
            when (command.panel) {
                SystemPanel.SYSTEM_PANEL_NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                SystemPanel.SYSTEM_PANEL_QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A panel is required")
            }
        context.checkpoint()
        context.markMutationStarted()
        val performed = instrumentation.uiAutomation.performGlobalAction(action)
        if (!performed) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The system refused to open ${command.panel}")
    }

    /**
     * Shared shape of every single-target gesture: checkpoint, resolve exactly one target, pass
     * the mutation gate, act, recycle. Everything after the gate is definitive.
     */
    private inline fun gesture(
        context: CommandContext,
        target: CompiledSelector,
        action: (UiObject2) -> Unit,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        try {
            // Atomically refuses on cancel, deadline, or a poisoned session; otherwise
            // cancellation is ignored from here on and the gesture result is definitive.
            context.markMutationStarted()
            action(element)
        } finally {
            element.recycle()
        }
    }
}
