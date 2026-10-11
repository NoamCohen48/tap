package io.github.noamcohen48.tap.driver

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader

/** Runs in the CI device lane, with the fixture installed and no Tap session attached. */
@RunWith(AndroidJUnit4::class)
class HierarchyDumpTest {
    @Test
    fun diagnosticDumpMatchesUiAutomatorApartFromFieldState() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("am force-stop $PACKAGE")
        try {
            device.executeShellCommand("am start -W -n $PACKAGE/.FieldStateActivity")
            assertTrue(device.wait(Until.hasObject(By.res(PACKAGE, "validate_field")), 10_000))
            device.waitForIdle()
            val upstream = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString("UTF-8")
            val tap = ByteArrayOutputStream().also { HierarchyDump.write(device, it) }.toString("UTF-8")
            val expected = fixtureTree(upstream)
            assertFalse("Fixture must be present, not two empty trees", expected.isEmpty())
            assertEquals(expected, fixtureTree(tap))
        } finally {
            device.executeShellCommand("am force-stop $PACKAGE")
        }
    }

    /** Compare structure and every attribute, not serializer whitespace. Ignore unrelated system
     * windows (the clock can change between dumps); omit only Tap's three extra attributes. */
    private fun fixtureTree(xml: String): List<Pair<Int, Map<String, String>>> {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val nodes = mutableListOf<Pair<Int, Map<String, String>>>()
        var fixtureWindow = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "node") continue
            val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            if (parser.depth == 2) fixtureWindow = attributes["package"] == PACKAGE
            if (fixtureWindow) nodes += parser.depth to attributes.filterKeys { it !in EXTRA_ATTRIBUTES }
        }
        return nodes
    }

    private companion object {
        const val PACKAGE = "io.github.noamcohen48.tap.fixture"
        val EXTRA_ATTRIBUTES = setOf("showing-hint", "content-invalid", "error")
    }
}
