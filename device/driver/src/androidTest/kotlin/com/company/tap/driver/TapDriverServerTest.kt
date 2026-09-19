package com.company.tap.driver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapDriverServerTest {
    @Test
    fun serve() {
        TapDriverServer(
            InstrumentationRegistry.getInstrumentation(),
            InstrumentationRegistry.getArguments(),
        ).serve()
    }
}
