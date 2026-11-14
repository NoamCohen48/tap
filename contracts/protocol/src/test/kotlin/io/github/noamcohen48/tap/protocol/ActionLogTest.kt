package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionLogTest {
    private val pay = Selectors.text("Pay")

    @Test
    fun aStartedLineNamesTheOperationAndItsTargetAndParsesBack() {
        val line = ActionLog.started(42, Commands.tap(pay))

        assertEquals("> #42 tap ${pay.render()}", line)
        assertEquals(ActionLog.Line(42, "tap", finished = false, target = pay.render()), ActionLog.parse(line))
    }

    @Test
    fun aFinishedLineCarriesTheOutcomeAndTheDuration() {
        val ok = ActionLog.finished(42, Commands.tap(pay), Responses.done(durationMs = 243))
        val missed = ActionLog.finished(43, Commands.waitVisible(pay), Responses.failure(ErrorCode.ERR_NOT_FOUND, durationMs = 5000))

        assertEquals("< #42 tap ok 243ms", ok)
        assertEquals(ActionLog.Line(42, "tap", finished = true, outcome = "ok", durationMs = 243), ActionLog.parse(ok))
        assertEquals(ActionLog.Line(43, "wait_visible", finished = true, outcome = "NOT_FOUND", durationMs = 5000), ActionLog.parse(missed))
    }

    @Test
    fun typedTextNeverReachesTheLine() {
        val line = ActionLog.started(7, Commands.typeText("hunter2"))

        assertEquals("> #7 type_text", line)
        assertEquals(ActionLog.Line(7, "type_text", finished = false), ActionLog.parse(line))
    }

    @Test
    fun aTargetIsOneBoundedLine() {
        val line = ActionLog.started(1, Commands.tap(Selectors.text("a\nb" + "x".repeat(500))))

        assertFalse('\n' in line)
        assertTrue(line.endsWith("…"))
        assertTrue(line.length <= "> #1 tap ".length + ActionLog.MAX_TARGET_CHARS)
    }

    @Test
    fun commandsThatActOrWaitAreLoggedAndQueriesAreNot() {
        assertTrue(ActionLog.logs(Commands.tap(pay)))
        assertTrue(ActionLog.logs(Commands.waitVisible(pay)))
        assertFalse(ActionLog.logs(Commands.exists(pay)))
        assertFalse(ActionLog.logs(Commands.deviceInfo()))
    }

    @Test
    fun otherMessagesAreNotActionLines() {
        assertNull(ActionLog.parse("onPayClicked"))
        assertNull(ActionLog.parse("> tap"))
        assertNull(ActionLog.parse("< #1 tap ok"))
    }
}
