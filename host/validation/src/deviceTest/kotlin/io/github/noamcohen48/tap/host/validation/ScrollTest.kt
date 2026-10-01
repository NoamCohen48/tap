package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.ok

/**
 * `SCROLL` is one gesture that reports `done`, whatever the list's state: repeated scrolls reach
 * a far item in both a Compose `LazyColumn` and a View `RecyclerView` (the loop the client
 * `scrollUntil` helpers run), a scroll at the end of the list is not an error, `SWIPE` reports
 * `done`, and an out-of-range scroll distance is refused on the host.
 */
@DeviceTest
class ScrollTest {
    @OnEachDevice
    fun `compose list scrolls to a far item`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.openFixtureMain(client)
                val composeList = Selectors.resource("composeList")
                check(scrollUntilExists(client, composeList, Selectors.resource("item-100"), maxScrolls = 30)) {
                    "Compose list never showed item-100"
                }
            }
        }

    @OnEachDevice
    fun `view list scrolls to its end, and scroll and swipe report done`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ViewListActivity")
                val viewList = Selectors.androidResource(FIXTURE_PACKAGE, "view_list")
                check(client.send(Commands.waitVisible(Selectors.text("View item 1")), timeoutMs = 10_000).ok)
                check(scrollUntilExists(client, viewList, Selectors.text("View item 100"), maxScrolls = 50)) {
                    "View list never showed View item 100"
                }
                // At the end the gesture still runs and reports done; nothing is inferred about movement.
                val scrollAtEnd = client.send(Commands.scroll(viewList, Direction.DIR_DOWN))
                check(scrollAtEnd.ok && scrollAtEnd.result.hasDone()) { "Scroll at the end should report done: $scrollAtEnd" }
                val scrollBack = client.send(Commands.scroll(viewList, Direction.DIR_UP))
                check(scrollBack.ok && scrollBack.result.hasDone()) { "Scroll up failed: $scrollBack" }
                val swipe = client.send(Commands.swipe(viewList, Direction.DIR_DOWN))
                check(swipe.ok && swipe.result.hasDone()) { "Swipe failed: $swipe" }
                // An out-of-range distance is unrepresentable on the host.
                check(rejectedByHost(client, Commands.scroll(viewList, Direction.DIR_DOWN, distancePercent = 0))) {
                    "SCROLL with an out-of-range distance must be rejected"
                }
            }
        }

    /** The client `scrollUntil` loop, over raw commands: exists inside the container, else scroll once. */
    private suspend fun scrollUntilExists(
        client: DriverClient,
        container: Selector,
        target: Selector,
        maxScrolls: Int,
    ): Boolean {
        val inContainer = Selectors.of(target.node and Nodes.ancestor(container.node))
        repeat(maxScrolls + 1) { scrolls ->
            val exists = client.send(Commands.exists(inContainer))
            check(exists.ok) { "exists failed: $exists" }
            if (exists.result.bool) return true
            if (scrolls < maxScrolls) {
                val scroll = client.send(Commands.scroll(container, Direction.DIR_DOWN))
                check(scroll.ok) { "scroll failed: $scroll" }
            }
        }
        return false
    }
}
