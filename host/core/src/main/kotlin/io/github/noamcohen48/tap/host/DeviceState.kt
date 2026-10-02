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

        /** The driver app as the device's mock-location app. */
        val DRIVER_MOCK_LOCATION = AppOp(DRIVER_PACKAGE, "android:mock_location").id

        /**
         * Captured together on the first network change, airplane mode last: restoring goes newest
         * first, so airplane mode is back before Wi-Fi and mobile data are written under it.
         */
        val NETWORK = listOf(Network.WIFI, Network.MOBILE_DATA, Network.AIRPLANE).map { it.id }

        private val APP_OP = Regex("appop:([A-Za-z0-9_.]+)/([A-Za-z0-9_:]+)")
        private val SETTING = Regex("setting:(system|secure|global)/([A-Za-z0-9_.]+)")

        fun parse(id: String): StateKey =
            when {
                id == NightMode.id -> NightMode
                id == Density.id -> Density
                id == SystemLocales.id -> SystemLocales
                id.startsWith("network:") -> Network.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Bad state key $id")
                id.startsWith("file:") -> DeviceFile(id.removePrefix("file:").also { require(it.startsWith("/")) { "Bad state key $id" } })
                id.startsWith("media:") -> DeviceFile(id.removePrefix("media:").also { require(it.startsWith("/")) { "Bad state key $id" } }, media = true)
                id.startsWith("appop:") -> APP_OP.matchEntire(id)?.let { AppOp(it.groupValues[1], it.groupValues[2]) } ?: throw IllegalArgumentException("Bad state key $id")
                id.startsWith("locale:") -> AppLocales(id.removePrefix("locale:").also { require(it.isNotBlank()) { "Bad state key $id" } })
                else -> SETTING.matchEntire(id)?.let { Setting(it.groupValues[1], it.groupValues[2]) } ?: throw IllegalArgumentException("Bad state key $id")
            }
    }
}
