package com.company.tap.samples

import com.company.tap.junit5.Devices
import com.company.tap.junit5.TapDevices
import com.company.tap.junit5.TapTest
import com.company.tap.sdk.text
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two roles acquired all-or-none; the test is skipped (assumption) when `tap.serials` lists
 * fewer devices. Devices are independent sessions, so per-device work runs on separate threads
 * and a failure on one does not disturb the other's session.
 */
@TapTest
class MultiDeviceTest {
    @Test
    @TapDevices("left", "right")
    fun drivesTwoDevicesConcurrently(devices: Devices) {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("left", "right").map { role ->
                pool.submit<String> {
                    val device = devices[role]
                    Fixture.launch(device)
                    device.element(Fixture.id("view_button")).tap()
                    device.await(text("View tapped")).visible()
                    device.info().model
                }
            }.map { it.get() }
            assertEquals(2, results.size)
        } finally {
            pool.shutdown()
        }
    }
}
