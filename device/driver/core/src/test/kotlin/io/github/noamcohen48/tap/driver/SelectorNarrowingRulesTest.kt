package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.pickAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorNarrowingRulesTest {
    private val pay = Nodes.text("Pay")
    private val clickable = Nodes.flag(NodeFlag.FLAG_CLICKABLE)
    private val shop = Nodes.packageName("com.shop")

    /** A fake screen: counts by node, near misses by operand, and a clock each lookup advances. */
    private class Screen(
        private val counts: Map<Node, Int>,
        private val lookupMs: Long = 0,
    ) {
        var nowMs = 0L
        val counted = mutableListOf<Node>()
        val nearMissReads = mutableListOf<Node>()

        fun rules(budgetMs: Long = SelectorNarrowingRules.BUDGET_MS) =
            SelectorNarrowingRules(
                count = { node ->
                    counted += node
                    nowMs += lookupMs
                    counts[node] ?: 0
                },
                nearMisses = { operand, _ ->
                    nearMissReads += operand
                    listOf("Pay now", "Pay later")
                },
                nowMs = { nowMs },
                budgetMs = budgetMs,
            )
    }

    private fun all(vararg nodes: Node): Node = Node.newBuilder().setAllOf(AllOf.newBuilder().addAllNodes(nodes.toList())).build()

    @Test
    fun eachPartIsCountedAloneAndLeftOut() {
        val selector = Selectors.of(all(pay, clickable, shop))
        val screen = Screen(mapOf(clickable to 14, shop to 40, all(clickable, shop) to 2))

        val narrowing = requireNotNull(screen.rules().explain(listOf(selector)))

        assertEquals(0, narrowing.matches)
        assertEquals(listOf(0 to 2, 14 to 0, 40 to 0), narrowing.partsList.map { it.alone to it.without })
        assertEquals(listOf("Pay now", "Pay later"), narrowing.partsList[0].nearMissesList)
        // Near misses only where the rest matched something.
        assertEquals(listOf(pay), screen.nearMissReads)
    }

    @Test
    fun aPickPastTheMatchesReportsOnlyTheCount() {
        val selector = Selectors.of(all(pay, clickable)).pickAt(3)
        val narrowing = requireNotNull(Screen(mapOf(all(pay, clickable) to 2)).rules().explain(listOf(selector)))
        assertEquals(2, narrowing.matches)
        assertTrue(narrowing.partsList.isEmpty())
    }

    @Test
    fun aSelectorWithEnoughMatchesNowIsNotExplained() {
        assertNull(Screen(mapOf(pay to 1)).rules().explain(listOf(Selectors.of(pay))))
    }

    @Test
    fun theFirstSelectorThatMissesIsExplained() {
        val source = Selectors.of(pay)
        val target = Selectors.of(clickable)
        val narrowing = requireNotNull(Screen(mapOf(pay to 1)).rules().explain(listOf(source, target)))
        assertEquals(1, narrowing.selectorIndex)
    }

    @Test
    fun moreThanTheMostPartsGetsTheCountOnly() {
        val operands = (1..SelectorNarrowingRules.MAX_PARTS + 1).map { Nodes.text("$it") }
        val screen = Screen(emptyMap())
        val narrowing = requireNotNull(screen.rules().explain(listOf(Selectors.of(all(*operands.toTypedArray())))))
        assertTrue(narrowing.partsList.isEmpty())
        assertEquals(1, screen.counted.size)
    }

    @Test
    fun theBudgetStopsThePartsAndNearMissesStopAtHalfOfIt() {
        // Each lookup takes 300 ms against a 1 s budget.
        val slow = Screen(mapOf(clickable to 14, shop to 40, all(clickable, shop) to 2, all(pay, shop) to 1), lookupMs = 300)
        val narrowing = requireNotNull(slow.rules().explain(listOf(Selectors.of(all(pay, clickable, shop)))))
        assertTrue(narrowing.partsList.isEmpty())
        assertTrue(slow.nowMs <= SelectorNarrowingRules.BUDGET_MS + 2 * 300)
        // The first part's lookups end at 900 ms, past half the budget: no near misses were read.
        assertTrue(slow.nearMissReads.isEmpty())
    }

    @Test
    fun aStaleCountOrAnyErrorLeavesTheNarrowingOut() {
        val stale = SelectorNarrowingRules(count = { null }, nearMisses = { _, _ -> emptyList() }, nowMs = { 0 })
        assertNull(stale.explain(listOf(Selectors.of(pay))))
        val broken = SelectorNarrowingRules(count = { error("boom") }, nearMisses = { _, _ -> emptyList() }, nowMs = { 0 })
        assertNull(broken.explain(listOf<Selector>(Selectors.of(pay))))
    }
}
