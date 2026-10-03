package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.DisplayRotation
import io.github.noamcohen48.tap.api.v1.Orientation
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ok

/** Rotation geometry, exact natural-relative values, sensor unlock, and explicit test cleanup. */
@DeviceTest
class RotationTest {
    @OnEachDevice
    fun `rotation changes are observed and sensor lock can be released`(serial: String) =
        deviceTest(serial) { device ->
            val initial = device.adb.rotationState(serial)
            try {
                device.withSession { session ->
                    val client = session.client
                    device.openFixtureMain(client)

                    val portraitResponse = client.send(Commands.setOrientation(Orientation.ORIENTATION_PORTRAIT))
                    check(portraitResponse.ok) { "Portrait command failed: $portraitResponse" }
                    val portrait = client.execute(Commands.deviceInfo()).deviceInfo
                    check(portrait.displayHeight >= portrait.displayWidth) { "Portrait was not observed: $portrait" }

                    val landscapeResponse = client.send(Commands.setOrientation(Orientation.ORIENTATION_LANDSCAPE))
                    check(landscapeResponse.ok) { "Landscape command failed: $landscapeResponse" }
                    val landscape = client.execute(Commands.deviceInfo()).deviceInfo
                    check(landscape.displayWidth >= landscape.displayHeight) { "Landscape was not observed: $landscape" }

                    val exact =
                        listOf(
                            DisplayRotation.DISPLAY_ROTATION_NATURAL to 0,
                            DisplayRotation.DISPLAY_ROTATION_LEFT to 1,
                            DisplayRotation.DISPLAY_ROTATION_UPSIDE_DOWN to 2,
                            DisplayRotation.DISPLAY_ROTATION_RIGHT to 3,
                        )
                    exact.forEach { (rotation, expected) ->
                        val response = client.send(Commands.setDisplayRotation(rotation))
                        check(response.ok) { "$rotation failed: $response" }
                        val observed = client.execute(Commands.deviceInfo()).deviceInfo.displayRotation
                        check(observed == expected) { "$rotation was observed as rotation=$observed, expected $expected" }
                    }

                    check(client.send(Commands.unfreezeRotation()).ok)
                    check(device.shell("settings", "get", "system", "accelerometer_rotation").trim() == "1")
                    report("rotation", serial, "portrait" to "ok", "landscape" to "ok", "exact" to "0,1,2,3")
                }
            } finally {
                device.adb.restoreRotationState(serial, initial)
            }
        }
}
