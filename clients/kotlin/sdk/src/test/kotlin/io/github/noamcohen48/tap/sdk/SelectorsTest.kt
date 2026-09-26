package io.github.noamcohen48.tap.sdk

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
        val combined = text("Add").at(1).inSystemPackage(system) and clickable()
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
        val other = text("x").inSystemPackage(system)
        val failure = assertFailsWith<IllegalArgumentException> { text("a") and other }
        assertTrue(failure.message!!.contains("scope"), failure.message)
        assertFailsWith<IllegalArgumentException> { text("a") or other }
        assertFailsWith<IllegalArgumentException> { text("a").inSystemPackage("other.pkg") and other }
        val same = text("a").inSystemPackage(system) or other
        assertEquals(system, same.proto.system.packageName)
        assertEquals(2, same.proto.node.anyOf.nodesCount)
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
            assertFailsWith<IllegalArgumentException> { relation(rawRes("card"), text("Play").inSystemPackage(system)) }
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
        val target = rawRes("list").inSystemPackage(system).descendant(text("row").at(3))
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
        assertFailsWith<IllegalArgumentException> { rawRes("list").descendant(text("row").inSystemPackage(system)) }
        val same = rawRes("list").inSystemPackage(system).descendant(text("row").inSystemPackage(system))
        assertEquals(system, same.proto.system.packageName)
    }

    @Test
    fun `selectors are values`() {
        assertEquals(text("a") and clickable(), text("a") and clickable())
        assertEquals((text("a") and clickable()).hashCode(), (text("a") and clickable()).hashCode())
        assertEquals(setOf(text("a")), setOf(text("a"), text("a")))
    }
}
