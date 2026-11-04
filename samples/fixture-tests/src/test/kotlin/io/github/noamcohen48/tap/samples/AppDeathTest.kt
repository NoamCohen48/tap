package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.CommandException
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.ErrorCode
import io.github.noamcohen48.tap.sdk.WaitTimeoutException
import io.github.noamcohen48.tap.sdk.desc
import io.github.noamcohen48.tap.sdk.res
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A wait on an app that died says so, instead of timing out on a node that will never come. */
@TapTest
class AppDeathTest {
    @Test
    fun aCrashIsReportedOnceAndNotAfterTheNextStart(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".DyingActivity")
            app.await(desc("crash_button")).visible().tap()
            val failure =
                assertFailsWith<CommandException> {
                    app.await(res("never_there"), timeout = 5.seconds).visible()
                }
            assertEquals(ErrorCode.AUT_CRASHED, failure.code)
            assertEquals("WAIT_TIMEOUT", failure.detail)
            assertTrue(Fixture.PACKAGE in failure.message!! && "IllegalStateException" in failure.message!!, failure.message)

            assertFailsWith<WaitTimeoutException> { app.await(res("never_there"), timeout = 1.seconds).visible() }

            app.coldLaunch(".DyingActivity")
            app.await(desc("crash_button")).visible()
            assertFailsWith<WaitTimeoutException> { app.await(res("never_there"), timeout = 1.seconds).visible() }
            app.forceStop()
        }

    @Test
    fun aHungAppIsReportedAsAnAnr(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".DyingActivity")
            // The button hangs on the finger going down, so the finger going up stays unanswered:
            // Android calls it an ANR after 5 s of that. A hang with no input waiting is no ANR.
            app.await(desc("hang_button")).visible().tap()
            val failure =
                assertFailsWith<CommandException> {
                    app.await(res("never_there"), timeout = 25.seconds).visible()
                }
            assertEquals(ErrorCode.AUT_ANR, failure.code)
            app.forceStop()
        }
}
