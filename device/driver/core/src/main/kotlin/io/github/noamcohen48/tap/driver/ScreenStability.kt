package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.StabilitySignal
import io.github.noamcohen48.tap.api.v1.WaitScreenStable
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.DEFAULT_STABLE_FOR_MS
import io.github.noamcohen48.tap.protocol.ErrorDetail
import java.util.concurrent.TimeoutException

/**
 * `wait_screen_stable`: succeeds once the package's focused window has been quiet for
 * `stable_for_ms` (default [DEFAULT_STABLE_FOR_MS]) over `signal` (default ALL) — no
 * `WINDOW_CONTENT_CHANGED` events from it, an unchanged accessibility tree fingerprint and
 * (down-sampled) pixels. This is an explicit wait a test asks for; no other command settles
 * implicitly. Reads raw accessibility windows so a running animation cannot stall the sampler
 * in UiAutomator's idle wait.
 */
internal class ScreenStability(
    private val instrumentation: Instrumentation,
) {
    fun waitScreenStable(
        context: CommandContext,
        command: WaitScreenStable,
    ) {
        val packageName = command.packageName
        val stableFor = if (command.hasStableForMs()) command.stableForMs else DEFAULT_STABLE_FOR_MS
        val signal = if (command.signal == StabilitySignal.STABILITY_UNSPECIFIED) StabilitySignal.STABILITY_ALL else command.signal
        var reference: ScreenSample? = null
        var stableSince = 0L
        var windowSeen = false
        while (true) {
            context.checkCancelled()
            val now = context.nowMs()
            val sample = sampleScreen(packageName, signal)
            when {
                sample == null -> reference = null
                reference == null || sample.changedFrom(reference) -> {
                    windowSeen = true
                    reference = sample
                    stableSince = now
                }
                now - stableSince >= stableFor -> return
            }
            val remaining = context.remainingMs()
            if (remaining <= 0) break
            val slice = minOf(remaining, if (reference == null) 50L else 100L)
            // Event-driven early exit: a content or state change from the package restarts the
            // quiet period. UiAutomation directly, not UiDevice.waitForWindowUpdate, which first
            // runs the implicit idle wait a changing screen can never satisfy.
            val before = context.nowMs()
            if (awaitWindowEvent(packageName, slice)) reference = null
            val waited = context.nowMs() - before
            if (waited < slice) context.sleep(slice - waited)
        }
        throw CommandFailure(
            ErrorCode.ERR_WAIT_TIMEOUT,
            detail = if (windowSeen) ErrorDetail.SCREEN_CHANGING else ErrorDetail.APP_NOT_VISIBLE,
        )
    }

    private fun awaitWindowEvent(
        packageName: String,
        timeoutMs: Long,
    ): Boolean =
        try {
            instrumentation.uiAutomation.executeAndWaitForEvent(
                {},
                { event ->
                    (
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    ) &&
                        event.packageName?.toString() == packageName
                },
                timeoutMs,
            )
            true
        } catch (_: TimeoutException) {
            false
        }

    /** The focused application window of [packageName], or null when it has none right now. */
    private fun sampleScreen(
        packageName: String,
        signal: StabilitySignal,
    ): ScreenSample? {
        val windows = instrumentation.uiAutomation.windows ?: return null
        var root: AccessibilityNodeInfo? = null
        val window =
            windows.firstOrNull { candidate ->
                if (candidate.type != AccessibilityWindowInfo.TYPE_APPLICATION || !candidate.isFocused) return@firstOrNull false
                // One root fetch per window: it is both the package check and the fingerprint root.
                val candidateRoot = rootOf(candidate) ?: return@firstOrNull false
                if (candidateRoot.packageName?.toString() == packageName) {
                    root = candidateRoot
                    true
                } else {
                    recycleQuietly(candidateRoot)
                    false
                }
            } ?: return null
        val windowRoot = root ?: return null
        val bounds = Rect().also(window::getBoundsInScreen)
        val treeHash =
            try {
                if (signal == StabilitySignal.STABILITY_PIXELS) 0L else fingerprint(windowRoot)
            } finally {
                recycleQuietly(windowRoot)
            }
        val pixels = if (signal == StabilitySignal.STABILITY_TREE) IntArray(0) else samplePixels(bounds)
        return ScreenSample(treeHash, pixels)
    }

    /** The window's root node; null when it has none or the window went away mid-read. */
    private fun rootOf(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        try {
            window.root
        } catch (_: RuntimeException) {
            null
        }

    private fun fingerprint(root: AccessibilityNodeInfo): Long {
        val hash = TreeFingerprint()
        var nodes = 0
        val bounds = Rect()

        fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
        ) {
            if (depth > MAX_FINGERPRINT_DEPTH || ++nodes > MAX_FINGERPRINT_NODES) return
            node.getBoundsInScreen(bounds)
            hash.mix(node.className)
            hash.mix(node.viewIdResourceName)
            hash.mix(node.text?.toString())
            hash.mix(node.contentDescription?.toString())
            hash.mix(bounds.left)
            hash.mix(bounds.top)
            hash.mix(bounds.right)
            hash.mix(bounds.bottom)
            hash.mix(node.isVisibleToUser)
            hash.mix(node.isEnabled)
            hash.mix(node.isChecked)
            hash.mix(node.isSelected)
            hash.mix(node.isFocused)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    recycleQuietly(child)
                }
            }
        }
        visit(root, 0)
        return hash.value
    }

    /** A coarse grid of quantised pixels inside [bounds]; empty when the capture fails. */
    private fun samplePixels(bounds: Rect): IntArray {
        val captured = instrumentation.uiAutomation.takeScreenshot() ?: return IntArray(0)
        val bitmap =
            if (captured.config == Bitmap.Config.HARDWARE) {
                captured.copy(Bitmap.Config.ARGB_8888, false).also { captured.recycle() }
            } else {
                captured
            }
        try {
            // intersect() leaves the rectangle untouched when there is no overlap, so its result
            // decides: sampling the unclipped window would read outside the bitmap.
            val area = Rect(bounds)
            if (!area.intersect(0, 0, bitmap.width, bitmap.height) || area.isEmpty) return IntArray(0)
            return PixelGrid.sample(area.left, area.top, area.width(), area.height(), bitmap::getPixel)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_FINGERPRINT_NODES = 4_000
        const val MAX_FINGERPRINT_DEPTH = 64
    }
}

/**
 * One stability sample: the accessibility tree fingerprint and the quantised pixel grid (either
 * may be absent, per the requested signal: 0 / empty).
 */
internal class ScreenSample(
    val treeHash: Long,
    val pixels: IntArray,
) {
    /**
     * Changed when the tree differs, the grid size differs, or more than [PIXEL_DIFF_THRESHOLD]
     * of the grid points differ (a blinking caret or the status-bar clock stay below it).
     */
    fun changedFrom(other: ScreenSample): Boolean {
        if (treeHash != other.treeHash) return true
        if (pixels.size != other.pixels.size) return true
        if (pixels.isEmpty()) return false
        var differing = 0
        for (i in pixels.indices) if (pixels[i] != other.pixels[i]) differing++
        return differing > pixels.size * PIXEL_DIFF_THRESHOLD
    }

    companion object {
        /** 0.5 % of the grid, as in Maestro's screen-static check. */
        const val PIXEL_DIFF_THRESHOLD = 0.005
    }
}

/** FNV-1a style 64-bit hash over the node properties the stability wait watches. */
internal class TreeFingerprint {
    var value: Long = OFFSET_BASIS
        private set

    fun mix(property: Any?) {
        value = (value xor (property?.hashCode() ?: 0).toLong()) * PRIME
    }

    private companion object {
        const val OFFSET_BASIS = 1_469_598_103_934_665_603L
        const val PRIME = 1_099_511_628_211L
    }
}

/**
 * The down-sampled pixel grid of `wait_screen_stable`: [COLUMNS] x [ROWS] cell centres
 * (4 608 points) of an area, each with the low bits of every channel dropped so compression and
 * dithering noise do not register.
 */
internal object PixelGrid {
    const val COLUMNS = 48
    const val ROWS = 96

    /** Samples the [width] x [height] area at ([left], [top]) through [pixel] (x, y) → ARGB. */
    inline fun sample(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        pixel: (x: Int, y: Int) -> Int,
    ): IntArray {
        val result = IntArray(COLUMNS * ROWS)
        var i = 0
        for (row in 0 until ROWS) {
            val y = top + (height * (2 * row + 1)) / (2 * ROWS)
            for (column in 0 until COLUMNS) {
                val x = left + (width * (2 * column + 1)) / (2 * COLUMNS)
                result[i++] = pixel(x, y) and QUANTISE_MASK
            }
        }
        return result
    }

    const val QUANTISE_MASK = 0x00F0F0F0
}

/** Recycles a raw node info (a no-op from API 33), ignoring one that was already recycled. */
@Suppress("DEPRECATION")
internal fun recycleQuietly(node: AccessibilityNodeInfo) {
    try {
        node.recycle()
    } catch (_: IllegalStateException) {
        // Already recycled.
    }
}
