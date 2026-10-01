package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.res.Configuration
import android.provider.Settings

/**
 * Read-backs for the device conditions the host changes (`.docs/device-actions.md`): the
 * animation scales from `Settings.Global` (readable by any app), the rest from the
 * configuration Android last delivered to this process, which is the one apps see.
 */
internal class DeviceConditionsReader(
    private val instrumentation: Instrumentation,
) {
    private val configuration: Configuration get() = instrumentation.context.resources.configuration

    /** Any of the three animation scales is not 0 (an unset scale is 1). */
    val animationsEnabled: Boolean
        get() {
            val resolver = instrumentation.context.contentResolver
            return SCALES.any { Settings.Global.getFloat(resolver, it, 1f) != 0f }
        }

    val darkMode: Boolean get() = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    val fontScale: Float get() = configuration.fontScale

    val densityDpi: Int get() = configuration.densityDpi

    private companion object {
        val SCALES =
            listOf(
                Settings.Global.WINDOW_ANIMATION_SCALE,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
    }
}
