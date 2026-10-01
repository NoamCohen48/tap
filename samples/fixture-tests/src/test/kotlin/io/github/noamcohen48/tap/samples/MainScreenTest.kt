package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.CommandException
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.Direction
import io.github.noamcohen48.tap.sdk.ErrorCode
import io.github.noamcohen48.tap.sdk.ExperimentalTapApi
import io.github.noamcohen48.tap.sdk.WaitReason
import io.github.noamcohen48.tap.sdk.WaitTimeoutException
import io.github.noamcohen48.tap.sdk.res
import io.github.noamcohen48.tap.sdk.text
import io.github.noamcohen48.tap.sdk.textMatches
import io.github.noamcohen48.tap.sdk.textStartsWith
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTapApi::class) // awaitIdle
@TapTest
class MainScreenTest {
    @Test
    fun tapsViewAndComposeButtons(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)

            app.element(res("view_button")).tap()
            app.await(text("View tapped")).visible()

            app.element(res("composeButton")).tap()
            app.await(res("composeStatus")).textEquals("Compose tapped")
        }
    }

    @Test
    fun typesAndClearsText(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            val input = app.element(res("view_input"))
            val keyboard = app.element(res("keyboard_input"))

            input.setText("hello tap")
            assertEquals("hello tap", input.text())

            keyboard.typeText("abc")
            app.await(text("Keyboard event received")).visible()
            assertEquals("abc", keyboard.text())

            keyboard.clearText()
            // Android reports an empty field's hint as its text; the snapshot says so.
            assertTrue(keyboard.snapshot().showingHint)
        }
    }

    @Test
    fun scrollsComposeListUntilItemIsVisible(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)

            val list = app.element(res("composeList"))
            // Up to 30 scrolls: a budget above the 10 s wait default (slow CI emulators).
            val item = list.scrollUntil(res("item-40"), maxScrolls = 30, timeout = 30.seconds)
            assertTrue(item.exists())
            assertEquals("Item 40", item.text())

            // Back up: UiAutomator scroll direction names the content edge you move towards.
            val first = list.scrollUntil(res("item-1"), direction = Direction.UP, maxScrolls = 30, timeout = 30.seconds)
            assertEquals("Item 1", first.text())
        }
    }

    @Test
    fun ambiguousTapFailsBeforeAnyInput(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)

            // Material buttons expose their all-caps rendering as accessibility text.
            val ambiguous = textMatches("(?i)ambiguous tap")
            val failure =
                assertFailsSuspend<CommandException> {
                    app.element(ambiguous).tap()
                }
            assertEquals(ErrorCode.AMBIGUOUS, failure.code)
            assertEquals(2, app.element(ambiguous).count())
            assertEquals("Ambiguous taps: left=0 right=0", app.element(res("ambiguous_status")).text())

            // A disjunction is still one selector: both buttons match, so it is just as AMBIGUOUS.
            val either = res("ambiguous_button_left") or res("ambiguous_button_right")
            assertEquals(2, app.element(either).count())
            assertEquals(ErrorCode.AMBIGUOUS, assertFailsSuspend<CommandException> { app.element(either).tap() }.code)

            // Disambiguate by resource id (or by relation/index) instead of relaxing the invariant.
            app.element(res("ambiguous_button_right").andText("AMBIGUOUS TAP")).tap()
            app.await(text("Ambiguous taps: left=0 right=1")).visible()
            app.element((res("no_such_button") or res("ambiguous_button_left")) and text("AMBIGUOUS TAP")).tap()
            app.await(text("Ambiguous taps: left=1 right=1")).visible()
        }
    }

    @Test
    fun waitsForAppOwnedSynchronization(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)

            app.element(res("sync_button")).tap()
            app.await(text("Synchronized work running")).visible()
            app.awaitIdle(timeout = 15.seconds)
            assertEquals("Synchronized work complete", app.element(res("view_status")).text())
        }
    }

    @Test
    fun waitTimeoutIsDiagnosable(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)

            val timeout =
                assertFailsSuspend<WaitTimeoutException> {
                    app.await(textStartsWith("Never rendered"), timeout = 1.seconds).visible()
                }
            assertTrue(timeout.message!!.contains(device.serial))
            assertEquals(WaitReason.NO_MATCH to 0, timeout.reason to timeout.matchCount)

            // visible() is "one or more"; one() is the explicit exactly-one wait and says how many matched.
            val ambiguous = textMatches("(?i)ambiguous tap")
            app.await(ambiguous).visible()
            val notOne = assertFailsSuspend<WaitTimeoutException> { app.await(ambiguous, timeout = 1.seconds).one() }
            assertEquals(WaitReason.AMBIGUOUS to 2, notOne.reason to notOne.matchCount)
            val present = assertFailsSuspend<WaitTimeoutException> { app.await(ambiguous, timeout = 1.seconds).gone() }
            assertEquals(WaitReason.STILL_PRESENT to 2, present.reason to present.matchCount)
            app.await(res("ambiguous_button_left")).one().tap()
        }
    }

    @Test
    fun backAndHomeKeys(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device, ".ViewListActivity")
            app.await(text("View item 1")).visible()

            device.pressBack()
            app.await(text("View item 1")).gone()
        }
    }
}
