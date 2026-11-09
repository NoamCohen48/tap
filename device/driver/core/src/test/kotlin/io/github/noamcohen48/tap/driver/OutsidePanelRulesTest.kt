package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.driver.OutsidePanel.Landing
import io.github.noamcohen48.tap.driver.OutsidePanel.Rules
import io.github.noamcohen48.tap.driver.OutsidePanel.Rules.Attempt
import io.github.noamcohen48.tap.driver.OutsidePanel.Rules.Candidate
import io.github.noamcohen48.tap.driver.OutsidePanel.Rules.Decision
import io.github.noamcohen48.tap.driver.OutsidePanel.Rules.Neighbour
import io.github.noamcohen48.tap.driver.WindowFrames.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coordinates as measured on the emulator (1080x2400, density 420) and the SM-J810G (720x1480). */
class OutsidePanelRulesTest {
    private val display = Box(0, 0, 1080, 2400)

    /** The emulator with gesture navigation: a 63 px status bar, an 84 px gesture area. */
    private val usable = Rules.inset(display, Box(0, 63, 0, 84))

    private fun window(
        box: Box,
        application: Boolean = true,
        layer: Int = 0,
        name: String = "w",
    ) = Neighbour({ x, y -> Rules.contains(box, x, y) }, application, layer, name)

    @Test
    fun aDialogWindowIsTouchedPastEachSideInOrder() {
        val dialog = Box(28, 943, 1052, 1456)
        assertEquals(
            // Right (1115) and left (-35) are off the display.
            listOf(Candidate("below", 540, 1519), Candidate("above", 540, 880)),
            Rules.pastFrame(dialog, display, margin = 63),
        )
    }

    @Test
    fun aPopupMenuIsTouchedOnEverySideThatIsOnScreen() {
        val menu = Box(0, 693, 515, 945)
        assertEquals(
            listOf(Candidate("below", 257, 1008), Candidate("above", 257, 630), Candidate("right", 578, 819)),
            Rules.pastFrame(menu, display, margin = 63),
        )
    }

    @Test
    fun aFullScreenWindowHasNoPointPastItsFrame() {
        assertEquals(emptyList<Candidate>(), Rules.pastFrame(display, display, margin = 63))
    }

    @Test
    fun aDrawerIsTouchedInTheMiddleOfTheFreeSpaceBesideIt() {
        // DrawerLayout [0,63][1080,2337], drawer [0,63][630,2337]: only the right side is free.
        assertEquals(
            listOf(Candidate("right", 855, 1200, Box(630, 63, 1080, 2337))),
            Rules.besideInside(Box(0, 63, 630, 2337), Box(0, 63, 1080, 2337), minGap = 63),
        )
    }

    @Test
    fun aBottomSheetIsTouchedAboveItInsideItsCoordinator() {
        // SM-J810G, an expanded sheet [0,379][720,1396] in its coordinator [0,42][720,1396].
        assertEquals(
            listOf(Candidate("above", 360, 210, Box(0, 42, 720, 379))),
            Rules.besideInside(Box(0, 379, 720, 1396), Box(0, 42, 720, 1396), minGap = 42),
        )
    }

    @Test
    fun aGapNarrowerThanTheMinimumIsNotUsed() {
        assertEquals(emptyList<Candidate>(), Rules.besideInside(Box(0, 100, 1080, 2337), Box(0, 63, 1080, 2337), minGap = 63))
    }

    @Test
    fun aPointInTheGestureAreaIsBlockedThoughNoWindowIsListedThere() {
        // Below a plain screen whose frame ends where the 24 dp navigation inset starts: 2337 + 40 is
        // on the display and in no listed window, but in the gesture area.
        assertEquals(Landing.Blocked("the system bars"), Rules.landing(540, 2377, usable, emptyList(), ownLayer = 1, insideOwnWindow = false))
        assertEquals(Landing.Free, Rules.landing(540, 1519, usable, emptyList(), ownLayer = 1, insideOwnWindow = false))
    }

    @Test
    fun systemWindowsAndAppWindowsAboveBlockAndAppWindowsBelowAreReachedOnlyOutsideTheOwnWindow() {
        val keyboard = window(Box(0, 1500, 1080, 2337), application = false, layer = 9, name = "keyboard")
        val popupAbove = window(Box(0, 0, 1080, 400), layer = 5, name = "popup")
        val activityBelow = window(display, layer = 0, name = "activity")
        val others = listOf(keyboard, popupAbove, activityBelow)
        assertEquals(Landing.Blocked("keyboard"), Rules.landing(540, 1600, usable, others, ownLayer = 2, insideOwnWindow = false))
        assertEquals(Landing.Blocked("popup"), Rules.landing(540, 200, usable, others, ownLayer = 2, insideOwnWindow = false))
        assertEquals(Landing.Below("activity"), Rules.landing(540, 1000, usable, others, ownLayer = 2, insideOwnWindow = false))
        assertEquals(Landing.Free, Rules.landing(540, 1000, usable, others, ownLayer = 2, insideOwnWindow = true))
    }

    @Test
    fun aPanelWithNoFreeSideFallsBackToItsWindow() {
        // A Compose dialog whose pane-titled surface is a few pixels inside its window: no gap
        // beside it, so the touch goes past the window frame instead of being refused.
        val inPanel = Attempt("pane \"Dialog\"", emptyList()) { Landing.Free }
        val window = Attempt("window", listOf(Candidate("below", 540, 1510))) { Landing.Free }
        assertEquals(Decision.Touch(Candidate("below", 540, 1510), "window, below"), Rules.decide(inPanel, window, allowReachBelow = false))
    }

    @Test
    fun aPanelPointOnAControlIsSkippedForTheNextSideThenTheWindow() {
        // A Snackbar offers dismiss: above it is a button, so the in-window rule gives nothing and
        // the full-screen window has no point either.
        val inPanel = Attempt("dismissible Snackbar", listOf(Candidate("above", 540, 1800))) { Landing.Blocked("a control (\"Pay\"), not a scrim") }
        val window = Attempt("window [0,0][1080,2400]", emptyList()) { Landing.Free }
        val decision = Rules.decide(inPanel, window, allowReachBelow = false)
        assertTrue(decision.toString(), decision is Decision.Nowhere)
        assertTrue((decision as Decision.Nowhere).tried, decision.tried.contains("above on a control (\"Pay\"), not a scrim"))
    }

    @Test
    fun pointsOverAWindowBelowAreRefusedUnlessAllowedAndAFreeSideWins() {
        val candidates = listOf(Candidate("below", 1, 1), Candidate("above", 2, 2))
        val overBelow = Attempt("window \"Popup Window\"", candidates) { Landing.Below("window \"Tap Fixture\"") }
        val refused = Rules.decide(null, overBelow, allowReachBelow = false)
        assertTrue(refused.toString(), refused is Decision.ReachesBelow)
        assertEquals(
            Decision.Touch(Candidate("below", 1, 1), "window \"Popup Window\", below, reaching window \"Tap Fixture\""),
            Rules.decide(null, overBelow, allowReachBelow = true),
        )
        val aboveFree = Attempt("window", candidates) { c -> if (c.side == "above") Landing.Free else Landing.Below("activity") }
        assertEquals(Decision.Touch(Candidate("above", 2, 2), "window, above"), Rules.decide(null, aboveFree, allowReachBelow = true))
    }

    @Test
    fun containmentAndIntersection() {
        assertEquals(Box(0, 63, 1080, 2337), Rules.intersect(Box(0, 0, 1080, 2400), Box(-10, 63, 2000, 2337)))
        assertTrue(Rules.containsBox(Box(0, 63, 1080, 2337), Box(630, 63, 1080, 2337)))
        assertEquals(false, Rules.containsBox(Box(630, 63, 1080, 2337), Box(0, 63, 1080, 2337)))
    }
}
