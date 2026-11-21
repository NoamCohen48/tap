package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings

/**
 * Read-backs for the device conditions the host changes (`.docs/device-actions.md`), each from
 * where the change lands first, so a read right after the host's change (or its restore) sees it:
 * the animation scales, network switches and stay-awake from `Settings.Global` (readable by any
 * app), the font scale from `Settings.System`, the accessibility display switches, bold text and the soft keyboard
 * from `Settings.Secure`, and dark mode, density and the locales from the system's configuration.
 *
 * Not from this process's own `Configuration`: Android delivers a change to each process after
 * the call that made it returns, and copies the font scale and bold-text settings into its
 * configuration asynchronously too (bold text measured 0.5 to 1 s behind on an API 34 emulator).
 * Running apps get the change on that schedule.
 */
internal class DeviceConditionsReader(
    private val instrumentation: Instrumentation,
) {
    private val ownConfiguration: Configuration get() = instrumentation.context.resources.configuration

    /**
     * The system's current configuration (`IActivityManager.getConfiguration`, the call Settings'
     * language picker starts from), which `cmd uimode`, `wm density` and the locale receiver update
     * before they return. This process's own configuration if Android refuses the call.
     */
    private val configuration: Configuration
        get() =
            try {
                val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
                Class.forName("android.app.IActivityManager").getMethod("getConfiguration").invoke(activityManager) as Configuration
            } catch (_: ReflectiveOperationException) {
                ownConfiguration
            } catch (_: SecurityException) {
                ownConfiguration
            }

    /** Any of the three animation scales is not 0 (an unset scale is 1). */
    val animationsEnabled: Boolean
        get() {
            val resolver = instrumentation.context.contentResolver
            return SCALES.any { Settings.Global.getFloat(resolver, it, 1f) != 0f }
        }

    val darkMode: Boolean get() = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** `font_scale`, what the host writes; Android copies it into the configuration later. */
    val fontScale: Float get() = Settings.System.getFloat(instrumentation.context.contentResolver, Settings.System.FONT_SCALE, 1f)

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

    /** `show_ime_with_hard_keyboard`: the soft keyboard shows with a hardware keyboard; null when unreadable. */
    val showImeWithHardKeyboard: Boolean? get() = secure(SHOW_IME_WITH_HARD_KEYBOARD)?.let { it == 1 }

    /**
     * Bold text (API 31+; older devices have no such setting): `font_weight_adjustment`, what the
     * host writes, which Android copies into the configuration later; the system's configuration
     * when the setting cannot be read.
     */
    val boldText: Boolean
        get() =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (secure(FONT_WEIGHT_ADJUSTMENT)?.let { it > 0 } ?: (configuration.fontWeightAdjustment > 0))

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

        /** `Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD`, hidden. */
        const val SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"
        const val DISPLAY_INVERSION = "accessibility_display_inversion_enabled"

        /** `Settings.Secure.FONT_WEIGHT_ADJUSTMENT` (API 31). */
        const val FONT_WEIGHT_ADJUSTMENT = "font_weight_adjustment"
        val SCALES =
            listOf(
                Settings.Global.WINDOW_ANIMATION_SCALE,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
    }
}
