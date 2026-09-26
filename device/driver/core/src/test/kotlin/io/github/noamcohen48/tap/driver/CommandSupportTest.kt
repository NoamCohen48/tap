package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CommandSupportTest {
    @Test
    fun wireDirectionsMapOntoUiAutomator() {
        assertEquals(androidx.test.uiautomator.Direction.UP, uiDirection(Direction.DIR_UP))
        assertEquals(androidx.test.uiautomator.Direction.DOWN, uiDirection(Direction.DIR_DOWN))
        assertEquals(androidx.test.uiautomator.Direction.LEFT, uiDirection(Direction.DIR_LEFT))
        assertEquals(androidx.test.uiautomator.Direction.RIGHT, uiDirection(Direction.DIR_RIGHT))
    }

    @Test
    fun anUnspecifiedDirectionIsAnInvalidRequest() {
        try {
            uiDirection(Direction.DIR_UNSPECIFIED)
            fail("expected INVALID_REQUEST")
        } catch (failure: CommandFailure) {
            assertEquals(ErrorCode.ERR_INVALID_REQUEST, failure.code)
        }
    }

    @Test
    fun postMutationCardinalityFailuresAreStaleWithTheCardinalityAsDetail() {
        val gone = staleTarget(ErrorCode.ERR_NOT_FOUND)
        val ambiguous = staleTarget(ErrorCode.ERR_AMBIGUOUS, "moved")

        assertEquals(ErrorCode.ERR_STALE_DURING_COMMAND, gone.code)
        assertEquals(ErrorDetail.TARGET_GONE, gone.detail)
        assertEquals(ErrorCode.ERR_STALE_DURING_COMMAND, ambiguous.code)
        assertEquals(ErrorDetail.TARGET_AMBIGUOUS, ambiguous.detail)
        assertEquals("moved", ambiguous.remoteMessage)
    }

    @Test
    fun distancePercentIsAFraction() {
        assertEquals(0.8f, fraction(80), 0f)
    }
}
