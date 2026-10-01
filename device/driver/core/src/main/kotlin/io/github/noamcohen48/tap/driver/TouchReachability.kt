package io.github.noamcohen48.tap.driver

import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * The check before a touch gesture: the finger must go down in the target's own window.
 *
 * The accessibility tree reports every window on screen, including those partly under another
 * one (the keyboard, a popup, another app's bubble or overlay). Android marks a node not visible
 * when the windows above it cover it completely, so UiAutomator never returns it; a node they
 * cover only partly is returned with its own bounds, and `UiObject2` puts the finger down inside
 * its visible bounds whether or not another window is on top there. A gesture is injected at
 * screen coordinates, so it reaches whichever window is topmost: the keyboard, not the button
 * half under it. [requireReachable] refuses that before any input with `NOT_INTERACTABLE` /
 * `OBSCURED`, which promises no mutation (device-checked by `OcclusionTest`).
 *
 * Windows that take no touches (a dimming or pointer-location overlay) are not reported, so they
 * give no false refusal. The check reads the target's window only through
 * the window list; covering views inside the target's own window are not detected.
 */
internal class TouchReachability(
    private val uiAutomation: () -> UiAutomation,
) {
    /** Throws `NOT_INTERACTABLE` / `OBSCURED` when [point] is in a window above [element]'s. */
    fun requireReachable(
        element: UiObject2,
        point: Point,
    ) {
        // Only the default display's windows are listed; other displays are not checked.
        if (element.displayId != Display.DEFAULT_DISPLAY) return
        val windowId = element.accessibilityNodeInfo.windowId
        val topmost = uiAutomation().windows.filter { it.touchableRegion().contains(point.x, point.y) }.maxByOrNull { it.layer }
        // No window at the point, or the list is momentarily out of date: nothing to refuse on.
        if (topmost == null || topmost.id == windowId) return
        throw CommandFailure(
            ErrorCode.ERR_NOT_INTERACTABLE,
            detail = ErrorDetail.OBSCURED,
            message = "The touch point (${point.x}, ${point.y}) is covered by ${topmost.describe()}",
        )
    }

    private fun AccessibilityWindowInfo.touchableRegion(): Region =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Region().also(::getRegionInScreen)
        } else {
            Region(Rect().also(::getBoundsInScreen))
        }

    private fun AccessibilityWindowInfo.describe(): String {
        val packageName = root?.packageName
        return listOfNotNull(title?.let { "window \"$it\"" } ?: "a window", packageName?.let { "of $it" })
            .joinToString(" ")
    }
}

/**
 * Where UiAutomator puts the finger down for each gesture, as `UiObject2` computes it
 * (androidx.test.uiautomator 2.4.0): clicks at the visible centre; swipes start on the edge of
 * the visible bounds shrunk by the default 10% gesture margin, opposite the finger's direction.
 */
internal object TouchPoints {
    private const val GESTURE_MARGIN = 0.1f

    fun center(element: UiObject2): Point = element.visibleCenter

    /** The start of a swipe whose finger moves towards [fingerDirection]. */
    fun swipeStart(
        element: UiObject2,
        fingerDirection: Direction,
    ): Point {
        val visible = element.visibleBounds
        val area =
            Rect(
                visible.left + (visible.width() * GESTURE_MARGIN).toInt(),
                visible.top + (visible.height() * GESTURE_MARGIN).toInt(),
                visible.right - (visible.width() * GESTURE_MARGIN).toInt(),
                visible.bottom - (visible.height() * GESTURE_MARGIN).toInt(),
            )
        return when (fingerDirection) {
            Direction.LEFT -> Point(area.right, area.centerY())
            Direction.RIGHT -> Point(area.left, area.centerY())
            Direction.UP -> Point(area.centerX(), area.bottom)
            Direction.DOWN -> Point(area.centerX(), area.top)
        }
    }

    /** A scroll swipes against its direction: scrolling DOWN moves the finger UP. */
    fun scrollStart(
        element: UiObject2,
        scrollDirection: Direction,
    ): Point = swipeStart(element, Direction.reverse(scrollDirection))
}
