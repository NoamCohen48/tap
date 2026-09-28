package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.children
import io.github.noamcohen48.tap.protocol.qualifyingPackage
import java.util.IdentityHashMap

/**
 * Evaluates selectors against a parsed dump the way the driver's *native* plan
 * (`SelectorCompiler` → one `BySelector`) evaluates them on the device, which is the plan every
 * synthesised selector compiles to:
 * - `aut` / `system{p}` search the focused window of the package with `BySelector.pkg(p)`, so a
 *   node matches only when its own package is `p`. The dump does not say which window is
 *   focused, so every window of `p` is searched: a count here is never lower than the driver's.
 * - `aut` rejects a resource of another package (`SCOPE_DENIED`); [matches] refuses it too.
 * - A resource compares `qualifyingPackage:id/name` (or the bare name) with the whole id.
 * - Text properties compare with the node's value; an absent value never matches.
 * - Relations walk the dump tree; `ancestor` is unbounded here (the driver's traversal plan
 *   stops at 32 levels, `BySelector` does not), so again the count is the larger one.
 *
 * Relation operands are memoised per dump node, so nested ancestor relations stay cheap. Not
 * thread-safe: one matcher per synthesis.
 */
internal class DumpMatcher(
    private val hierarchy: Hierarchy,
    private val autPackage: String,
) {
    private val nodes = hierarchy.nodes
    private val memo = IdentityHashMap<Node, HashMap<Int, Boolean>>()

    /** Window indexes a scope searches, and the package a matching node must have (null: any). */
    class Scope(
        val windows: Set<Int>,
        val packageName: String?,
    )

    fun scope(selector: Selector): Scope =
        when (selector.scopeCase) {
            Selector.ScopeCase.SYSTEM -> packageScope(selector.system.packageName)
            Selector.ScopeCase.ANY_WINDOW -> Scope(hierarchy.windowPackages.indices.toSet(), null)
            Selector.ScopeCase.AUT, Selector.ScopeCase.SCOPE_NOT_SET, null -> packageScope(autPackage)
        }

    fun packageScope(packageName: String): Scope =
        Scope(hierarchy.windowPackages.withIndex().filter { it.value == packageName }.map { it.index }.toSet(), packageName)

    /** Whether [node] may be searched by [scope] at all. */
    fun inScope(
        node: DumpNode,
        scope: Scope,
    ): Boolean = node.window in scope.windows && (scope.packageName == null || node.packageName == scope.packageName)

    /**
     * Every node [selector] matches, in dump pre-order, ignoring its pick; restricted to one
     * [window] when given (the driver's focused window).
     */
    fun matches(
        selector: Selector,
        window: Int? = null,
    ): List<DumpNode> {
        CommandValidation.validateSelector(selector)
        if (selector.scopeCase.let { it == Selector.ScopeCase.AUT || it == Selector.ScopeCase.SCOPE_NOT_SET }) {
            require(autResourcesOnly(selector.node)) { "AUT-scoped selector names a resource of another package" }
        }
        val scope = scope(selector)
        return nodes.filter { (window == null || it.window == window) && inScope(it, scope) && matches(selector.node, it) }
    }

    /** Whether [candidate] satisfies the predicate [node]. */
    fun matches(
        node: Node,
        candidate: DumpNode,
    ): Boolean = evaluate(node, candidate)

    /** [matches] for a relation's operand, which many candidates share: memoised (sparsely). */
    private fun relatedMatches(
        node: Node,
        candidate: DumpNode,
    ): Boolean = memo.getOrPut(node) { HashMap() }.getOrPut(candidate.index) { evaluate(node, candidate) }

    private fun evaluate(
        node: Node,
        candidate: DumpNode,
    ): Boolean =
        when (node.kindCase) {
            Node.KindCase.MATCH -> {
                matchText(node.match, candidate)
            }

            Node.KindCase.FLAG -> {
                flag(node.flag.property, candidate) == node.flag.value
            }

            Node.KindCase.RESOURCE -> {
                val resource = node.resource
                val expected = resource.qualifyingPackage(autPackage)?.let { "$it:id/${resource.name}" } ?: resource.name
                candidate.resourceName == expected
            }

            Node.KindCase.RELATED -> {
                val inner = node.related.node
                when (node.related.relation) {
                    Relation.RELATION_PARENT -> {
                        candidate.parent >= 0 && relatedMatches(inner, nodes[candidate.parent])
                    }

                    Relation.RELATION_ANCESTOR -> {
                        generateSequence(candidate.parent.takeIf { it >= 0 }) { nodes[it].parent.takeIf { p -> p >= 0 } }
                            .any { relatedMatches(inner, nodes[it]) }
                    }

                    Relation.RELATION_CHILD -> {
                        children(candidate).any { relatedMatches(inner, it) }
                    }

                    Relation.RELATION_DESCENDANT -> {
                        (candidate.index + 1 until candidate.subtreeEnd).any { relatedMatches(inner, nodes[it]) }
                    }

                    Relation.RELATION_UNSPECIFIED, Relation.UNRECOGNIZED, null -> {
                        false
                    }
                }
            }

            Node.KindCase.ALL_OF -> {
                node.allOf.nodesList.all { matches(it, candidate) }
            }

            Node.KindCase.ANY_OF -> {
                node.anyOf.nodesList.any { matches(it, candidate) }
            }

            Node.KindCase.KIND_NOT_SET, null -> {
                false
            }
        }

    private fun children(node: DumpNode): Sequence<DumpNode> =
        generateSequence(node.index + 1) { nodes[it].subtreeEnd }
            .takeWhile { it < node.subtreeEnd }
            .map { nodes[it] }

    private fun autResourcesOnly(node: Node): Boolean {
        if (node.kindCase == Node.KindCase.RESOURCE) {
            val packageName = node.resource.qualifyingPackage(autPackage)
            if (packageName != null && packageName != autPackage) return false
        }
        return node.children.all(::autResourcesOnly)
    }

    private companion object {
        fun matchText(
            match: Match,
            node: DumpNode,
        ): Boolean {
            val actual =
                when (match.property) {
                    TextProperty.PROPERTY_TEXT -> node.text
                    TextProperty.PROPERTY_CONTENT_DESCRIPTION -> node.contentDescription
                    TextProperty.PROPERTY_HINT -> node.hint
                    TextProperty.PROPERTY_CLASS_NAME -> node.className
                    TextProperty.PROPERTY_UNSPECIFIED, TextProperty.UNRECOGNIZED, null -> null
                } ?: return false
            val value = match.value
            return when (match.mode) {
                MatchMode.MATCH_EXACT, MatchMode.MATCH_UNSPECIFIED -> actual == value
                MatchMode.MATCH_CONTAINS -> actual.contains(value)
                MatchMode.MATCH_STARTS_WITH -> actual.startsWith(value)
                MatchMode.MATCH_ENDS_WITH -> actual.endsWith(value)
                MatchMode.MATCH_REGEX -> CommandValidation.compileRegex(value).matcher(actual).matches()
                MatchMode.UNRECOGNIZED, null -> false
            }
        }

        fun flag(
            property: NodeFlag,
            node: DumpNode,
        ): Boolean? =
            when (property) {
                NodeFlag.FLAG_ENABLED -> node.enabled
                NodeFlag.FLAG_CHECKED -> node.checked
                NodeFlag.FLAG_CHECKABLE -> node.checkable
                NodeFlag.FLAG_CLICKABLE -> node.clickable
                NodeFlag.FLAG_FOCUSED -> node.focused
                NodeFlag.FLAG_FOCUSABLE -> node.focusable
                NodeFlag.FLAG_LONG_CLICKABLE -> node.longClickable
                NodeFlag.FLAG_SCROLLABLE -> node.scrollable
                NodeFlag.FLAG_SELECTED -> node.selected
                NodeFlag.FLAG_UNSPECIFIED, NodeFlag.UNRECOGNIZED -> null
            }
    }
}
