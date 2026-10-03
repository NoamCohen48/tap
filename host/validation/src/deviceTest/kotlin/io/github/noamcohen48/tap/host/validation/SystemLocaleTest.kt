package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.SavedState
import io.github.noamcohen48.tap.host.StateKey
import io.github.noamcohen48.tap.protocol.Commands

/**
 * The device language set through the driver app's `SystemLocaleReceiver` (as the server does)
 * reads back over ADB and in the driver's `DeviceInfo.system_locales`, then is restored through
 * the same [io.github.noamcohen48.tap.host.Adb.restoreState].
 */
@DeviceTest
class SystemLocaleTest {
    @OnEachDevice
    fun `the device locale set through the driver receiver reads back and is restored`(serial: String) =
        deviceTest(serial) { device ->
            val key = StateKey.SystemLocales.id
            val before = device.adb.readState(serial, key)
            val target = if (before?.startsWith("de-DE") == true) "fr-FR,en-US" else "de-DE,en-US"
            try {
                device.adb.writeState(serial, key, target)
                val read = device.adb.readState(serial, key)
                check(read == target) { "The device reports $read, not $target" }
                device.withSession { session ->
                    val info = session.client.execute(Commands.deviceInfo()).deviceInfo
                    check(info.systemLocalesList.take(2) == target.split(',')) { "The driver sees ${info.systemLocalesList}" }
                }
            } finally {
                device.adb.restoreState(serial, listOf(SavedState(key, before)))
            }
            val after = device.adb.readState(serial, key)
            check(after == before) { "Restored to $after, not $before" }
            report("system_locale", serial, "before" to before.orEmpty(), "set" to target)
        }
}
