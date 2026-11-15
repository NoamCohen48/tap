package io.github.noamcohen48.tap.driver

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/** Validation-only feasibility probe; it never sends input or changes the app under test. */
@RunWith(AndroidJUnit4::class)
class RuntimeReconnectProbeTest {
    @Test
    fun reconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destroy = UiAutomation::class.java.getDeclaredMethod("destroy").apply { isAccessible = true }
        val singleton = UiDevice::class.java.getDeclaredField("sInstance").apply { isAccessible = true }
        val configurator = Configurator.getInstance()
        val originalIdle = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 0
        try {
            repeat(5) { cycle ->
                singleton.set(null, null)
                val start = SystemClock.elapsedRealtimeNanos()
                val automation = requireNotNull(instrumentation.uiAutomation)
                var destroyed = false
                try {
                    val device = UiDevice.getInstance(instrumentation)
                    val roots = device.findObjects(By.depth(0)).size
                    val connected = SystemClock.elapsedRealtimeNanos()
                    destroy.invoke(automation)
                    destroyed = true
                    val ended = SystemClock.elapsedRealtimeNanos()
                    println(
                        "TAP_RUNTIME_PROBE cycle=$cycle connect_ms=${(connected - start) / 1_000_000} disconnect_ms=${(ended - connected) / 1_000_000} roots=$roots",
                    )
                } finally {
                    if (!destroyed) destroy.invoke(automation)
                }
            }
        } finally {
            singleton.set(null, null)
            configurator.waitForIdleTimeout = originalIdle
        }
    }
}
