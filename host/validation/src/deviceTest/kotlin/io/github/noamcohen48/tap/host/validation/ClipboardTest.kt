package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok

/**
 * The device clipboard both ways while the app (not the driver) has focus: text the driver puts
 * there is what the app pastes, text the app copies is what the driver reads (API 29+ restricts
 * that read to the focused app, which the driver is not), and an empty clipboard reads as "".
 * Whether Android shows its "pasted from your clipboard" toast for the driver's read is reported.
 */
@DeviceTest
class ClipboardTest {
    @OnEachDevice
    fun `the driver writes what the app pastes and reads what the app copies`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("FormActivity")
                fun id(name: String) = Selectors.androidResource(FIXTURE_PACKAGE, name)
                check(client.send(Commands.waitVisible(id("paste_button")), timeoutMs = 10_000).ok) { "Form activity did not appear" }

                val set = client.send(Commands.setClipboard("tap clip 1"))
                check(set.ok) { "Set clipboard failed: $set" }
                check(client.send(Commands.tap(id("paste_button"))).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Pasted: tap clip 1")), timeoutMs = 5_000).ok) {
                    "The app did not paste the driver's text: ${client.execute(Commands.snapshot(id("clipboard_status"))).snapshot.text}"
                }

                check(client.send(Commands.setText(id("search_field"), "from the app")).ok)
                check(client.send(Commands.tap(id("copy_button"))).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Copied: from the app")), timeoutMs = 5_000).ok)
                val read = client.send(Commands.getClipboard())
                check(read.ok && read.result.text == "from the app") { "The driver did not read the app's copy: $read" }
                val notice = client.send(Commands.awaitToast("clipboard", MatchMode.MATCH_CONTAINS), timeoutMs = 1_500)

                check(client.send(Commands.setClipboard("")).ok)
                val empty = client.send(Commands.getClipboard())
                check(empty.ok && empty.result.text == "") { "An empty clipboard should read as \"\": $empty" }
                report(
                    "clipboard",
                    serial,
                    "write" to "ok",
                    "read" to "ok",
                    "read_notice" to if (notice.ok) "'${notice.result.toast.text}'" else "none",
                )
            }
        }
}
