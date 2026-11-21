package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.WindowKind
import io.github.noamcohen48.tap.api.v1.WindowKind.WINDOW_KIND_APPLICATION
import io.github.noamcohen48.tap.api.v1.WindowKind.WINDOW_KIND_NAVIGATION_BAR
import io.github.noamcohen48.tap.api.v1.WindowKind.WINDOW_KIND_STATUS_BAR
import io.github.noamcohen48.tap.api.v1.WindowKind.WINDOW_KIND_SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals

/** The geometries are the ones measured on an API 34 emulator and an API 29 Samsung (SM-J810G). */
class WindowKindsTest {
    private fun window(
        bounds: String,
        pkg: String = "com.android.systemui",
        type: Int = 3,
    ) = """<node package="$pkg" window-type="$type" bounds="$bounds" text="w"/>"""

    private fun kinds(
        display: String,
        vararg windows: String,
    ): List<WindowKind> {
        val xml = "<hierarchy rotation=\"0\" $display>${windows.joinToString("")}</hierarchy>"
        return ScreenSnapshots.screen(xml).nodes.map { it.windowKind }
    }

    @Test
    fun `the status bar of an API 34 emulator is a bar, the app is an application`() {
        assertEquals(
            listOf(WINDOW_KIND_APPLICATION, WINDOW_KIND_STATUS_BAR),
            kinds("""width="1080" height="2400" density="420"""", window("[0,0][1080,2400]", "p", 1), window("[0,0][1080,63]")),
        )
    }

    @Test
    fun `on the Samsung only the full-width thin strips are bars`() {
        // Nav bar 84 px = 48 dp at the bottom; the Samsung Pay handle (another package, not full
        // width) and the volume panel (a 27 dp top strip, not full width) are not bars.
        assertEquals(
            listOf(WINDOW_KIND_APPLICATION, WINDOW_KIND_SYSTEM, WINDOW_KIND_NAVIGATION_BAR, WINDOW_KIND_SYSTEM, WINDOW_KIND_STATUS_BAR),
            kinds(
                """width="720" height="1480" density="280"""",
                window("[0,0][720,1480]", "p", 1),
                window("[186,1438][534,1480]", "com.samsung.android.spaymini"),
                window("[0,1396][720,1480]"),
                window("[54,0][666,77]"),
                window("[0,0][720,42]"),
            ),
        )
    }

    @Test
    fun `in landscape the navigation bar is a full-height strip at the side`() {
        assertEquals(
            listOf(WINDOW_KIND_NAVIGATION_BAR, WINDOW_KIND_STATUS_BAR),
            kinds("""width="1480" height="720" density="280"""", window("[1396,0][1480,720]"), window("[0,0][1480,42]")),
        )
    }

    @Test
    fun `the expanded shade and a system dialog stay system windows`() {
        // API 29's shade is the status-bar window grown to full screen; the ANR dialog is 121 dp.
        assertEquals(
            listOf(WINDOW_KIND_SYSTEM, WINDOW_KIND_SYSTEM),
            kinds("""width="720" height="1480" density="280"""", window("[0,0][720,1480]"), window("[0,1057][719,1396]", "android")),
        )
    }

    @Test
    fun `without the display nothing is a bar`() {
        assertEquals(listOf(WINDOW_KIND_SYSTEM), kinds("", window("[0,0][1080,63]")))
    }
}
