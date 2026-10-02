package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings

/**
 * Read-backs for the device conditions the host changes (`.docs/device-actions.md`): the
 * animation scales, network switches and stay-awake from `Settings.Global` (readable by any app), the
 * accessibility display switches from `Settings.Secure`, the rest from the
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

    val stayAwake: Boolean get() = global(Settings.Global.STAY_ON_WHILE_PLUGGED_IN) != 0

    /** Null when Android refuses the driver this hidden setting (non-readable keys, API 31+ targets). */
    val highContrastText: Boolean? get() = secure(HIGH_TEXT_CONTRAST)?.let { it == 1 }

    val colorInversion: Boolean? get() = secure(DISPLAY_INVERSION)?.let { it == 1 }

    /** Bold text is a configuration field on API 31+; older devices have no such setting. */
    val boldText: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && configuration.fontWeightAdjustment > 0

    private fun global(name: String): Int = Settings.Global.getInt(instrumentation.context.contentResolver, name, 0)

    private fun secure(name: String): Int? =
        try {
            Settings.Secure.getInt(instrumentation.context.contentResolver, name, 0)
        } catch (_: SecurityException) {
            null
        }

    private companion object {
        /** `Settings.Global.MOBILE_DATA`, hidden from the SDK. */
        const val MOBILE_DATA = "mobile_data"

        /** `Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED` / `ACCESSIBILITY_DISPLAY_INVERSION_ENABLED`, hidden. */
        const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"
        const val DISPLAY_INVERSION = "accessibility_display_inversion_enabled"
        val SCALES =
            listOf(
                Settings.Global.WINDOW_ANIMATION_SCALE,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
    }
}
