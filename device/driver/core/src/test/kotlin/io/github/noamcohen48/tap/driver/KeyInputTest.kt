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
        keys.record(KeyEvent.ACTION_DOWN, 29, injected = true)
        keys.record(KeyEvent.ACTION_UP, 29, injected = true)
        keys.record(KeyEvent.ACTION_DOWN, 59, injected = true)
        keys.record(KeyEvent.ACTION_DOWN, 30, injected = false)

        val released = mutableListOf<Int>()
        assertTrue(keys.releaseAll { released += it; true })
        assertEquals(listOf(59), released)
        assertTrue(keys.isEmpty)
    }

    @Test
    fun releaseRetriesAndReportsAKeyThatStaysDown() {
        val keys = PressedKeys()
        keys.record(KeyEvent.ACTION_DOWN, 59, injected = true)
        var attempts = 0

        assertFalse(keys.releaseAll(attempts = 3) { attempts++; false })
        assertEquals(3, attempts)
        assertFalse(keys.isEmpty)
    }

    @Test
    fun releaseStopsRetryingOnceInjected() {
        val keys = PressedKeys()
        keys.record(KeyEvent.ACTION_DOWN, 59, injected = true)
        var attempts = 0

        assertTrue(keys.releaseAll(attempts = 3) { ++attempts == 2 })
        assertEquals(2, attempts)
    }
}
