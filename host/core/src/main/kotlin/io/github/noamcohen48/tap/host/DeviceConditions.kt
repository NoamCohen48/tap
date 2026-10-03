package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.Commands

/**
 * Device-wide conditions a test may change for its session: animations, dark mode, font scale,
 * display density, the device locale, the network switches, a mock location, stay-awake, the
 * accessibility display settings, and the driver's notification access. Each change captures the value it replaces first ([DeviceSession.captureBeforeChange]),
 * so detach restores the device as its owner left it, and is read back afterwards: a value the
 * device did not take is a [DeviceSettingException]. Apps see the new configuration as Android
 * delivers it (activities may be recreated); waiting for that is the test's business.
 */
class DeviceConditions internal constructor(
    private val session: DeviceSession,
) {
    private val adb get() = session.adb
    private val serial get() = session.serial

    /** All three animation scales to 0 (off) or 1 (on). */
    suspend fun setAnimations(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        change(StateKey.ANIMATIONS.associateWith { value })
    }

    /** `cmd uimode night yes|no`; API 29+, where Android has a system dark theme. */
    suspend fun setDarkMode(enabled: Boolean) {
        session.requireApi(DARK_MODE_API, "Dark mode")
        try {
            change(mapOf(StateKey.NightMode.id to if (enabled) "yes" else "no"))
        } catch (error: DeviceSettingException) {
            val locked = runCatching { session.guardAdb { adb.nightModeLocked(serial) } }.getOrDefault(false)
            if (!locked) throw error
            throw DeviceSettingException(serial, "${error.message}; $serial locks the day/night mode (dumpsys uimode: mNightModeLocked=true)")
        }
    }

    /** The system font scale, [MIN_FONT_SCALE] to [MAX_FONT_SCALE]. */
    suspend fun setFontScale(scale: Float) {
        require(scale in MIN_FONT_SCALE..MAX_FONT_SCALE) { "font scale must be $MIN_FONT_SCALE to $MAX_FONT_SCALE, not $scale" }
        change(mapOf(StateKey.FONT_SCALE to scale.toString()))
    }

    /** A display density override in dpi, or null for the physical density. */
    suspend fun setDensity(dpi: Int?) {
        require(dpi == null || dpi in MIN_DENSITY..MAX_DENSITY) { "density must be $MIN_DENSITY to $MAX_DENSITY dpi, not $dpi" }
        change(mapOf(StateKey.Density.id to dpi?.toString()))
    }

    /**
     * The device-wide locale list (Settings › Languages), BCP-47 tags in preference order,
     * canonicalised as Android reports them ([canonicalLocales]). Android has no shell command
     * for it: the driver app's receiver applies it, as Settings' language picker does, and the
     * list is read back from the device. Apps see it as a configuration change.
     */
    suspend fun setSystemLocales(locales: List<String>) {
        require(locales.isNotEmpty()) { "the device needs at least one locale" }
        change(mapOf(StateKey.SystemLocales.id to canonicalLocales(locales).joinToString(",")))
    }

    /**
     * Mock location: the device reports this fix ([latitude], [longitude], [accuracyM] meters,
     * [altitudeM] meters or none) from its location providers until detach. The driver app
     * becomes the mock-location app (its `android:mock_location` app-op) and, when location is
     * off, location is turned on; both are captured first and restored on detach. The driver
     * serves the fix from LocationManager test providers, which outlive it and its app-op: they
     * are captured too ([StateKey.MockLocationProviders], restored first) and removed on detach.
     */
    suspend fun setLocation(
        latitude: Double,
        longitude: Double,
        accuracyM: Float?,
        altitudeM: Double?,
    ) {
        require(latitude in -90.0..90.0) { "latitude must be -90 to 90, not $latitude" }
        require(longitude in -180.0..180.0) { "longitude must be -180 to 180, not $longitude" }
        require(accuracyM == null || (accuracyM.isFinite() && accuracyM > 0f)) { "accuracy must be a positive number of meters, not $accuracyM" }
        require(altitudeM == null || altitudeM.isFinite()) { "altitude must be finite, not $altitudeM" }
        // Newest first on restore: the test providers go while the driver still has the app-op.
        session.captureBeforeChange(listOf(StateKey.LOCATION_MODE, StateKey.DRIVER_MOCK_LOCATION, StateKey.MockLocationProviders.id))
        val locationOff = session.guardAdb { adb.readState(serial, StateKey.LOCATION_MODE) }.let { it == null || it == "0" }
        change(mapOfNotNull(StateKey.DRIVER_MOCK_LOCATION to "allow", (StateKey.LOCATION_MODE to LOCATION_ON).takeIf { locationOff }))
        session.checkUsable()
        session.client.execute(Commands.setLocation(latitude, longitude, accuracyM, altitudeM), timeoutMs = LOCATION_TIMEOUT_MS)
    }

    /**
     * Turns airplane mode, Wi-Fi and mobile data on or off; null leaves a switch as it is. Real
     * switches, nothing mocked: airplane mode is written first, so Wi-Fi turned on with it stays
     * on under it. All three are captured on the first network change and restored airplane mode
     * first. A device reached over ADB on Wi-Fi refuses a change that would cut that connection.
     */
    suspend fun setNetwork(
        airplaneMode: Boolean?,
        wifi: Boolean?,
        mobileData: Boolean?,
    ) {
        require(airplaneMode != null || wifi != null || mobileData != null) { "set at least one of airplane mode, Wi-Fi or mobile data" }
        session.requireApi(NETWORK_API, "Network switches")
        if (overNetwork(serial) && (airplaneMode == true || wifi == false)) {
            throw DeviceSettingException(serial, "$serial is reached over ADB on the network; turning Wi-Fi off or airplane mode on would cut the connection")
        }
        session.captureBeforeChange(StateKey.NETWORK)
        val values =
            listOfNotNull(
                airplaneMode?.let { StateKey.Network.AIRPLANE.id to it },
                wifi?.let { StateKey.Network.WIFI.id to it },
                mobileData?.let { StateKey.Network.MOBILE_DATA.id to it },
            ).associate { (key, on) -> key to if (on) "1" else "0" }
        change(values)
    }

    /**
     * Keeps the screen on while plugged in (`stay_on_while_plugged_in` 7: USB, AC and wireless),
     * or lets it time out (0). A device on ADB over USB is plugged in.
     */
    suspend fun setStayAwake(enabled: Boolean) {
        change(mapOf(StateKey.STAY_AWAKE to if (enabled) STAY_ON_ALL else "0"))
    }

    /**
     * High-contrast text, color inversion and bold text (API 31+) as Settings › Accessibility
     * writes them; null leaves a setting as it is. Bold text below API 31 is refused before
     * anything changes.
     */
    suspend fun setAccessibilityDisplay(
        highContrastText: Boolean?,
        colorInversion: Boolean?,
        boldText: Boolean?,
    ) {
        require(highContrastText != null || colorInversion != null || boldText != null) {
            "set at least one of high-contrast text, color inversion or bold text"
        }
        if (boldText != null) session.requireApi(BOLD_TEXT_API, "Bold text")
        val values =
            listOfNotNull(
                highContrastText?.let { StateKey.HIGH_CONTRAST_TEXT to if (it) "1" else "0" },
                colorInversion?.let { StateKey.COLOR_INVERSION to if (it) "1" else "0" },
                boldText?.let { StateKey.BOLD_TEXT to if (it) BOLD_WEIGHT_ADJUSTMENT else "0" },
            ).toMap()
        change(values)
    }

    /**
     * Gives the driver app's notification listener notification access until detach, for the
     * notification commands (the server calls it before the first one). Once per session.
     */
    suspend fun allowNotificationListener() {
        if (notificationListenerAllowed) return
        change(mapOf(StateKey.DriverNotificationListener.id to "allowed"))
        notificationListenerAllowed = true
    }

    @Volatile
    private var notificationListenerAllowed = false

    private suspend fun change(values: Map<String, String?>) = session.change(values)

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>?): Map<String, String?> = pairs.filterNotNull().toMap()

    companion object {
        const val DARK_MODE_API = 29

        /** `cmd connectivity airplane-mode`; Wi-Fi and data go through `svc`, also on older APIs. */
        const val NETWORK_API = 29
        /** `Settings.Secure.FONT_WEIGHT_ADJUSTMENT`, which the system applies to the configuration. */
        const val BOLD_TEXT_API = 31

        /** What Settings › Bold text writes (FontStyle.FONT_WEIGHT_BOLD - FONT_WEIGHT_NORMAL). */
        const val BOLD_WEIGHT_ADJUSTMENT = "300"

        /** BatteryManager.BATTERY_PLUGGED_AC | USB | WIRELESS: `svc power stayon true`. */
        const val STAY_ON_ALL = "7"
        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 2.0f
        /** `location_mode` 3: on, high accuracy (what Settings writes when location is turned on). */
        const val LOCATION_ON = "3"
        const val LOCATION_TIMEOUT_MS = 10_000L
        const val MIN_DENSITY = 100
        const val MAX_DENSITY = 1000
    }
}

/** A serial ADB reaches over TCP (`host:port`) or wireless debugging (`adb-<id>._adb-tls-connect._tcp`). */
internal fun overNetwork(serial: String): Boolean = "._adb-tls-connect." in serial || Regex(".+:\\d+").matches(serial)
