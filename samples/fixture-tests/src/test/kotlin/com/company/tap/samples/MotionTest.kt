package com.company.tap.samples

import com.company.tap.junit5.TapTest
import com.company.tap.sdk.Device
import com.company.tap.sdk.WaitTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

/** Settling is explicit: nothing here waits on its own; each test asks for the signal it needs. */
@TapTest
class MotionTest {
    @Test
    fun waitsForAnimationToEnd(device: Device) {
        Fixture.launch(device, ".MotionActivity")
        val status = device.element(Fixture.id("motion_status"))

        device.element(Fixture.id("motion_button")).tap()
        val started = System.nanoTime()
        device.awaitAnimationEnd(stableFor = 500.milliseconds, timeout = 10.seconds)
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        // The box moves for 2 s; the pixel wait must have outlived it without any status wait.
        assertEquals("Animation done", status.text())
        assertTrue(waitedMs >= 2_000, "returned after ${waitedMs}ms, before the motion ended")
    }

    @Test
    fun settlesAfterTheHierarchyStopsMoving(device: Device) {
        Fixture.launch(device, ".MotionActivity")
        val status = device.element(Fixture.id("motion_status"))

        device.element(Fixture.id("motion_button")).tap()
        val started = System.nanoTime()
        device.awaitAppSettled(stableFor = 500.milliseconds, timeout = 10.seconds)
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        // The moving box changes its accessibility bounds every frame; no screenshots involved.
        assertEquals("Animation done", status.text())
        assertTrue(waitedMs >= 2_000, "returned after ${waitedMs}ms, before the motion ended")
    }

    @Test
    fun screenThatKeepsChangingTimesOut(device: Device) {
        Fixture.launch(device, ".MotionActivity")
        val ticker = device.element(Fixture.id("ticker_button"))
        ticker.tap()
        try {
            val failure = assertFailsWith<WaitTimeoutException> {
                device.awaitAppSettled(stableFor = 500.milliseconds, timeout = 3.seconds)
            }
            assertEquals("SCREEN_CHANGING", failure.lastObservation)
            assertTrue(failure.elapsedMs >= 3_000, "gave up after ${failure.elapsedMs}ms")
        } finally {
            ticker.tap()
        }
        device.awaitScreenStable(timeout = 5.seconds)
        assertEquals("Ticker stopped", device.element(Fixture.id("ticker_status")).text())
    }
}
