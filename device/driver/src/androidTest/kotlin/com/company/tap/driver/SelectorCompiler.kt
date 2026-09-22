package com.company.tap.driver

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import com.company.tap.protocol.CommandValidation
import com.company.tap.protocol.InvalidSelectorException
import com.company.tap.protocol.MAX_SELECTOR_DEPTH
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.Node
import com.company.tap.protocol.NodeFlag
import com.company.tap.protocol.Relation
import com.company.tap.protocol.Scope
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SelectorPlanKind
import com.company.tap.protocol.SelectorScopeDeniedException
import com.company.tap.protocol.TextProperty
import com.google.re2j.Pattern

/**
 * A selector compiled for one command. [scopePackage] is the single package whose focused
 * window is searched; nothing outside it is ever matched.
 */
internal sealed interface CompiledSelector {
    val scopePackage: String

    /** Every predicate maps onto `BySelector`; UiAutomator evaluates it natively. */
    class Native(override val scopePackage: String, val by: BySelector) : CompiledSelector

    /**
     * Evaluated by walking the focused window's node tree. Only used for predicates
     * `BySelector` cannot express safely: RE2 regex matching, `any_of`, and conjunctions that
     * repeat one of its single-valued constraints.
     */
    class Traversal(override val scopePackage: String, val predicate: NodePredicate) : CompiledSelector
}

/**
 * Turns a wire [Selector] into a [CompiledSelector], enforcing scope policy on top of the
 * structural validation shared with the host. Compilation never weakens a selector: anything
 * not representable by the chosen plan is an [InvalidSelectorException].
 */
internal class SelectorCompiler(
    private val expectedAut: String,
    private val allowedSystemPackages: Set<String>,
) {
    fun compile(selector: Selector): CompiledSelector {
        val plan = CommandValidation.validateSelector(selector)
        val scopePackage = scopePackage(selector)
        if (selector.scope == Scope.Aut) requireAutResources(selector.node)
        return when (plan) {
            SelectorPlanKind.NATIVE -> CompiledSelector.Native(
                scopePackage,
                nativeSelector(selector.node).pkg(scopePackage),
            )
            SelectorPlanKind.TRAVERSAL -> CompiledSelector.Traversal(scopePackage, NodePredicate(selector.node))
        }
    }

    fun scopePackage(selector: Selector): String = when (val scope = selector.scope) {
        Scope.Aut -> expectedAut
        is Scope.System -> {
            if (scope.packageName !in allowedSystemPackages) {
                throw SelectorScopeDeniedException(
                    "System package ${scope.packageName} is not on the driver allowlist",
                )
            }
            scope.packageName
        }
    }

    /** An AUT-scoped selector may only name resources of the AUT, at any nesting level. */
    private fun requireAutResources(node: Node) {
        if (node is Node.Resource && node.packageName != null && node.packageName != expectedAut) {
            throw SelectorScopeDeniedException(
                "AUT-scoped resource package ${node.packageName} does not match $expectedAut",
            )
        }
        node.children.forEach(::requireAutResources)
    }

    /** One `BySelector` for a conjunction; validation already ruled out anything it cannot hold. */
    private fun nativeSelector(node: Node): BySelector {
        val builder = ByBuilder()
        node.conjunction.forEach { operand ->
            when (operand) {
                is Node.Match -> when (operand.property) {
                    TextProperty.TEXT -> builder.string(operand, By::text, BySelector::text, By::textContains, BySelector::textContains, By::textStartsWith, BySelector::textStartsWith, By::textEndsWith, BySelector::textEndsWith)
                    TextProperty.CONTENT_DESCRIPTION -> builder.string(operand, By::desc, BySelector::desc, By::descContains, BySelector::descContains, By::descStartsWith, BySelector::descStartsWith, By::descEndsWith, BySelector::descEndsWith)
                    TextProperty.HINT -> builder.string(operand, By::hint, BySelector::hint, By::hintContains, BySelector::hintContains, By::hintStartsWith, BySelector::hintStartsWith, By::hintEndsWith, BySelector::hintEndsWith)
                    TextProperty.CLASS_NAME -> builder.className(operand)
                }
                is Node.Flag -> {
                    val v = operand.value
                    when (operand.property) {
                        NodeFlag.ENABLED -> builder.add({ By.enabled(v) }, { enabled(v) })
                        NodeFlag.CHECKED -> builder.add({ By.checked(v) }, { checked(v) })
                        NodeFlag.CHECKABLE -> builder.add({ By.checkable(v) }, { checkable(v) })
                        NodeFlag.CLICKABLE -> builder.add({ By.clickable(v) }, { clickable(v) })
                        NodeFlag.FOCUSED -> builder.add({ By.focused(v) }, { focused(v) })
                        NodeFlag.FOCUSABLE -> builder.add({ By.focusable(v) }, { focusable(v) })
                        NodeFlag.LONG_CLICKABLE -> builder.add({ By.longClickable(v) }, { longClickable(v) })
                        NodeFlag.SCROLLABLE -> builder.add({ By.scrollable(v) }, { scrollable(v) })
                        NodeFlag.SELECTED -> builder.add({ By.selected(v) }, { selected(v) })
                    }
                }
                is Node.Resource -> {
                    val packageName = operand.packageName
                    // Both overloads quote the value internally; raw names are never treated as patterns.
                    if (packageName == null) builder.add({ By.res(operand.name) }, { res(operand.name) })
                    else builder.add({ By.res(packageName, operand.name) }, { res(packageName, operand.name) })
                }
                is Node.Related -> {
                    val by = nativeSelector(operand.node)
                    when (operand.relation) {
                        Relation.PARENT -> builder.add({ By.hasParent(by) }, { hasParent(by) })
                        Relation.ANCESTOR -> builder.add({ By.hasAncestor(by) }, { hasAncestor(by) })
                        Relation.CHILD -> builder.add({ By.hasChild(by) }, { hasChild(by) })
                        Relation.DESCENDANT -> builder.add({ By.hasDescendant(by) }, { hasDescendant(by) })
                    }
                }
                is Node.AllOf -> error("conjunction is flattened")
                is Node.AnyOf -> error("any_of is not a native match")
            }
        }
        return builder.build()
    }

    /** `By` starts a selector and `BySelector` extends one; this hides the split. */
    private class ByBuilder {
        private var by: BySelector? = null

        fun add(first: () -> BySelector, next: BySelector.() -> BySelector) {
            by = by?.next() ?: first()
        }

        fun string(
            match: Node.Match,
            exact: (String) -> BySelector, exactNext: BySelector.(String) -> BySelector,
            contains: (String) -> BySelector, containsNext: BySelector.(String) -> BySelector,
            startsWith: (String) -> BySelector, startsWithNext: BySelector.(String) -> BySelector,
            endsWith: (String) -> BySelector, endsWithNext: BySelector.(String) -> BySelector,
        ) {
            val value = match.value
            when (match.mode) {
                MatchMode.EXACT -> add({ exact(value) }, { exactNext(value) })
                MatchMode.CONTAINS -> add({ contains(value) }, { containsNext(value) })
                MatchMode.STARTS_WITH -> add({ startsWith(value) }, { startsWithNext(value) })
                MatchMode.ENDS_WITH -> add({ endsWith(value) }, { endsWithNext(value) })
                MatchMode.REGEX -> error("REGEX is not a native match")
            }
        }

        /** `By.clazz` has no contains/startsWith overloads; build a quoted `java.util.regex` pattern. */
        fun className(match: Node.Match) {
            val quoted = java.util.regex.Pattern.quote(match.value)
            val pattern = when (match.mode) {
                MatchMode.EXACT -> java.util.regex.Pattern.compile(quoted)
                MatchMode.CONTAINS -> java.util.regex.Pattern.compile(".*$quoted.*", java.util.regex.Pattern.DOTALL)
                MatchMode.STARTS_WITH -> java.util.regex.Pattern.compile("$quoted.*", java.util.regex.Pattern.DOTALL)
                MatchMode.ENDS_WITH -> java.util.regex.Pattern.compile(".*$quoted", java.util.regex.Pattern.DOTALL)
                MatchMode.REGEX -> error("REGEX is not a native match")
            }
            add({ By.clazz(pattern) }, { clazz(pattern) })
        }

        fun build(): BySelector = requireNotNull(by) { "Empty selector node" }
    }
}

/**
 * Traversal-plan predicate over one node. Reads each element's [AccessibilityNodeInfo] once per
 * evaluation, however many property predicates the tree holds; relations walk the live
 * `UiObject2` tree and recycle every intermediate object.
 */
internal class NodePredicate(node: Node) {
    private val root = Compiled.of(node)

    fun matches(element: UiObject2): Boolean = root.matches(element, element.accessibilityNodeInfo)

    private sealed interface Compiled {
        fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean

        class Text(private val property: TextProperty, match: Node.Match) : Compiled {
            private val value = match.value
            private val mode = match.mode
            private val regex: Pattern? = if (mode == MatchMode.REGEX) CommandValidation.compileRegex(value) else null

            override fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean {
                val actual: CharSequence? = when (property) {
                    TextProperty.TEXT -> info.text
                    TextProperty.CONTENT_DESCRIPTION -> info.contentDescription
                    TextProperty.HINT -> info.hintText
                    TextProperty.CLASS_NAME -> info.className
                }
                val text = actual?.toString() ?: return false
                return when (mode) {
                    MatchMode.EXACT -> text == value
                    MatchMode.CONTAINS -> text.contains(value)
                    MatchMode.STARTS_WITH -> text.startsWith(value)
                    MatchMode.ENDS_WITH -> text.endsWith(value)
                    MatchMode.REGEX -> requireNotNull(regex).matcher(text).matches()
                }
            }
        }

        class Flag(private val flag: Node.Flag) : Compiled {
            override fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean {
                val actual = when (flag.property) {
                    NodeFlag.ENABLED -> info.isEnabled
                    NodeFlag.CHECKED -> info.isChecked
                    NodeFlag.CHECKABLE -> info.isCheckable
                    NodeFlag.CLICKABLE -> info.isClickable
                    NodeFlag.FOCUSED -> info.isFocused
                    NodeFlag.FOCUSABLE -> info.isFocusable
                    NodeFlag.LONG_CLICKABLE -> info.isLongClickable
                    NodeFlag.SCROLLABLE -> info.isScrollable
                    NodeFlag.SELECTED -> info.isSelected
                }
                return actual == flag.value
            }
        }

        class Resource(resource: Node.Resource) : Compiled {
            private val expected = resource.packageName?.let { "$it:id/${resource.name}" } ?: resource.name

            override fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean =
                info.viewIdResourceName == expected
        }

        class Related(private val relation: Relation, private val predicate: NodePredicate) : Compiled {
            override fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean = when (relation) {
                Relation.PARENT -> element.parentMatches(predicate)
                Relation.ANCESTOR -> element.ancestorMatches(predicate)
                Relation.CHILD -> element.childMatches(predicate)
                Relation.DESCENDANT -> element.descendantMatches(predicate)
            }
        }

        class AllOf(private val operands: List<Compiled>) : Compiled {
            override fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean =
                operands.all { it.matches(element, info) }
        }

        class AnyOf(private val operands: List<Compiled>) : Compiled {
            override fun matches(element: UiObject2, info: AccessibilityNodeInfo): Boolean =
                operands.any { it.matches(element, info) }
        }

        companion object {
            fun of(node: Node): Compiled = when (node) {
                is Node.Match -> Text(node.property, node)
                is Node.Flag -> Flag(node)
                is Node.Resource -> Resource(node)
                is Node.Related -> Related(node.relation, NodePredicate(node.node))
                // Cheap property checks first so a relation walk only runs when they hold.
                is Node.AllOf -> AllOf(node.nodes.sortedBy { it is Node.Related }.map(::of))
                is Node.AnyOf -> AnyOf(node.nodes.sortedBy { it is Node.Related }.map(::of))
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
