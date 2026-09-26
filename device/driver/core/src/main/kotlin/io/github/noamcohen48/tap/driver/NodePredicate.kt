package io.github.noamcohen48.tap.driver

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiObject2
import com.google.re2j.Pattern
import io.github.noamcohen48.tap.api.v1.Flag
import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.ResourceId
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.MAX_SELECTOR_DEPTH
import io.github.noamcohen48.tap.protocol.qualifyingPackage

/**
 * Traversal-plan predicate over one node. Reads each element's [AccessibilityNodeInfo] once per
 * evaluation, however many property predicates the tree holds; relations walk the live
 * `UiObject2` tree and recycle every intermediate object. [autPackage] qualifies
 * `ResourceId.aut_package` resources.
 */
internal class NodePredicate(
    node: Node,
    autPackage: String,
) {
    private val root = Compiled.of(node, autPackage)

    fun matches(element: UiObject2): Boolean = root.matches(element, element.accessibilityNodeInfo)

    private sealed interface Compiled {
        fun matches(
            element: UiObject2,
            info: AccessibilityNodeInfo,
        ): Boolean

        class Text(
            match: Match,
        ) : Compiled {
            private val property = match.property
            private val matcher = TextMatcher(match.mode, match.value)

            override fun matches(
                element: UiObject2,
                info: AccessibilityNodeInfo,
            ): Boolean {
                val actual: CharSequence? =
                    when (property) {
                        TextProperty.PROPERTY_TEXT -> info.text
                        TextProperty.PROPERTY_CONTENT_DESCRIPTION -> info.contentDescription
                        TextProperty.PROPERTY_HINT -> info.hintText
                        TextProperty.PROPERTY_CLASS_NAME -> info.className
                        TextProperty.PROPERTY_UNSPECIFIED, TextProperty.UNRECOGNIZED, null -> null
                    }
                return matcher.matches(actual?.toString())
            }
        }

        class FlagCheck(
            private val flag: Flag,
        ) : Compiled {
            override fun matches(
                element: UiObject2,
                info: AccessibilityNodeInfo,
            ): Boolean {
                val actual =
                    when (flag.property) {
                        NodeFlag.FLAG_ENABLED -> info.isEnabled
                        NodeFlag.FLAG_CHECKED -> info.isChecked
                        NodeFlag.FLAG_CHECKABLE -> info.isCheckable
                        NodeFlag.FLAG_CLICKABLE -> info.isClickable
                        NodeFlag.FLAG_FOCUSED -> info.isFocused
                        NodeFlag.FLAG_FOCUSABLE -> info.isFocusable
                        NodeFlag.FLAG_LONG_CLICKABLE -> info.isLongClickable
                        NodeFlag.FLAG_SCROLLABLE -> info.isScrollable
                        NodeFlag.FLAG_SELECTED -> info.isSelected
                        NodeFlag.FLAG_UNSPECIFIED, NodeFlag.UNRECOGNIZED, null -> return false
                    }
                return actual == flag.value
            }
        }

        class Resource(
            resource: ResourceId,
            autPackage: String,
        ) : Compiled {
            private val expected = resource.qualifyingPackage(autPackage)?.let { "$it:id/${resource.name}" } ?: resource.name

            override fun matches(
                element: UiObject2,
                info: AccessibilityNodeInfo,
            ): Boolean = info.viewIdResourceName == expected
        }

        class Related(
            private val relation: Relation,
            private val predicate: NodePredicate,
        ) : Compiled {
            override fun matches(
                element: UiObject2,
                info: AccessibilityNodeInfo,
            ): Boolean =
                when (relation) {
                    Relation.RELATION_PARENT -> element.parentMatches(predicate)
                    Relation.RELATION_ANCESTOR -> element.ancestorMatches(predicate)
                    Relation.RELATION_CHILD -> element.childMatches(predicate)
                    Relation.RELATION_DESCENDANT -> element.descendantMatches(predicate)
                    Relation.RELATION_UNSPECIFIED, Relation.UNRECOGNIZED -> false
                }
        }

        class AllOf(
            private val operands: List<Compiled>,
        ) : Compiled {
            override fun matches(
                element: UiObject2,
                info: AccessibilityNodeInfo,
            ): Boolean = operands.all { it.matches(element, info) }
        }

        class AnyOf(
            private val operands: List<Compiled>,
        ) : Compiled {
            override fun matches(
                element: UiObject2,
                info: AccessibilityNodeInfo,
            ): Boolean = operands.any { it.matches(element, info) }
        }

        companion object {
            fun of(
                node: Node,
                autPackage: String,
            ): Compiled {
                // Cheap property checks first so a relation walk only runs when they hold.
                fun operands(nodes: List<Node>): List<Compiled> =
                    nodes.sortedBy { it.kindCase == Node.KindCase.RELATED }.map { of(it, autPackage) }
                return when (node.kindCase) {
                    Node.KindCase.MATCH -> Text(node.match)
                    Node.KindCase.FLAG -> FlagCheck(node.flag)
                    Node.KindCase.RESOURCE -> Resource(node.resource, autPackage)
                    Node.KindCase.RELATED -> Related(node.related.relation, NodePredicate(node.related.node, autPackage))
                    Node.KindCase.ALL_OF -> AllOf(operands(node.allOf.nodesList))
                    Node.KindCase.ANY_OF -> AnyOf(operands(node.anyOf.nodesList))
                    Node.KindCase.KIND_NOT_SET, null -> error("validation rejects an empty node")
                }
            }
        }
    }

    private companion object {
        fun UiObject2.parentMatches(predicate: NodePredicate): Boolean {
            val parent = parent ?: return false
            return try {
                predicate.matches(parent)
            } finally {
                parent.recycle()
            }
        }

        fun UiObject2.ancestorMatches(predicate: NodePredicate): Boolean {
            var current = parent
            var depth = 0
            while (current != null && depth++ < MAX_SELECTOR_DEPTH) {
                val matched = predicate.matches(current)
                val next = if (matched) null else current.parent
                current.recycle()
                if (matched) return true
                current = next
            }
            current?.recycle()
            return false
        }

        fun UiObject2.childMatches(predicate: NodePredicate): Boolean {
            val children = children
            var matched = false
            children.forEach { child ->
                try {
                    if (!matched && predicate.matches(child)) matched = true
                } finally {
                    child.recycle()
                }
            }
            return matched
        }

        fun UiObject2.descendantMatches(predicate: NodePredicate): Boolean {
            val children = children
            var matched = false
            children.forEach { child ->
                try {
                    if (!matched && (predicate.matches(child) || child.descendantMatches(predicate))) matched = true
                } finally {
                    child.recycle()
                }
            }
            return matched
        }
    }
}

/**
 * One string comparison of the traversal plan. `MATCH_UNSPECIFIED` is exact; `MATCH_REGEX`
 * is RE2 and must match the whole value. An absent property never matches.
 */
internal class TextMatcher(
    private val mode: MatchMode,
    private val value: String,
) {
    private val regex: Pattern? = if (mode == MatchMode.MATCH_REGEX) CommandValidation.compileRegex(value) else null

    fun matches(actual: String?): Boolean {
        val text = actual ?: return false
        return when (mode) {
            MatchMode.MATCH_EXACT, MatchMode.MATCH_UNSPECIFIED -> text == value
            MatchMode.MATCH_CONTAINS -> text.contains(value)
            MatchMode.MATCH_STARTS_WITH -> text.startsWith(value)
            MatchMode.MATCH_ENDS_WITH -> text.endsWith(value)
            MatchMode.MATCH_REGEX -> requireNotNull(regex).matcher(text).matches()
            MatchMode.UNRECOGNIZED -> false
        }
    }
}
