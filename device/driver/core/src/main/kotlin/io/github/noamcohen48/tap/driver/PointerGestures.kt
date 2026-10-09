package io.github.noamcohen48.tap.driver

import android.app.UiAutomation
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Single-finger gestures at points the caller already checked (tap, long tap, double tap, drag),
 * injected as touchscreen finger events on the default display the way AndroidX
 * `GestureController` injects them: without waiting for the app to handle each event, so a
 * stalled main thread cannot hold the command past its deadline. Each is one timed sequence: it
 * starts after the caller passed the mutation gate and always ends with the finger lifted (or
 * the stream cancelled) before returning, so a failed injection never leaves a pointer down.
 */
internal class PointerGestures(
    private val uiAutomation: () -> UiAutomation,
    private val density: Float,
) {
    /** One tap at [point]. */
    fun tap(point: Point) = stroke(listOf(point), holdMs = TAP_MS)

    /** A press at [point] held 1.5 × the system long-press timeout, as AndroidX `longClick`. */
    fun longTap(point: Point) = stroke(listOf(point), holdMs = LONG_PRESS_MS)

    /** Two taps at [point], the second inside Android's double-tap window. */
    fun doubleTap(point: Point) {
        stroke(listOf(point), holdMs = TAP_MS)
        SystemClock.sleep(DOUBLE_TAP_GAP_MS)
        stroke(listOf(point), holdMs = TAP_MS, partial = true)
    }

    /**
     * Presses [from] until the press is a long press (1.5 × the system long-press timeout, as
     * AndroidX `longClick`), moves to [to] at [DRAG_SPEED_DP_PER_S], holds there so drop targets
     * see the finger arrive, and lifts.
     */
    fun drag(
        from: Point,
        to: Point,
    ) {
        val distance = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
        val moveMs = (distance / (DRAG_SPEED_DP_PER_S * density) * 1000).roundToLong().coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
        val steps = (moveMs / FRAME_MS).coerceAtLeast(1)
        val path = (0..steps).map { i -> interpolate(from, to, i.toFloat() / steps) }
        stroke(
            path,
            holdMs = LONG_PRESS_MS,
            stepMs = moveMs / steps,
            endHoldMs = DROP_HOLD_MS,
        )
    }

    /**
     * Down at the first point, [holdMs], moves through the rest [stepMs] apart, [endHoldMs] at the
     * last, up there. [partial]: an earlier stroke of the same gesture already reached the screen.
     */
    private fun stroke(
        path: List<Point>,
        holdMs: Long,
        stepMs: Long = 0,
        endHoldMs: Long = 0,
        partial: Boolean = false,
    ) {
        val downTime = SystemClock.uptimeMillis()
        if (!inject(downTime, MotionEvent.ACTION_DOWN, path.first())) {
            // Refused by the input system, so nothing is down; the cancel is only a safeguard.
            inject(downTime, MotionEvent.ACTION_CANCEL, path.first())
            throw CommandFailure(
                ErrorCode.ERR_ACTION_REJECTED,
                if (partial) ErrorDetail.PARTIAL_INPUT else null,
                "The touch was not injected",
            )
        }
        var last = path.first()
        try {
            SystemClock.sleep(holdMs)
            for (point in path.drop(1)) {
                check(inject(downTime, MotionEvent.ACTION_MOVE, point)) { "A move was not injected" }
                last = point
                SystemClock.sleep(stepMs)
            }
            if (endHoldMs > 0) {
                SystemClock.sleep(endHoldMs)
                check(inject(downTime, MotionEvent.ACTION_MOVE, last)) { "A move was not injected" }
            }
            check(inject(downTime, MotionEvent.ACTION_UP, last)) { "The release was not injected" }
        } catch (failure: IllegalStateException) {
            inject(downTime, MotionEvent.ACTION_CANCEL, last)
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, ErrorDetail.PARTIAL_INPUT, "${failure.message}; the gesture was cancelled")
        }
    }

    private fun inject(
        downTime: Long,
        action: Int,
        point: Point,
    ): Boolean {
        // A new PointerProperties has id INVALID_POINTER_ID (-1), which the input system refuses.
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                x = point.x.toFloat()
                y = point.y.toFloat()
                pressure = 1f
                size = 1f
            }
        // As AndroidX GestureController: a finger, 1 × 1 precision, touchscreen source.
        val event =
            MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        return try {
            uiAutomation().injectInputEvent(event, false)
        } finally {
            event.recycle()
        }
    }

    private fun interpolate(
        from: Point,
        to: Point,
        t: Float,
    ): Point = Point(from.x + ((to.x - from.x) * t).toInt(), from.y + ((to.y - from.y) * t).toInt())

    private companion object {
        /** AndroidX `Gestures.LONG_PRESS_DURATION_MS`: well past the long-press timeout. */
        val LONG_PRESS_MS: Long get() = (ViewConfiguration.getLongPressTimeout() * 1.5f).toLong()

        /** Short of `ViewConfiguration.getTapTimeout()` (100 ms), so each touch is a tap. */
        const val TAP_MS = 50L

        /** Inside the double-tap window: more than its 40 ms minimum, well under its 300 ms timeout. */
        const val DOUBLE_TAP_GAP_MS = 100L

        /** AndroidX `UiObject2` default drag speed. */
        const val DRAG_SPEED_DP_PER_S = 2_500.0
        const val MIN_MOVE_MS = 300L
        const val MAX_MOVE_MS = 2_000L
        const val FRAME_MS = 16L

        /** Drop targets typically react to the finger arriving (drag-enter) before the release. */
        const val DROP_HOLD_MS = 300L
    }
}
