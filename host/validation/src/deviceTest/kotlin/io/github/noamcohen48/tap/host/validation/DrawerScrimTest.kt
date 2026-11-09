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
import io.github.noamcohen48.tap.wire.v1.Response
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A cover inside the target's own window: an open `DrawerLayout` drawer. Its scrim is drawn by
 * the layout, so the window list shows nothing above the content, and UiAutomator still reports
 * the content under it, visible and with its own bounds. A touch there would close the drawer,
 * not press the button. The driver refuses a gesture on that content with `NOT_INTERACTABLE` /
 * `OBSCURED` before any input; the fixture's counters prove nothing was clicked and the drawer
 * stayed open. With the drawer closed the same taps go through, a view the app marks not
 * important for accessibility itself included, and the drawer's own content is reachable while
 * it is open. A Compose `ModalNavigationDrawer` leaves the content out of the tree while open, so
 * the same tap there is `NOT_FOUND`.
 */
@DeviceTest
class DrawerScrimTest {
    @OnEachDevice
    fun `a tap on content under an open drawer is refused as OBSCURED before any input`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("DrawerActivity")
                check(client.send(Commands.waitVisible(OPEN), timeoutMs = 10_000).ok) { "The drawer screen did not appear" }

                // Closed: a view the app marks not important is no reason to refuse.
                check(client.send(Commands.tap(DECORATIVE)).ok) { "Tap on the decorative view with the drawer closed failed" }
                awaitStatus(client, "Drawer: open=false under=0 decorative=1 item=0")

                check(client.send(Commands.tap(OPEN)).ok)
                awaitStatus(client, "Drawer: open=true under=0 decorative=1 item=0")
                requireObscured(client.send(Commands.tap(UNDER)))
                requireObscured(client.send(Commands.longTap(UNDER)))
                requireObscured(client.send(Commands.tap(DECORATIVE)))
                delay(500)
                check(status(client) == "Drawer: open=true under=0 decorative=1 item=0") { "A refused tap was delivered: ${status(client)}" }

                // The drawer itself takes touches.
                check(client.send(Commands.tap(ITEM)).ok) { "Tap on the drawer's own item failed" }
                awaitStatus(client, "Drawer: open=true under=0 decorative=1 item=1")

                check(client.send(Commands.tap(CLOSE)).ok)
                awaitStatus(client, "Drawer: open=false under=0 decorative=1 item=1")
                check(client.send(Commands.tap(UNDER)).ok) { "Tap on the content with the drawer closed failed" }
                awaitStatus(client, "Drawer: open=false under=1 decorative=1 item=1")
            }
        }

    @OnEachDevice
    fun `content under an open Compose drawer is not found`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ComposeDrawerActivity")
                check(client.send(Commands.waitVisible(COMPOSE_OPEN), timeoutMs = 10_000).ok) { "The Compose drawer screen did not appear" }
                check(client.send(Commands.tap(COMPOSE_OPEN)).ok)
                check(client.send(Commands.waitVisible(COMPOSE_ITEM), timeoutMs = 10_000).ok) { "The Compose drawer did not open" }
                check(client.send(Commands.waitGone(COMPOSE_UNDER), timeoutMs = 10_000).ok) { "The content stayed in the tree under the open drawer" }
                val tap = client.send(Commands.tap(COMPOSE_UNDER))
                check(tap.errorCode == ErrorCode.ERR_NOT_FOUND) { "Expected NOT_FOUND under the open Compose drawer: $tap" }
                check(client.send(Commands.tap(COMPOSE_CLOSE)).ok)
                check(client.send(Commands.waitVisible(COMPOSE_UNDER), timeoutMs = 10_000).ok) { "The content did not come back" }
                check(client.send(Commands.tap(COMPOSE_UNDER)).ok)
                awaitText(client, COMPOSE_STATUS, "Compose drawer: under=1")
            }
        }

    private fun requireObscured(response: Response) {
        check(!response.ok && response.errorCode == ErrorCode.ERR_NOT_INTERACTABLE && response.detail == ErrorDetail.OBSCURED) {
            "Expected NOT_INTERACTABLE / OBSCURED: $response"
        }
    }

    private suspend fun awaitStatus(
        client: DriverClient,
        expected: String,
    ) = awaitText(client, STATUS, expected)

    /** Polls [selector]'s text; a read that fails while the text is changing counts as not yet. */
    private suspend fun awaitText(
        client: DriverClient,
        selector: Selector,
        expected: String,
    ) {
        var last: String? = null
        val reached =
            withTimeoutOrNull(10_000) {
                while (true) {
                    last = text(client, selector)
                    if (last == expected) break
                    delay(100)
                }
            }
        checkNotNull(reached) { "Expected \"$expected\", last read \"$last\"" }
    }

    private suspend fun status(client: DriverClient): String? = text(client, STATUS)

    private suspend fun text(
        client: DriverClient,
        selector: Selector,
    ): String? = client.send(Commands.snapshot(selector)).takeIf { it.ok }?.result?.snapshot?.text

    private companion object {
        val OPEN = Selectors.androidResource(FIXTURE_PACKAGE, "drawer_open")
        val DECORATIVE = Selectors.androidResource(FIXTURE_PACKAGE, "drawer_decorative")
        val UNDER = Selectors.androidResource(FIXTURE_PACKAGE, "drawer_under")
        val ITEM = Selectors.androidResource(FIXTURE_PACKAGE, "drawer_item")
        val CLOSE = Selectors.androidResource(FIXTURE_PACKAGE, "drawer_close")
        val STATUS = Selectors.androidResource(FIXTURE_PACKAGE, "drawer_status")
        val COMPOSE_OPEN = Selectors.resource("composeDrawerOpen")
        val COMPOSE_ITEM = Selectors.resource("composeDrawerItem")
        val COMPOSE_UNDER = Selectors.resource("composeDrawerUnder")
        val COMPOSE_STATUS = Selectors.resource("composeDrawerStatus")
        val COMPOSE_CLOSE = Selectors.resource("composeDrawerClose")
    }
}
