package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.driver.WindowFrames.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowFramesTest {
    @Test
    fun aWindowReportedMovedByItsSurfaceInsetsTakesItsRootsFrame() {
        // SM-J810G (API 29): a dialog with 56 px surface insets, frame [18,545][701,893].
        val reported = Box(-38, 489, 645, 837)
        val root = Box(18, 545, 701, 893)
        assertEquals(root, WindowFrames.correctedFrame(reported, root))
    }

    @Test
    fun aWindowReportedWhereItsRootIsKeepsItsBounds() {
        val bounds = Box(28, 943, 1052, 1456)
        assertEquals(bounds, WindowFrames.correctedFrame(bounds, bounds))
    }

    @Test
    fun aRootLargerThanTheReportedBoundsDoesNotReplaceThem() {
        // The keyboard and the shade take touches on part of their surface: the bounds say which.
        val keyboard = Box(0, 1517, 1080, 2400)
        val surface = Box(0, 63, 1080, 2400)
        assertEquals(keyboard, WindowFrames.correctedFrame(keyboard, surface))
    }

    @Test
    fun anEmptyRootDoesNotReplaceTheReportedBounds() {
        val reported = Box(10, 10, 20, 20)
        assertEquals(reported, WindowFrames.correctedFrame(reported, Box(30, 30, 30, 30)))
    }
}
