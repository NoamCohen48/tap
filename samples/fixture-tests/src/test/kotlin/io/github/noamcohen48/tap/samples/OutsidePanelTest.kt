package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.App
import io.github.noamcohen48.tap.sdk.CommandException
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.ErrorCode
import io.github.noamcohen48.tap.sdk.res
import io.github.noamcohen48.tap.sdk.text
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `Element.tapOutsidePanel()` through the server: a dialog (its window) and a drawer (its dismiss
 * action) close with no stray click on the screen under them, and a pass-through popup is
 * refused until the test opts in. The fixture's status line counts the stray clicks.
 */
@TapTest
class OutsidePanelTest {
    @Test
    fun aTapOutsideClosesTheDialogOrDrawerAndTouchesNothingElse(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device, ".OutsidePanelActivity")
            open(app, "Open dialog", "Dialog title")
            app.element(text("Dialog title")).tapOutsidePanel()
            app.await(text("Dialog title")).gone()

            open(app, "Open drawer", "Drawer body")
            app.element(text("Drawer body")).tapOutsidePanel()
            app.await(text("Drawer body")).gone()
            app.await(text("Outside: under=0 rows=0 menu=0 closed=dialog,drawer")).visible()
        }
    }

    @Test
    fun aPopupThatPassesTouchesThroughNeedsTheOptIn(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device, ".OutsidePanelActivity")
            val plain = assertFailsSuspend<CommandException> { app.element(text("Under")).tapOutsidePanel() }
            assertEquals(ErrorCode.NOT_INTERACTABLE to "NO_OUTSIDE_POINT", plain.code to plain.detail)

            open(app, "Open pass-through", "Pass-through body")
            val popup = app.element(text("Pass-through body"))
            val refused = assertFailsSuspend<CommandException> { popup.tapOutsidePanel() }
            assertEquals(ErrorCode.NOT_INTERACTABLE to "TOUCH_REACHES_BELOW", refused.code to refused.detail)
            popup.tapOutsidePanel(allowReachBelow = true)
            app.await(text("Pass-through body")).gone()
            // The opted-in touch reached the button under the popup, as it warned.
            app.await(text("Outside: under=1 rows=0 menu=0 closed=pass-through")).visible()
        }
    }

    /** Opens a panel and waits until it has stopped moving: the point is placed from where it is. */
    private suspend fun open(
        app: App,
        opener: String,
        inside: String,
    ) {
        app.element(text(opener)).tap()
        app.await(text(inside)).visible()
        app.awaitScreenStable()
    }
}
