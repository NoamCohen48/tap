package com.company.tap.samples

import com.company.tap.junit5.TapTest
import com.company.tap.sdk.res
import com.company.tap.sdk.Device
import com.company.tap.sdk.text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

@TapTest
class LifecycleTest {
    @Test
    fun coldLaunchProducesANewProcessAndSurvivesClearData(device: Device) {
        val app = Fixture.launch(device)
        val first = app.process()

        device.element(res("fault_button")).tap()
        device.await(text("Fault taps: 1")).visible()

        app.forceStop()
        assertFalse(app.isRunning())
        val second = app.coldLaunch(".MainActivity")
        assertNotEquals(first, second)
        // The in-process counter is gone with the old process.
        assertEquals("Fault taps: 0", device.element(res("fault_status")).text())

        app.clearData()
        assertFalse(app.isRunning())
        app.launch(".MainActivity")
        assertTrue(device.element(res("view_button")).exists())

        val info = device.info()
        assertEquals(Fixture.PACKAGE, info.currentPackage)
    }
}
