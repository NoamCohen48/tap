package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.res.Configuration
import android.provider.Settings

/**
 * Read-backs for the device conditions the host changes (`.docs/device-actions.md`): the
 * animation scales and network switches from `Settings.Global` (readable by any app), the rest from the
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

    /** The system locale list as BCP-47 tags: the driver has no locales of its own, so it follows the system. */
    val systemLocales: List<String> get() = configuration.locales.toLanguageTags().split(',').filter { it.isNotEmpty() }

    val airplaneMode: Boolean get() = global(Settings.Global.AIRPLANE_MODE_ON) == 1

    /** `wifi_on` 1, or 2: on while airplane mode is on (3 is off until airplane mode ends). */
    val wifiEnabled: Boolean get() = global(Settings.Global.WIFI_ON).let { it == 1 || it == 2 }

    val mobileDataEnabled: Boolean get() = global(MOBILE_DATA) == 1

    private fun global(name: String): Int = Settings.Global.getInt(instrumentation.context.contentResolver, name, 0)

    private companion object {
        /** `Settings.Global.MOBILE_DATA`, hidden from the SDK. */
        const val MOBILE_DATA = "mobile_data"
        val SCALES =
            listOf(
                Settings.Global.WINDOW_ANIMATION_SCALE,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
    }
}
