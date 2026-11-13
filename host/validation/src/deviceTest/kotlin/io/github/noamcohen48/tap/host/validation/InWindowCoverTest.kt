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
 * A control drawn over the target's centre inside the same window: the window list shows nothing
 * above the target, and UiAutomator reports it visible with its own bounds, so a tap at its centre
 * would press the control on top. The driver refuses a tap and a long press there as
 * `NOT_INTERACTABLE` / `OBSCURED` before any input, for Views and Compose; the fixture's counters
 * prove nothing was clicked. The control on top is itself tappable, and a label that takes no
 * touches over another target's centre is no reason to refuse: that tap reaches its target.
 */
@DeviceTest
class InWindowCoverTest {
    @OnEachDevice
    fun `a tap on a Views target under another control is refused as OBSCURED`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("InWindowCoverActivity")
                check(client.send(Commands.waitVisible(TARGET), timeoutMs = 10_000).ok) { "The cover screen did not appear" }
                requireObscured(client.send(Commands.tap(TARGET)))
                requireObscured(client.send(Commands.longTap(TARGET)))
                delay(500)
                awaitText(client, STATUS, "Covers: target=0 cover=0 badged=0")
                check(client.send(Commands.tap(COVER)).ok) { "Tap on the control on top failed" }
                check(client.send(Commands.tap(BADGED)).ok) { "Tap on the target under a label failed" }
                awaitText(client, STATUS, "Covers: target=0 cover=1 badged=1")
            }
        }

    @OnEachDevice
    fun `a tap on a Compose target under another control is refused as OBSCURED`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ComposeInWindowCoverActivity")
                check(client.send(Commands.waitVisible(COMPOSE_TARGET), timeoutMs = 10_000).ok) { "The Compose cover screen did not appear" }
                requireObscured(client.send(Commands.tap(COMPOSE_TARGET)))
                delay(500)
                awaitText(client, COMPOSE_STATUS, "Compose covers: target=0 cover=0 badged=0")
                check(client.send(Commands.tap(COMPOSE_COVER)).ok) { "Tap on the Compose control on top failed" }
                check(client.send(Commands.tap(COMPOSE_BADGED)).ok) { "Tap on the Compose target under a label failed" }
                awaitText(client, COMPOSE_STATUS, "Compose covers: target=0 cover=1 badged=1")
            }
        }

    private fun requireObscured(response: Response) {
        check(!response.ok && response.errorCode == ErrorCode.ERR_NOT_INTERACTABLE && response.detail == ErrorDetail.OBSCURED) {
            "Expected NOT_INTERACTABLE / OBSCURED: $response"
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
        val STATUS = Selectors.androidResource(FIXTURE_PACKAGE, "cover_status")
        val TARGET = Selectors.androidResource(FIXTURE_PACKAGE, "cover_target")
        val COVER = Selectors.androidResource(FIXTURE_PACKAGE, "cover_button")
        val BADGED = Selectors.androidResource(FIXTURE_PACKAGE, "badge_target")
        val COMPOSE_STATUS = Selectors.resource("composeCoverStatus")
        val COMPOSE_TARGET = Selectors.resource("composeCoverTarget")
        val COMPOSE_COVER = Selectors.resource("composeCoverButton")
        val COMPOSE_BADGED = Selectors.resource("composeBadgeTarget")
    }
}
