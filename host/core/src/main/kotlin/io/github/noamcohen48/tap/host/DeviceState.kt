package io.github.noamcohen48.tap.host

import kotlinx.serialization.Serializable

/**
 * One piece of device (or app) state a session changed, as it was before the change: [key] is a
 * [StateKey] id, [value] what [Adb.readState] returned (null = absent). Journaled, so a daemon
 * that dies mid-session leaves the next attach a record of what to put back.
 */
@Serializable
data class SavedState(
    val key: String,
    val value: String?,
)

/** Where a saved value lives on the device. Ids are stable: they are written to the journal. */
sealed interface StateKey {
    val id: String

    /** A `settings` value: [namespace] is `system`, `secure` or `global`. */
    data class Setting(
        val namespace: String,
        val name: String,
    ) : StateKey {
        override val id: String get() = "setting:$namespace/$name"
    }

    /** `cmd uimode night`: `yes`, `no`, `auto` or `custom`. */
    data object NightMode : StateKey {
        override val id: String = "uimode:night"
    }

    /** `wm density`'s override, absent when the physical density applies. */
    data object Density : StateKey {
        override val id: String = "wm:density"
    }

    /** An app's own locales (`cmd locale`, API 33+). */
    data class AppLocales(
        val packageName: String,
    ) : StateKey {
        override val id: String get() = "locale:$packageName"
    }

    /**
     * The device-wide locale list (Settings › Languages), comma-separated BCP-47 tags: read from
     * `system_locales`, else `persist.sys.locale`, else the build's `ro.product.locale`; written
     * through the driver app's `SystemLocaleReceiver`, since Android has no shell command for it.
     */
    data object SystemLocales : StateKey {
        override val id: String = "system-locales"
    }

    /**
     * The location providers that are LocationManager test providers (`dumpsys location`):
     * sorted, comma-separated names, empty when none. Mock location replaces `gps` and `network`
     * (and `fused`) with test providers that outlive the driver and its app-op, so restoring
     * removes every one not in the saved list, through the driver app's `MockLocationReceiver`.
     */
    data object MockLocationProviders : StateKey {
        override val id: String = "mock-location-providers"
    }

    /**
     * Whether the driver app's `TapNotificationListener` has notification access: `allowed` or
     * `disallowed`, read from `dumpsys notification` (the approved listeners) and written with
     * `cmd notification allow_listener|disallow_listener`, as Settings › Notification access does.
     */
    data object DriverNotificationListener : StateKey {
        override val id: String = "driver-notification-listener"
    }

    /**
     * An app-op mode of one package (`appops get|set`): `allow`, `ignore`, `deny`, `foreground`
     * or `default`. A package with the op at its default mode reads `default`.
     */
    data class AppOp(
        val packageName: String,
        val op: String,
    ) : StateKey {
        override val id: String get() = "appop:$packageName/$op"
    }

    /**
     * A file this session created on the device, captured as absent (null) before it is written,
     * so restoring removes it: `present` while it exists. A [media] file is also in the media
     * index, so removing it rescans its path.
     */
    data class DeviceFile(
        val path: String,
        val media: Boolean = false,
    ) : StateKey {
        override val id: String get() = (if (media) "media:" else "file:") + path
    }

    /**
     * A radio switch, `1` (on) or `0` (off): airplane mode (`cmd connectivity airplane-mode`),
     * Wi-Fi (`svc wifi`) or mobile data (`svc data`), read from their global settings.
     */
    enum class Network(
        val setting: String,
    ) : StateKey {
        AIRPLANE("airplane_mode_on"),
        WIFI("wifi_on"),
        MOBILE_DATA("mobile_data"),
        ;

        override val id: String get() = "network:${name.lowercase()}"
    }

    companion object {
        val ACCELEROMETER_ROTATION = Setting("system", "accelerometer_rotation").id
        val USER_ROTATION = Setting("system", "user_rotation").id

        /** The rotation lock before the numeric rotation: restoring goes newest first. */
        val ROTATION = listOf(ACCELEROMETER_ROTATION, USER_ROTATION)
        val ANIMATIONS =
            listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").map { Setting("global", it).id }
        val FONT_SCALE = Setting("system", "font_scale").id

        /** The master location switch: `0` off, `3` on (high accuracy). */
        val LOCATION_MODE = Setting("secure", "location_mode").id

        /** The screen stays on while plugged in: a bit set of power sources, 0 = never. */
        val STAY_AWAKE = Setting("global", "stay_on_while_plugged_in").id

        /** The accessibility display settings: 1/0, and bold text's font weight adjustment (0 = off). */
        val HIGH_CONTRAST_TEXT = Setting("secure", "high_text_contrast_enabled").id
        val COLOR_INVERSION = Setting("secure", "accessibility_display_inversion_enabled").id
        val BOLD_TEXT = Setting("secure", "font_weight_adjustment").id

        /** The driver app as the device's mock-location app. */
        val DRIVER_MOCK_LOCATION = AppOp(DRIVER_PACKAGE, "android:mock_location").id

        /**
         * Captured together on the first network change, airplane mode last: restoring goes newest
         * first, so airplane mode is back before Wi-Fi and mobile data are written under it.
         */
        val NETWORK = listOf(Network.WIFI, Network.MOBILE_DATA, Network.AIRPLANE).map { it.id }

        /**
         * Settings the system writes back at their default once deleted: Android persists the
         * configuration, so an absent `font_scale` reads `1.0` right after `settings delete`.
         */
        private val ABSENT_DEFAULTS = mapOf(FONT_SCALE to "1.0")

        /**
         * Whether [actual], as [Adb.readState] returned it for [id], is [expected]: an absent
         * (null) [expected] also holds when the system wrote the setting back at its default.
         */
        fun holds(
            id: String,
            expected: String?,
            actual: String?,
        ): Boolean = actual == expected || (expected == null && actual == ABSENT_DEFAULTS[id])

        private val APP_OP = Regex("appop:([A-Za-z0-9_.]+)/([A-Za-z0-9_:]+)")
        private val SETTING = Regex("setting:(system|secure|global)/([A-Za-z0-9_.]+)")

        fun parse(id: String): StateKey =
            when {
                id == NightMode.id -> NightMode
                id == Density.id -> Density
                id == SystemLocales.id -> SystemLocales
                id == MockLocationProviders.id -> MockLocationProviders
                id == DriverNotificationListener.id -> DriverNotificationListener
                id.startsWith("network:") -> Network.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Bad state key $id")
                id.startsWith("file:") -> DeviceFile(id.removePrefix("file:").also { require(it.startsWith("/")) { "Bad state key $id" } })
                id.startsWith("media:") -> DeviceFile(id.removePrefix("media:").also { require(it.startsWith("/")) { "Bad state key $id" } }, media = true)
                id.startsWith("appop:") -> APP_OP.matchEntire(id)?.let { AppOp(it.groupValues[1], it.groupValues[2]) } ?: throw IllegalArgumentException("Bad state key $id")
                id.startsWith("locale:") -> AppLocales(id.removePrefix("locale:").also { require(it.isNotBlank()) { "Bad state key $id" } })
                else -> SETTING.matchEntire(id)?.let { Setting(it.groupValues[1], it.groupValues[2]) } ?: throw IllegalArgumentException("Bad state key $id")
            }
    }
}
