package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.MAX_MATCH_COUNT

/**
 * Selector evaluation against all visible windows. Selectors arrive compiled (once per
 * request); objects are resolved per call and recycled by the caller, so
 * nothing here outlives one command.
 */
internal class UiObjectAccess(
    private val device: UiDevice,
) {
    /** Exactly one of [element] and [errorCode] is set. */
    data class Resolution(val element: UiObject2?, val errorCode: ErrorCode?)

    /** Resolves an action target according to the selector's [SelectorPick]. */
    fun resolve(target: CompiledSelector): Resolution {
        val elements = findObjects(target, target.pick.wanted)
        val outcome = target.pick.choose(elements.size)
        val chosenIndex = (outcome as? SelectorPick.Outcome.Chosen)?.index ?: -1
        elements.forEachIndexed { i, element -> if (i != chosenIndex) element.recycle() }
        return when (outcome) {
            is SelectorPick.Outcome.Chosen -> Resolution(elements[chosenIndex], null)
            is SelectorPick.Outcome.Failed -> Resolution(null, outcome.code)
        }
    }

    /**
     * Presence query: at least one match in the searched windows, ignoring the match limit. Null
     * when the tree changed under the search (a stale object): the answer is unknown, never
     * "absent", so callers poll again instead of reporting a re-render as gone.
     */
    fun presence(target: CompiledSelector): Boolean? =
        try {
            when (target) {
                is CompiledSelector.Native -> device.hasObject(target.by)

                is CompiledSelector.Traversal -> findObjects(target, 1).onEach(UiObject2::recycle).isNotEmpty()
            }
        } catch (_: StaleObjectException) {
            null
        }

    /** Number of matches in the searched windows, capped at [MAX_MATCH_COUNT]; null when stale (see [presence]). */
    fun count(target: CompiledSelector): Int? =
        try {
            findObjects(target, MAX_MATCH_COUNT).onEach(UiObject2::recycle).size
        } catch (_: StaleObjectException) {
            null
        }

    /**
     * Presence of [target] among the descendants of an already resolved container; the
     * container itself never counts. Both plans agree on this: `UiObject2.hasObject` searches
     * from (and matches) its own node, so the native plan asks each child instead.
     */
    fun containerHasObject(
        container: UiObject2,
        target: CompiledSelector,
    ): Boolean =
        when (target) {
            is CompiledSelector.Native -> {
                val children = container.children
                try {
                    children.any { it.hasObject(target.by) }
                } finally {
                    children.forEach(UiObject2::recycle)
                }
            }

            is CompiledSelector.Traversal -> {
                val matches = mutableListOf<UiObject2>()
                collectMatches(container, target.predicate, matches, limit = 1)
                matches.forEach(UiObject2::recycle)
                matches.isNotEmpty()
            }
        }

    /**
     * Up to [limit] matches, each window in order. The native plan keeps `ByMatcher`'s order,
     * post-order (a match after the matches inside it); the traversal plan walks pre-order
     * (DR-23 in `.docs/code-review-status.md`). The caller recycles them.
     */
    private fun findObjects(
        compiled: CompiledSelector,
        limit: Int,
    ): List<UiObject2> {
        return when (compiled) {
            is CompiledSelector.Native -> {
                val found = device.findObjects(compiled.by)
                val all = if (compiled.mayRepeat) distinct(found) else found
                all.drop(limit).forEach(UiObject2::recycle)
                all.take(limit)
            }

            is CompiledSelector.Traversal -> {
                // Depth 0 is each window's root, in the order the accessibility service reports windows.
                val roots = device.findObjects(By.depth(0))
                val matches = mutableListOf<UiObject2>()
                val pending = ArrayDeque(roots)
                try {
                    while (pending.isNotEmpty()) {
                        val root = pending.removeFirst()
                        if (matches.size >= limit) {
                            root.recycle()
                            continue
                        }
                        try {
                            val rootMatched = compiled.predicate.matches(root)
                            if (rootMatched) matches += root
                            if (matches.size < limit) collectMatches(root, compiled.predicate, matches, limit)
                            if (!rootMatched) root.recycle()
                        } catch (error: Throwable) {
                            if (root !in matches) recycleQuietly(root)
                            throw error
                        }
                    }
                    matches
                } catch (error: Throwable) {
                    // Cleanup only; the error itself is rethrown unchanged.
                    matches.forEach { recycleQuietly(it) }
                    pending.forEach { recycleQuietly(it) }
                    throw error
                }
            }
        }
    }

    /**
     * [found] without repeats, first occurrence kept, the repeats recycled. `ByMatcher` searches
     * an ancestor relation from the ancestor down, so a node under two nested matching ancestors
     * (a `Button` under `LinearLayout`s) comes back once per ancestor. `UiObject2.equals` compares
     * the node identity, refreshing each node: only paid when a selector looks up.
     */
    private fun distinct(found: List<UiObject2>): List<UiObject2> {
        val seen = LinkedHashSet<UiObject2>()
        found.forEach { if (!seen.add(it)) it.recycle() }
        return seen.toList()
    }

    /** Depth-first pre-order over [root]'s descendants (not [root] itself). */
    private fun collectMatches(
        root: UiObject2,
        predicate: NodePredicate,
        matches: MutableList<UiObject2>,
        limit: Int,
    ) {
        for (child in root.children) {
            if (matches.size >= limit) {
                child.recycle()
                continue
            }
            val matched = predicate.matches(child)
            if (matched) matches += child
            if (matches.size < limit) collectMatches(child, predicate, matches, limit)
            if (!matched) child.recycle()
        }
    }
}

/** Recycles [element], ignoring an already recycled or stale object. */
internal fun recycleQuietly(element: UiObject2) {
    try {
        element.recycle()
    } catch (_: RuntimeException) {
        // Already recycled; nothing to release.
    }
}
