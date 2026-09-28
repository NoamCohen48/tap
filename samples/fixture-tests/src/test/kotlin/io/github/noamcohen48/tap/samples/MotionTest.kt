package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.WaitReason
import io.github.noamcohen48.tap.sdk.WaitTimeoutException
import io.github.noamcohen48.tap.sdk.res
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Settling is explicit: nothing here waits on its own; each test asks for the signal it needs. */
@TapTest
class MotionTest {
    @Test
    fun waitsForAnimationToEnd(device: Device): Unit {
        tapTest {
            Fixture.launch(device, ".MotionActivity")
            val status = device.element(res("motion_status"))

            device.element(res("motion_button")).tap()
            val started = System.nanoTime()
            device.awaitAnimationEnd(stableFor = 500.milliseconds, timeout = 10.seconds)
            val waitedMs = (System.nanoTime() - started) / 1_000_000

            // The box moves for 2 s; the pixel wait must have outlived it without any status wait.
            assertEquals("Animation done", status.text())
            assertTrue(waitedMs >= 2_000, "returned after ${waitedMs}ms, before the motion ended")
        }
    }

    @Test
    fun settlesAfterTheHierarchyStopsMoving(device: Device): Unit {
        tapTest {
            Fixture.launch(device, ".MotionActivity")
            val status = device.element(res("motion_status"))

            device.element(res("motion_button")).tap()
            val started = System.nanoTime()
            device.awaitAppSettled(stableFor = 500.milliseconds, timeout = 10.seconds)
            val waitedMs = (System.nanoTime() - started) / 1_000_000

            // The moving box changes its accessibility bounds every frame; no screenshots involved.
            assertEquals("Animation done", status.text())
            assertTrue(waitedMs >= 2_000, "returned after ${waitedMs}ms, before the motion ended")
        }
    }

    @Test
    fun screenThatKeepsChangingTimesOut(device: Device): Unit {
        tapTest {
            Fixture.launch(device, ".MotionActivity")
            val ticker = device.element(res("ticker_button"))
            ticker.tap()
            try {
                val failure =
                    assertFailsSuspend<WaitTimeoutException> {
                        device.awaitAppSettled(stableFor = 500.milliseconds, timeout = 3.seconds)
                    }
                assertEquals(WaitReason.SCREEN_CHANGING, failure.reason)
                assertTrue(failure.elapsedMs >= 3_000, "gave up after ${failure.elapsedMs}ms")
            } finally {
                ticker.tap()
            }
            device.awaitScreenStable(timeout = 5.seconds)
            assertEquals("Ticker stopped", device.element(res("ticker_status")).text())
        }
    }
}
