package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.SavedState
import io.github.noamcohen48.tap.host.StateKey
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok

/**
 * The driver's mock location reaches an app's gps listener: with the driver as the mock-location
 * app and location on (as the server sets them), `set_location` makes the fixture read that fix,
 * and a second call moves it. The app-op and location mode are restored afterwards; whether the
 * driver's test providers are gone once its session ends is reported.
 */
@DeviceTest
class MockLocationTest {
    @OnEachDevice
    fun `a mocked fix reaches the app and moves`(serial: String) =
        deviceTest(serial) { device ->
            val keys = listOf(StateKey.LOCATION_MODE, StateKey.DRIVER_MOCK_LOCATION)
            val before = keys.map { SavedState(it, device.adb.readState(serial, it)) }
            try {
                device.adb.writeState(serial, StateKey.DRIVER_MOCK_LOCATION, "allow")
                if (device.adb.readState(serial, StateKey.LOCATION_MODE) == "0") device.adb.writeState(serial, StateKey.LOCATION_MODE, "3")
                device.shell("pm", "grant", FIXTURE_PACKAGE, "android.permission.ACCESS_FINE_LOCATION")
                device.withSession { session ->
                    val client = session.client
                    device.wakeAndDismissKeyguard()
                    device.launchFixture("PermissionActivity")
                    fun id(name: String) = Selectors.androidResource(FIXTURE_PACKAGE, name)
                    check(client.send(Commands.waitVisible(id("read_location")), timeoutMs = 10_000).ok) { "Permission activity did not appear" }

                    for ((latitude, longitude, shown) in listOf(Triple(48.8584, 2.2945, "At 48.85840, 2.29450"), Triple(51.5007, -0.1246, "At 51.50070, -0.12460"))) {
                        val set = client.send(Commands.setLocation(latitude, longitude, 3f), timeoutMs = 10_000)
                        check(set.ok) { "set_location failed: $set" }
                        check(client.send(Commands.tap(id("read_location"))).ok)
                        check(client.send(Commands.waitVisible(Selectors.text(shown)), timeoutMs = 15_000).ok) {
                            "The app did not read $shown: ${client.execute(Commands.snapshot(id("location_value"))).snapshot.text}"
                        }
                    }
                }
            } finally {
                device.adb.restoreState(serial, before)
            }
            val providers = device.shell("dumpsys", "location").lineSequence().count { "$DRIVER_PACKAGE" in it && "mock" in it.lowercase() }
            report("mock_location", serial, "fix" to "ok", "moved" to "ok", "driver_mock_lines_after" to providers)
        }
}
