package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.DeviceKind
import io.github.noamcohen48.tap.junit5.RequireDevice
import io.github.noamcohen48.tap.junit5.TapDevice
import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

@TapTest
class DeviceCriteriaTest {
    @Test
    @RequireDevice(minApi = 26)
    fun aFittingDeviceIsAttached(device: Device) {
        tapTest { assertTrue(device.info().apiLevel >= 26) }
    }

    /** Skipped on every device: the skip happens before anything is attached. */
    @Test
    @RequireDevice(role = "only", maxApi = 1, kind = DeviceKind.PHYSICAL)
    fun noFittingDeviceSkipsTheTest(
        @TapDevice("only") device: Device,
    ) {
        fail("no device fits, so $device must not reach the test")
    }
}
