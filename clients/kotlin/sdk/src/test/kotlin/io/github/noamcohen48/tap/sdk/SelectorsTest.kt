package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector.PickCase
import io.github.noamcohen48.tap.api.v1.TextProperty
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure selector-building rules: composition never silently drops a match choice. */
class SelectorsTest {
    private val system = "com.google.android.permissioncontroller"

    @Test
    fun `and flattens plain predicates and keeps the receiver's pick`() {
        val combined = text("Add").at(1) and clickable()
        assertEquals(2, combined.proto.node.allOf.nodesCount)
        assertEquals(PickCase.AT, combined.proto.pickCase)
        assertEquals(1, combined.proto.at.index)
    }

    @Test
    fun `an app binds its package as one more predicate and keeps the pick`() {
        val bound = (text("Add") or desc("Add")).at(1).inPackage(system)
        val (either, owner) = bound.proto.node.allOf.nodesList
        assertEquals(2, either.anyOf.nodesCount)
        assertEquals(TextProperty.PROPERTY_PACKAGE_NAME, owner.match.property)
        assertEquals(system, owner.match.value)
        assertEquals(1, bound.proto.at.index)
    }

    @Test
    fun `res names an id in any package and resId pins the package`() {
        assertEquals(false, res("login").proto.node.resource.hasPackageName())
        assertEquals("android", resId("android", "button1").proto.node.resource.packageName)
        assertEquals(res("a").andRes("android", "b").proto.node.allOf.nodesList.map { it.resource.packageName }, listOf("", "android"))
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
    fun `has relations reject operands with a pick`() {
        val relations: List<(Selector, Selector) -> Selector> =
            listOf(
                { a, b -> a.hasDescendant(b) },
                { a, b -> a.hasChild(b) },
                { a, b -> a.hasParent(b) },
                { a, b -> a.hasAncestor(b) },
            )
        for (relation in relations) {
            assertFailsWith<IllegalArgumentException> { relation(res("card"), text("Play").at(0)) }
            val ok = relation(res("card").first(), text("Play"))
            assertEquals(PickCase.FIRST, ok.proto.pickCase)
        }
    }

    @Test
    fun `descendant and child reject a picked receiver`() {
        val list = res("list").at(2)
        val descendant = assertFailsWith<IllegalArgumentException> { list.descendant(text("row")) }
        assertTrue(descendant.message!!.contains("receiver"), descendant.message)
        assertFailsWith<IllegalArgumentException> { list.child(text("row")) }
        assertFailsWith<IllegalArgumentException> { res("list").first().descendant(text("row")) }
    }

    @Test
    fun `descendant keeps the target's pick`() {
        val target = res("list").descendant(text("row").at(3))
        assertEquals(PickCase.AT, target.proto.pickCase)
        assertEquals(3, target.proto.at.index)
        val related = target.proto.node.allOf.nodesList.single { it.hasRelated() }.related
        assertEquals(Relation.RELATION_ANCESTOR, related.relation)
        val child = res("list").child(text("row"))
        assertEquals(Relation.RELATION_PARENT, child.proto.node.allOf.nodesList.single { it.hasRelated() }.related.relation)
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
            "node { all_of { nodes { resource { name: \"login\" } } " +
                "nodes { flag { property: FLAG_CLICKABLE value: true } } } } first { }",
            (res("login") and clickable()).first().render(),
        )
        assertEquals(
            """node { resource { name: "button1" package_name: "android" } } at { }""",
            resId("android", "button1").at(0).render(),
        )
        assertEquals(
            "node { all_of { nodes { match { property: PROPERTY_TEXT value: \"a\" mode: MATCH_EXACT } } " +
                "nodes { related { relation: RELATION_PARENT node { any_of { " +
                "nodes { match { property: PROPERTY_CLASS_NAME value: \"List\" mode: MATCH_CONTAINS } } " +
                "nodes { flag { property: FLAG_SCROLLABLE value: true } } } } } } } } at { index: 2 }",
            text("a").hasParent(className("List", MatchMode.CONTAINS) or scrollable()).at(2).render(),
        )
        assertEquals(text("x").render(), text("x").toString())
    }

    @Test
    fun `render escapes strings like protobuf text format`() {
        assertEquals(
            """node { match { property: PROPERTY_TEXT value: "say \"hi\" \\ \303\274\n" mode: MATCH_EXACT } }""",
            text("say \"hi\" \\ \u00fc\n").render(),
        )
        assertEquals("""node { resource { name: "tag" } }""", res("tag").render())
        assertEquals("""node { match { property: PROPERTY_HINT mode: MATCH_EXACT } }""", hint("").render())
    }
}
