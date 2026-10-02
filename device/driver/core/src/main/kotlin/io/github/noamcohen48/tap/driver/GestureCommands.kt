package io.github.noamcohen48.tap.driver

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.graphics.Point
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Fling
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.Pinch
import io.github.noamcohen48.tap.api.v1.PinchDirection
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.DEFAULT_GESTURE_PERCENT

/**
 * Element gestures (`tap`, `long_tap`, `double_tap`, `drag`, `swipe`, `scroll`, `fling`,
 * `pinch`), `press_key` and `open_system_panel`. Every gesture resolves each element it uses
 * to exactly one match before any input, and reports only whether the input was injected: what
 * the app did with it is for the test to observe.
 */
internal class GestureCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val objects: UiObjectAccess,
    private val reachability: TouchReachability,
    private val faults: FaultHooks,
) {
    private val pointer = PointerGestures({ instrumentation.uiAutomation }, instrumentation.context.resources.displayMetrics.density)
    /** Clicks the one matching node, whatever its state: what the app does with it is for the test. */
    fun tap(
        context: CommandContext,
        command: Tap,
        target: CompiledSelector,
    ) = gesture(context, target, TouchPoints::center) { element ->
        faults.beforeTapClick(command, context.requestId)
        element.click()
        faults.afterTapClick(command, context.requestId)
    }

    /** Long-clicks the one matching node, as for [tap]. */
    fun longTap(
        context: CommandContext,
        target: CompiledSelector,
    ) = gesture(context, target, TouchPoints::center) { element ->
        element.longClick()
    }

    /** Two taps on the element's visible centre as one gesture (Android's double-tap timing). */
    fun doubleTap(
        context: CommandContext,
        target: CompiledSelector,
    ) = gesture(context, target, TouchPoints::center) { element ->
        pointer.doubleTap(element.visibleCenter)
    }

    /**
     * Long-presses the source element's centre, moves to the destination element's centre, holds
     * and releases. Both elements resolve to exactly one match before any input; the destination
     * is only needed for its centre, so it is released before the gesture starts.
     */
    fun drag(
        context: CommandContext,
        source: CompiledSelector,
        destination: CompiledSelector,
    ) {
        context.checkpoint()
        val to =
            objects.resolveTarget(destination).let { element ->
                try {
                    element.visibleCenter
                } finally {
                    element.recycle()
                }
            }
        gesture(context, source, TouchPoints::center) { element -> pointer.drag(element.visibleCenter, to) }
    }

    /**
     * Two fingers apart ([PinchDirection.PINCH_OPEN]) or together across the element. The
     * occlusion check uses the element's centre, where the fingers start or end.
     */
    fun pinch(
        context: CommandContext,
        command: Pinch,
        target: CompiledSelector,
    ) = gesture(context, target, TouchPoints::center) { element ->
        val percent = fraction(if (command.hasPercent()) command.percent else DEFAULT_GESTURE_PERCENT)
        when (command.direction) {
            PinchDirection.PINCH_OPEN -> element.pinchOpen(percent)
            PinchDirection.PINCH_CLOSE -> element.pinchClose(percent)
            else -> throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A pinch direction is required")
        }
    }

    /**
     * A fast swipe across the whole element towards the content edge [Fling.getDirection] names
     * (as `scroll`: DOWN reveals content below, so the finger moves up). Unlike
     * `UiObject2.fling`, it does not wait for scrolling to end or guess whether more content is
     * left: that is for the test to observe.
     */
    fun fling(
        context: CommandContext,
        command: Fling,
        target: CompiledSelector,
    ) = gesture(context, target, { TouchPoints.scrollStart(it, uiDirection(command.direction)) }) { element ->
        val finger =
            when (uiDirection(command.direction)) {
                androidx.test.uiautomator.Direction.UP -> androidx.test.uiautomator.Direction.DOWN
                androidx.test.uiautomator.Direction.DOWN -> androidx.test.uiautomator.Direction.UP
                androidx.test.uiautomator.Direction.LEFT -> androidx.test.uiautomator.Direction.RIGHT
                androidx.test.uiautomator.Direction.RIGHT -> androidx.test.uiautomator.Direction.LEFT
            }
        val density = instrumentation.context.resources.displayMetrics.density
        element.swipe(finger, 1f, (FLING_SPEED_DP_PER_S * density).toInt())
    }

    /** Finger gesture across the element. */
    fun swipe(
        context: CommandContext,
        command: Swipe,
        target: CompiledSelector,
    ) = gesture(context, target, { TouchPoints.swipeStart(it, uiDirection(command.direction)) }) { element ->
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
    ) = gesture(context, target, { TouchPoints.scrollStart(it, uiDirection(command.direction)) }) { element ->
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
     * Shared shape of every single-target gesture: checkpoint, resolve exactly one target, check
     * the finger would land in its window ([touchDown], [TouchReachability]), pass the mutation
     * gate, act, recycle. Everything before the gate promises no input; everything after it is
     * definitive.
     */
    private inline fun gesture(
        context: CommandContext,
        target: CompiledSelector,
        touchDown: (UiObject2) -> Point,
        action: (UiObject2) -> Unit,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        try {
            reachability.requireReachable(element, touchDown(element))
            // Atomically refuses on cancel, deadline, or a poisoned session; otherwise
            // cancellation is ignored from here on and the gesture result is definitive.
            context.markMutationStarted()
            action(element)
        } finally {
            element.recycle()
        }
    }

    private companion object {
        /** AndroidX `UiObject2` default fling speed. */
        const val FLING_SPEED_DP_PER_S = 7_500
    }
}
