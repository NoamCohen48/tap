package io.github.noamcohen48.tap.driver

import android.app.UiAutomation
import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.driver.WindowFrames.Box
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail
import kotlin.math.roundToInt

/**
 * Where `tap_outside_panel` touches: outside the panel (dialog, popup, menu, drawer, bottom
 * sheet) that holds a node.
 *
 * A node outside a modal panel cannot be selected: while a window that takes every touch is
 * open, Android leaves the windows below it out of the accessibility window list. So the test
 * names a node inside the panel, and the panel is found from it:
 *
 * 1. **In-window panel.** The nearest node from the selected one up that offers
 *    `ACTION_DISMISS` or has a pane title, and is smaller than its window. Android sets these on
 *    in-window panels: a `DrawerLayout` drawer and a Material `BottomSheetBehavior` sheet offer
 *    dismiss, a Compose `ModalNavigationDrawer` sheet offers it and is titled "Navigation menu",
 *    a Compose `ModalBottomSheet` is titled "Bottom Sheet" (device-checked, API 29 and 34). The
 *    touch goes in the middle of the free space beside the panel, inside the panel's nearest
 *    ancestor that contains it and is larger (where a scrim lies).
 *
 *    Other nodes carry these marks too (a Snackbar offers dismiss, a screen may have a pane
 *    title), and beside them is ordinary content. So the point is accepted only when what is
 *    there would not take the touch as a press of its own: no actionable node, a scrim (a
 *    clickable node covering the whole free strip, not around the panel) or content the panel
 *    hid from accessibility (a `DrawerLayout` marks its content not important while a drawer is
 *    open). A point on any other control is skipped.
 * 2. **Window.** Otherwise, or when no side of the in-window panel is accepted, the selected
 *    node's window by its real frame ([WindowFrames]). The touch goes past the frame by the window
 *    touch slop plus 8 dp: platform dialogs ignore touches within the slop of their frame
 *    (`Window.isOutOfBounds`).
 *
 * Sides are tried below, above, right, left ([Rules]). A side is skipped when its point is off
 * the display or on the system bars' insets (the gesture area is not a listed window), on a
 * system window (bars, keyboard) or on another app window above the panel's; a point outside the
 * panel's window, in an app window below it (a panel that lets touches through to the screen
 * under it), is used only when the command allows it. The pane titles are localized; only their
 * presence is read.
 */
internal class OutsidePanel(
    private val context: Context,
    private val uiAutomation: () -> UiAutomation,
) {
    /** The point outside [node]'s panel, with a description of the panel and side for reports. */
    fun place(
        node: AccessibilityNodeInfo,
        allowReachBelow: Boolean,
    ): Placement {
        val window =
            node.window ?: throw CommandFailure(ErrorCode.ERR_STALE_BEFORE_INPUT, message = "The element's window is gone; nothing was sent")
        val windowFrame = WindowFrames.frame(window).toBox()
        val density = context.resources.displayMetrics.density
        val display = display(window)
        val usable = Rules.inset(display, barInsets(display))
        val others = uiAutomation().windows.filter { it.id != window.id }.map(::neighbour)
        val windowDescription = "window${window.title?.let { " \"$it\"" } ?: ""} ${windowFrame.text()}"

        val panel = panelNode(node, windowFrame)
        val inPanel =
            panel?.let {
                val bounds = Rect().also(it::getBoundsInScreen).toBox()
                val space = Rules.intersect(containerBounds(it, bounds) ?: windowFrame, windowFrame)
                val candidates = Rules.besideInside(bounds, space, (MIN_GAP_DP * density).roundToInt())
                val root = window.root
                Rules.Attempt(
                    description = "${it.describePanel()} ${bounds.text()}",
                    candidates = candidates,
                    land = { c ->
                        hitBlock(root, it, c)?.let(Landing::Blocked) ?: Rules.landing(c.x, c.y, usable, others, window.layer, insideOwnWindow = true)
                    },
                )
            }
        val margin = ViewConfiguration.get(context).scaledWindowTouchSlop + (MARGIN_DP * density).roundToInt()
        val pastWindow =
            Rules.Attempt(
                description = windowDescription,
                candidates = Rules.pastFrame(windowFrame, display, margin),
                land = { c -> Rules.landing(c.x, c.y, usable, others, window.layer, insideOwnWindow = false) },
            )
        return when (val decision = Rules.decide(inPanel, pastWindow, allowReachBelow)) {
            is Rules.Decision.Touch -> Placement(Point(decision.candidate.x, decision.candidate.y), decision.description)
            is Rules.Decision.ReachesBelow ->
                throw CommandFailure(
                    ErrorCode.ERR_NOT_INTERACTABLE,
                    detail = ErrorDetail.TOUCH_REACHES_BELOW,
                    message = "Every free point outside ${decision.description} is in another app window below it (${decision.tried}); " +
                        "the panel lets touches through, so allow reaching below to send it anyway",
                )
            is Rules.Decision.Nowhere ->
                throw CommandFailure(ErrorCode.ERR_NOT_INTERACTABLE, detail = ErrorDetail.NO_OUTSIDE_POINT, message = "No point to touch outside ${decision.tried}")
        }
    }

    /** The nearest node from [node] up that marks an in-window panel smaller than its window. */
    private fun panelNode(
        node: AccessibilityNodeInfo,
        windowFrame: Box,
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.marksPanel() && Rect().also(current::getBoundsInScreen).toBox() != windowFrame) return current
            current = current.parent
        }
        return null
    }

    /** The bounds of [panel]'s nearest ancestor that contains it and is larger: the space a scrim beside it lies in. */
    private fun containerBounds(
        panel: AccessibilityNodeInfo,
        bounds: Box,
    ): Box? {
        var ancestor = panel.parent
        while (ancestor != null) {
            val box = Rect().also(ancestor::getBoundsInScreen).toBox()
            if (box != bounds && Rules.containsBox(box, bounds)) return box
            ancestor = ancestor.parent
        }
        return null
    }

    /**
     * Why the in-window point [c] beside [panel] would press something of its own, or null when
     * it would not: the deepest visible node there, topmost by drawing order, that is important
     * for accessibility and actionable is none, or a scrim (clickable, covering the whole free
     * strip, not an ancestor of the panel).
     */
    private fun hitBlock(
        root: AccessibilityNodeInfo?,
        panel: AccessibilityNodeInfo,
        c: Rules.Candidate,
    ): String? {
        var node = root ?: return "content that could not be read"
        var actionable: AccessibilityNodeInfo? = null
        while (true) {
            if (node.isImportantForAccessibility && node.isActionable()) actionable = node
            val child =
                (0 until node.childCount).mapNotNull(node::getChild)
                    .filter { it.isVisibleToUser && Rect().also(it::getBoundsInScreen).contains(c.x, c.y) }
                    .maxByOrNull { it.drawingOrder } ?: break
            node = child
        }
        val hit = actionable ?: return null
        val bounds = Rect().also(hit::getBoundsInScreen).toBox()
        val strip = c.strip ?: return null
        if (hit.isClickable && Rules.containsBox(bounds, strip) && !hit.isAncestorOf(panel)) return null
        return "a control (${hit.label()} ${bounds.text()}), not a scrim"
    }

    private fun AccessibilityNodeInfo.isActionable(): Boolean =
        isClickable || isLongClickable || isCheckable || isEditable ||
            actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK || it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }

    private fun AccessibilityNodeInfo.isAncestorOf(other: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = other
        while (current != null) {
            if (current == this) return true
            current = current.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.label(): String =
        listOfNotNull(text, contentDescription, viewIdResourceName).map(CharSequence::toString).firstOrNull(String::isNotEmpty)?.let { "\"$it\"" }
            ?: className?.toString()?.substringAfterLast('.').orEmpty()

    private fun neighbour(window: AccessibilityWindowInfo): Rules.Neighbour {
        val region = touchableRegion(window)
        return Rules.Neighbour(
            contains = region::contains,
            application = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
            layer = window.layer,
            description = window.title?.let { "window \"$it\"" } ?: "a window",
        )
    }

    private fun display(window: AccessibilityWindowInfo): Box {
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.displayId else Display.DEFAULT_DISPLAY
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId) ?: return Box(0, 0, 0, 0)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return Box(0, 0, size.x, size.y)
    }

    /**
     * The thickness of the system bars and the bottom gesture area on each side of [display], as
     * a [Box] of insets. The gesture area of gesture navigation is not an accessibility window, so
     * the window check alone would let a point land there. API 30+: the window manager's insets;
     * before, or if they cannot be read, the platform's bar dimensions.
     */
    private fun barInsets(display: Box): Box {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
                val types = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement() or WindowInsets.Type.mandatorySystemGestures()
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(types)
                return Box(insets.left, insets.top, insets.right, insets.bottom)
            } catch (_: RuntimeException) {
                // Fall back to the dimensions below.
            }
        }
        val system = Resources.getSystem()
        fun dimen(name: String): Int = system.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(system::getDimensionPixelSize) ?: 0
        val portrait = display.height >= display.width
        return if (portrait) {
            Box(0, dimen("status_bar_height"), 0, dimen("navigation_bar_height"))
        } else {
            Box(0, dimen("status_bar_height"), dimen("navigation_bar_width"), dimen("navigation_bar_height_landscape"))
        }
    }

    private fun AccessibilityNodeInfo.marksPanel(): Boolean =
        actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !paneTitle.isNullOrEmpty())

    private fun AccessibilityNodeInfo.describePanel(): String {
        val pane = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) paneTitle?.toString()?.takeIf(String::isNotEmpty) else null
        return pane?.let { "pane \"$it\"" } ?: viewIdResourceName?.let { "dismissible $it" } ?: "dismissible ${className?.toString()?.substringAfterLast('.')}"
    }

    /** Where to touch and what was chosen, for the result and logs. */
    data class Placement(val point: Point, val description: String)

    sealed interface Landing {
        data object Free : Landing

        data class Blocked(val what: String) : Landing

        data class Below(val what: String) : Landing
    }

    /** The rules on plain data, so they are unit-tested without a device. */
    object Rules {
        val SIDES = listOf("below", "above", "right", "left")

        /** A point to try; [strip] is the free strip it lies in, for an in-window panel. */
        data class Candidate(val side: String, val x: Int, val y: Int, val strip: Box? = null)

        /** Another window on screen: its touchable area, whether it is an app window, its layer. */
        class Neighbour(val contains: (Int, Int) -> Boolean, val application: Boolean, val layer: Int, val description: String)

        /** One way of touching outside: a panel's [description], its [candidates] in side order and what each lands on. */
        class Attempt(val description: String, val candidates: List<Candidate>, val land: (Candidate) -> Landing)

        sealed interface Decision {
            data class Touch(val candidate: Candidate, val description: String) : Decision

            data class ReachesBelow(val description: String, val tried: String) : Decision

            data class Nowhere(val tried: String) : Decision
        }

        /**
         * The in-window panel's first free point if there is one, else the window's; a point in an
         * app window below the panel's is free only with [allowReachBelow].
         */
        fun decide(
            inPanel: Attempt?,
            pastWindow: Attempt,
            allowReachBelow: Boolean,
        ): Decision {
            val tried = mutableListOf<String>()
            var below = false
            for (attempt in listOfNotNull(inPanel, pastWindow)) {
                val notes = mutableListOf<String>()
                // A free side wins over one that reaches a window below, even when that is allowed.
                var reaching: Decision.Touch? = null
                for (candidate in attempt.candidates) {
                    when (val landing = attempt.land(candidate)) {
                        Landing.Free -> return Decision.Touch(candidate, "${attempt.description}, ${candidate.side}")
                        is Landing.Below -> {
                            if (allowReachBelow && reaching == null) {
                                reaching = Decision.Touch(candidate, "${attempt.description}, ${candidate.side}, reaching ${landing.what}")
                            }
                            below = true
                            notes += "${candidate.side} over ${landing.what}"
                        }
                        is Landing.Blocked -> notes += "${candidate.side} on ${landing.what}"
                    }
                }
                reaching?.let { return it }
                val sides = SIDES.joinToString { side -> notes.firstOrNull { it.startsWith("$side ") } ?: "$side has no room" }
                tried += "${attempt.description} ($sides)"
            }
            return if (below) Decision.ReachesBelow(pastWindow.description, tried.joinToString("; ")) else Decision.Nowhere(tried.joinToString("; "))
        }

        /**
         * What a touch at ([x], [y]) reaches besides the panel's window: off the [usable] area
         * (bars, gesture area) or on a system window or an app window above the panel's
         * ([ownLayer]) is blocked; inside the panel's own window nothing below matters; outside
         * it, an app window below is [Landing.Below].
         */
        fun landing(
            x: Int,
            y: Int,
            usable: Box,
            others: List<Neighbour>,
            ownLayer: Int,
            insideOwnWindow: Boolean,
        ): Landing {
            if (!contains(usable, x, y)) return Landing.Blocked("the system bars")
            val hits = others.filter { it.contains(x, y) }
            hits.firstOrNull { !it.application }?.let { return Landing.Blocked(it.description) }
            hits.firstOrNull { it.layer > ownLayer }?.let { return Landing.Blocked(it.description) }
            if (!insideOwnWindow) hits.firstOrNull()?.let { return Landing.Below(it.description) }
            return Landing.Free
        }

        /**
         * One point per side of [frame], [margin] past its edge and centred along it, in
         * [SIDES] order, for the sides whose point is on [display].
         */
        fun pastFrame(
            frame: Box,
            display: Box,
            margin: Int,
        ): List<Candidate> {
            val cx = (frame.left + frame.right) / 2
            val cy = (frame.top + frame.bottom) / 2
            return listOf(
                Candidate("below", cx, frame.bottom + margin),
                Candidate("above", cx, frame.top - margin),
                Candidate("right", frame.right + margin, cy),
                Candidate("left", frame.left - margin, cy),
            ).filter { contains(display, it.x, it.y) }
        }

        /**
         * One point per side of [panel] where [space] leaves at least [minGap] free beside it, in
         * the middle of that free strip and centred along the panel, in [SIDES] order.
         */
        fun besideInside(
            panel: Box,
            space: Box,
            minGap: Int,
        ): List<Candidate> {
            val cx = (panel.left + panel.right) / 2
            val cy = (panel.top + panel.bottom) / 2
            return listOf(
                Candidate("below", cx, (panel.bottom + space.bottom) / 2, Box(space.left, panel.bottom, space.right, space.bottom)),
                Candidate("above", cx, (space.top + panel.top) / 2, Box(space.left, space.top, space.right, panel.top)),
                Candidate("right", (panel.right + space.right) / 2, cy, Box(panel.right, space.top, space.right, space.bottom)),
                Candidate("left", (space.left + panel.left) / 2, cy, Box(space.left, space.top, panel.left, space.bottom)),
            ).filter { c -> c.strip!!.let { if (c.side == "below" || c.side == "above") it.height else it.width } >= minGap }
        }

        fun intersect(
            a: Box,
            b: Box,
        ): Box = Box(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom))

        /** [display] less [insets] (a box of thicknesses per side). */
        fun inset(
            display: Box,
            insets: Box,
        ): Box = Box(display.left + insets.left, display.top + insets.top, display.right - insets.right, display.bottom - insets.bottom)

        fun contains(
            box: Box,
            x: Int,
            y: Int,
        ): Boolean = x >= box.left && x < box.right && y >= box.top && y < box.bottom

        fun containsBox(
            outer: Box,
            inner: Box,
        ): Boolean = outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom
    }

    private companion object {
        /** Beyond the window touch slop, where every panel tried dismissed (8 dp short of it, some did not). */
        const val MARGIN_DP = 8f

        /** The least free space beside an in-window panel worth touching. */
        const val MIN_GAP_DP = 24f
    }
}

private fun Box.text() = "[$left,$top][$right,$bottom]"
