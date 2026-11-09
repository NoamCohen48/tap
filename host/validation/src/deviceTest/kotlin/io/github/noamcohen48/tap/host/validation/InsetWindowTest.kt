package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Windows with surface insets (a dialog, an elevated popup), which some devices report to
 * accessibility moved up and left by that margin (SM-J810G, API 29: by 56 px). The driver takes
 * the window's real frame from its root node, so:
 *
 * - a node at a dialog's bottom-right edge is reported, in a snapshot and in the hierarchy dump,
 *   with the bounds the app itself measures (not cut short at the moved window's edge), and a tap
 *   on it is delivered;
 * - next to an elevated popup window that does not take focus, a button just left of it is
 *   tapped (not refused as under the moved popup), and a button whose centre is under the
 *   popup's bottom edge is refused as `OBSCURED` (not tapped through to the popup).
 *
 * On a device that reports the bounds exactly (the API 34 emulator) the same holds unchanged.
 */
@DeviceTest
class InsetWindowTest {
    @OnEachDevice
    fun `bounds and touch checks use a window's real frame`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("InsetWindowActivity")
                check(client.send(Commands.waitVisible(SHOW_COVER), timeoutMs = 10_000).ok) { "The inset-window screen did not appear" }

                // An elevated popup beside one button and over half of another.
                check(client.send(Commands.tap(SHOW_COVER)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Elevated cover")), timeoutMs = 10_000).ok) { "The cover did not appear" }
                delay(500)
                report("inset-window", serial, "cover" to windowFrames(device, "PopupWindow"))
                val beside = client.send(Commands.tap(BESIDE))
                check(beside.ok) { "A tap beside the elevated popup was refused: $beside" }
                awaitText(client, STATUS, "Taps: beside=1 halfUnder=0 cover=0 corner=0")
                val halfUnder = client.send(Commands.tap(HALF_UNDER))
                check(!halfUnder.ok && halfUnder.errorCode == ErrorCode.ERR_NOT_INTERACTABLE && halfUnder.detail == ErrorDetail.OBSCURED) {
                    "A tap whose point is under the elevated popup was not refused as OBSCURED: $halfUnder"
                }
                delay(500)
                check(text(client, STATUS) == "Taps: beside=1 halfUnder=0 cover=0 corner=0") { "A refused tap was delivered: ${text(client, STATUS)}" }
                check(client.send(Commands.tap(SHOW_COVER)).ok)
                check(client.send(Commands.waitGone(Selectors.text("Elevated cover")), timeoutMs = 10_000).ok) { "The cover did not go away" }

                // A dialog's bottom-right corner.
                check(client.send(Commands.tap(SHOW_DIALOG)).ok)
                check(client.send(Commands.waitVisible(CORNER), timeoutMs = 10_000).ok) { "The dialog did not appear" }
                awaitText(client, CORNER_LOCATION, null)
                delay(500)
                report("inset-window", serial, "dialog" to windowFrames(device, "InsetWindowActivity"))
                val measured = text(client, CORNER_LOCATION)!!.removePrefix("Corner at ")
                val snapshot = client.execute(Commands.snapshot(CORNER)).snapshot.bounds
                val reported = "${snapshot.left},${snapshot.top},${snapshot.right},${snapshot.bottom}"
                report("inset-window", serial, "corner_measured" to measured, "corner_reported" to reported)
                check(reported == measured) { "The corner's snapshot bounds $reported differ from where the app has it, $measured" }
                val (l, t, r, b) = measured.split(',')
                check("bounds=\"[$l,$t][$r,$b]\"" in client.execute(Commands.dumpHierarchy()).text) {
                    "The hierarchy dump does not give the corner its measured bounds [$l,$t][$r,$b]"
                }
                check(client.send(Commands.tap(CORNER)).ok) { "Tap on the dialog's corner failed" }
                // The dialog hides the activity's window from accessibility: close it to read the count.
                device.shell("input", "keyevent", "KEYCODE_BACK")
                check(client.send(Commands.waitGone(CORNER), timeoutMs = 10_000).ok) { "The dialog did not close" }
                awaitText(client, STATUS, "Taps: beside=1 halfUnder=0 cover=0 corner=1")
            }
        }

    /** The window manager's frames and surface insets of the windows titled [title], as evidence. */
    private suspend fun windowFrames(
        device: DeviceHarness,
        title: String,
    ): String =
        device.shell("dumpsys", "window", "windows")
            .lines()
            .map(String::trim)
            .filter { line -> (line.startsWith("Window #") && title in line) || line.startsWith("mFrame=") || line.startsWith("frame=") || "surfaceInsets" in line }
            .joinToString(" | ") { it.take(160) }
            .replace(' ', '_')

    /** Polls [selector]'s text until it is [expected] (any text, for null); a failed read counts as not yet. */
    private suspend fun awaitText(
        client: DriverClient,
        selector: Selector,
        expected: String?,
    ) {
        var last: String? = null
        val reached =
            withTimeoutOrNull(10_000) {
                while (true) {
                    last = text(client, selector)
                    if (if (expected == null) !last.isNullOrEmpty() else last == expected) break
                    delay(100)
                }
            }
        checkNotNull(reached) { "Expected \"${expected ?: "any text"}\", last read \"$last\"" }
    }

    private suspend fun text(
        client: DriverClient,
        selector: Selector,
    ): String? = client.send(Commands.snapshot(selector)).takeIf { it.ok }?.result?.snapshot?.text

    private companion object {
        val STATUS = Selectors.androidResource(FIXTURE_PACKAGE, "inset_status")
        val SHOW_DIALOG = Selectors.androidResource(FIXTURE_PACKAGE, "inset_show_dialog")
        val SHOW_COVER = Selectors.androidResource(FIXTURE_PACKAGE, "inset_show_cover")
        val BESIDE = Selectors.androidResource(FIXTURE_PACKAGE, "inset_beside")
        val HALF_UNDER = Selectors.androidResource(FIXTURE_PACKAGE, "inset_half_under")
        val CORNER = Selectors.androidResource(FIXTURE_PACKAGE, "inset_corner")
        val CORNER_LOCATION = Selectors.androidResource(FIXTURE_PACKAGE, "inset_corner_location")
    }
}
