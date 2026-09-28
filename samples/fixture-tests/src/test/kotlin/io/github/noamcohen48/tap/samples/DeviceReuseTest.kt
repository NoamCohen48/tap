package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.DeviceLifetime
import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.res
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** PER_CLASS keeps one attached device across the class and replaces one that stopped working. */
@TapTest(deviceLifetime = DeviceLifetime.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceReuseTest {
    @Test
    @Order(1)
    fun firstTestAttaches(device: Device): Unit {
        tapTest {
            Fixture.launch(device)
            device.await(res("view_button")).visible()
            first = device
        }
    }

    @Test
    @Order(2)
    fun secondTestReusesTheSameDeviceAndDetachesIt(device: Device): Unit {
        tapTest {
            assertSame(first, device)
            // The app keeps its state: nothing is reset between tests.
            device.await(res("view_button")).visible()
            device.detach()
        }
    }

    @Test
    @Order(3)
    fun aDetachedDeviceIsReplaced(device: Device): Unit {
        tapTest {
            assertNotSame(first, device)
            assertFalse(device.isDetached)
            Fixture.launch(device)
            third = device
        }
    }

    @Test
    @Order(4)
    fun theReplacementIsReusedInTurn(device: Device): Unit {
        tapTest { assertSame(third, device) }
    }

    private companion object {
        var first: Device? = null
        var third: Device? = null
    }
}
