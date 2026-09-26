package io.github.noamcohen48.tap.driver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumentation entry point `am instrument` runs: it only hands the arguments to the
 * driver core's [TapDriverServer]. [driverFaultHooks] comes from the flavor's source set: none in
 * `product`, the fault controller in `validation`.
 */
@RunWith(AndroidJUnit4::class)
class TapDriverServerTest {
    @Test
    fun serve() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        TapDriverServer(instrumentation, arguments, driverFaultHooks(instrumentation, arguments)).serve()
    }
}
