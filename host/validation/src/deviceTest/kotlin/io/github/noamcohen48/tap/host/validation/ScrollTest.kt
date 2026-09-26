package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

/**
 * `SCROLL_UNTIL` reaches a far item and detects the end (`INDETERMINATE/END_REACHED`) in both
 * a Compose `LazyColumn` and a View `RecyclerView`; `SCROLL` and `SWIPE` report movement; an
 * out-of-range scroll distance is refused on the host.
 */
@DeviceTest
class ScrollTest {
    @OnEachDevice
    fun `compose list scrolls to a far item and detects its end`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.openFixtureMain(client)
                val composeList = Selectors.rawResource("composeList")
                val composeScroll =
                    client.send(Commands.scrollUntil(Selectors.rawResource("item-100"), container = composeList, maxScrolls = 30), timeoutMs = 45_000)
                check(composeScroll.ok) { "Compose scroll failed: $composeScroll" }
                val composeEnd =
                    client.send(
                        Commands.scrollUntil(Selectors.rawResource("missing-compose-item"), container = composeList, maxScrolls = 5),
                        timeoutMs = 30_000,
                    )
                check(!composeEnd.ok && composeEnd.errorCode == ErrorCode.ERR_INDETERMINATE && composeEnd.detail == ErrorDetail.END_REACHED) {
                    "Compose end detection failed: $composeEnd"
                }
            }
        }

    @OnEachDevice
    fun `view list scrolls, detects its end, and reports scroll and swipe movement`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ViewListActivity")
                val viewList = Selectors.androidResource(FIXTURE_PACKAGE, "view_list")
                check(client.send(Commands.waitVisible(Selectors.text("View item 1")), timeoutMs = 10_000).ok)
                val viewScroll =
                    client.send(Commands.scrollUntil(Selectors.text("View item 100"), container = viewList, maxScrolls = 50), timeoutMs = 45_000)
                check(viewScroll.ok) { "View scroll failed: $viewScroll" }
                val viewEnd =
                    client.send(Commands.scrollUntil(Selectors.text("Missing View item"), container = viewList, maxScrolls = 10), timeoutMs = 45_000)
                check(!viewEnd.ok && viewEnd.errorCode == ErrorCode.ERR_INDETERMINATE && viewEnd.detail == ErrorDetail.END_REACHED) {
                    "View end detection failed: $viewEnd"
                }
                val scrollAtEnd = client.execute(Commands.scroll(viewList, Direction.DIR_DOWN))
                check(!scrollAtEnd.moved) { "Scroll at end should report no movement: $scrollAtEnd" }
                val scrollBack = client.execute(Commands.scroll(viewList, Direction.DIR_UP))
                check(scrollBack.moved) { "Scroll up should move: $scrollBack" }
                val swipe = client.execute(Commands.swipe(viewList, Direction.DIR_DOWN))
                check(swipe.moved) { "Swipe failed: $swipe" }
                // An out-of-range distance is unrepresentable on the host.
                check(rejectedByHost(client, Commands.scroll(viewList, Direction.DIR_DOWN, distancePercent = 0))) {
                    "SCROLL with an out-of-range distance must be rejected"
                }
            }
        }
}
