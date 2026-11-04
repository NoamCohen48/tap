package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.res
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/** Devices drive `@BeforeEach`, `@AfterEach` and test templates as they do a plain `@Test`. */
@TapTest
class LifecycleMethodsTest {
    @BeforeEach
    fun launch(device: Device) = tapTest { Fixture.launch(device) }

    @AfterEach
    fun stop(device: Device) = tapTest { device.app(Fixture.PACKAGE).forceStop() }

    @Test
    fun aPlainTestSeesTheLaunchedApp(device: Device) =
        tapTest {
            device.app(Fixture.PACKAGE).await(res("view_button")).visible()
        }

    @RepeatedTest(2)
    fun aTestTemplateIsBoundToo(device: Device) =
        tapTest {
            device.app(Fixture.PACKAGE).await(res("view_button")).visible()
        }
}
