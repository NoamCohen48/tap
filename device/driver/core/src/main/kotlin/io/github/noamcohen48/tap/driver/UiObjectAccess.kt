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
                val children = container.children
                val matches = postOrderMatches(children, UiObject2::getChildren, target.predicate::matches, ::recycleQuietly, limit = 1)
                matches.forEach(UiObject2::recycle)
                matches.isNotEmpty()
            }
        }

    /**
     * Up to [limit] matches, each window in order, and within a window in post-order (a match
     * after the matches inside it) on both plans: the native plan keeps `ByMatcher`'s order and
     * the traversal plan walks the same way ([postOrderMatches]), so `first` / `at n` pick the
     * same node whichever plan a selector compiles to. The caller recycles them.
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
                postOrderMatches(device.findObjects(By.depth(0)), UiObject2::getChildren, compiled.predicate::matches, ::recycleQuietly, limit)
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
}

/**
 * Up to [limit] of the nodes under [roots] (the roots included) that [matches] accepts, in
 * depth-first post-order: a node comes after every match inside it, as `ByMatcher` returns
 * them. Every node that is not returned is [recycle]d, as is everything when the walk throws
 * (the error itself propagates unchanged).
 */
internal fun <T> postOrderMatches(
    roots: List<T>,
    children: (T) -> List<T>,
    matches: (T) -> Boolean,
    recycle: (T) -> Unit,
    limit: Int,
): List<T> {
    val found = mutableListOf<T>()

    // Its children's matches first, then the node itself while there is room.
    fun visit(node: T) {
        var kept = false
        try {
            if (found.size < limit) {
                children(node).let { kids ->
                    kids.forEachIndexed { i, child ->
                        try {
                            if (found.size < limit) visit(child) else recycle(child)
                        } catch (error: Throwable) {
                            kids.drop(i + 1).forEach(recycle)
                            throw error
                        }
                    }
                }
            }
            if (found.size < limit && matches(node)) {
                found += node
                kept = true
            }
        } finally {
            if (!kept) recycle(node)
        }
    }
    roots.forEachIndexed { i, root ->
        try {
            if (found.size < limit) visit(root) else recycle(root)
        } catch (error: Throwable) {
            roots.drop(i + 1).forEach(recycle)
            found.forEach(recycle)
            throw error
        }
    }
    return found
}

/** Recycles [element], ignoring an already recycled or stale object. */
internal fun recycleQuietly(element: UiObject2) {
    try {
        element.recycle()
    } catch (_: RuntimeException) {
        // Already recycled; nothing to release.
    }
}
