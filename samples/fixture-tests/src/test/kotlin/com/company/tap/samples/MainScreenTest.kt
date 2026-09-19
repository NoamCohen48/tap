package com.company.tap.samples

import com.company.tap.api.v1.Direction
import com.company.tap.api.v1.ErrorCode
import com.company.tap.junit5.TapTest
import com.company.tap.sdk.res
import com.company.tap.sdk.CommandException
import com.company.tap.sdk.Device
import com.company.tap.sdk.WaitTimeoutException
import com.company.tap.sdk.rawRes
import com.company.tap.sdk.text
import com.company.tap.sdk.textMatches
import com.company.tap.sdk.textStartsWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

@TapTest
class MainScreenTest {
    @Test
    fun tapsViewAndComposeButtons(device: Device) {
        Fixture.launch(device)

        device.element(res("view_button")).tap()
        device.await(text("View tapped")).visible()

        device.element(rawRes("composeButton")).tap()
        device.await(rawRes("composeStatus")).textEquals("Compose tapped")
    }

    @Test
    fun typesAndClearsText(device: Device) {
        Fixture.launch(device)
        val input = device.element(res("view_input"))
        val keyboard = device.element(res("keyboard_input"))

        input.setText("hello tap")
        assertEquals("hello tap", input.text())

        keyboard.typeText("abc")
        device.await(text("Keyboard event received")).visible()
        assertEquals("abc", keyboard.text())

        keyboard.clearText()
        assertEquals("", keyboard.text().orEmpty())
    }

    @Test
    fun scrollsComposeListUntilItemIsVisible(device: Device) {
        Fixture.launch(device)

        val list = device.element(rawRes("composeList"))
        // Up to 30 gestures in one command: give it more than the 10 s action default (slow CI emulators).
        val item = list.scrollUntil(rawRes("item-40"), maxScrolls = 30, timeout = 30.seconds)
        assertTrue(item.exists())
        assertEquals("Item 40", item.text())

        // Back up: UiAutomator scroll direction names the content edge you move towards.
        val first = list.scrollUntil(rawRes("item-1"), direction = Direction.DIR_UP, maxScrolls = 30, timeout = 30.seconds)
        assertEquals("Item 1", first.text())
    }

    @Test
    fun ambiguousTapFailsBeforeAnyInput(device: Device) {
        Fixture.launch(device)

        // Material buttons expose their all-caps rendering as accessibility text.
        val ambiguous = textMatches("(?i)ambiguous tap")
        val failure = assertFailsWith<CommandException> {
            device.element(ambiguous).tap()
        }
        assertEquals(ErrorCode.ERR_AMBIGUOUS, failure.code)
        assertEquals(2, device.element(ambiguous).count())
        assertEquals("Ambiguous taps: left=0 right=0", device.element(res("ambiguous_status")).text())

        // Disambiguate by resource id (or by relation/index) instead of relaxing the invariant.
        device.element(res("ambiguous_button_right").andText("AMBIGUOUS TAP")).tap()
        device.await(text("Ambiguous taps: left=0 right=1")).visible()
    }

    @Test
    fun waitsForAppOwnedSynchronization(device: Device) {
        val app = Fixture.launch(device)

        device.element(res("sync_button")).tap()
        device.await(text("Synchronized work running")).visible()
        app.awaitIdle(timeout = 15.seconds)
        assertEquals("Synchronized work complete", device.element(res("view_status")).text())
    }

    @Test
    fun waitTimeoutIsDiagnosable(device: Device) {
        Fixture.launch(device)

        val timeout = assertFailsWith<WaitTimeoutException> {
            device.await(textStartsWith("Never rendered"), timeout = 1.seconds).visible()
        }
        assertTrue(timeout.message!!.contains(device.serial))
    }

    @Test
    fun backAndHomeKeys(device: Device) {
        Fixture.launch(device, ".ViewListActivity")
        device.await(text("View item 1")).visible()

        device.pressBack()
        device.await(text("View item 1")).gone()
    }
}
