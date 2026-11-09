package io.github.noamcohen48.tap.driver

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Where a window really is on screen.
 *
 * Some devices report an accessibility window's bounds moved up and left by the window's surface
 * insets: the margin a window with an elevation (a dialog, a popup, a menu) draws its shadow in.
 * On a Samsung SM-J810G (API 29) a dialog whose window manager frame is `[18,545][701,893]`, with
 * 56 px surface insets, reports `(-38,489)-(645,837)`; a Material dialog with 21 px insets is off
 * by 21 px, a popup without an elevation is exact, and an emulator (API 34) is exact throughout.
 * Its root node's bounds are not moved. Clipping a node to the moved window, as UiAutomator does
 * for `visibleBounds`, cuts the window's last inset-width of pixels off at the right and bottom,
 * and the occlusion check below API 33 read the moved rectangle as the window's touch area.
 *
 * So when the root node has exactly the window's size but another origin, the root's bounds
 * are the frame. A root of another size is not a moved window: the keyboard and the notification
 * shade take touches on part of their surface only, and the window's bounds say which part.
 */
internal object WindowFrames {
    /** The window's frame in screen coordinates (see the class comment). */
    fun frame(window: AccessibilityWindowInfo): Rect {
        val reported = Rect().also(window::getBoundsInScreen)
        val root =
            try {
                window.root
            } catch (_: RuntimeException) {
                null
            } ?: return reported
        return try {
            frame(reported, Rect().also(root::getBoundsInScreen))
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    /** [reported] corrected by the window's [root] bounds, when they show it was moved. */
    fun frame(
        reported: Rect,
        root: Rect,
    ): Rect {
        val corrected =
            correctedFrame(
                Box(reported.left, reported.top, reported.right, reported.bottom),
                Box(root.left, root.top, root.right, root.bottom),
            )
        return Rect(corrected.left, corrected.top, corrected.right, corrected.bottom)
    }

    /** The rule, on plain numbers: the root's box when it is the reported box moved, else the reported one. */
    fun correctedFrame(
        reported: Box,
        root: Box,
    ): Box {
        val sameSize = root.width == reported.width && root.height == reported.height
        val moved = root.left != reported.left || root.top != reported.top
        return if (sameSize && moved && root.width > 0 && root.height > 0) root else reported
    }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
}

/**
 * A node's visible bounds the way `UiObject2.getVisibleBounds` computes them (uiautomator 2.4.0
 * `AccessibilityNodeInfoHelper.getVisibleBoundsInScreen`): its screen bounds clipped to the
 * display, then to its window, then to the visible bounds of its nearest scrollable ancestor,
 * each clip skipped when it does not overlap. The one difference is the window: its
 * [WindowFrames.frame], not the bounds the accessibility service reports, which some devices move.
 * Written independently from the AndroidX behaviour, not copied.
 */
internal class VisibleBounds(
    private val context: Context,
) {
    fun of(node: AccessibilityNodeInfo): Rect = of(node, display(node), trimByScrollable = true)

    fun center(node: AccessibilityNodeInfo): Point = of(node).let { Point(it.centerX(), it.centerY()) }

    private fun of(
        node: AccessibilityNodeInfo,
        display: Rect,
        trimByScrollable: Boolean,
    ): Rect {
        val bounds = Rect().also(node::getBoundsInScreen)
        bounds.clipTo(display)
        node.window?.let { bounds.clipTo(WindowFrames.frame(it)) }
        if (trimByScrollable) {
            var ancestor = node.parent
            while (ancestor != null) {
                if (ancestor.isScrollable) {
                    bounds.clipTo(of(ancestor, display, trimByScrollable = true))
                    break
                }
                ancestor = ancestor.parent
            }
        }
        return bounds
    }

    /** The display the node is on, as `Rect(0, 0, width, height)` of its real size. */
    private fun display(node: AccessibilityNodeInfo): Rect {
        val displayId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.window?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY
        val display =
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId)
                ?: return Rect()
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return Rect(0, 0, size.x, size.y)
    }

    /** `Rect.intersect`, which leaves the rectangle as it is when the two do not overlap. */
    private fun Rect.clipTo(other: Rect) {
        intersect(other)
    }
}

/** The window's touchable region from API 33; before it, its real frame ([WindowFrames]). */
internal fun touchableRegion(window: AccessibilityWindowInfo): Region =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Region().also(window::getRegionInScreen)
    } else {
        Region(WindowFrames.frame(window))
    }

internal fun Rect.toBox() = WindowFrames.Box(left, top, right, bottom)
