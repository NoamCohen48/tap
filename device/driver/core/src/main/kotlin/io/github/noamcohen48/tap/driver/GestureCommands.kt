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
    /** A disabled target is `NOT_INTERACTABLE` before the gate: no input is sent. */
    fun tap(
        context: CommandContext,
        command: Tap,
        target: CompiledSelector,
    ) = gesture(context, target, interactable = UiObject2::isEnabled) { element ->
        faults.beforeTapClick(command, context.requestId)
        element.click()
        faults.afterTapClick(command, context.requestId)
    }

    /** A disabled target is `NOT_INTERACTABLE` before the gate, as for [tap]. */
    fun longTap(
        context: CommandContext,
        target: CompiledSelector,
    ) = gesture(context, target, interactable = UiObject2::isEnabled) { element ->
        element.longClick()
    }

    /** Finger gesture across the element; always reports moved once injected. */
    fun swipe(
        context: CommandContext,
        command: Swipe,
        target: CompiledSelector,
    ): Boolean =
        gesture(context, target) { element ->
            val distance = if (command.hasDistancePercent()) command.distancePercent else DEFAULT_GESTURE_PERCENT
            element.swipe(uiDirection(command.direction), fraction(distance))
            true
        }

    /** One scroll segment of a container; reports whether the content moved. */
    fun scroll(
        context: CommandContext,
        command: Scroll,
        target: CompiledSelector,
    ): Boolean =
        gesture(context, target, interactable = UiObject2::isScrollable) { element ->
            val distance = if (command.hasDistancePercent()) command.distancePercent else DEFAULT_GESTURE_PERCENT
            element.scroll(uiDirection(command.direction), fraction(distance))
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
     * Shared shape of every single-target gesture: checkpoint, resolve exactly one target,
     * check it can take the gesture, pass the mutation gate, act, recycle. Everything after
     * the gate is definitive.
     */
    private inline fun <R> gesture(
        context: CommandContext,
        target: CompiledSelector,
        interactable: (UiObject2) -> Boolean = { true },
        action: (UiObject2) -> R,
    ): R {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        try {
            if (!interactable(element)) throw CommandFailure(ErrorCode.ERR_NOT_INTERACTABLE)
            // Atomically refuses on cancel, deadline, or a poisoned session; otherwise
            // cancellation is ignored from here on and the gesture result is definitive.
            context.markMutationStarted()
            return action(element)
        } finally {
            element.recycle()
        }
    }
}
