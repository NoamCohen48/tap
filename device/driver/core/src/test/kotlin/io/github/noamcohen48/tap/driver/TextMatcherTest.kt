package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.MatchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {
    @Test
    fun unspecifiedModeIsExact() {
        assertTrue(TextMatcher(MatchMode.MATCH_UNSPECIFIED, "Save").matches("Save"))
        assertFalse(TextMatcher(MatchMode.MATCH_UNSPECIFIED, "Save").matches("Save all"))
    }

    @Test
    fun substringModes() {
        assertTrue(TextMatcher(MatchMode.MATCH_CONTAINS, "ave").matches("Save all"))
        assertTrue(TextMatcher(MatchMode.MATCH_STARTS_WITH, "Save").matches("Save all"))
        assertTrue(TextMatcher(MatchMode.MATCH_ENDS_WITH, "all").matches("Save all"))
        assertFalse(TextMatcher(MatchMode.MATCH_ENDS_WITH, "Save").matches("Save all"))
    }

    @Test
    fun regexMustMatchTheWholeValue() {
        val matcher = TextMatcher(MatchMode.MATCH_REGEX, "Item [0-9]+")

        assertTrue(matcher.matches("Item 42"))
        assertFalse(matcher.matches("Item 42 selected"))
    }

    @Test
    fun anAbsentPropertyNeverMatches() {
        assertFalse(TextMatcher(MatchMode.MATCH_CONTAINS, "").matches(null))
    }
}
