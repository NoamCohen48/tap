package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.WindowKind

/**
 * What each window of a dump is ([WindowKind]), by zero-based window ordinal. Android's window type
 * says application, input method, system, …; the status and navigation bars are found by their
 * geometry, since Android gives them no type of their own and only the navigation bar has a
 * (translated) title: a `com.android.systemui` system window that runs along one whole edge of the
 * display and is at most [BAR_MAX_DP] thick. A dump without the display's size and density has no
 * bars. Measured on an API 34 emulator and an API 29 Samsung, portrait and landscape.
 */
internal object WindowKinds {
    /** The thickest a system bar is: status bars are 24 dp (taller around a cutout), navigation bars 48 dp. */
    const val BAR_MAX_DP = 64
    private const val SYSTEM_UI = "com.android.systemui"

    fun of(hierarchy: Hierarchy): Map<Int, WindowKind> =
        hierarchy.nodes
            .groupBy { it.window }
            .mapValues { (_, nodes) -> kind(nodes.first(), hierarchy) }

    private fun kind(
        root: DumpNode,
        hierarchy: Hierarchy,
    ): WindowKind {
        val type = byType(root.windowType)
        if (type != WindowKind.WINDOW_KIND_SYSTEM || root.windowPackage != SYSTEM_UI) return type
        return bar(root, hierarchy.displayWidth, hierarchy.displayHeight, hierarchy.densityDpi) ?: type
    }

    private fun byType(type: Int): WindowKind =
        when (type) {
            1 -> WindowKind.WINDOW_KIND_APPLICATION
            2 -> WindowKind.WINDOW_KIND_INPUT_METHOD
            3 -> WindowKind.WINDOW_KIND_SYSTEM
            4 -> WindowKind.WINDOW_KIND_ACCESSIBILITY_OVERLAY
            5 -> WindowKind.WINDOW_KIND_SPLIT_SCREEN_DIVIDER
            6 -> WindowKind.WINDOW_KIND_MAGNIFICATION_OVERLAY
            else -> WindowKind.WINDOW_KIND_UNSPECIFIED
        }

    /** STATUS_BAR or NAVIGATION_BAR when the window's root lies along one display edge and is thin; else null. */
    private fun bar(
        root: DumpNode,
        width: Int,
        height: Int,
        density: Int,
    ): WindowKind? {
        val (left, top, right, bottom) = root.bounds ?: return null
        if (width <= 0 || height <= 0 || density <= 0) return null
        val across = left <= 1 && right >= width - 1
        val down = top <= 1 && bottom >= height - 1
        val (thickness, kind) =
            when {
                across && top <= 0 -> bottom - top to WindowKind.WINDOW_KIND_STATUS_BAR
                across && bottom >= height -> bottom - top to WindowKind.WINDOW_KIND_NAVIGATION_BAR
                down && (left <= 0 || right >= width) -> right - left to WindowKind.WINDOW_KIND_NAVIGATION_BAR
                else -> return null
            }
        return kind.takeIf { thickness > 0 && thickness * 160 <= BAR_MAX_DP * density }
    }
}
