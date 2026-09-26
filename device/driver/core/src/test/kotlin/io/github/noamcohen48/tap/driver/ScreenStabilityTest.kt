package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStabilityTest {
    private val gridSize = PixelGrid.COLUMNS * PixelGrid.ROWS

    @Test
    fun aDifferentTreeIsAChange() {
        assertTrue(ScreenSample(1, IntArray(0)).changedFrom(ScreenSample(2, IntArray(0))))
        assertFalse(ScreenSample(1, IntArray(0)).changedFrom(ScreenSample(1, IntArray(0))))
    }

    @Test
    fun pixelNoiseBelowTheThresholdIsNotAChange() {
        val reference = IntArray(gridSize)
        val tolerated = (gridSize * ScreenSample.PIXEL_DIFF_THRESHOLD).toInt()

        assertFalse(ScreenSample(0, reference.differingIn(tolerated)).changedFrom(ScreenSample(0, reference)))
        assertTrue(ScreenSample(0, reference.differingIn(tolerated + 1)).changedFrom(ScreenSample(0, reference)))
    }

    @Test
    fun aGridOfAnotherSizeIsAChange() {
        assertTrue(ScreenSample(0, IntArray(0)).changedFrom(ScreenSample(0, IntArray(gridSize))))
    }

    @Test
    fun fingerprintDependsOnEveryPropertyAndItsOrder() {
        fun hash(vararg properties: Any?) = TreeFingerprint().apply { properties.forEach(::mix) }.value

        assertEquals(hash("Button", 10, true), hash("Button", 10, true))
        assertNotEquals(hash("Button", 10, true), hash("Button", 11, true))
        assertNotEquals(hash("a", "b"), hash("b", "a"))
        assertNotEquals(hash(null), hash())
    }

    @Test
    fun pixelGridSamplesCellCentresOfTheAreaAndQuantises() {
        val points = mutableListOf<Pair<Int, Int>>()

        val grid = PixelGrid.sample(left = 10, top = 20, width = 480, height = 960) { x, y -> points += x to y; 0x12345678 }

        assertEquals(gridSize, grid.size)
        assertEquals(10 + 5 to 20 + 5, points.first())
        assertEquals(10 + 475 to 20 + 955, points.last())
        assertArrayEquals(IntArray(gridSize) { 0x12345678 and PixelGrid.QUANTISE_MASK }, grid)
    }

    private fun IntArray.differingIn(count: Int): IntArray = copyOf().also { for (i in 0 until count) it[i] = 1 }
}
