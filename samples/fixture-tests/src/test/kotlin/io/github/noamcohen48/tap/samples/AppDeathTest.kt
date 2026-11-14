package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.AppDeath
import io.github.noamcohen48.tap.sdk.AppDiedException
import io.github.noamcohen48.tap.sdk.CommandException
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.ErrorCode
import io.github.noamcohen48.tap.sdk.WaitTimeoutException
import io.github.noamcohen48.tap.sdk.desc
import io.github.noamcohen48.tap.sdk.res
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A wait on an app that died says so, instead of timing out on a node that will never come; a
 * crash that failed no command is still seen by `guard` / `expectDeath`.
 */
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
            // CANCELLED when the crash was logged while the wait ran, WAIT_TIMEOUT when before it.
            assertTrue(failure.detail in setOf("CANCELLED", "WAIT_TIMEOUT"), failure.detail)
            assertTrue(Fixture.PACKAGE in failure.message!! && "IllegalStateException" in failure.message!!, failure.message)
            val death = assertNotNull(failure.appDeath)
            assertEquals(Fixture.PACKAGE, death.packageName)
            assertEquals("java.lang.IllegalStateException", death.exceptionClass)
            assertTrue("FATAL EXCEPTION" in death.stackTrace, death.stackTrace)

            assertFailsWith<WaitTimeoutException> { app.await(res("never_there"), timeout = 1.seconds).visible() }

            app.coldLaunch(".DyingActivity")
            app.await(desc("crash_button")).visible()
            assertFailsWith<WaitTimeoutException> { app.await(res("never_there"), timeout = 1.seconds).visible() }
            app.forceStop()
        }

    @Test
    fun aWaitOnAnAppThatCrashesWhileItRunsEndsAtOnce(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".DyingActivity")
            val crash = app.await(desc("crash_button")).visible()
            val started = TimeSource.Monotonic.markNow()
            val failure =
                assertFailsWith<CommandException> {
                    coroutineScope {
                        // The wait is already running when the tap crashes the app.
                        val waiting = async { app.await(res("never_there"), timeout = 30.seconds).visible() }
                        delay(500)
                        crash.tap()
                        waiting.await()
                    }
                }
            assertEquals(ErrorCode.AUT_CRASHED, failure.code)
            assertEquals("CANCELLED", failure.detail)
            assertTrue(started.elapsedNow() < 15.seconds, "ended after ${started.elapsedNow()}")
            app.forceStop()
        }

    @Test
    fun aGuardThrowsForACrashThatFailedNoCommand(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".DyingActivity")
            val died = assertFailsWith<AppDiedException> { app.guard { app.await(desc("crash_button")).visible().tap() } }
            assertEquals(Fixture.PACKAGE, died.death.packageName)
            assertEquals(AppDeath.Kind.CRASH, died.death.kind)
            assertTrue(app.deaths().any { it.sequence == died.death.sequence && it.watched })
            app.forceStop()
        }

    @Test
    fun expectDeathReturnsTheCrashItCaused(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".DyingActivity")
            val death = app.expectDeath { app.await(desc("crash_button")).visible().tap() }
            assertEquals("java.lang.IllegalStateException", death.exceptionClass)
            // Started again, the app works and its old crash explains nothing.
            app.coldLaunch(".DyingActivity")
            app.await(desc("crash_button")).visible()
            app.forceStop()
        }

    @Test
    fun aHungAppIsReportedAsAnAnr(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".DyingActivity")
            // The button hangs on the finger going down, so the finger going up stays unanswered.
            // Android 11+ calls that an ANR after 5 s, and the wait that follows reports it.
            // Android 10 and older call it an ANR only once more input is waiting for the window
            // (seen on the SM-J810G: "Waiting to send non-key event"), so there the next input,
            // a key press, is what fails with it. A hang with no input waiting is no ANR.
            app.await(desc("hang_button")).visible().tap()
            val failure =
                if (device.info().apiLevel <= 29) {
                    assertFailsWith<CommandException> { device.pressBack() }
                } else {
                    assertFailsWith<CommandException> {
                        app.await(res("never_there"), timeout = 25.seconds).visible()
                    }
                }
            assertEquals(ErrorCode.AUT_ANR, failure.code)
            app.forceStop()
        }
}
