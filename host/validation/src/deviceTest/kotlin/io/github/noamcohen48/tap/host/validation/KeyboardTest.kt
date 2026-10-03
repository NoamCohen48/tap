package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay

/**
 * The soft keyboard: `device_info.keyboard_shown` follows the input-method window, hiding it
 * with no keyboard up sends nothing (the app stays where it is), and with one up hides it. The
 * keyboard action runs the field's own editor action (the app sees `IME_ACTION_SEARCH`, not
 * Enter) on API 30+, is refused before input below, and is refused before input by a node that
 * does not offer it: a field without input focus, or a button.
 */
@DeviceTest
class KeyboardTest {
    @OnEachDevice
    fun `keyboard state, hide and the keyboard action`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("FormActivity")
                fun id(name: String) = Selectors.androidResource(FIXTURE_PACKAGE, name)
                val field = id("search_field")
                check(client.send(Commands.waitVisible(field), timeoutMs = 10_000).ok) { "Form activity did not appear" }
                check(!awaitKeyboard(client, shown = false)) { "No keyboard should show before the field is focused" }

                val idle = client.send(Commands.hideKeyboard())
                check(idle.ok) { "Hiding no keyboard failed: $idle" }
                check(client.send(Commands.waitVisible(field), timeoutMs = 2_000).ok) { "Hiding no keyboard must not press Back" }

                if (device.apiLevel >= 30) {
                    val unfocused = client.send(Commands.performImeAction(field))
                    check(!unfocused.ok && unfocused.errorCode == ErrorCode.ERR_ACTION_REJECTED) {
                        "A field without input focus does not offer the keyboard action: ok=${unfocused.ok} ${unfocused.errorCode}"
                    }
                }
                check(client.send(Commands.tap(field)).ok)
                check(awaitKeyboard(client, shown = true)) { "The keyboard did not show after tapping the field" }
                check(client.send(Commands.setText(field, "shoes")).ok)

                val search = client.send(Commands.performImeAction(field))
                if (device.apiLevel >= 30) {
                    check(search.ok) { "Keyboard action failed: $search" }
                    check(client.send(Commands.waitVisible(Selectors.text("Searched: shoes")), timeoutMs = 5_000).ok) {
                        "The app did not get IME_ACTION_SEARCH: ${client.execute(Commands.snapshot(id("search_status"))).snapshot.text}"
                    }
                    val notAField = client.send(Commands.performImeAction(id("copy_button")))
                    check(!notAField.ok && notAField.errorCode == ErrorCode.ERR_ACTION_REJECTED) {
                        "A button should refuse the keyboard action: ok=${notAField.ok} ${notAField.errorCode} ${notAField.detail}"
                    }
                } else {
                    check(!search.ok && search.errorCode == ErrorCode.ERR_UNSUPPORTED && search.detail == ErrorDetail.REQUIRES_API_30) {
                        "Below API 30 the keyboard action should be UNSUPPORTED / REQUIRES_API_30: $search"
                    }
                    check(client.execute(Commands.snapshot(id("search_status"))).snapshot.text == "Not searched") {
                        "A refused keyboard action must not reach the app"
                    }
                }

                val hide = client.send(Commands.hideKeyboard())
                check(hide.ok) { "Hide keyboard failed: $hide" }
                check(!awaitKeyboard(client, shown = false)) { "The keyboard is still showing after hide" }
                check(client.send(Commands.waitVisible(field), timeoutMs = 2_000).ok) { "Back went past the keyboard to the app" }
                report("keyboard", serial, "hide" to "ok", "ime_action" to if (device.apiLevel >= 30) "ok" else "unsupported")
            }
        }

    /** Polls `keyboard_shown` for up to 5 s until it is [shown]; returns the last value. */
    private suspend fun awaitKeyboard(
        client: DriverClient,
        shown: Boolean,
    ): Boolean {
        repeat(25) {
            val now = client.execute(Commands.deviceInfo()).deviceInfo.keyboardShown
            if (now == shown) return now
            delay(200)
        }
        return client.execute(Commands.deviceInfo()).deviceInfo.keyboardShown
    }
}
