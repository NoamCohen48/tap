package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.daemon.snapshot.Dumps.AUT
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.SYSTEM_UI
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.node
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.wrap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyParserTest {
    @Test
    fun `recorded dumps parse into every node and window`() {
        val expected =
            mapOf(
                "emulator-5554-MainActivity" to 71,
                "emulator-5554-ViewListActivity" to 51,
                "emulator-5554-AmbiguityActivity" to 51,
                "85e49002-MainActivity" to 76,
                "85e49002-ViewListActivity" to 59,
                "85e49002-AmbiguityActivity" to 61,
            )
        for ((name, count) in expected) {
            val hierarchy = Dumps.hierarchy(name)
            assertEquals(count, hierarchy.nodes.size, name)
            assertEquals(0, hierarchy.rotation, name)
            val windows = if (name.startsWith("emulator")) listOf(AUT, SYSTEM_UI) else listOf(AUT, SYSTEM_UI, SYSTEM_UI)
            assertEquals(windows, hierarchy.windowPackages, name)
            // Pre-order bookkeeping: parents come first, subtrees are contiguous and nested.
            hierarchy.nodes.forEach { node ->
                if (node.parent >= 0) {
                    val parent = hierarchy.nodes[node.parent]
                    assertTrue(parent.index < node.index && node.subtreeEnd <= parent.subtreeEnd, name)
                    assertEquals(parent.depth + 1, node.depth)
                    assertEquals(parent.window, node.window)
                    assertEquals(parent.windowPackage, node.windowPackage)
                }
            }
        }
    }

    @Test
    fun `attributes are read, entities unescaped, empty values absent`() {
        val ambiguity = Dumps.hierarchy("emulator-5554-AmbiguityActivity")
        val left = ambiguity.nodes.single { it.text?.startsWith("Left 1") == true }
        assertEquals((1..12).joinToString("\n") { "Left $it" }, left.text)
        assertNull(left.resourceName)
        assertNull(left.contentDescription)
        assertNull(left.hint)
        val security = ambiguity.nodes.single { it.contentDescription?.startsWith("Security") == true }
        assertEquals("Security & privacy notification: Set a screen lock", security.contentDescription)
        assertEquals(SYSTEM_UI, security.windowPackage)

        val main = Dumps.hierarchy("emulator-5554-MainActivity")
        val button = main.nodes.single { it.resourceName == "$AUT:id/view_button" }
        assertEquals("android.widget.Button", button.className)
        assertTrue(button.clickable && button.enabled && !button.checked)
        assertEquals(4, button.bounds?.size)
        assertEquals(AUT, button.packageName)
    }

    @Test
    fun `character references, single quotes and literal whitespace follow XML`() {
        val xml =
            wrap(
                "<node text='a &quot;b&quot; &lt;c&gt; &#x41;&#66;&apos;' class=\"X\" package=\"p\" " +
                    "content-desc=\"line&#10;break\ttab\" bounds=\"[1,2][3,4]\"/>",
            )
        val node = HierarchyParser.parse(xml).nodes.single()
        assertEquals("a \"b\" <c> AB'", node.text)
        assertEquals("line\nbreak tab", node.contentDescription)
        assertContentEquals(intArrayOf(1, 2, 3, 4), node.bounds)
        assertEquals(1, HierarchyParser.parse(xml).rotation)
    }

    @Test
    fun `field state is read from the driver's dump and defaults off without it`() {
        val xml =
            wrap(
                "<node class=\"android.widget.EditText\" package=\"p\" text=\"Name\" hint=\"Name\" showing-hint=\"true\" " +
                    "content-invalid=\"true\" error=\"A name is required.\" bounds=\"[0,0][1,1]\"/>" +
                    "<node class=\"android.widget.EditText\" package=\"p\" text=\"Ada\" error=\"\" bounds=\"[0,0][1,1]\"/>",
            )
        val (invalid, plain) = HierarchyParser.parse(xml).nodes
        assertTrue(invalid.showingHint)
        assertTrue(invalid.contentInvalid)
        assertEquals("A name is required.", invalid.error)
        assertEquals(false, plain.showingHint || plain.contentInvalid)
        assertNull(plain.error)

        val screen = ScreenSnapshots.screen(xml).nodes
        assertTrue(screen[0].showingHint && screen[0].contentInvalid)
        assertEquals("A name is required.", screen[0].error)
        assertEquals(false, screen[1].hasError())
    }

    @Test
    fun `invisible nodes are dropped with their subtree`() {
        val xml =
            wrap(
                node(
                    className = "Root",
                    children = node(className = "Hidden", visible = false, children = node(className = "Inner")) + node(className = "Shown"),
                ),
            )
        val nodes = HierarchyParser.parse(xml).nodes
        assertEquals(listOf("Root", "Shown"), nodes.map { it.className })
        assertEquals(0, nodes[1].parent)
        assertEquals(2, nodes[0].subtreeEnd)
    }

    @Test
    fun `hostile or malformed input is rejected, never expanded`() {
        val hostile =
            listOf(
                // XXE: an external entity must never be resolved.
                """<?xml version="1.0"?><!DOCTYPE hierarchy [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>""" +
                    """<hierarchy rotation="0"><node text="&xxe;" class="X" package="p"/></hierarchy>""",
                // Billion laughs.
                """<!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]><hierarchy><node text="&lol2;"/></hierarchy>""",
                // An entity with no DTD at all is undefined.
                """<hierarchy><node text="&xxe;" class="X"/></hierarchy>""",
                """<hierarchy><node text="&#0;"/></hierarchy>""",
                """<hierarchy><node text="a<b"/></hierarchy>""",
                """<hierarchy><node text="x"></hierarchy>""",
                """<hierarchy><node text="x" text="y"/></hierarchy>""",
                """<hierarchy><node text="unterminated/></hierarchy>""",
                """<node class="X"/>""",
                "",
            )
        for (xml in hostile) {
            assertFailsWith<HierarchyParseException>(xml) { HierarchyParser.parse(xml) }
        }
    }
}
