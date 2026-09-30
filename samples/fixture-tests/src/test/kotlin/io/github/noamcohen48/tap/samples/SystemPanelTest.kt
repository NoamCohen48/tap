package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.WaitTimeoutException
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private const val SYSTEM_UI = "com.android.systemui"

@TapTest
class SystemPanelTest {
    /**
     * Each panel takes the focus from the app, and Back gives it back. From quick settings,
     * newer Android (API 34) goes back to the notification shade first, so a second Back may be
     * needed. The next panel opens only once the last one has finished closing: a panel action
     * sent while the shade is still animating can be accepted and ignored (seen on a Samsung API 29).
     */
    @Test
    fun opensNotificationsAndQuickSettingsAndBackClosesThem(device: Device): Unit {
        tapTest {
            Fixture.launch(device)
            val panels = listOf<Pair<String, suspend () -> Unit>>(
                "notifications" to { device.openNotifications() },
                "quick settings" to { device.openQuickSettings() },
            )
            for ((name, open) in panels) {
                open()
                device.awaitUntil("$name focused", observe = { device.info().currentPackage }) {
                    device.info().currentPackage == SYSTEM_UI
                }
                val appFocused: suspend () -> Boolean = { device.info().currentPackage == Fixture.PACKAGE }
                device.pressBack()
                try {
                    device.awaitUntil("the app focused after closing $name", timeout = 3.seconds, condition = appFocused)
                } catch (_: WaitTimeoutException) {
                    device.pressBack()
                    device.awaitUntil("the app focused after closing $name", observe = { device.info().currentPackage }, condition = appFocused)
                }
                // The panel is still sliding away: an action sent now may be accepted and ignored.
                device.awaitAnimationEnd()
            }
        }
    }
}
