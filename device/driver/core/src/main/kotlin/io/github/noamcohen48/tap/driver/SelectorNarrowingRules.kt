package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.NarrowingPart
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.SelectorNarrowing

/**
 * What `Error.narrowing` says about a selector that resolved no node, given [count] (the capped
 * match count of a node, null when the tree went stale) and [nearMisses] (the values an operand's
 * property has on the nodes the other operands match). Kept apart from the device lookups so the
 * rules are tested without one.
 *
 * It runs after the command already failed, often past its deadline, so it is bounded: at most
 * [MAX_PARTS] operands are explained (a longer `all_of` gets the match count only), near misses
 * are read only in the first half of [budgetMs], and once the budget is spent the parts are left
 * out and only the match count is reported.
 */
internal class SelectorNarrowingRules(
    private val count: (Node) -> Int?,
    private val nearMisses: (operand: Node, rest: Node) -> List<String>,
    private val nowMs: () -> Long,
    private val budgetMs: Long = BUDGET_MS,
) {
    /**
     * The narrowing of the first of [selectors] that has fewer matches than its pick needs, or
     * null when none does (the screen changed since the failure) or the tree went stale.
     */
    fun explain(selectors: List<Selector>): SelectorNarrowing? {
        val startedMs = nowMs()
        return try {
            selectors.withIndex().firstNotNullOfOrNull { (index, selector) -> explainOne(index, selector, startedMs) }
        } catch (_: RuntimeException) {
            // A stale tree or anything else: diagnostics never turn a failure into a different one.
            null
        }
    }

    private fun explainOne(
        index: Int,
        selector: Selector,
        startedMs: Long,
    ): SelectorNarrowing? {
        val needed = if (selector.pickCase == Selector.PickCase.AT) selector.at.index + 1 else 1
        val matches = count(selector.node) ?: return null
        if (matches >= needed) return null
        val narrowing = SelectorNarrowing.newBuilder().setSelectorIndex(index).setMatches(matches)
        val operands = if (selector.node.kindCase == Node.KindCase.ALL_OF) selector.node.allOf.nodesList else emptyList()
        if (matches > 0 || operands.size < 2 || operands.size > MAX_PARTS) return narrowing.build()
        fun spentMs() = nowMs() - startedMs
        val parts =
            operands.mapIndexed { i, operand ->
                if (spentMs() >= budgetMs) return narrowing.build()
                val others = operands.filterIndexed { j, _ -> j != i }
                val rest = others.singleOrNull() ?: Node.newBuilder().setAllOf(AllOf.newBuilder().addAllNodes(others)).build()
                val without = count(rest) ?: return null
                val alone = count(operand) ?: return null
                val seen = if (without > 0 && spentMs() < budgetMs / 2) nearMisses(operand, rest) else emptyList()
                NarrowingPart.newBuilder().setAlone(alone).setWithout(without).addAllNearMisses(seen).build()
            }
        if (spentMs() >= budgetMs) return narrowing.build()
        return narrowing.addAllParts(parts).build()
    }

    companion object {
        /** The most operands explained one by one: each costs two lookups and its rest grows with them. */
        const val MAX_PARTS = 8

        /** Wall time for the whole narrowing, well inside the driver's grace past a deadline. */
        const val BUDGET_MS = 1_000L
    }
}
