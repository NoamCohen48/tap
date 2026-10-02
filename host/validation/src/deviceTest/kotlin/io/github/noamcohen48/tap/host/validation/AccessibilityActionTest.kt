package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.StandardAction
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok

/**
 * Named accessibility actions as a screen reader runs them, on the fixture's ControlsActivity:
 * a standard action the node offers (expand), the app's custom action (Archive), an action it
 * does not offer refused before input (`ACTION_NOT_OFFERED`), and `set_progress` inside and
 * outside the slider's range (`OUT_OF_RANGE`).
 */
@DeviceTest
class AccessibilityActionTest {
    @OnEachDevice
    fun `standard and custom actions and slider progress reach the app`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ControlsActivity")
                fun id(name: String) = Selectors.androidResource(FIXTURE_PACKAGE, name)
                check(client.send(Commands.waitVisible(id("details_header")), timeoutMs = 10_000).ok) { "Controls activity did not appear" }

                val header = client.execute(Commands.snapshot(id("details_header"))).snapshot
                check(StandardAction.A11Y_EXPAND in header.actionsList) { "The header does not offer expand: ${header.actionsList}" }
                check(client.send(Commands.performAccessibilityAction(id("details_header"), StandardAction.A11Y_EXPAND)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Details (expanded)")), timeoutMs = 5_000).ok) { "Expand did not reach the app" }
                val dismiss = client.send(Commands.performAccessibilityAction(id("details_header"), StandardAction.A11Y_DISMISS))
                check(!dismiss.ok && dismiss.result.error.detail == ErrorDetail.ACTION_NOT_OFFERED) { "Dismiss should be ACTION_NOT_OFFERED: $dismiss" }

                val card = client.execute(Commands.snapshot(id("message_card"))).snapshot
                check(card.customActionsList.containsAll(listOf("Archive", "Mark unread"))) { "Custom actions: ${card.customActionsList}" }
                check(client.send(Commands.performCustomAction(id("message_card"), "Archive")).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Archived")), timeoutMs = 5_000).ok) { "Archive did not reach the app" }

                val slider = client.execute(Commands.snapshot(id("volume_slider"))).snapshot
                check(slider.hasRange() && slider.range.max == 100f) { "The slider's range: ${slider.range}" }
                check(client.send(Commands.setProgress(id("volume_slider"), 55f)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Volume: 55")), timeoutMs = 5_000).ok) { "Progress did not reach the app" }
                val outside = client.send(Commands.setProgress(id("volume_slider"), 150f))
                check(!outside.ok && outside.result.error.detail == ErrorDetail.OUT_OF_RANGE) { "150 should be OUT_OF_RANGE: $outside" }
                report("accessibility_actions", serial, "standard" to "ok", "custom" to "ok", "progress" to "ok", "range_type" to slider.range.type)
            }
        }
}
