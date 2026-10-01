package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.AUT
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.SYSTEM_UI
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.node
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.wrap
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.SelectorPlanKind
import io.github.noamcohen48.tap.protocol.conjunction
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
    private fun synthesise(hierarchy: Hierarchy) = SelectorSynthesis(hierarchy).synthesise()

    /** [node] owned by [packageName], as synthesis binds every selector. */
    private fun owned(
        node: Node,
        packageName: String = AUT,
    ): Selector = Nodes.allOf(node, Nodes.packageName(packageName)).toSelector()

    @Test
    fun `every synthesised selector is valid, native and resolves to its node alone`() {
        val summary = StringBuilder()
        for (name in Dumps.names) {
            val hierarchy = Dumps.hierarchy(name)
            val matcher = DumpMatcher(hierarchy)
            val synthesised = synthesise(hierarchy)
            hierarchy.nodes.forEach { node ->
                val result = synthesised[node.index] ?: return@forEach
                val selector = result.selector
                assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(selector), selector.render())
                // Package ownership is a predicate of every selector; the status bar is as reachable as the app.
                assertTrue(Nodes.packageName(node.packageName!!) in selector.node.conjunction, selector.render())
                if (result.byIndex) {
                    // The pick counts every window's matches, in pre-order.
                    assertSame(node, matcher.matches(selector)[selector.at.index], "$name ${selector.render()}")
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
    fun `an id of the node's own package is package-less, a testTag stays raw`() {
        for (name in Dumps.names.filter { it.endsWith("MainActivity") }) {
            val hierarchy = Dumps.hierarchy(name)
            val synthesised = synthesise(hierarchy)

            fun selectorOf(resourceId: String): Synthesised? = synthesised[hierarchy.nodes.single { it.resourceName == resourceId }.index]

            val button = assertNotNull(selectorOf("$AUT:id/view_button"), name)
            assertEquals(SelectorKind.PLAIN, button.kind)
            assertEquals(owned(Nodes.resource("view_button")), button.selector)
            // A Compose testTag under testTagsAsResourceId is a raw resource name.
            assertEquals(owned(Nodes.resource("composeButton")), selectorOf("composeButton")?.selector)
        }
        val emulator = Dumps.hierarchy("emulator-5554-MainActivity")
        val clock = synthesise(emulator)[emulator.nodes.single { it.resourceName == "$SYSTEM_UI:id/clock" }.index]
        assertEquals(owned(Nodes.resource("clock"), SYSTEM_UI), clock?.selector)
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
                assertEquals(Nodes.resource(half), related.node)
            }
            val inputs = hierarchy.nodes.filter { it.resourceName == "$AUT:id/duplicate_input" }
            assertEquals(2, inputs.size)
            inputs.forEach { input ->
                val result = assertNotNull(synthesised[input.index], name)
                assertTrue(result.kind == SelectorKind.ANCESTOR || result.kind == SelectorKind.BY_INDEX, result.selector.render())
            }
            // A unique id stays the plain selector a person would write.
            val status = hierarchy.nodes.single { it.resourceName == "$AUT:id/duplicate_status" }
            assertEquals(owned(Nodes.resource("duplicate_status")), synthesised[status.index]?.selector)
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
        assertEquals(owned(Nodes.allOf(Nodes.text("Twice"), Nodes.className("android.widget.Button"))), at("Twice").selector)
        assertEquals(SelectorKind.ANCESTOR, at("Row", 1).kind)
        // The ancestor's own package predicate is left out: the node's one already says whose window it is.
        assertEquals(owned(Nodes.allOf(Nodes.text("Row"), Nodes.ancestor(Nodes.resource("b")))), at("Row", 1).selector)
        // "Same" has only its class, which is no candidate alone, so it is itself picked by index and
        // cannot anchor an ancestor relation: the clones fall back to an index too.
        assertEquals(SelectorKind.BY_INDEX, at("Clone", 1).kind)
        assertEquals(1, at("Clone", 1).selector.at.index)
        assertEquals(owned(Nodes.allOf(Nodes.text("Clone"), Nodes.className("android.widget.TextView"))).node, at("Clone", 1).selector.node)
    }

    @Test
    fun `candidates start with the selector and are all unique and minimal`() {
        for (name in Dumps.names) {
            val hierarchy = Dumps.hierarchy(name)
            val matcher = DumpMatcher(hierarchy)
            val synthesis = SelectorSynthesis(hierarchy)
            val primary = synthesis.synthesise()
            val candidates = synthesis.candidates()
            var alternatives = 0
            hierarchy.nodes.forEach { node ->
                val ranked = candidates[node.index]
                val first = primary[node.index]
                assertEquals(first?.selector, ranked.firstOrNull()?.selector, "$name node ${node.index}")
                assertEquals(first?.kind, ranked.firstOrNull()?.kind, "$name node ${node.index}")
                assertEquals(ranked.size, ranked.map { it.selector }.toSet().size, "$name node ${node.index}")
                if (ranked.any { it.byIndex }) assertEquals(1, ranked.size, "$name: an index pick only when nothing else is unique")
                ranked.filterNot { it.byIndex }.forEach { candidate ->
                    assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(candidate.selector))
                    assertEquals(listOf(node), matcher.matches(candidate.selector), "$name ${candidate.selector.render()}")
                }
                ranked.forEachIndexed { i, later ->
                    ranked.take(i).forEach { earlier ->
                        assertTrue(!later.operands.containsAll(earlier.operands), "$name: ${later.selector.render()} only adds to ${earlier.selector.render()}")
                    }
                }
                alternatives += (ranked.size - 1).coerceAtLeast(0)
            }
            assertTrue(alternatives > 0, "$name offers no alternative selector")
        }
    }

    @Test
    fun `candidates list every minimal way to single out a node`() {
        val hierarchy =
            HierarchyParser.parse(
                wrap(
                    node(
                        className = "Root",
                        children =
                            node(resourceId = "$AUT:id/go", text = "Go", desc = "Start") +
                                node(resourceId = "$AUT:id/a", children = node(text = "Row")) +
                                node(resourceId = "$AUT:id/b", children = node(text = "Row")) +
                                node(className = "Same", children = node(text = "Clone") + node(text = "Clone")),
                    ),
                ),
            )
        val candidates = SelectorSynthesis(hierarchy).candidates()

        fun of(text: String, occurrence: Int = 0) = candidates[hierarchy.nodes.filter { it.text == text }[occurrence].index]

        // Each property alone is unique, so no pair of them is offered.
        assertEquals(
            listOf(Nodes.resource("go"), Nodes.text("Go"), Nodes.contentDescription("Start")).map { owned(it) to SelectorKind.PLAIN },
            of("Go").map { it.selector to it.kind },
        )
        // Only the row's ancestor tells it apart, with its text or its class.
        assertEquals(
            listOf(
                Nodes.allOf(Nodes.text("Row"), Nodes.ancestor(Nodes.resource("b"))),
                Nodes.allOf(Nodes.className("android.widget.TextView"), Nodes.ancestor(Nodes.resource("b"))),
            ).map { owned(it) to SelectorKind.ANCESTOR },
            of("Row", 1).map { it.selector to it.kind },
        )
        assertEquals(listOf(SelectorKind.BY_INDEX), of("Clone", 1).map { it.kind })
    }

    @Test
    fun `snapshots carry the candidates only when asked`() {
        val xml = Dumps.xml("emulator-5554-MainActivity")
        val plain = ScreenSnapshots.screen(xml)
        val withCandidates = ScreenSnapshots.screen(xml, candidates = true)
        assertTrue(plain.nodes.all { it.candidatesCount == 0 })
        assertEquals(plain.nodes.map { it.selector }, withCandidates.nodes.map { it.selector })
        withCandidates.nodes.forEach { node ->
            assertEquals(node.hasSelector(), node.candidatesCount > 0)
            if (node.hasSelector()) assertEquals(node.selector, node.candidatesList.first().selector)
        }
        assertTrue(withCandidates.nodes.any { it.candidatesCount > 1 })
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
                            // Its own package differs from its window's: it is bound to its own.
                            node(text = "Foreign", packageName = "com.other") +
                                // Only an over-long text and no class: no candidate fits the selector limits.
                                node(className = "", text = tooLong) +
                                // Another package's resource keeps that package.
                                node(className = "", resourceId = "android:id/content"),
                    ),
                ),
            )
        val result = synthesise(hierarchy)
        assertEquals(listOf(false, false, true, false), result.map { it == null })
        assertEquals(owned(Nodes.text("Foreign"), "com.other"), result[1]?.selector)
        assertEquals(owned(Nodes.androidResource("android", "content")), result[3]?.selector)
    }

    @Test
    fun `the matcher searches every window and treats the package as a predicate`() {
        val hierarchy = Dumps.hierarchy("85e49002-MainActivity")
        val matcher = DumpMatcher(hierarchy)
        val clock = matcher.matches(Nodes.androidResource(SYSTEM_UI, "clock").toSelector())
        assertEquals(1, clock.size)
        assertEquals(clock, matcher.matches(Nodes.resource("clock").toSelector()))
        assertEquals(clock, matcher.matches(owned(Nodes.resource("clock"), SYSTEM_UI)))
        assertTrue(matcher.matches(owned(Nodes.resource("clock"))).isEmpty())
        assertFailsWith<InvalidCommandException> { matcher.matches(Nodes.resource("$SYSTEM_UI:id/clock").toSelector()) }
        assertNull(matcher.matches(Nodes.resource("no_such_id").toSelector()).firstOrNull())
    }
}
