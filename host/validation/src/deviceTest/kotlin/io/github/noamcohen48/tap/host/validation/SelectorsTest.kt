package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.protocol.op
import io.github.noamcohen48.tap.protocol.or
import io.github.noamcohen48.tap.protocol.pickAt
import io.github.noamcohen48.tap.protocol.pickFirst

/**
 * Selector proof on `AmbiguityActivity`: every mutating operation returns `AMBIGUOUS` before
 * input when two targets match, explicit `first()`/`at()` limits and relations pick one, the
 * traversal plan (regex, any_of, repeated text) agrees with the native plan, structural and
 * scope errors are refused, and long-press/tap/clear-text gestures verify their effect.
 */
@DeviceTest
class SelectorsTest {
    @OnEachDevice
    fun `ambiguity, limits, relations, traversal plan and gestures`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.openAmbiguityFixture(client)

                val duplicateButton = Selectors.text("Duplicate action")
                val duplicateInput = Selectors.androidResource(FIXTURE_PACKAGE, "duplicate_input")
                val duplicateScroll = Selectors.androidResource(FIXTURE_PACKAGE, "duplicate_scroll")

                suspend fun expectAmbiguous(
                    command: Command,
                    timeoutMs: Long = 5_000,
                ) {
                    val response = client.send(command, timeoutMs)
                    check(response.errorCode == ErrorCode.ERR_AMBIGUOUS) { "${command.op} should be AMBIGUOUS: $response" }
                }
                expectAmbiguous(Commands.tap(duplicateButton))
                expectAmbiguous(Commands.longTap(duplicateButton))
                expectAmbiguous(Commands.setText(duplicateInput, "leak"))
                expectAmbiguous(Commands.typeText(duplicateInput, "leak"))
                expectAmbiguous(Commands.clearText(duplicateInput))
                expectAmbiguous(Commands.swipe(duplicateScroll, Direction.DIR_UP))
                expectAmbiguous(Commands.scroll(duplicateScroll, Direction.DIR_DOWN))
                expectAmbiguous(Commands.scrollUntil(Selectors.text("never"), container = duplicateScroll), timeoutMs = 10_000)
                check(client.execute(Commands.exists(Selectors.text("Duplicate taps: 0"))).bool) {
                    "An AMBIGUOUS tap changed the fixture"
                }
                check(!client.execute(Commands.exists(Selectors.text("leak"))).bool) {
                    "An AMBIGUOUS text operation changed the fixture"
                }

                // Explicit limits and relations resolve one of the duplicates.
                check(client.send(Commands.tap(duplicateButton.pickFirst())).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Duplicate taps: 1"))).ok)
                check(client.send(Commands.tap(duplicateButton.pickAt(1))).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Duplicate taps: 2"))).ok)
                val missingIndex = client.send(Commands.tap(duplicateButton.pickAt(2)))
                check(!missingIndex.ok && missingIndex.errorCode == ErrorCode.ERR_NOT_FOUND) { "at(2) should be NOT_FOUND: $missingIndex" }
                val rightButton =
                    Selectors.of(Nodes.text("Duplicate action") and Nodes.ancestor(Nodes.androidResource(FIXTURE_PACKAGE, "right_half")))
                check(client.send(Commands.tap(rightButton)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Duplicate taps: 3"))).ok)
                val leftHalfWithButton =
                    Selectors.of(
                        Nodes.androidResource(FIXTURE_PACKAGE, "left_half") and
                            Nodes.child(Nodes.className("Button", MatchMode.MATCH_ENDS_WITH) and Nodes.flag(NodeFlag.FLAG_CLICKABLE)),
                    )
                check(client.execute(Commands.exists(leftHalfWithButton)).bool) { "child relation did not match" }

                // Regex forces the traversal plan; it must agree with the native plan on cardinality.
                val regexButton = Selectors.text("^Duplicate act.*", MatchMode.MATCH_REGEX)
                expectAmbiguous(Commands.tap(regexButton))
                val regexLeft =
                    Selectors.of(
                        Nodes.text("^Duplicate act.*", MatchMode.MATCH_REGEX) and
                            Nodes.ancestor(Nodes.androidResource(FIXTURE_PACKAGE, "left_half")),
                    )
                check(client.send(Commands.tap(regexLeft)).ok) { "Traversal-plan tap failed" }
                check(client.send(Commands.waitVisible(Selectors.text("^Duplicate taps: \\d+$", MatchMode.MATCH_REGEX))).ok)
                check(client.execute(Commands.exists(Selectors.text("Duplicate taps: 4"))).bool)
                check(!client.execute(Commands.exists(Selectors.text("^Duplicate taps: 9$", MatchMode.MATCH_REGEX))).bool)

                // any_of and a repeated text constraint also take the traversal plan; both must agree with native counts.
                val duplicates = client.execute(Commands.count(Selectors.text("Duplicate action"))).count
                val eitherText = Selectors.of(Nodes.text("Duplicate action") or Nodes.text("Ambiguity fixture ready"))
                check(client.execute(Commands.count(eitherText)).count == duplicates + 1) { "any_of count disagrees with the native plan" }
                expectAmbiguous(Commands.tap(Selectors.of(Nodes.text("Duplicate action") or Nodes.text("never on screen"))))
                val twoTexts =
                    Selectors.of(Nodes.text("Duplicate", MatchMode.MATCH_STARTS_WITH) and Nodes.text("action", MatchMode.MATCH_ENDS_WITH))
                check(client.execute(Commands.count(twoTexts)).count == duplicates) { "repeated-text conjunction disagrees with the native plan" }
                val eitherOnRight =
                    Selectors.of(
                        (Nodes.text("never on screen") or Nodes.text("Duplicate action")) and
                            Nodes.ancestor(Nodes.androidResource(FIXTURE_PACKAGE, "right_half")),
                    )
                check(client.send(Commands.tap(eitherOnRight)).ok) { "any_of inside a conjunction failed to tap" }
                check(client.send(Commands.waitVisible(Selectors.text("Duplicate taps: 5"))).ok)

                // Structural rejections never consume a request on the host and are INVALID_SELECTOR on the driver.
                val emptyConjunction = Node.newBuilder().setAllOf(AllOf.getDefaultInstance()).build()
                runCatching { client.send(Commands.exists(Selectors.of(emptyConjunction))) }.exceptionOrNull().let { error ->
                    check(error is InvalidCommandException && error.code == ErrorCode.ERR_INVALID_SELECTOR) {
                        "Host validation should reject an empty conjunction: $error"
                    }
                }
                val foreignResource = client.send(Commands.exists(Selectors.androidResource("com.other.app", "duplicate_button")))
                check(
                    !foreignResource.ok && foreignResource.errorCode == ErrorCode.ERR_INVALID_SELECTOR &&
                        foreignResource.detail == ErrorDetail.SCOPE_DENIED,
                ) { "Foreign AUT resource should be SCOPE_DENIED: $foreignResource" }

                // Gestures.
                check(client.send(Commands.longTap(GESTURE_TARGET)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Gesture: long press"))).ok)
                check(client.send(Commands.tap(GESTURE_TARGET)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Gesture: tap"))).ok)
                val prefilled = Selectors.androidResource(FIXTURE_PACKAGE, "prefilled_input")
                check(client.execute(Commands.exists(Selectors.text("prefilled"))).bool)
                val cleared = client.send(Commands.clearText(prefilled))
                check(cleared.ok) { "CLEAR_TEXT failed: $cleared" }
                check(!client.execute(Commands.exists(Selectors.text("prefilled"))).bool) { "CLEAR_TEXT left text" }
                val notEditable = client.send(Commands.clearText(GESTURE_TARGET))
                check(!notEditable.ok && notEditable.errorCode == ErrorCode.ERR_NOT_INTERACTABLE) {
                    "CLEAR_TEXT on a button should be NOT_INTERACTABLE: $notEditable"
                }
            }
        }
}

/** The fixture's `AmbiguityActivity`: duplicate targets, a gesture target and a prefilled field. */
internal suspend fun DeviceHarness.openAmbiguityFixture(client: DriverClient) {
    wakeAndDismissKeyguard()
    launchFixture("AmbiguityActivity")
    val ready = client.send(Commands.waitVisible(Selectors.text("Ambiguity fixture ready")), timeoutMs = 10_000)
    check(ready.ok) { "Ambiguity fixture did not appear: $ready" }
}

internal val GESTURE_TARGET = Selectors.androidResource(FIXTURE_PACKAGE, "gesture_target")
