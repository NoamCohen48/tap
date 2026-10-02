package io.github.noamcohen48.tap.host

/**
 * The device state [Adb.readState] / [Adb.writeState] reach, keyed by [StateKey] id (null =
 * absent), answering the shell commands as the local devices print them. Writes to a key in
 * [stuck] are accepted and ignored, as a device that does not take a value would.
 */
class FakeDeviceState(
    initial: Map<String, String?> = emptyMap(),
    var apiLevel: Int = 34,
    var physicalDensity: Int = 280,
    /** As Samsung's One UI: `cmd uimode night` is accepted and ignored. */
    var nightModeLocked: Boolean = false,
) {
    val values = initial.toMutableMap()
    val stuck = mutableSetOf<String>()
    val writes = mutableListOf<String>()

    private fun write(
        key: String,
        value: String?,
    ): Adb.Result {
        writes += "$key=$value"
        if (key !in stuck) values[key] = value
        return ok("")
    }

    /** The reply to [command], or null when it is not a state command. */
    fun answer(command: String): Adb.Result? {
        val args = command.split(' ')
        if (args.firstOrNull() != "shell") return null
        return when {
            command == "shell getprop ro.build.version.sdk" -> ok("$apiLevel\n")
            args.getOrNull(1) == "settings" -> {
                val key = StateKey.Setting(args[3], args[4]).id
                when (args[2]) {
                    "get" -> ok((values[key] ?: "null") + "\n")
                    "put" -> write(key, args[5])
                    "delete" -> write(key, null)
                    else -> null
                }
            }
            command == "shell cmd uimode night" -> ok("Night mode: ${values[StateKey.NightMode.id] ?: "no"}\n")
            command.startsWith("shell cmd uimode night ") -> if (nightModeLocked) ok("") else write(StateKey.NightMode.id, args[4])
            command == "shell dumpsys uimode" -> ok("Current UI Mode Service state:\n  mNightMode=1 (no)  mNightModeLocked=$nightModeLocked\n")
            command == "shell wm density" ->
                ok("Physical density: $physicalDensity\n" + (values[StateKey.Density.id]?.let { "Override density: $it\n" } ?: ""))
            command == "shell wm density reset" -> write(StateKey.Density.id, null)
            command.startsWith("shell wm density ") -> write(StateKey.Density.id, args[3])
            command.startsWith("shell cmd locale get-app-locales ") -> {
                val pkg = args[4]
                ok("Locales for $pkg for user -2 are [${values[StateKey.AppLocales(pkg).id].orEmpty()}]\n")
            }
            command.startsWith("shell cmd locale set-app-locales ") -> {
                val pkg = args[4]
                write(StateKey.AppLocales(pkg).id, args.getOrNull(args.indexOf("--locales") + 1)?.takeIf { "--locales" in args } ?: "")
            }
            command == "shell cmd connectivity airplane-mode enable" -> airplane(true)
            command == "shell cmd connectivity airplane-mode disable" -> airplane(false)
            command.startsWith("shell svc wifi ") -> {
                val airplane = values[AIRPLANE] == "1"
                write(WIFI, if (args[3] == "enable") (if (airplane) "2" else "1") else "0")
            }
            command.startsWith("shell svc data ") -> write(DATA, if (args[3] == "enable") "1" else "0")
            else -> null
        }
    }

    /** As Android does: airplane mode turns Wi-Fi off (`3`, back on with it) and back on when it ends. */
    private fun airplane(on: Boolean): Adb.Result {
        write(AIRPLANE, if (on) "1" else "0")
        val wifi = values[WIFI]
        if (on && (wifi == "1" || wifi == "2")) values[WIFI] = "3"
        if (!on && (wifi == "3" || wifi == "2")) values[WIFI] = "1"
        return ok("")
    }

    companion object {
        /** The raw global settings behind [StateKey.Network], as the device stores them. */
        val AIRPLANE = StateKey.Setting("global", "airplane_mode_on").id
        val WIFI = StateKey.Setting("global", "wifi_on").id
        val DATA = StateKey.Setting("global", "mobile_data").id
    }
}
