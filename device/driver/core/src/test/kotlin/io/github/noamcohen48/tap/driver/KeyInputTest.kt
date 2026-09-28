package io.github.noamcohen48.tap.driver

import android.view.KeyEvent
import io.github.noamcohen48.tap.protocol.KEYCODE_BACK
import io.github.noamcohen48.tap.protocol.KEYCODE_HOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyInputTest {
    @Test
    fun backAndHomeUseTheDedicatedCallsEverythingElseIsAKeyCode() {
        assertEquals(KeyPress.Back, KeyPress.of(KEYCODE_BACK))
        assertEquals(KeyPress.Home, KeyPress.of(KEYCODE_HOME))
        assertEquals(KeyPress.Code(66), KeyPress.of(66))
    }

    @Test
    fun onlyInjectedDownsWithoutAnInjectedUpStayPressed() {
        val keys = PressedKeys()
        keys.record(KeyEvent.ACTION_DOWN, 29, downTime = 100, injected = true)
        keys.record(KeyEvent.ACTION_UP, 29, downTime = 100, injected = true)
        keys.record(KeyEvent.ACTION_DOWN, 59, downTime = 200, injected = true)
        keys.record(KeyEvent.ACTION_DOWN, 30, downTime = 300, injected = false)

        val released = mutableListOf<Pair<Int, Long>>()
        assertTrue(keys.releaseAll { keyCode, downTime -> released += keyCode to downTime; true })
        assertEquals(listOf(59 to 200L), released)
        assertTrue(keys.isEmpty)
    }

    @Test
    fun aReleaseCarriesTheDownTimeOfItsPress() {
        val keys = PressedKeys()
        assertEquals(100L, keys.downTimeFor(KeyEvent.ACTION_DOWN, 59, now = 100))
        keys.record(KeyEvent.ACTION_DOWN, 59, downTime = 100, injected = true)

        assertEquals(100L, keys.downTimeFor(KeyEvent.ACTION_UP, 59, now = 150))
        assertEquals(150L, keys.downTimeFor(KeyEvent.ACTION_DOWN, 29, now = 150))
        assertEquals(150L, keys.downTimeFor(KeyEvent.ACTION_UP, 29, now = 150))
    }

    @Test
    fun releaseRetriesAndReportsAKeyThatStaysDown() {
        val keys = PressedKeys()
        keys.record(KeyEvent.ACTION_DOWN, 59, downTime = 1, injected = true)
        var attempts = 0

        assertFalse(keys.releaseAll(attempts = 3) { _, _ -> attempts++; false })
        assertEquals(3, attempts)
        assertFalse(keys.isEmpty)
    }

    @Test
    fun releaseStopsRetryingOnceInjected() {
        val keys = PressedKeys()
        keys.record(KeyEvent.ACTION_DOWN, 59, downTime = 1, injected = true)
        var attempts = 0

        assertTrue(keys.releaseAll(attempts = 3) { _, _ -> ++attempts == 2 })
        assertEquals(2, attempts)
    }
}
