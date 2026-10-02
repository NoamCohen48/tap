package io.github.noamcohen48.tap.host

/**
 * Device-wide conditions a test may change for its session: animations, dark mode, font scale,
 * display density and the network switches. Each change captures the value it replaces first ([DeviceSession.captureBeforeChange]),
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

    private suspend fun change(values: Map<String, String?>) = session.change(values)

    companion object {
        const val DARK_MODE_API = 29

        /** `cmd connectivity airplane-mode`; Wi-Fi and data go through `svc`, also on older APIs. */
        const val NETWORK_API = 29
        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 2.0f
        const val MIN_DENSITY = 100
        const val MAX_DENSITY = 1000
    }
}

/** A serial ADB reaches over TCP (`host:port`) or wireless debugging (`adb-<id>._adb-tls-connect._tcp`). */
internal fun overNetwork(serial: String): Boolean = "._adb-tls-connect." in serial || Regex(".+:\\d+").matches(serial)
