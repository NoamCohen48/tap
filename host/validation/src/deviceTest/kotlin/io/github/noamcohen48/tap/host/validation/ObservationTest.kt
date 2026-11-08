package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.KEYCODE_BACK
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.errorOrNull
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.protocol.pickAt
import io.github.noamcohen48.tap.protocol.pickFirst

/**
 * Query and key operations: `COUNT` against duplicates, `SNAPSHOT` state (and `AMBIGUOUS` on
 * duplicates), `WAIT_STATE` (reached, not reached with the last state and per-part results, `AMBIGUOUS`
 * without a pick, a picked duplicate, no match), the `NOT_FOUND` narrowing (per-part counts, near
 * misses, a pick past the matches), `DEVICE_INFO`, `WAIT_APP_VISIBLE`, a host-refused key code, and `PRESS_KEY`
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

                val enabled = client.send(Commands.waitState(GESTURE_TARGET, Nodes.flag(NodeFlag.FLAG_ENABLED)), timeoutMs = 5_000)
                check(enabled.ok) { "WAIT_STATE enabled should hold at once: $enabled" }
                val disabled = client.send(Commands.waitState(GESTURE_TARGET, Nodes.flag(NodeFlag.FLAG_ENABLED, false)), timeoutMs = 500)
                val notReached = disabled.errorOrNull
                check(
                    notReached?.code == ErrorCode.ERR_WAIT_TIMEOUT && notReached.detail == ErrorDetail.STATE_NOT_REACHED &&
                        notReached.lastState.enabled && notReached.lastState.resourceName == state.resourceName,
                ) { "WAIT_STATE disabled should time out with the last state: $disabled" }
                // A combined condition: each top-level part's result comes from the same final read.
                val combined = Nodes.allOf(Nodes.flag(NodeFlag.FLAG_ENABLED), Nodes.text("not its text"))
                val partly = client.send(Commands.waitState(GESTURE_TARGET, combined), timeoutMs = 500).errorOrNull
                check(partly?.detail == ErrorDetail.STATE_NOT_REACHED && partly.conditionHoldsList == listOf(true, false)) {
                    "WAIT_STATE should report which condition part failed: $partly"
                }
                val unpicked = client.send(Commands.waitState(Selectors.text("Duplicate action"), Nodes.flag(NodeFlag.FLAG_ENABLED)), timeoutMs = 5_000)
                check(unpicked.errorCode == ErrorCode.ERR_AMBIGUOUS) { "WAIT_STATE on duplicates should be AMBIGUOUS: $unpicked" }
                val picked = client.send(Commands.waitState(Selectors.text("Duplicate action").pickFirst(), Nodes.flag(NodeFlag.FLAG_ENABLED)), timeoutMs = 5_000)
                check(picked.ok) { "WAIT_STATE on a picked duplicate should hold: $picked" }
                val missing = client.send(Commands.waitState(Selectors.text("never on screen"), Nodes.flag(NodeFlag.FLAG_ENABLED)), timeoutMs = 500)
                check(missing.errorOrNull?.detail == ErrorDetail.NO_MATCH) { "WAIT_STATE with no match should be NO_MATCH: $missing" }

                // Two "Duplicate action" buttons, neither scrollable: the flag is what rules them out.
                val unscrollable = Nodes.allOf(Nodes.text("Duplicate action"), Nodes.flag(NodeFlag.FLAG_SCROLLABLE, true))
                val narrowed = client.send(Commands.tap(Selectors.of(unscrollable)))
                val parts = narrowed.errorOrNull?.narrowing?.partsList.orEmpty()
                check(
                    narrowed.errorCode == ErrorCode.ERR_NOT_FOUND && narrowed.errorOrNull?.narrowing?.matches == 0 && parts.size == 2 &&
                        parts[0].alone == 2 && parts[1].without == 2 && parts[1].nearMissesCount == 0,
                ) { "NOT_FOUND should narrow to the scrollable flag: $narrowed" }
                // A wrong text on the gesture target: the text part rules it out, and its real text is a near miss.
                val wrongText = Nodes.allOf(GESTURE_TARGET.node, Nodes.text("not its text"))
                val near = client.send(Commands.tap(Selectors.of(wrongText))).errorOrNull?.narrowing?.partsList.orEmpty()
                check(near.size == 2 && near[1].alone == 0 && near[1].without == 1 && near[1].nearMissesCount <= 3) {
                    "NOT_FOUND should narrow to the text part: $near"
                }
                val pastTheEnd = client.send(Commands.tap(Selectors.text("Duplicate action").pickAt(5)))
                check(pastTheEnd.errorCode == ErrorCode.ERR_NOT_FOUND && pastTheEnd.errorOrNull?.narrowing?.matches == 2) {
                    "A pick past the matches should report them: $pastTheEnd"
                }

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
