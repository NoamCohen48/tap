package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.At
import io.github.noamcohen48.tap.api.v1.AutScope
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.SystemScope
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.MAX_SELECTOR_DEPTH
import io.github.noamcohen48.tap.protocol.MAX_SELECTOR_STRING_CHARS
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.SelectorPlanKind
import io.github.noamcohen48.tap.protocol.conjunction
import io.github.noamcohen48.tap.protocol.qualifyingPackage

/** How a synthesised selector singles out its node; [BY_INDEX] is the only fragile one. */
internal enum class SelectorKind {
    /** One predicate: resource id, text, description or hint. */
    PLAIN,

    /** Two predicates of the node itself (e.g. resource and text, text and class). */
    COMBINED,

    /** The node's own predicates plus an `ancestor` relation to an addressable ancestor. */
    ANCESTOR,

    /** The most specific predicate available plus an `At(index)` pick. */
    BY_INDEX,
}

internal class Synthesised(
    val selector: Selector,
    val kind: SelectorKind,
) {
    val byIndex: Boolean get() = kind == SelectorKind.BY_INDEX
}

/**
 * Synthesises, for every node of a dump, a selector the driver would resolve to that node alone
 * (`.docs/agent-surface.md`, decision 2). Candidates go from what a person would write to the
 * fragile: resource id, text, description, their pairs with each other and the class, the hint;
 * then each of those with an `ancestor` relation to the nearest ancestor that has its own
 * non-index selector; last, the most specific conjunction with an `At` pick.
 *
 * Uniqueness is checked with [DumpMatcher] in the node's scope: a window of the AUT package is
 * `aut`, any other package's window is `system{package}`. Every emitted selector passes the
 * shared [CommandValidation] and compiles to the driver's native plan, whose semantics
 * [DumpMatcher] emulates. A node whose own package differs from its window's (the native
 * `pkg` filter can never match it) or that has no string property at all gets no selector.
 */
internal class SelectorSynthesis(
    hierarchy: Hierarchy,
    private val autPackage: String,
) {
    private val nodes = hierarchy.nodes
    private val matcher = DumpMatcher(hierarchy, autPackage)
    private val byText = index { it.text }
    private val byDescription = index { it.contentDescription }
    private val byHint = index { it.hint }
    private val byClass = index { it.className }
    private val byResource = index { it.resourceName }

    /** One entry per [Hierarchy.nodes] entry; null where no selector was found. */
    fun synthesise(): List<Synthesised?> {
        val result = arrayOfNulls<Synthesised>(nodes.size)
        // Pre-order: every ancestor is done before its descendants need it.
        nodes.forEach { result[it.index] = synthesise(it, result) }
        return result.asList()
    }

    private fun synthesise(
        node: DumpNode,
        done: Array<Synthesised?>,
    ): Synthesised? {
        if (node.packageName != node.windowPackage) return null
        val scopePackage = node.windowPackage
        val scope = matcher.packageScope(scopePackage)
        val resource = resource(node.resourceName, scopePackage)
        val text = node.text?.usable()?.let { Nodes.text(it) }
        val description = node.contentDescription?.usable()?.let { Nodes.contentDescription(it) }
        val hint = node.hint?.usable()?.let { Nodes.hint(it) }
        val className = node.className?.usable()?.let { Nodes.className(it) }

        fun pair(
            first: Node?,
            second: Node?,
        ): Node? = if (first != null && second != null) Nodes.allOf(first, second) else null

        val candidates =
            listOf(
                resource to SelectorKind.PLAIN,
                text to SelectorKind.PLAIN,
                description to SelectorKind.PLAIN,
                pair(resource, text) to SelectorKind.COMBINED,
                pair(resource, description) to SelectorKind.COMBINED,
                pair(text, className) to SelectorKind.COMBINED,
                pair(description, className) to SelectorKind.COMBINED,
                pair(resource, className) to SelectorKind.COMBINED,
                hint to SelectorKind.PLAIN,
            ).filter { it.first != null }
        for ((predicate, kind) in candidates) {
            unique(predicate!!, node, scope, scopePackage)?.let { return Synthesised(it, kind) }
        }

        val ancestor = addressableAncestor(node, done)
        if (ancestor != null) {
            val related = Nodes.ancestor(ancestor.node)
            // The class alone is no candidate by itself, but "a Button under X" is.
            val own = candidates.map { it.first!! } + listOfNotNull(className)
            for (predicate in own) {
                unique(Nodes.allOf(predicate, related), node, scope, scopePackage)?.let { return Synthesised(it, SelectorKind.ANCESTOR) }
            }
        }

        val specific = listOfNotNull(resource, text, description, hint, className).takeIf { it.isNotEmpty() } ?: return null
        val predicate = Nodes.allOf(specific)
        val position =
            seeds(predicate)
                .map { nodes[it] }
                .filter { it.window == node.window && matcher.inScope(it, scope) && matcher.matches(predicate, it) }
                .indexOf(node)
        if (position < 0) return null
        val selector = selector(predicate, scopePackage).toBuilder().setAt(At.newBuilder().setIndex(position)).build()
        return selector.takeIf { valid(it) }?.let { Synthesised(it, SelectorKind.BY_INDEX) }
    }

    /**
     * The selector of [predicate] when it matches [target] and nothing else in [scope] (a
     * selector the driver would reject is no candidate).
     */
    private fun unique(
        predicate: Node,
        target: DumpNode,
        scope: DumpMatcher.Scope,
        scopePackage: String,
    ): Selector? {
        var matchedTarget = false
        for (i in seeds(predicate)) {
            val node = nodes[i]
            if (!matcher.inScope(node, scope) || !matcher.matches(predicate, node)) continue
            if (node !== target) return null
            matchedTarget = true
        }
        if (!matchedTarget) return null
        return selector(predicate, scopePackage).takeIf { valid(it) }
    }

    private fun selector(
        predicate: Node,
        scopePackage: String,
    ): Selector =
        Selector
            .newBuilder()
            .setNode(predicate)
            .apply {
                if (scopePackage == autPackage) {
                    setAut(AutScope.getDefaultInstance())
                } else {
                    setSystem(SystemScope.newBuilder().setPackageName(scopePackage))
                }
            }.build()

    /** Only selectors the shared validation accepts, on the native plan [DumpMatcher] emulates. */
    private fun valid(selector: Selector): Boolean =
        try {
            CommandValidation.validateSelector(selector) == SelectorPlanKind.NATIVE
        } catch (_: InvalidCommandException) {
            false
        }

    /**
     * The nearest ancestor, at most [MAX_SELECTOR_DEPTH] levels up (the driver's traversal walk
     * stops there), whose selector is not an index pick; its predicate becomes the relation.
     */
    private fun addressableAncestor(
        node: DumpNode,
        done: Array<Synthesised?>,
    ): Selector? {
        var parent = node.parent
        var distance = 1
        while (parent >= 0 && distance <= MAX_SELECTOR_DEPTH) {
            val synthesised = done[parent]
            if (synthesised != null && !synthesised.byIndex) return synthesised.selector
            parent = nodes[parent].parent
            distance++
        }
        return null
    }

    /**
     * A resource-id predicate: `aut_package` for the AUT's ids, `package_name` for another
     * package's (except in `aut` scope, where the driver denies it), the raw name when the id is
     * not `package:id/name` (a Compose testTag).
     */
    private fun resource(
        id: String?,
        scopePackage: String,
    ): Node? {
        val usable = id?.usable() ?: return null
        val qualified = QUALIFIED_ID.matchEntire(usable) ?: return Nodes.rawResource(usable)
        val (packageName, name) = qualified.destructured
        return when {
            packageName == autPackage -> Nodes.autResource(name)
            scopePackage == autPackage -> null
            else -> Nodes.androidResource(packageName, name)
        }
    }

    /**
     * Candidate node indexes for [predicate], in pre-order: the shortest posting list among its
     * top-level exact operands, so a check never scans the whole dump for a rare value.
     */
    private fun seeds(predicate: Node): IntArray {
        val lists =
            predicate.conjunction.mapNotNull { operand ->
                when (operand.kindCase) {
                    Node.KindCase.RESOURCE -> {
                        val resource = operand.resource
                        val id = resource.qualifyingPackage(autPackage)?.let { "$it:id/${resource.name}" } ?: resource.name
                        byResource[id] ?: EMPTY
                    }

                    Node.KindCase.MATCH -> {
                        val index =
                            when (operand.match.property) {
                                TextProperty.PROPERTY_TEXT -> byText
                                TextProperty.PROPERTY_CONTENT_DESCRIPTION -> byDescription
                                TextProperty.PROPERTY_HINT -> byHint
                                TextProperty.PROPERTY_CLASS_NAME -> byClass
                                else -> null
                            }
                        index?.let { it[operand.match.value] ?: EMPTY }
                    }

                    else -> {
                        null
                    }
                }
            }
        return lists.minByOrNull { it.size } ?: IntArray(nodes.size) { it }
    }

    private fun index(property: (DumpNode) -> String?): Map<String, IntArray> =
        nodes
            .mapNotNull { node -> property(node)?.let { it to node.index } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toIntArray() }

    private companion object {
        val QUALIFIED_ID = Regex("([^:/]+):id/(.+)", RegexOption.DOT_MATCHES_ALL)
        val EMPTY = IntArray(0)

        fun String.usable(): String? = takeIf { it.isNotEmpty() && it.length <= MAX_SELECTOR_STRING_CHARS }
    }
}
