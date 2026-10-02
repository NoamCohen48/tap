package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.matchesId
import java.util.IdentityHashMap

/**
 * Evaluates selectors against a parsed dump the way the driver's *native* plan
 * (`SelectorCompiler` → one `BySelector`) evaluates them on the device, which is the plan every
 * synthesised selector compiles to:
 * - Every window of the dump is searched, as the driver searches every window it is given.
 *   Package ownership is an ordinary `PROPERTY_PACKAGE_NAME` predicate on the node.
 * - A resource compares with [matchesId], the function the driver's traversal plan uses.
 * - Text properties compare with the node's value; an absent value never matches.
 * - Relations walk the dump tree; `ancestor` is unbounded here (the driver's traversal plan
 *   stops at 32 levels, `BySelector` does not), so a count here is never lower than the driver's.
 * - The dump keeps nodes the driver skips (a node not visible to the user never matches on the
 *   device), so again the count here is the larger one.
 * - Matches come in `ByMatcher`'s order, which an `At` pick counts in: windows in dump order, and
 *   within a window *post-order* — `ByMatcher.findMatches` adds a node after searching its
 *   children, so a match comes after the matches inside it ([NATIVE_ORDER]).
 *
 * Relation operands are memoised per dump node, so nested ancestor relations stay cheap. Not
 * thread-safe: one matcher per synthesis.
 */
internal class DumpMatcher(
    private val hierarchy: Hierarchy,
) {
    private val nodes = hierarchy.nodes
    private val memo = IdentityHashMap<Node, HashMap<Int, Boolean>>()

    /** Every node [selector] matches, in the driver's [NATIVE_ORDER], ignoring its pick. */
    fun matches(selector: Selector): List<DumpNode> {
        CommandValidation.validateSelector(selector)
        return nodes.filter { matches(selector.node, it) }.sortedWith(NATIVE_ORDER)
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
                node.resource.matchesId(candidate.resourceName)
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

    companion object {
        /**
         * The order `UiDevice.findObjects` returns native-plan matches in, which an `At` pick
         * indexes: post-order. A node's subtree ends (`subtreeEnd`) no later than its ancestors'
         * and before its later siblings start; a node and its last descendant share the end,
         * and the descendant comes first.
         */
        val NATIVE_ORDER: Comparator<DumpNode> = compareBy<DumpNode> { it.subtreeEnd }.thenByDescending { it.index }

        private fun matchText(
            match: Match,
            node: DumpNode,
        ): Boolean {
            val actual =
                when (match.property) {
                    TextProperty.PROPERTY_TEXT -> node.text
                    TextProperty.PROPERTY_CONTENT_DESCRIPTION -> node.contentDescription
                    TextProperty.PROPERTY_HINT -> node.hint
                    TextProperty.PROPERTY_CLASS_NAME -> node.className
                    TextProperty.PROPERTY_PACKAGE_NAME -> node.packageName
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

        private fun flag(
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
