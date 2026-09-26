package io.github.noamcohen48.tap.driver

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import com.google.re2j.Pattern
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Flag
import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.ResourceId
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.MAX_SELECTOR_DEPTH
import io.github.noamcohen48.tap.protocol.SelectorPlanKind
import io.github.noamcohen48.tap.protocol.children
import io.github.noamcohen48.tap.protocol.conjunction
import io.github.noamcohen48.tap.protocol.qualifyingPackage
import io.github.noamcohen48.tap.protocol.systemPackage

/**
 * A selector compiled for one command. [scopePackage] is the single package whose focused
 * window is searched; nothing outside it is ever matched.
 */
internal sealed interface CompiledSelector {
    val scopePackage: String

    /** Every predicate maps onto `BySelector`; UiAutomator evaluates it natively. */
    class Native(
        override val scopePackage: String,
        val by: BySelector,
    ) : CompiledSelector

    /**
     * Evaluated by walking the focused window's node tree. Only used for predicates
     * `BySelector` cannot express safely: RE2 regex matching, `any_of`, and conjunctions that
     * repeat one of its single-valued constraints.
     */
    class Traversal(
        override val scopePackage: String,
        val predicate: NodePredicate,
    ) : CompiledSelector
}

/**
 * Turns a wire [Selector] into a [CompiledSelector], enforcing scope policy on top of the
 * structural validation shared with the host. Compilation never weakens a selector: anything
 * not representable by the chosen plan is an `INVALID_SELECTOR` [InvalidCommandException].
 *
 * The selector defaults are applied here, the one place they exist: an unset scope is the AUT,
 * `MATCH_UNSPECIFIED` is exact, and a `ResourceId.aut_package` resolves to [expectedAut].
 */
internal class SelectorCompiler(
    private val expectedAut: String,
    private val allowedSystemPackages: Set<String>,
) {
    fun compile(selector: Selector): CompiledSelector {
        val plan = CommandValidation.validateSelector(selector)
        val scopePackage = scopePackage(selector)
        if (selector.systemPackage == null) requireAutResources(selector.node)
        return when (plan) {
            SelectorPlanKind.NATIVE -> {
                CompiledSelector.Native(
                    scopePackage,
                    nativeSelector(selector.node).pkg(scopePackage),
                )
            }

            SelectorPlanKind.TRAVERSAL -> {
                CompiledSelector.Traversal(scopePackage, NodePredicate(selector.node, expectedAut))
            }
        }
    }

    fun scopePackage(selector: Selector): String {
        val packageName = selector.systemPackage ?: return expectedAut
        if (packageName !in allowedSystemPackages) {
            throw scopeDenied("System package $packageName is not on the driver allowlist")
        }
        return packageName
    }

    /** An AUT-scoped selector may only name resources of the AUT, at any nesting level. */
    private fun requireAutResources(node: Node) {
        if (node.kindCase == Node.KindCase.RESOURCE) {
            val packageName = node.resource.qualifyingPackage(expectedAut)
            if (packageName != null && packageName != expectedAut) {
                throw scopeDenied("AUT-scoped resource package $packageName does not match $expectedAut")
            }
        }
        node.children.forEach(::requireAutResources)
    }

    private fun scopeDenied(message: String): InvalidCommandException =
        InvalidCommandException(ErrorCode.ERR_INVALID_SELECTOR, ErrorDetail.SCOPE_DENIED, message)

    /** One `BySelector` for a conjunction; validation already ruled out anything it cannot hold. */
    private fun nativeSelector(node: Node): BySelector {
        val builder = ByBuilder()
        node.conjunction.forEach { operand ->
            when (operand.kindCase) {
                Node.KindCase.MATCH -> {
                    val match = operand.match
                    when (match.property) {
                        TextProperty.PROPERTY_TEXT -> {
                            builder.string(
                                match,
                                By::text,
                                BySelector::text,
                                By::textContains,
                                BySelector::textContains,
                                By::textStartsWith,
                                BySelector::textStartsWith,
                                By::textEndsWith,
                                BySelector::textEndsWith,
                            )
                        }

                        TextProperty.PROPERTY_CONTENT_DESCRIPTION -> {
                            builder.string(
                                match,
                                By::desc,
                                BySelector::desc,
                                By::descContains,
                                BySelector::descContains,
                                By::descStartsWith,
                                BySelector::descStartsWith,
                                By::descEndsWith,
                                BySelector::descEndsWith,
                            )
                        }

                        TextProperty.PROPERTY_HINT -> {
                            builder.string(
                                match,
                                By::hint,
                                BySelector::hint,
                                By::hintContains,
                                BySelector::hintContains,
                                By::hintStartsWith,
                                BySelector::hintStartsWith,
                                By::hintEndsWith,
                                BySelector::hintEndsWith,
                            )
                        }

                        TextProperty.PROPERTY_CLASS_NAME -> {
                            builder.className(match)
                        }

                        TextProperty.PROPERTY_UNSPECIFIED, TextProperty.UNRECOGNIZED, null -> {
                            error("validation rejects an unknown property")
                        }
                    }
                }

                Node.KindCase.FLAG -> {
                    val v = operand.flag.value
                    when (operand.flag.property) {
                        NodeFlag.FLAG_ENABLED -> builder.add({ By.enabled(v) }, { enabled(v) })
                        NodeFlag.FLAG_CHECKED -> builder.add({ By.checked(v) }, { checked(v) })
                        NodeFlag.FLAG_CHECKABLE -> builder.add({ By.checkable(v) }, { checkable(v) })
                        NodeFlag.FLAG_CLICKABLE -> builder.add({ By.clickable(v) }, { clickable(v) })
                        NodeFlag.FLAG_FOCUSED -> builder.add({ By.focused(v) }, { focused(v) })
                        NodeFlag.FLAG_FOCUSABLE -> builder.add({ By.focusable(v) }, { focusable(v) })
                        NodeFlag.FLAG_LONG_CLICKABLE -> builder.add({ By.longClickable(v) }, { longClickable(v) })
                        NodeFlag.FLAG_SCROLLABLE -> builder.add({ By.scrollable(v) }, { scrollable(v) })
                        NodeFlag.FLAG_SELECTED -> builder.add({ By.selected(v) }, { selected(v) })
                        NodeFlag.FLAG_UNSPECIFIED, NodeFlag.UNRECOGNIZED, null -> error("validation rejects an unknown flag")
                    }
                }

                Node.KindCase.RESOURCE -> {
                    val name = operand.resource.name
                    val packageName = operand.resource.qualifyingPackage(expectedAut)
                    // Both overloads quote the value internally; raw names are never treated as patterns.
                    if (packageName == null) {
                        builder.add({ By.res(name) }, { res(name) })
                    } else {
                        builder.add({ By.res(packageName, name) }, { res(packageName, name) })
                    }
                }

                Node.KindCase.RELATED -> {
                    val by = nativeSelector(operand.related.node)
                    when (operand.related.relation) {
                        Relation.RELATION_PARENT -> builder.add({ By.hasParent(by) }, { hasParent(by) })
                        Relation.RELATION_ANCESTOR -> builder.add({ By.hasAncestor(by) }, { hasAncestor(by) })
                        Relation.RELATION_CHILD -> builder.add({ By.hasChild(by) }, { hasChild(by) })
                        Relation.RELATION_DESCENDANT -> builder.add({ By.hasDescendant(by) }, { hasDescendant(by) })
                        Relation.RELATION_UNSPECIFIED, Relation.UNRECOGNIZED, null -> error("validation rejects an unknown relation")
                    }
                }

                Node.KindCase.ALL_OF -> {
                    error("conjunction is flattened")
                }

                Node.KindCase.ANY_OF -> {
                    error("any_of is not a native match")
                }

                Node.KindCase.KIND_NOT_SET, null -> {
                    error("validation rejects an empty node")
                }
            }
        }
        return builder.build()
    }

    /** `By` starts a selector and `BySelector` extends one; this hides the split. */
    private class ByBuilder {
        private var by: BySelector? = null

        fun add(
            first: () -> BySelector,
            next: BySelector.() -> BySelector,
        ) {
            by = by?.next() ?: first()
        }

        fun string(
            match: Match,
            exact: (String) -> BySelector,
            exactNext: BySelector.(String) -> BySelector,
            contains: (String) -> BySelector,
            containsNext: BySelector.(String) -> BySelector,
            startsWith: (String) -> BySelector,
            startsWithNext: BySelector.(String) -> BySelector,
            endsWith: (String) -> BySelector,
            endsWithNext: BySelector.(String) -> BySelector,
        ) {
            val value = match.value
            when (match.mode) {
                MatchMode.MATCH_EXACT, MatchMode.MATCH_UNSPECIFIED -> add({ exact(value) }, { exactNext(value) })
                MatchMode.MATCH_CONTAINS -> add({ contains(value) }, { containsNext(value) })
                MatchMode.MATCH_STARTS_WITH -> add({ startsWith(value) }, { startsWithNext(value) })
                MatchMode.MATCH_ENDS_WITH -> add({ endsWith(value) }, { endsWithNext(value) })
                MatchMode.MATCH_REGEX, MatchMode.UNRECOGNIZED, null -> error("REGEX is not a native match")
            }
        }

        /** `By.clazz` has no contains/startsWith overloads; build a quoted `java.util.regex` pattern. */
        fun className(match: Match) {
            val quoted =
                java.util.regex.Pattern
                    .quote(match.value)
            val pattern =
                when (match.mode) {
                    MatchMode.MATCH_EXACT, MatchMode.MATCH_UNSPECIFIED -> {
                        java.util.regex.Pattern
                            .compile(quoted)
                    }

                    MatchMode.MATCH_CONTAINS -> {
                        java.util.regex.Pattern
                            .compile(".*$quoted.*", java.util.regex.Pattern.DOTALL)
                    }

                    MatchMode.MATCH_STARTS_WITH -> {
                        java.util.regex.Pattern
                            .compile("$quoted.*", java.util.regex.Pattern.DOTALL)
                    }

                    MatchMode.MATCH_ENDS_WITH -> {
                        java.util.regex.Pattern
                            .compile(".*$quoted", java.util.regex.Pattern.DOTALL)
                    }

                    MatchMode.MATCH_REGEX, MatchMode.UNRECOGNIZED, null -> {
                        error("REGEX is not a native match")
                    }
                }
            add({ By.clazz(pattern) }, { clazz(pattern) })
        }

        fun build(): BySelector = requireNotNull(by) { "Empty selector node" }
    }
}

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
            private val value = match.value
            private val mode = match.mode
            private val regex: Pattern? = if (mode == MatchMode.MATCH_REGEX) CommandValidation.compileRegex(value) else null

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
                val text = actual?.toString() ?: return false
                return when (mode) {
                    MatchMode.MATCH_EXACT, MatchMode.MATCH_UNSPECIFIED -> text == value
                    MatchMode.MATCH_CONTAINS -> text.contains(value)
                    MatchMode.MATCH_STARTS_WITH -> text.startsWith(value)
                    MatchMode.MATCH_ENDS_WITH -> text.endsWith(value)
                    MatchMode.MATCH_REGEX -> requireNotNull(regex).matcher(text).matches()
                    MatchMode.UNRECOGNIZED, null -> false
                }
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
