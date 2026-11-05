package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.res
import io.github.noamcohen48.tap.sdk.text
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TapTest
class TimedTouchTest {
    @Test
    fun countedTapsAndTimedPressReachTheApp(device: Device) =
        tapTest {
            val app = Fixture.launch(device, ".TimedTouchActivity")
            val target = app.element(res("timed_touch_target"))
            target.tripleTap()
            app.await(text("Taps: 3")).visible()
            target.tap(count = 7, interval = 200.milliseconds)
            app.await(text("Taps: 10")).visible()
            target.longTap(duration = 1200.milliseconds, timeout = 5.seconds)
            app.await(text("Taps: 11")).visible()
            val held =
                app
                    .element(res("touch_duration"))
                    .text()!!
                    .removePrefix("Held: ")
                    .removeSuffix(" ms")
                    .toLong()
            assertTrue(held in 1200..3000, "Observed press: $held ms")
        }
}
