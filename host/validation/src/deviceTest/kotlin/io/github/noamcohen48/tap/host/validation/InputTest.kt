package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

/**
 * Text input: `SET_TEXT`, key-event `TYPE_TEXT` (the field sees real key events), a refused
 * character the key map cannot type, and `CLEAR_TEXT` on a hinted field that reports its hint
 * as text once empty. The driver does not read the field back; like any test, this one asserts
 * the effect itself.
 */
@DeviceTest
class InputTest {
    @OnEachDevice
    fun `set, type and clear text reach the field`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.openFixtureMain(client)

                val input = Selectors.androidResource(FIXTURE_PACKAGE, "view_input")
                check(client.send(Commands.setText(input, "phase zero")).ok)
                check(client.send(Commands.waitVisible(Selectors.text("phase zero"))).ok)
                val keyboardInput = Selectors.androidResource(FIXTURE_PACKAGE, "keyboard_input")
                val typedInput = client.send(Commands.typeText(keyboardInput, "keys 42"), timeoutMs = 30_000)
                check(typedInput.ok) { "Keyboard input failed: $typedInput" }
                check(client.send(Commands.waitVisible(Selectors.text("keys 42"))).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Keyboard event received"))).ok)
                val unsupportedInput = client.send(Commands.typeText(keyboardInput, "emoji 😀"))
                check(
                    !unsupportedInput.ok && unsupportedInput.errorCode == ErrorCode.ERR_INVALID_REQUEST &&
                        unsupportedInput.detail == ErrorDetail.UNSUPPORTED_CHARACTERS,
                ) { "Untypeable characters were not refused: $unsupportedInput" }
                check(client.execute(Commands.exists(Selectors.text("keys 42"))).bool)
                // A hinted field reports its hint as `text` once empty; the snapshot must not show it.
                val clearedHinted = client.send(Commands.clearText(keyboardInput))
                check(clearedHinted.ok) { "CLEAR_TEXT on a hinted field failed: $clearedHinted" }
                val clearedSnapshot = client.execute(Commands.snapshot(keyboardInput)).snapshot
                check(clearedSnapshot.text.isNullOrEmpty() && clearedSnapshot.hint == "Keyboard input") {
                    "Cleared hinted field should snapshot as empty text with hint: $clearedSnapshot"
                }
                device.shell("input", "keyevent", "KEYCODE_BACK")
            }
        }
}
