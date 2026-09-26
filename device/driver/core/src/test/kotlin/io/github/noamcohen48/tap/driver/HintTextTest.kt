package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HintTextTest {
    @Test
    fun aShownHintIsNoText() {
        assertNull(HintText.displayedText("Email", showingHintText = true))
        assertEquals("a@b.c", HintText.displayedText("a@b.c", showingHintText = false))
        assertNull(HintText.displayedText(null, showingHintText = false))
    }
}
