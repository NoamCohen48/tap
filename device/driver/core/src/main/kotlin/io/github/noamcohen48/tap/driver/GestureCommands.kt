package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.DEFAULT_GESTURE_PERCENT

/** Single-target gestures (`tap`, `long_tap`, `swipe`, `scroll`) and `press_key`. */
internal class GestureCommands(
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

    /** Key injection is a mutation: it passes the gate and is never replayed. */
    fun pressKey(
        context: CommandContext,
        command: PressKey,
    ) {
        val keyCode = command.keyCode
        context.checkpoint()
        context.markMutationStarted()
        val injected =
            when (val press = KeyPress.of(keyCode)) {
                KeyPress.Back -> device.pressBack()
                KeyPress.Home -> device.pressHome()
                is KeyPress.Code -> device.pressKeyCode(press.keyCode)
            }
        if (!injected) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "Key $keyCode was not injected")
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
