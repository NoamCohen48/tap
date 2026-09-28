package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.AutScope
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.AUT
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.SYSTEM_UI
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.node
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.wrap
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.SelectorPlanKind
import io.github.noamcohen48.tap.protocol.conjunction
import io.github.noamcohen48.tap.protocol.inAnyWindow
import io.github.noamcohen48.tap.protocol.inPackage
import io.github.noamcohen48.tap.protocol.render
import io.github.noamcohen48.tap.protocol.toSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectorSynthesisTest {
    private fun synthesise(hierarchy: Hierarchy) = SelectorSynthesis(hierarchy, AUT).synthesise()

    private fun aut(node: Node): Selector = node.toSelector().toBuilder().setAut(AutScope.getDefaultInstance()).build()

    @Test
    fun `every synthesised selector is valid, native and resolves to its node alone`() {
        val summary = StringBuilder()
        for (name in Dumps.names) {
            val hierarchy = Dumps.hierarchy(name)
            val matcher = DumpMatcher(hierarchy, AUT)
            val synthesised = synthesise(hierarchy)
            hierarchy.nodes.forEach { node ->
                val result = synthesised[node.index] ?: return@forEach
                val selector = result.selector
                assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(selector), selector.render())
                assertEquals(node.windowPackage == AUT, selector.hasAut(), selector.render())
                // Another package's window: every window, so the status bar is reachable too.
                if (!selector.hasAut()) assertTrue(selector.hasAnyWindow(), selector.render())
                if (result.byIndex) {
                    // `aut` picks among its focused window's matches, `any_window` among all, in pre-order.
                    val matches = matcher.matches(selector, window = node.window.takeIf { selector.hasAut() })
                    assertSame(node, matches[selector.at.index], "$name ${selector.render()}")
                } else {
                    assertTrue(selector.pickCase == Selector.PickCase.PICK_NOT_SET, selector.render())
                    assertEquals(listOf(node), matcher.matches(selector), "$name ${selector.render()}")
                }
            }
            val counts = synthesised.groupingBy { it?.kind?.name ?: "NONE" }.eachCount()
            summary.appendLine("$name: ${hierarchy.nodes.size} nodes $counts")
        }
        println(summary)
    }

    @Test
    fun `resource ids become plain aut, package or raw selectors`() {
        for (name in Dumps.names.filter { it.endsWith("MainActivity") }) {
            val hierarchy = Dumps.hierarchy(name)
            val synthesised = synthesise(hierarchy)

            fun selectorOf(resourceId: String): Synthesised? = synthesised[hierarchy.nodes.single { it.resourceName == resourceId }.index]

            val button = assertNotNull(selectorOf("$AUT:id/view_button"), name)
            assertEquals(SelectorKind.PLAIN, button.kind)
            assertEquals(aut(Nodes.autResource("view_button")), button.selector)
            // A Compose testTag under testTagsAsResourceId is a raw resource name.
            assertEquals(aut(Nodes.rawResource("composeButton")), selectorOf("composeButton")?.selector)
        }
        val emulator = Dumps.hierarchy("emulator-5554-MainActivity")
        val clock = synthesise(emulator)[emulator.nodes.single { it.resourceName == "$SYSTEM_UI:id/clock" }.index]
        assertEquals(Nodes.androidResource(SYSTEM_UI, "clock").toSelector().inAnyWindow(), clock?.selector)
    }

    @Test
    fun `duplicate rows are told apart by an ancestor or an index`() {
        for (name in Dumps.names.filter { it.endsWith("AmbiguityActivity") }) {
            val hierarchy = Dumps.hierarchy(name)
            val synthesised = synthesise(hierarchy)
            val buttons = hierarchy.nodes.filter { it.resourceName == "$AUT:id/duplicate_button" }
            assertEquals(2, buttons.size)
            val halves = listOf("left_half", "right_half")
            buttons.zip(halves).forEach { (button, half) ->
                val result = assertNotNull(synthesised[button.index], name)
                assertEquals(SelectorKind.ANCESTOR, result.kind, result.selector.render())
                val related = result.selector.node.conjunction.single { it.kindCase == Node.KindCase.RELATED }.related
                assertEquals(Relation.RELATION_ANCESTOR, related.relation)
                assertEquals(Nodes.autResource(half), related.node)
            }
            val inputs = hierarchy.nodes.filter { it.resourceName == "$AUT:id/duplicate_input" }
            assertEquals(2, inputs.size)
            inputs.forEach { input ->
                val result = assertNotNull(synthesised[input.index], name)
                assertTrue(result.kind == SelectorKind.ANCESTOR || result.kind == SelectorKind.BY_INDEX, result.selector.render())
            }
            // A unique id stays the plain selector a person would write.
            val status = hierarchy.nodes.single { it.resourceName == "$AUT:id/duplicate_status" }
            assertEquals(aut(Nodes.autResource("duplicate_status")), synthesised[status.index]?.selector)
        }
    }

    @Test
    fun `candidates go from plain to combined to ancestor to index`() {
        val hierarchy =
            HierarchyParser.parse(
                wrap(
                    node(
                        className = "Root",
                        children =
                            node(text = "Unique") +
                                node(text = "Twice", className = "android.widget.Button") +
                                node(text = "Twice", className = "android.widget.TextView") +
                                node(resourceId = "$AUT:id/a", children = node(text = "Row")) +
                                node(resourceId = "$AUT:id/b", children = node(text = "Row")) +
                                node(className = "Same", children = node(text = "Clone") + node(text = "Clone")),
                    ),
                ),
            )
        val result = synthesise(hierarchy)

        fun at(text: String, occurrence: Int = 0) = result[hierarchy.nodes.filter { it.text == text }[occurrence].index]!!
        assertEquals(SelectorKind.PLAIN, at("Unique").kind)
        assertEquals(SelectorKind.COMBINED, at("Twice").kind)
        assertEquals(Nodes.allOf(Nodes.text("Twice"), Nodes.className("android.widget.Button")), at("Twice").selector.node)
        assertEquals(SelectorKind.ANCESTOR, at("Row", 1).kind)
        assertEquals(Nodes.allOf(Nodes.text("Row"), Nodes.ancestor(Nodes.autResource("b"))), at("Row", 1).selector.node)
        // "Same" has only its class, which is no candidate alone, so it is itself picked by index and
        // cannot anchor an ancestor relation: the clones fall back to an index too.
        assertEquals(SelectorKind.BY_INDEX, at("Clone", 1).kind)
        assertEquals(1, at("Clone", 1).selector.at.index)
        assertEquals(Nodes.allOf(Nodes.text("Clone"), Nodes.className("android.widget.TextView")), at("Clone", 1).selector.node)
    }

    @Test
    fun `nodes the driver could never match alone get no selector`() {
        val tooLong = "x".repeat(2_000)
        val hierarchy =
            HierarchyParser.parse(
                wrap(
                    node(
                        className = "Root",
                        children =
                            // Its own package differs from its window's: BySelector.pkg never matches it.
                            node(text = "Foreign", packageName = "com.other") +
                                // Only an over-long text and no class: no candidate fits the selector limits.
                                node(className = "", text = tooLong) +
                                // An AUT-scoped selector may not name another package's resource.
                                node(className = "", resourceId = "android:id/content"),
                    ),
                ),
            )
        val result = synthesise(hierarchy)
        assertEquals(listOf(false, true, true, true), result.map { it == null })
    }

    @Test
    fun `the matcher follows the driver's scope rules`() {
        val hierarchy = Dumps.hierarchy("85e49002-MainActivity")
        val matcher = DumpMatcher(hierarchy, AUT)
        // aut scope never sees the system UI windows, and denies another package's resource.
        val clock = Nodes.androidResource(SYSTEM_UI, "clock")
        assertFailsWith<IllegalArgumentException> { matcher.matches(clock.toSelector()) }
        assertTrue(matcher.matches(Nodes.rawResource("$SYSTEM_UI:id/clock").toSelector()).isEmpty())
        assertEquals(1, matcher.matches(clock.toSelector().inPackage(SYSTEM_UI)).size)
        // The two system UI windows (status and navigation bar) are both searched.
        assertEquals(2, matcher.packageScope(SYSTEM_UI).windows.size)
        assertNull(matcher.matches(Nodes.autResource("no_such_id").toSelector()).firstOrNull())
    }
}
