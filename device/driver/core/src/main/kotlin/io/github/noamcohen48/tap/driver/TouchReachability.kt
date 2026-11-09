package io.github.noamcohen48.tap.driver

import android.app.UiAutomation
import android.graphics.Point
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
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
 * give no false refusal.
 *
 * Inside the target's own window one cover is detected: an open `DrawerLayout` drawer. Its scrim
 * is drawn by the layout, not a view, and catches every touch outside the drawer, so a tap on the
 * content under it closes the drawer instead. The layout hides that content from accessibility
 * while a drawer is open (`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`), but UiAutomator still
 * reports it, visible and with its own bounds. A target that is not important for accessibility,
 * under a `DrawerLayout` that has another child on screen (the open drawer), is refused as
 * `OBSCURED` too (device-checked by `DrawerScrimTest`). Other covering views inside the window
 * (a floating bar, an in-window bottom sheet, a custom scrim) are not detected; a Compose
 * `ModalNavigationDrawer` leaves the content out of the tree instead, so it is `NOT_FOUND`.
 */
internal class TouchReachability(
    private val uiAutomation: () -> UiAutomation,
) {
    /**
     * Throws `NOT_INTERACTABLE` / `OBSCURED` when [point] is in a window above [element]'s, or
     * when [element] is under an open `DrawerLayout` drawer.
     */
    fun requireReachable(
        element: UiObject2,
        point: Point,
    ) {
        requireNotUnderOpenDrawer(element.accessibilityNodeInfo)
        // Only the default display's windows are listed; other displays are not checked.
        if (element.displayId != Display.DEFAULT_DISPLAY) return
        val windowId = element.accessibilityNodeInfo.windowId
        val topmost = uiAutomation().windows.filter { touchableRegion(it).contains(point.x, point.y) }.maxByOrNull { it.layer }
        // No window at the point, or the list is momentarily out of date: nothing to refuse on.
        if (topmost == null || topmost.id == windowId) return
        throw CommandFailure(
            ErrorCode.ERR_NOT_INTERACTABLE,
            detail = ErrorDetail.OBSCURED,
            message = "The touch point (${point.x}, ${point.y}) is covered by ${topmost.describe()}",
        )
    }

    /**
     * A node `DrawerLayout` hides while a drawer is open: not important for accessibility (a
     * `DrawerLayout` sets that on its content only then; an ordinary clickable view is important
     * on its own), with a `DrawerLayout` ancestor that has a child on screen other than the
     * branch holding [node]: the drawer. Both are needed, because apps also mark views not
     * important themselves, and many wrap their whole content in a `DrawerLayout`.
     */
    private fun requireNotUnderOpenDrawer(node: AccessibilityNodeInfo) {
        if (node.isImportantForAccessibility) return
        var branch = node
        var parent = node.parent
        while (parent != null) {
            if (parent.className?.endsWith(DRAWER_LAYOUT) == true && parent.hasOtherVisibleChild(branch)) {
                throw CommandFailure(
                    ErrorCode.ERR_NOT_INTERACTABLE,
                    detail = ErrorDetail.OBSCURED,
                    message = "The target is under an open drawer of ${parent.className}: a touch on it would close the drawer",
                )
            }
            branch = parent
            parent = parent.parent
        }
    }

    private fun AccessibilityNodeInfo.hasOtherVisibleChild(branch: AccessibilityNodeInfo): Boolean =
        (0 until childCount).any { index ->
            val child = getChild(index) ?: return@any false
            child != branch && child.isVisibleToUser
        }

    private fun AccessibilityWindowInfo.describe(): String {
        val packageName = root?.packageName
        return listOfNotNull(title?.let { "window \"$it\"" } ?: "a window", packageName?.let { "of $it" })
            .joinToString(" ")
    }

    private companion object {
        /** `androidx.drawerlayout.widget.DrawerLayout` and the support library's `android.support.v4.widget.DrawerLayout`. */
        const val DRAWER_LAYOUT = ".DrawerLayout"
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
