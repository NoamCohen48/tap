package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector.PickCase
import io.github.noamcohen48.tap.api.v1.Selector.ScopeCase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure selector-building rules: composition never silently drops a scope or a match choice. */
class SelectorsTest {
    private val system = "com.google.android.permissioncontroller"

    @Test
    fun `and flattens plain predicates and keeps the receiver's pick and scope`() {
        val combined = text("Add").at(1).inPackage(system) and clickable()
        assertEquals(2, combined.proto.node.allOf.nodesCount)
        assertEquals(PickCase.AT, combined.proto.pickCase)
        assertEquals(1, combined.proto.at.index)
        assertEquals(system, combined.proto.system.packageName)
    }

    @Test
    fun `and and or reject an operand with a match choice`() {
        val picked = listOf(text("x").first(), text("x").at(2))
        for (operand in picked) {
            val and = assertFailsWith<IllegalArgumentException> { text("a") and operand }
            assertTrue(and.message!!.contains("match choice"), and.message)
            assertFailsWith<IllegalArgumentException> { text("a") or operand }
            assertFailsWith<IllegalArgumentException> { allOf(text("a"), operand) }
            assertFailsWith<IllegalArgumentException> { anyOf(text("a"), operand) }
        }
    }

    @Test
    fun `and and or reject an operand with a different scope but accept the same one`() {
        val other = text("x").inPackage(system)
        val failure = assertFailsWith<IllegalArgumentException> { text("a") and other }
        assertTrue(failure.message!!.contains("scope"), failure.message)
        assertFailsWith<IllegalArgumentException> { text("a") or other }
        assertFailsWith<IllegalArgumentException> { text("a").inPackage("other.pkg") and other }
        val same = text("a").inPackage(system) or other
        assertEquals(system, same.proto.system.packageName)
        assertEquals(2, same.proto.node.anyOf.nodesCount)
    }

    @Test
    fun `any window scope composes like a package scope and renders in field order`() {
        val anywhere = text("OK").inAnyWindow()
        assertEquals(ScopeCase.ANY_WINDOW, anywhere.proto.scopeCase)
        assertFailsWith<IllegalArgumentException> { text("a") and anywhere }
        assertFailsWith<IllegalArgumentException> { text("a").inPackage(system) or anywhere }
        assertEquals(ScopeCase.ANY_WINDOW, (text("a").inAnyWindow() and anywhere).proto.scopeCase)
        val row = rawRes("list").inAnyWindow().descendant(text("row"))
        assertEquals(ScopeCase.ANY_WINDOW, row.proto.scopeCase)
        val rendered = anywhere.first().render()
        assertTrue(rendered.endsWith(" first { } any_window { }"), rendered)
    }

    @Test
    fun `has relations reject operands with a pick or a different scope`() {
        val relations: List<(Selector, Selector) -> Selector> =
            listOf(
                { a, b -> a.hasDescendant(b) },
                { a, b -> a.hasChild(b) },
                { a, b -> a.hasParent(b) },
                { a, b -> a.hasAncestor(b) },
            )
        for (relation in relations) {
            assertFailsWith<IllegalArgumentException> { relation(rawRes("card"), text("Play").at(0)) }
            assertFailsWith<IllegalArgumentException> { relation(rawRes("card"), text("Play").inPackage(system)) }
            val ok = relation(rawRes("card").first(), text("Play"))
            assertEquals(PickCase.FIRST, ok.proto.pickCase)
        }
    }

    @Test
    fun `descendant and child reject a picked receiver`() {
        val list = rawRes("list").at(2)
        val descendant = assertFailsWith<IllegalArgumentException> { list.descendant(text("row")) }
        assertTrue(descendant.message!!.contains("receiver"), descendant.message)
        assertFailsWith<IllegalArgumentException> { list.child(text("row")) }
        assertFailsWith<IllegalArgumentException> { rawRes("list").first().descendant(text("row")) }
    }

    @Test
    fun `descendant keeps the target's pick and the receiver's scope`() {
        val target = rawRes("list").inPackage(system).descendant(text("row").at(3))
        assertEquals(PickCase.AT, target.proto.pickCase)
        assertEquals(3, target.proto.at.index)
        assertEquals(ScopeCase.SYSTEM, target.proto.scopeCase)
        val related = target.proto.node.allOf.nodesList.single { it.hasRelated() }.related
        assertEquals(Relation.RELATION_ANCESTOR, related.relation)
        val child = rawRes("list").child(text("row"))
        assertEquals(Relation.RELATION_PARENT, child.proto.node.allOf.nodesList.single { it.hasRelated() }.related.relation)
    }

    @Test
    fun `descendant rejects a target with a different scope`() {
        assertFailsWith<IllegalArgumentException> { rawRes("list").descendant(text("row").inPackage(system)) }
        val same = rawRes("list").inPackage(system).descendant(text("row").inPackage(system))
        assertEquals(system, same.proto.system.packageName)
    }

    @Test
    fun `selectors are values`() {
        assertEquals(text("a") and clickable(), text("a") and clickable())
        assertEquals((text("a") and clickable()).hashCode(), (text("a") and clickable()).hashCode())
        assertEquals(setOf(text("a")), setOf(text("a"), text("a")))
    }

    @Test
    fun `render prints one-line protobuf text of the tree`() {
        assertEquals(
            """node { match { property: PROPERTY_TEXT value: "OK" mode: MATCH_EXACT } }""",
            text("OK").render(),
        )
        assertEquals(
            "node { all_of { nodes { resource { name: \"login\" aut_package: true } } " +
                "nodes { flag { property: FLAG_CLICKABLE value: true } } } } first { }",
            (res("login") and clickable()).first().render(),
        )
        assertEquals(
            """node { resource { name: "button1" package_name: "android" } } system { package_name: "$system" } at { }""",
            resId("android", "button1").inPackage(system).at(0).render(),
        )
        assertEquals(
            "node { all_of { nodes { match { property: PROPERTY_TEXT value: \"a\" mode: MATCH_EXACT } } " +
                "nodes { related { relation: RELATION_PARENT node { any_of { " +
                "nodes { match { property: PROPERTY_CLASS_NAME value: \"List\" mode: MATCH_CONTAINS } } " +
                "nodes { flag { property: FLAG_SCROLLABLE value: true } } } } } } } } at { index: 2 }",
            text("a").hasParent(className("List", MatchMode.MATCH_CONTAINS) or scrollable()).at(2).render(),
        )
        assertEquals(text("x").render(), text("x").toString())
    }

    @Test
    fun `render escapes strings like protobuf text format`() {
        assertEquals(
            """node { match { property: PROPERTY_TEXT value: "say \"hi\" \\ \303\274\n" mode: MATCH_EXACT } }""",
            text("say \"hi\" \\ \u00fc\n").render(),
        )
        assertEquals("""node { resource { name: "tag" } }""", rawRes("tag").render())
        assertEquals("""node { match { property: PROPERTY_HINT mode: MATCH_EXACT } }""", hint("").render())
    }
}
