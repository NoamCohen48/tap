package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.res
import io.github.noamcohen48.tap.sdk.text
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@TapTest
class LifecycleTest {
    @Test
    fun coldLaunchProducesANewProcessAndSurvivesClearData(device: Device) {
        tapTest {
            val app = Fixture.launch(device)
            val first = app.process()

            // A launch returns on the new process identity, not on content: on a slow
            // first boot the hierarchy still lays out after that (seen on CI).
            app.await(res("fault_button")).visible()
            app.element(res("fault_button")).tap()
            app.await(text("Fault taps: 1")).visible()

            app.forceStop()
            assertFalse(app.isRunning())
            val second = app.coldLaunch(".MainActivity")
            assertNotEquals(first, second)
            // The in-process counter is gone with the old process.
            assertEquals("Fault taps: 0", app.element(res("fault_status")).text())

            app.clearData()
            assertFalse(app.isRunning())
            app.launch(".MainActivity")
            // launch does not wait for the window; the test does.
            app.await(res("view_button")).visible()

            val info = device.info()
            assertEquals(Fixture.PACKAGE, info.currentPackage)
        }
    }
}
