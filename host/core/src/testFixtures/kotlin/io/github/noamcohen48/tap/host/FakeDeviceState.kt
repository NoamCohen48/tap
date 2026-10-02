package io.github.noamcohen48.tap.host

import java.nio.file.Files
import java.nio.file.Path

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
    /** The build's locale (`ro.product.locale`), the one in effect until the system locale is set. */
    var productLocale: String = "en-US",
    /** The driver app's locale receiver answers `result=2` instead of applying the locale. */
    var localeReceiverFails: Boolean = false,
) {
    val values = initial.toMutableMap()

    /** Device files by absolute path, the directories that exist, and the paths the media index lists. */
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf("/", "/sdcard", "/sdcard/Pictures", "/sdcard/Movies", "/data", "/data/local", "/data/local/tmp")
    val mediaIndex = mutableSetOf<String>()

    /** As a device whose media scanner skips files (API 29 without `scan_file`, say). */
    var mediaScannerIgnores: Boolean = false

    /** Index queries before a scan broadcast's result shows (the broadcast scans asynchronously). */
    var broadcastScanQueries: Int = 0
    private val pendingScans = mutableMapOf<String, Int>()

    /** Files that exist but the shell user may not read (as `/system/build.prop` on Samsung). */
    val unreadable = mutableSetOf<String>()
    val stuck = mutableSetOf<String>()

    /** The location providers that are test providers, as a mock location leaves them. */
    val mockProviders = mutableSetOf<String>()

    /**
     * The driver's listener is among the live (bound) listeners: Android binds it when its access
     * is given and unbinds it when the access is taken back, and Android 10 binds it again after its
     * process is killed, whatever the access (set this for that).
     */
    var driverListenerBound: Boolean = false

    /** The resumed activity `dumpsys activity activities` reports (`pkg/.Name` or `pkg/full.Name`); null = none. */
    var resumedActivity: String? = null

    /** `dumpsys activity` in the API 26-28 layout (`mResumedActivity:` without `topResumedActivity=`). */
    var legacyActivityDump: Boolean = false

    /** `dumpsys location` in the API 26-28 layout (a "Mock Providers" section). */
    var legacyLocationDump: Boolean = false
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
        fileAnswer(command, args)?.let { return it }
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
            command == "shell getprop persist.sys.locale" -> ok((values[PERSIST_LOCALE] ?: "") + "\n")
            command == "shell getprop ro.product.locale" -> ok("$productLocale\n")
            command.startsWith("shell pm grant $DRIVER_PACKAGE ") || command == "shell appops set $DRIVER_PACKAGE WRITE_SETTINGS allow" -> ok("")
            args.getOrNull(1) == "appops" && args.size == 5 && args[2] == "get" -> {
                val mode = values[StateKey.AppOp(args[3], args[4]).id]
                // As Android prints it: an op at its default (or deny, the default) has no entry.
                if (mode == null || mode == "deny") {
                    ok("No operations.\nDefault mode: deny\n")
                } else {
                    ok("${args[4].removePrefix("android:").uppercase()}: $mode; time=+1s ago\n")
                }
            }
            args.getOrNull(1) == "appops" && args.size == 6 && args[2] == "set" -> write(StateKey.AppOp(args[3], args[4]).id, args[5])
            command.startsWith("shell am broadcast ") && "$DRIVER_PACKAGE/.SystemLocaleReceiver" in command -> {
                val tags = args[args.indexOf("locales") + 1].trim('\'')
                if (localeReceiverFails) {
                    ok("Broadcasting: Intent { }\nBroadcast completed: result=2, data=\"java.lang.SecurityException: denied\"\n")
                } else {
                    write(SYSTEM_LOCALES, tags)
                    values[PERSIST_LOCALE] = tags.substringBefore(',')
                    ok("Broadcasting: Intent { }\nBroadcast completed: result=1, data=\"$tags\"\n")
                }
            }
            command == "shell dumpsys location" -> ok(locationDump())
            command == "shell dumpsys notification" -> ok(notificationDump())
            command == "shell cmd notification allow_listener $DRIVER_NOTIFICATION_LISTENER" -> {
                if (StateKey.DriverNotificationListener.id !in stuck) driverListenerBound = true
                write(StateKey.DriverNotificationListener.id, "allowed")
            }
            command == "shell cmd notification disallow_listener $DRIVER_NOTIFICATION_LISTENER" -> {
                // Android unbinds on the change only: a listener bound while disallowed stays.
                if (values[StateKey.DriverNotificationListener.id] == "allowed" && StateKey.DriverNotificationListener.id !in stuck) driverListenerBound = false
                write(StateKey.DriverNotificationListener.id, "disallowed")
            }
            command == "shell dumpsys activity activities" -> ok(activityDump())
            command.startsWith("shell am broadcast ") && "$DRIVER_PACKAGE/.MockLocationReceiver" in command -> {
                val names = args[args.indexOf("remove") + 1].trim('\'').split(',')
                writes += "mock-providers-removed=${names.joinToString(",")}"
                // As LocationManager: without the app-op the call is ignored.
                if (values[StateKey.DRIVER_MOCK_LOCATION] == "allow") mockProviders -= names.toSet()
                ok("Broadcasting: Intent { }\nBroadcast completed: result=1, data=\"${names.joinToString(",")}\"\n")
            }
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

    /** The file commands [Adb] sends (paths without spaces, as the tests use). */
    private fun fileAnswer(
        command: String,
        args: List<String>,
    ): Adb.Result? {
        fun path(token: String) = token.removeSurrounding("'")
        return when {
            args[0] == "push" -> {
                val target = path(args[2])
                if (target.substringBeforeLast('/').ifEmpty { "/" } !in directories) return Adb.Result(1, "adb: error: failed to copy: No such file or directory\n")
                files[target] = Files.readAllBytes(Path.of(args[1]))
                ok("1 file pushed.\n")
            }
            args[0] == "pull" -> {
                if (path(args[1]) in unreadable) return Adb.Result(1, "adb: error: failed to copy: remote open failed: Permission denied\n")
                val bytes = files[path(args[1])] ?: return Adb.Result(1, "adb: error: remote object does not exist\n")
                Files.write(Path.of(args[2]), bytes)
                ok("1 file pulled.\n")
            }
            command.startsWith("shell stat -c ") -> {
                val target = path(args.last())
                when {
                    target in files -> ok((if (files.getValue(target).isEmpty()) "regular empty file" else "regular file") + "|${files.getValue(target).size}\n")
                    target in directories -> ok("directory|4096\n")
                    else -> Adb.Result(1, "stat: '$target': No such file or directory\n")
                }
            }
            command.startsWith("shell test -r ") -> path(args[3]).let { Adb.Result(if ((it in files || it in directories) && it !in unreadable) 0 else 1, "") }
            command.startsWith("shell test -d ") -> Adb.Result(if (path(args[3]) in directories) 0 else 1, "")
            command.startsWith("shell mkdir -p ") -> {
                var directory = path(args[3])
                while (directory.isNotEmpty()) {
                    directories += directory
                    directory = directory.substringBeforeLast('/')
                }
                ok("")
            }
            command.startsWith("shell rm -f ") -> {
                files.remove(path(args[3]))
                ok("")
            }
            command.startsWith("shell rmdir ") -> {
                val directory = path(args[2])
                if (files.keys.any { it.startsWith("$directory/") }) return Adb.Result(1, "rmdir: '$directory': Directory not empty\n")
                directories -= directory
                ok("")
            }
            command.startsWith("shell content call --uri content://media/ --method scan_file ") -> {
                val target = path(args.last())
                // As API 29's MediaProvider: it reads a Uri extra `content call` cannot send.
                if (apiLevel < 30) return ok("Error while accessing provider:media\njava.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.String android.net.Uri.getPath()' on a null object reference\n")
                if (!mediaScannerIgnores) scanned(target)
                ok("Result: Bundle[{}]\n")
            }
            command.startsWith("shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE ") -> {
                val target = path(args.last()).removePrefix("file://")
                if (!mediaScannerIgnores) if (broadcastScanQueries > 0) pendingScans[target] = broadcastScanQueries else scanned(target)
                ok("Broadcast completed: result=0\n")
            }
            command.startsWith("shell content query --uri content://media/external/file ") -> {
                pendingScans.keys.toList().forEach { target ->
                    val left = pendingScans.getValue(target) - 1
                    if (left > 0) pendingScans[target] = left else scanned(target).also { pendingScans -= target }
                }
                val where = command.replace("'\\''", "'")
                val name = Regex("_display_name='([^']*)'").find(where)!!.groupValues[1]
                val folder = Regex("LIKE '%/(.*)/").find(where)!!.groupValues[1]
                val rows = mediaIndex.filter { it.endsWith("/$folder/$name") }
                ok(if (rows.isEmpty()) "No result found.\n" else rows.mapIndexed { i, _ -> "Row: $i _id=${100 + i}" }.joinToString("\n") + "\n")
            }
            else -> null
        }
    }

    private fun activityDump(): String {
        val record = resumedActivity?.let { "ActivityRecord{40ee6c0 u0 $it t1063}" }
        return "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n  Display #0:\n" +
            if (record == null) {
                "  ResumedActivity: null\n"
            } else if (legacyActivityDump) {
                "    mResumedActivity: $record\n  ResumedActivity: $record\n"
            } else {
                "    mResumedActivity: $record\n  topResumedActivity=$record\n  ResumedActivity: $record\n"
            }
    }

    /** As the Samsung prints it: the approved listeners on one line, colon-separated. */
    private fun notificationDump(): String {
        val approved = listOfNotNull("com.sec.android.app.launcher/com.android.launcher3.notification.NotificationListener", "$DRIVER_PACKAGE/.TapNotificationListener".takeIf { values[StateKey.DriverNotificationListener.id] == "allowed" })
        return "Current Notification Manager state:\n  Notification listeners:\n    Allowed notification listeners:\n" +
            "      ${approved.joinToString(":")} (user: 0 isPrimary: true)\n" +
            "    All notification listeners (1) enabled for current profiles:\n      ComponentInfo{com.sec.android.app.launcher/com.android.launcher3.notification.NotificationListener}\n" +
            "    Live notification listeners (${if (driverListenerBound) 2 else 1}):\n" +
            "      ComponentInfo{com.sec.android.app.launcher/com.android.launcher3.notification.NotificationListener} (user 0): Proxy@1\n" +
            (if (driverListenerBound) "      ComponentInfo{$DRIVER_NOTIFICATION_LISTENER} (user 0): Proxy@2\n" else "")
    }

    private fun locationDump(): String =
        if (legacyLocationDump) {
            "Location Providers:\n    gps Internal State:\n  Mock Providers:\n" +
                mockProviders.joinToString("") { "      $it\n      mHasLocation=true\n      mLocation:\n" }
        } else {
            "Location Manager State:\n  Location Providers:\n" +
                listOf("passive", "network", "fused", "gps").joinToString("") { name ->
                    "    $name provider${if (name in mockProviders) " [mock]" else ""}:\n      enabled=true\n"
                }
        }

    private fun scanned(target: String) {
        if (target in files) mediaIndex += target else mediaIndex -= target
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

        /** The settings value and property the system writes when its locale is set. */
        val SYSTEM_LOCALES = StateKey.Setting("system", "system_locales").id
        const val PERSIST_LOCALE = "prop:persist.sys.locale"
    }
}
