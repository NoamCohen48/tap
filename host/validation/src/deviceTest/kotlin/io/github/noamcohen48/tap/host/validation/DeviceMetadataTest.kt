package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Metadata is a snapshot, not a request to settle the accessibility event stream. */
@DeviceTest
class DeviceMetadataTest {
    @OnEachDevice
    fun `device info follows foreground changes without retaining an old root`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client

                suspend fun awaitPackage(fixture: Boolean) {
                    withTimeout(5_000) {
                        while (true) {
                            val pkg = client.execute(Commands.deviceInfo()).deviceInfo.currentPackage
                            if (pkg.isNotBlank() && (pkg == FIXTURE_PACKAGE) == fixture) break
                            delay(50)
                        }
                    }
                }
                device.openFixtureMain(client)
                awaitPackage(true)
                client.execute(Commands.pressKey(3)) // Android KEYCODE_HOME, not an app-specific route.
                awaitPackage(false)
                device.openFixtureMain(client)
                awaitPackage(true)
                report("metadata", serial, "fresh_package" to "ok")
            }
        }

    @OnEachDevice
    fun `device info remains readable while the fixture emits accessibility events`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("MotionActivity")
                val toggle = Selectors.androidResource(FIXTURE_PACKAGE, "ticker_button")
                check(client.send(Commands.waitVisible(toggle), timeoutMs = 5_000).ok)
                check(client.send(Commands.tap(toggle)).ok)
                try {
                    // Do not use a selector while the ticker is active: selector idle behavior is
                    // intentionally unchanged. Give the fixture's click handler time to start it.
                    delay(350)
                    val durations = mutableListOf<Long>()
                    repeat(5) {
                        // Shorter than UiAutomator's 1000 ms idle cap, but ample for platform reads.
                        val response = client.send(Commands.deviceInfo(), timeoutMs = 750)
                        check(response.ok) { "Metadata waited for a never-idle screen: $response" }
                        val info = response.result.deviceInfo
                        check(info.currentPackage == FIXTURE_PACKAGE) { "Wrong current package: $info" }
                        check(info.displayRotation in 0..3 && info.displayWidth > 0 && info.displayHeight > 0)
                        check(info.screenOn)
                        durations += response.result.durationMs
                        delay(120)
                    }
                    report("metadata", serial, "busy_screen" to "ok", "driver_ms" to durations.joinToString(","))
                } finally {
                    // Stop only the test fixture, without going through a busy-screen selector.
                    device.shell("am", "force-stop", FIXTURE_PACKAGE)
                }
            }
        }
}
