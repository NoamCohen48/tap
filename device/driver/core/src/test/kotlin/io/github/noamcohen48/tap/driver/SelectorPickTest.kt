package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.pickAt
import io.github.noamcohen48.tap.protocol.pickFirst
import io.github.noamcohen48.tap.protocol.toSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorPickTest {
    private val selector = Nodes.text("row").toSelector()

    @Test
    fun exactlyOneIsTheDefaultAndSearchesForASecondMatch() {
        val pick = SelectorPick.of(selector)

        assertTrue(pick.exactlyOne)
        assertEquals(2, pick.wanted)
        assertEquals(SelectorPick.Outcome.Failed(ErrorCode.ERR_NOT_FOUND), pick.choose(0))
        assertEquals(SelectorPick.Outcome.Chosen(0), pick.choose(1))
        assertEquals(SelectorPick.Outcome.Failed(ErrorCode.ERR_AMBIGUOUS), pick.choose(2))
    }

    @Test
    fun firstStopsAtTheFirstMatchAndIsNeverAmbiguous() {
        val pick = SelectorPick.of(selector.pickFirst())

        assertEquals(1, pick.wanted)
        assertEquals(SelectorPick.Outcome.Failed(ErrorCode.ERR_NOT_FOUND), pick.choose(0))
        assertEquals(SelectorPick.Outcome.Chosen(0), pick.choose(1))
    }

    @Test
    fun atNeedsIndexPlusOneMatches() {
        val pick = SelectorPick.of(selector.pickAt(2))

        assertEquals(3, pick.wanted)
        assertEquals(SelectorPick.Outcome.Failed(ErrorCode.ERR_NOT_FOUND), pick.choose(2))
        assertEquals(SelectorPick.Outcome.Chosen(2), pick.choose(3))
    }
}
