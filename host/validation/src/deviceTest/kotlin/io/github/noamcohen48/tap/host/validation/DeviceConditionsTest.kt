package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.host.StateKey
import io.github.noamcohen48.tap.protocol.Commands
import kotlinx.coroutines.delay

/**
 * The driver's read-backs for the device conditions: animations, dark mode, font scale and
 * density changed over ADB (as the server does) show in `DeviceInfo` as Android applies them.
 * The server's capture and restore are proven by the client suites; this test restores what it
 * changed itself, through the same [io.github.noamcohen48.tap.host.Adb.restoreState].
 */
@DeviceTest
class DeviceConditionsTest {
    @OnEachDevice
    fun `device conditions changed over adb read back in device info`(serial: String) =
        deviceTest(serial) { device ->
            val keys = StateKey.ANIMATIONS + StateKey.FONT_SCALE + StateKey.NightMode.id + StateKey.Density.id
            val before = keys.map { io.github.noamcohen48.tap.host.SavedState(it, device.adb.readState(serial, it)) }
            try {
                device.withSession { session ->
                    val client = session.client
                    suspend fun info(): DeviceInfo = client.execute(Commands.deviceInfo()).deviceInfo

                    suspend fun await(
                        what: String,
                        holds: (DeviceInfo) -> Boolean,
                    ): DeviceInfo {
                        val deadline = System.nanoTime() + 10_000_000_000L
                        var last = info()
                        while (!holds(last)) {
                            check(System.nanoTime() < deadline) { "$what was not observed: $last" }
                            delay(200)
                            last = info()
                        }
                        return last
                    }
                    val physical = device.shell("wm", "density").lineSequence().first().substringAfter(':').trim().toInt()
                    val target = if (physical == 320) 360 else 320

                    StateKey.ANIMATIONS.forEach { device.adb.writeState(serial, it, "0") }
                    await("animations off") { !it.animationsEnabled }
                    StateKey.ANIMATIONS.forEach { device.adb.writeState(serial, it, "1") }
                    await("animations on") { it.animationsEnabled }

                    // Some devices lock the day/night mode (Samsung's One UI): `cmd uimode night` is ignored there.
                    val nightLocked = device.adb.nightModeLocked(serial)
                    if (!nightLocked) {
                        device.adb.writeState(serial, StateKey.NightMode.id, "yes")
                        await("dark mode") { it.darkMode }
                        device.adb.writeState(serial, StateKey.NightMode.id, "no")
                        await("light mode") { !it.darkMode }
                    }

                    device.adb.writeState(serial, StateKey.FONT_SCALE, "1.3")
                    await("font scale 1.3") { it.fontScale == 1.3f }

                    device.adb.writeState(serial, StateKey.Density.id, target.toString())
                    await("density $target") { it.densityDpi == target }
                    device.adb.writeState(serial, StateKey.Density.id, null)
                    val reset = await("physical density $physical") { it.densityDpi == physical }
                    report(
                        "conditions",
                        serial,
                        "animations" to "ok",
                        "dark_mode" to if (nightLocked) "locked" else "ok",
                        "font_scale" to "ok",
                        "density" to "$target,$physical",
                        "font_scale_now" to reset.fontScale,
                    )
                }
            } finally {
                device.adb.restoreState(serial, before)
            }
        }
}
