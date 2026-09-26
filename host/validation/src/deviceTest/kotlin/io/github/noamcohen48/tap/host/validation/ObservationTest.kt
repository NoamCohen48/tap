package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.KEYCODE_BACK
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

/**
 * Query and key operations: `COUNT` against duplicates, `SNAPSHOT` state (and `AMBIGUOUS` on
 * duplicates), `DEVICE_INFO`, `WAIT_APP_VISIBLE`, a host-refused key code, and `PRESS_KEY`
 * back leaving the activity, proven by `WAIT_GONE`.
 */
@DeviceTest
class ObservationTest {
    @OnEachDevice
    fun `count, snapshot, device info and keys observe the fixture`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                // Back from AmbiguityActivity lands on the main screen underneath.
                device.openFixtureMain(client)
                device.openAmbiguityFixture(client)

                val duplicates = client.execute(Commands.count(Selectors.text("Duplicate action"))).count
                check(duplicates == 2) { "COUNT should report 2 duplicates: $duplicates" }
                check(client.execute(Commands.count(Selectors.text("never on screen"))).count == 0)

                val state = client.execute(Commands.snapshot(GESTURE_TARGET)).snapshot
                check(state.clickable && state.longClickable && state.enabled && state.bounds.right > state.bounds.left) {
                    "Unexpected gesture target snapshot: $state"
                }
                check(state.resourceName == "$FIXTURE_PACKAGE:id/gesture_target") { "Unexpected resource: $state" }
                val ambiguousSnapshot = client.send(Commands.snapshot(Selectors.text("Duplicate action")))
                check(ambiguousSnapshot.errorCode == ErrorCode.ERR_AMBIGUOUS) { "SNAPSHOT should be AMBIGUOUS: $ambiguousSnapshot" }

                val info = client.execute(Commands.deviceInfo()).deviceInfo
                check(info.apiLevel >= 26 && info.displayWidth > 0 && info.currentPackage == FIXTURE_PACKAGE) {
                    "Unexpected device info: $info"
                }
                check(client.send(Commands.waitAppVisible(FIXTURE_PACKAGE), timeoutMs = 5_000).ok)
                // A negative key code is rejected on the host before any frame is written.
                check(rejectedByHost(client, Commands.pressKey(-1))) { "Negative key code should be invalid" }

                val back = client.send(Commands.pressKey(KEYCODE_BACK))
                check(back.ok) { "PRESS_KEY back failed: $back" }
                val gone = client.send(Commands.waitGone(Selectors.text("Ambiguity fixture ready")), timeoutMs = 10_000)
                check(gone.ok) { "Ambiguity screen did not go away after back: $gone" }
                report("observation", serial, "api" to info.apiLevel, "model" to info.model)
            }
        }
}
