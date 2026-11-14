package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Orientation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.wire.v1.Response
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `tap_outside_panel` on real panels, naming each by a node inside it:
 *
 * - window panels close: a dialog, a popup menu over that dialog (the dialog stays), a popup
 *   menu, a Compose `AlertDialog`;
 * - in-window panels found by their dismiss action or pane title close: a Views navigation
 *   drawer (its point is over content the drawer hides from accessibility), a fully expanded
 *   Views bottom sheet that covers its scrim's centre (a plain tap on the scrim would press a
 *   row), a Compose navigation drawer and bottom sheet;
 * - in landscape, a dialog and a drawer on the end side close;
 * - refused before any input: a popup that lets touches through (`TOUCH_REACHES_BELOW`; with the
 *   opt-in its touch is sent, closes it and reaches the button under it), a bottom sheet that
 *   cannot be cancelled (no dismiss action, full-screen window: `NO_OUTSIDE_POINT`) and a node on
 *   a plain screen (`NO_OUTSIDE_POINT`).
 *
 * Each screen counts every click a stray touch could cause, including on a control that fills the
 * screen under every panel, and lists the panels as they close; the test reads it after each case.
 */
@DeviceTest
class OutsidePanelTest {
    @OnEachDevice
    fun `a tap outside a Views panel closes it and touches nothing else`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("OutsidePanelActivity")
                check(client.send(Commands.waitVisible(Selectors.text("Open dialog")), timeoutMs = 10_000).ok) { "The outside-panel screen did not appear" }
                val closed = mutableListOf<String>()
                suspend fun expect(under: Int = 0) =
                    awaitText(client, STATUS, "Outside: under=$under rows=0 menu=0 closed=${closed.joinToString(",")}")

                closeFromInside(client, opener = "Open dialog", inside = "Dialog title", panel = "window")
                closed += "dialog"
                expect()

                // A popup menu over a dialog: the touch closes the menu only; then the dialog.
                open(client, "Open dialog", "Dialog title")
                closeFromInside(client, opener = "Open nested menu", inside = "Nested two", panel = "window")
                check(client.send(Commands.waitVisible(Selectors.text("Dialog title")), timeoutMs = 10_000).ok) { "The dialog under the menu closed too" }
                closeOpen(client, inside = "Dialog title", panel = "window")
                closed += listOf("nested", "dialog")
                expect()

                closeFromInside(client, opener = "Open menu", inside = "Menu two", panel = "window")
                closed += "menu"
                expect()
                closeFromInside(client, opener = "Open sheet", inside = "Sheet row 2", panel = "dismissible")
                closed += "sheet"
                expect()
                closeFromInside(client, opener = "Open drawer", inside = "Drawer body", panel = "dismissible")
                closed += "drawer"
                expect()

                // A sheet that cannot be cancelled offers no dismiss action and fills its window.
                open(client, "Open fixed sheet", "Fixed sheet body")
                refused(client.send(Commands.tapOutsidePanel(Selectors.text("Fixed sheet body"))), ErrorDetail.NO_OUTSIDE_POINT, "a sheet that cannot be cancelled")
                check(client.execute(Commands.exists(Selectors.text("Fixed sheet body"))).bool) { "The refused touch closed the fixed sheet" }
                check(client.send(Commands.tap(Selectors.text("Close fixed sheet"))).ok)
                check(client.send(Commands.waitGone(Selectors.text("Fixed sheet body")), timeoutMs = 10_000).ok) { "The fixed sheet did not close" }
                closed += "fixed sheet"
                expect()

                // A popup that passes touches through: refused, then sent with the opt-in, when the
                // touch also reaches the button under it.
                open(client, "Open pass-through", "Pass-through body")
                refused(client.send(Commands.tapOutsidePanel(Selectors.text("Pass-through body"))), ErrorDetail.TOUCH_REACHES_BELOW, "a pass-through popup")
                check(client.execute(Commands.exists(Selectors.text("Pass-through body"))).bool) { "The refused touch closed the popup" }
                expect()
                val allowed = client.send(Commands.tapOutsidePanel(Selectors.text("Pass-through body"), allowReachBelow = true))
                check(allowed.ok) { "The allowed touch below the pass-through popup failed: $allowed" }
                report("outside-panel", serial, "pass_through" to allowed.result.outsideTouch.panel.replace(' ', '_'))
                check(client.send(Commands.waitGone(Selectors.text("Pass-through body")), timeoutMs = 10_000).ok) { "The pass-through popup did not close" }
                closed += "pass-through"
                expect(under = 1)

                // A plain screen has no panel around its nodes.
                refused(client.send(Commands.tapOutsidePanel(Selectors.text("Under"))), ErrorDetail.NO_OUTSIDE_POINT, "a plain screen's node")
                delay(500)
                expect(under = 1)
            }
        }

    @OnEachDevice
    fun `in landscape a tap outside a dialog or an end drawer closes it`(serial: String) =
        // The session puts the rotation back when it ends, as for any orientation command.
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                // The launcher stays in portrait: rotate with the fixture in front.
                device.launchFixture("OutsidePanelActivity")
                check(client.send(Commands.waitVisible(Selectors.text("Open dialog")), timeoutMs = 10_000).ok) { "The outside-panel screen did not appear" }
                check(client.send(Commands.setOrientation(Orientation.ORIENTATION_LANDSCAPE)).ok) { "Landscape failed" }
                val info = client.execute(Commands.deviceInfo()).deviceInfo
                check(info.displayWidth > info.displayHeight) { "Landscape was not observed: $info" }
                // The activity is recreated in landscape.
                check(client.send(Commands.waitVisible(Selectors.text("Open dialog")), timeoutMs = 10_000).ok) { "The screen did not come back in landscape" }
                check(client.send(Commands.waitScreenStable(FIXTURE_PACKAGE), timeoutMs = 10_000).ok) { "The screen did not settle in landscape" }
                closeFromInside(client, opener = "Open dialog", inside = "Dialog title", panel = "window")
                // A drawer on the end side: its free space is to its left.
                closeFromInside(client, opener = "Open end drawer", inside = "End drawer body", panel = "dismissible")
                awaitText(client, STATUS, "Outside: under=0 rows=0 menu=0 closed=dialog,end drawer")
            }
        }

    @OnEachDevice
    fun `a tap outside a Compose drawer, sheet or dialog closes it`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ComposeOutsidePanelActivity")
                check(client.send(Commands.waitVisible(Selectors.text("Open compose drawer")), timeoutMs = 10_000).ok) { "The Compose outside-panel screen did not appear" }

                closeFromInside(client, opener = "Open compose drawer", inside = "Compose drawer body", panel = "pane")
                awaitText(client, COMPOSE_STATUS, "Compose: under=0 rows=0 closed=drawer")
                closeFromInside(client, opener = "Open compose sheet", inside = "Compose sheet body", panel = "pane")
                awaitText(client, COMPOSE_STATUS, "Compose: under=0 rows=0 closed=drawer,sheet")
                // Its pane-titled surface is the whole window: the window rule.
                closeFromInside(client, opener = "Open compose dialog", inside = "Compose dialog title", panel = "window")
                awaitText(client, COMPOSE_STATUS, "Compose: under=0 rows=0 closed=drawer,sheet,dialog")
            }
        }

    /** Taps [opener] and waits until [inside] shows and the screen stops moving (open animations). */
    private suspend fun open(
        client: DriverClient,
        opener: String,
        inside: String,
    ) {
        check(client.send(Commands.tap(Selectors.text(opener))).ok) { "\"$opener\" failed" }
        check(client.send(Commands.waitVisible(Selectors.text(inside)), timeoutMs = 10_000).ok) { "\"$inside\" did not appear" }
        val stable = client.send(Commands.waitScreenStable(FIXTURE_PACKAGE), timeoutMs = 10_000)
        check(stable.ok) { "The screen did not stop moving after \"$opener\": $stable" }
    }

    /** Opens a panel, taps outside it naming [inside], checks how the panel was found, waits for it to close. */
    private suspend fun closeFromInside(
        client: DriverClient,
        opener: String,
        inside: String,
        panel: String,
    ) {
        open(client, opener, inside)
        closeOpen(client, inside, panel)
    }

    private suspend fun closeOpen(
        client: DriverClient,
        inside: String,
        panel: String,
    ) {
        val response = client.send(Commands.tapOutsidePanel(Selectors.text(inside)))
        check(response.ok) { "The tap outside the panel of \"$inside\" failed: $response" }
        val touch = response.result.outsideTouch
        check(touch.panel.startsWith(panel)) { "\"$inside\"'s panel was found as ${touch.panel}, not by its $panel" }
        check(client.send(Commands.waitGone(Selectors.text(inside)), timeoutMs = 10_000).ok) {
            "The panel of \"$inside\" did not close after a touch at (${touch.x}, ${touch.y}) outside ${touch.panel}"
        }
    }

    private fun refused(
        response: Response,
        detail: String,
        what: String,
    ) {
        check(!response.ok && response.errorCode == ErrorCode.ERR_NOT_INTERACTABLE && response.detail == detail) {
            "A touch outside $what was not refused as $detail: $response"
        }
    }

    /** Polls [selector]'s text until it is [expected]; a failed read counts as not yet. */
    private suspend fun awaitText(
        client: DriverClient,
        selector: Selector,
        expected: String,
    ) {
        var last: String? = null
        val reached =
            withTimeoutOrNull(10_000) {
                while (true) {
                    last = client.send(Commands.snapshot(selector)).takeIf { it.ok }?.result?.snapshot?.text
                    if (last == expected) break
                    delay(100)
                }
            }
        checkNotNull(reached) { "Expected \"$expected\", last read \"$last\"" }
    }

    private companion object {
        val STATUS = Selectors.androidResource(FIXTURE_PACKAGE, "outside_status")
        val COMPOSE_STATUS = Selectors.resource("compose_outside_status")
    }
}
