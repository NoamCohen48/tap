package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.RemoteCommandException
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay

/**
 * `device_info` reports screen power and the keyguard as Android sees them, `KEYCODE_SLEEP` /
 * `KEYCODE_WAKEUP` change the first, and `dismiss_keyguard` takes down a keyguard without a
 * PIN and is a no-op when none shows. A device with a PIN, pattern or password is never put to
 * sleep here (it would stay locked for the rest of the suite); its refusal is not exercised on a device.
 */
@DeviceTest
class ScreenTest {
    @OnEachDevice
    fun `sleep, wake and keyguard dismissal are reported by device_info`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                val awake = client.execute(Commands.deviceInfo()).deviceInfo
                check(awake.screenOn) { "The screen should be on after waking: $awake" }
                if (awake.keyguardSecure) {
                    report("screen", serial, "skipped" to "the device has a secure lock screen")
                    return@withSession
                }

                check(client.send(Commands.pressKey(KEYCODE_SLEEP)).ok)
                check(awaitInfo(client) { !it.screenOn }) { "The screen did not turn off" }
                check(client.send(Commands.pressKey(KEYCODE_WAKEUP)).ok)
                check(awaitInfo(client) { it.screenOn }) { "The screen did not turn on" }
                val locked = client.execute(Commands.deviceInfo()).deviceInfo
                val dismiss = client.send(Commands.dismissKeyguard())
                check(dismiss.ok) { "Dismissing an insecure keyguard failed: $dismiss" }
                check(awaitInfo(client) { !it.keyguardLocked }) { "The keyguard is still showing after dismiss_keyguard" }
                // With nothing showing it is a no-op that still succeeds.
                check(client.send(Commands.dismissKeyguard()).ok)
                // isInteractive turns true before the display can be captured; leave the device
                // ready for the next test (a screenshot right after waking can be CAPTURE_FAILED).
                check(awaitScreenshot(client)) { "The display could not be captured 5 s after waking" }
                report("screen", serial, "sleep_wake" to "ok", "keyguard_after_wake" to locked.keyguardLocked, "dismissed" to "ok")
            }
        }

    private suspend fun awaitInfo(
        client: DriverClient,
        condition: (DeviceInfo) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition(client.execute(Commands.deviceInfo()).deviceInfo)) return true
            delay(100)
        }
        return false
    }

    private suspend fun awaitScreenshot(client: DriverClient): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            try {
                client.screenshot()
                return true
            } catch (_: RemoteCommandException) {
                delay(200)
            }
        }
        return false
    }

    private companion object {
        const val KEYCODE_SLEEP = 223
        const val KEYCODE_WAKEUP = 224
    }
}
