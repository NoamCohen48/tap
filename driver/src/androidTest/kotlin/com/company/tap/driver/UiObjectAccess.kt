package com.company.tap.driver

import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiWindow
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.MAX_MATCH_COUNT
import com.company.tap.protocol.MatchLimit
import com.company.tap.protocol.Selector

/**
 * Selector evaluation against the focused window of the scope package. Objects are resolved
 * per command and recycled by the caller; nothing here outlives one command.
 */
internal class UiObjectAccess(
    private val device: UiDevice,
    private val compiler: SelectorCompiler,
) {
    /** Exactly one of [element] and [errorCode] is set. */
    data class Resolution(val element: UiObject2?, val errorCode: ErrorCode?)

    fun compile(selector: Selector): CompiledSelector = compiler.compile(selector)

    /**
     * Resolves an action target according to the selector's [MatchLimit]. `EXACTLY_ONE` stops
     * after a second match and reports `AMBIGUOUS`; `FIRST`/`AT` pick from traversal order.
     */
    fun resolve(selector: Selector): Resolution {
        val compiled = compile(selector)
        val wanted = when (selector.limit) {
            MatchLimit.EXACTLY_ONE -> 2
            MatchLimit.FIRST -> 1
            MatchLimit.AT -> requireNotNull(selector.index) + 1
        }
        val elements = findObjects(compiled, wanted)
        val chosenIndex = when (selector.limit) {
            MatchLimit.EXACTLY_ONE -> if (elements.size == 1) 0 else -1
            MatchLimit.FIRST -> 0
            MatchLimit.AT -> requireNotNull(selector.index)
        }
        val chosen = elements.getOrNull(chosenIndex)
        elements.forEachIndexed { index, element -> if (index != chosenIndex) element.recycle() }
        return when {
            chosen != null -> Resolution(chosen, null)
            selector.limit == MatchLimit.EXACTLY_ONE && elements.size > 1 -> Resolution(null, ErrorCode.AMBIGUOUS)
            else -> Resolution(null, ErrorCode.NOT_FOUND)
        }
    }

    /** Presence query: at least one match in the focused window. Ignores the match limit. */
    fun hasObject(selector: Selector): Boolean = try {
        when (val compiled = compile(selector)) {
            is CompiledSelector.Native -> focusedWindow(compiled)?.hasObject(compiled.by) == true
            is CompiledSelector.Traversal -> findObjects(compiled, 1).onEach(UiObject2::recycle).isNotEmpty()
        }
    } catch (_: StaleObjectException) {
        false
    }

    /** Number of matches in the focused window, capped at [MAX_MATCH_COUNT]. */
    fun count(selector: Selector): Int = try {
        findObjects(compile(selector), MAX_MATCH_COUNT).onEach(UiObject2::recycle).size
    } catch (_: StaleObjectException) {
        0
    }

    /** Presence of [target] inside an already resolved container. */
    fun containerHasObject(container: UiObject2, target: Selector): Boolean =
        when (val compiled = compile(target)) {
            is CompiledSelector.Native -> container.hasObject(compiled.by)
            is CompiledSelector.Traversal -> {
                val matches = mutableListOf<UiObject2>()
                collectMatches(container, compiled.predicate, matches, limit = 1)
                matches.forEach(UiObject2::recycle)
                matches.isNotEmpty()
            }
        }

    /** Up to [limit] matches in accessibility traversal order. The caller recycles them. */
    private fun findObjects(compiled: CompiledSelector, limit: Int): List<UiObject2> {
        val window = focusedWindow(compiled) ?: return emptyList()
        return when (compiled) {
            is CompiledSelector.Native -> {
                val all = window.findObjects(compiled.by)
                all.drop(limit).forEach(UiObject2::recycle)
                all.take(limit)
            }
            is CompiledSelector.Traversal -> {
                val root = window.rootObject ?: return emptyList()
                val matches = mutableListOf<UiObject2>()
                try {
                    val rootMatched = compiled.predicate.matches(root)
                    if (rootMatched) matches += root
                    if (matches.size < limit) collectMatches(root, compiled.predicate, matches, limit)
                    if (!rootMatched) root.recycle()
                    matches
                } catch (error: Throwable) {
                    matches.forEach { runCatching { it.recycle() } }
                    runCatching { root.recycle() }
                    throw error
                }
            }
        }
    }

    /** Depth-first pre-order over [root]'s descendants (not [root] itself). */
    private fun collectMatches(root: UiObject2, predicate: NodePredicate, matches: MutableList<UiObject2>, limit: Int) {
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

    private fun focusedWindow(compiled: CompiledSelector): UiWindow? =
        device.findWindow(By.Window.pkg(compiled.scopePackage).focused(true))
}
