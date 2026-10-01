package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.wire.v1.Response
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * A node partly under another window is still reported, with its own bounds, but a tap at its
 * centre would land in the window on top. The fixture's `OcclusionActivity` covers the middle of
 * one button with a popup window of its own: the driver refuses the tap with `NOT_INTERACTABLE` /
 * `OBSCURED`. With the keyboard open (the activity never resizes for it) a button at the bottom
 * is under the keyboard: Android reports a node another window covers completely as invisible,
 * so the tap is `NOT_FOUND`, or `OBSCURED` if the keyboard leaves part of it showing. Either way
 * the on-screen counters prove nothing was clicked (not the target, not the cover) and no key
 * reached the focused field. Uncovered, the same taps go through.
 */
@DeviceTest
class OcclusionTest {
    @OnEachDevice
    fun `a tap on a node another window covers is refused as OBSCURED before any input`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("OcclusionActivity")
                check(client.send(Commands.waitVisible(TOGGLE), timeoutMs = 10_000).ok) { "The occlusion screen did not appear" }

                // An in-app popup window over the target.
                check(client.send(Commands.tap(TOGGLE)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Cover")), timeoutMs = 10_000).ok) { "The cover did not appear" }
                requireObscured(client.send(Commands.tap(COVERED)))
                check(status(client) == "Taps: covered=0 bottom=0 cover=0") { "A refused tap was delivered: ${status(client)}" }

                // The keyboard (another package's window) over the bottom button.
                check(client.send(Commands.tap(INPUT)).ok)
                withTimeout(10_000) {
                    while ("mInputShown=true" !in device.shell("dumpsys", "input_method")) delay(100)
                }
                val underKeyboard = client.send(Commands.tap(BOTTOM))
                check(underKeyboard.errorCode == ErrorCode.ERR_NOT_FOUND || isObscured(underKeyboard)) {
                    "A tap under the keyboard was not refused: $underKeyboard"
                }
                println("$serial: tap under the keyboard -> ${underKeyboard.errorCode} ${underKeyboard.detail.orEmpty()}")
                check(status(client) == "Taps: covered=0 bottom=0 cover=0") { "A refused tap was delivered: ${status(client)}" }
                val field = client.execute(Commands.snapshot(INPUT)).snapshot
                check(field.showingHint) { "A refused tap reached the keyboard: $field" }
                device.shell("input", "keyevent", "KEYCODE_BACK")
                withTimeout(10_000) {
                    while ("mInputShown=true" in device.shell("dumpsys", "input_method")) delay(100)
                }

                // Uncovered, both taps go through: the check does not refuse a reachable node.
                check(client.send(Commands.tap(TOGGLE)).ok)
                check(client.send(Commands.waitGone(Selectors.text("Cover")), timeoutMs = 10_000).ok) { "The cover did not go away" }
                check(client.send(Commands.tap(COVERED)).ok) { "Tap on the uncovered target failed" }
                check(client.send(Commands.tap(BOTTOM)).ok) { "Tap on the uncovered bottom target failed" }
                check(status(client) == "Taps: covered=1 bottom=1 cover=0") { "Unexpected taps: ${status(client)}" }
            }
        }

    private fun isObscured(response: Response): Boolean =
        !response.ok && response.errorCode == ErrorCode.ERR_NOT_INTERACTABLE && response.detail == ErrorDetail.OBSCURED

    private fun requireObscured(response: Response) {
        check(isObscured(response)) { "Expected NOT_INTERACTABLE / OBSCURED: $response" }
    }

    private suspend fun status(client: DriverClient): String = client.execute(Commands.snapshot(STATUS)).snapshot.text

    private companion object {
        val TOGGLE = Selectors.androidResource(FIXTURE_PACKAGE, "cover_toggle")
        val COVERED = Selectors.androidResource(FIXTURE_PACKAGE, "covered_target")
        val BOTTOM = Selectors.androidResource(FIXTURE_PACKAGE, "bottom_target")
        val INPUT = Selectors.androidResource(FIXTURE_PACKAGE, "occlusion_input")
        val STATUS = Selectors.androidResource(FIXTURE_PACKAGE, "occlusion_status")
    }
}
