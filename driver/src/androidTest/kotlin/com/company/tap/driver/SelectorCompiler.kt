package com.company.tap.driver

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.InvalidSelectorException
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.NodeSelector
import com.company.tap.protocol.ResourceId
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SelectorPlanKind
import com.company.tap.protocol.SelectorValidation
import com.company.tap.protocol.StringMatch
import com.company.tap.protocol.TargetScope
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
     * `BySelector` cannot express safely, currently RE2 regex matching.
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
        val plan = SelectorValidation.validate(selector)
        val scopePackage = scopePackage(selector)
        if (selector.scope == TargetScope.AUT) requireAutResources(selector.node)
        return when (plan) {
            SelectorPlanKind.NATIVE -> CompiledSelector.Native(
                scopePackage,
                nativeSelector(selector.node).pkg(scopePackage),
            )
            SelectorPlanKind.TRAVERSAL -> CompiledSelector.Traversal(scopePackage, NodePredicate(selector.node))
        }
    }

    fun scopePackage(selector: Selector): String = when (selector.scope) {
        TargetScope.AUT -> expectedAut
        TargetScope.SYSTEM -> {
            val scopePackage = requireNotNull(selector.scopePackage)
            if (scopePackage !in allowedSystemPackages) {
                throw InvalidSelectorException(
                    ErrorDetail.SCOPE_DENIED,
                    "System package $scopePackage is not on the driver allowlist",
                )
            }
            scopePackage
        }
    }

    /** An AUT-scoped selector may only name resources of the AUT, at any nesting level. */
    private fun requireAutResources(node: NodeSelector) {
        val packageName = node.resource?.packageName
        if (packageName != null && packageName != expectedAut) {
            throw InvalidSelectorException(
                ErrorDetail.SCOPE_DENIED,
                "AUT-scoped resource package $packageName does not match $expectedAut",
            )
        }
        node.relations.forEach { (_, related) -> requireAutResources(related) }
    }

    private fun nativeSelector(node: NodeSelector): BySelector {
        val builder = ByBuilder()
        node.text?.let { builder.string(it, By::text, BySelector::text, By::textContains, BySelector::textContains, By::textStartsWith, BySelector::textStartsWith, By::textEndsWith, BySelector::textEndsWith) }
        node.contentDescription?.let { builder.string(it, By::desc, BySelector::desc, By::descContains, BySelector::descContains, By::descStartsWith, BySelector::descStartsWith, By::descEndsWith, BySelector::descEndsWith) }
        node.hint?.let { builder.string(it, By::hint, BySelector::hint, By::hintContains, BySelector::hintContains, By::hintStartsWith, BySelector::hintStartsWith, By::hintEndsWith, BySelector::hintEndsWith) }
        node.className?.let { builder.className(it) }
        node.resource?.let { resource ->
            val packageName = resource.packageName
            // Both overloads quote the value internally; raw names are never treated as patterns.
            if (packageName == null) builder.add({ By.res(resource.name) }, { res(resource.name) })
            else builder.add({ By.res(packageName, resource.name) }, { res(packageName, resource.name) })
        }
        node.enabled?.let { v -> builder.add({ By.enabled(v) }, { enabled(v) }) }
        node.checked?.let { v -> builder.add({ By.checked(v) }, { checked(v) }) }
        node.checkable?.let { v -> builder.add({ By.checkable(v) }, { checkable(v) }) }
        node.clickable?.let { v -> builder.add({ By.clickable(v) }, { clickable(v) }) }
        node.focused?.let { v -> builder.add({ By.focused(v) }, { focused(v) }) }
        node.focusable?.let { v -> builder.add({ By.focusable(v) }, { focusable(v) }) }
        node.longClickable?.let { v -> builder.add({ By.longClickable(v) }, { longClickable(v) }) }
        node.scrollable?.let { v -> builder.add({ By.scrollable(v) }, { scrollable(v) }) }
        node.selected?.let { v -> builder.add({ By.selected(v) }, { selected(v) }) }
        node.parent?.let { related -> val by = nativeSelector(related); builder.add({ By.hasParent(by) }, { hasParent(by) }) }
        node.ancestor?.let { related -> val by = nativeSelector(related); builder.add({ By.hasAncestor(by) }, { hasAncestor(by) }) }
        node.child?.let { related -> val by = nativeSelector(related); builder.add({ By.hasChild(by) }, { hasChild(by) }) }
        node.descendant?.let { related -> val by = nativeSelector(related); builder.add({ By.hasDescendant(by) }, { hasDescendant(by) }) }
        return builder.build()
    }

    /** `By` starts a selector and `BySelector` extends one; this hides the split. */
    private class ByBuilder {
        private var by: BySelector? = null

        fun add(first: () -> BySelector, next: BySelector.() -> BySelector) {
            by = by?.next() ?: first()
        }

        fun string(
            match: StringMatch,
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
        fun className(match: StringMatch) {
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
 * Traversal-plan predicate over one node. Reads each node's [AccessibilityNodeInfo] once per
 * evaluation; relations walk the live `UiObject2` tree and recycle every intermediate object.
 */
internal class NodePredicate(private val node: NodeSelector) {
    private val text = node.text?.let(::StringPredicate)
    private val contentDescription = node.contentDescription?.let(::StringPredicate)
    private val hint = node.hint?.let(::StringPredicate)
    private val className = node.className?.let(::StringPredicate)
    private val parent = node.parent?.let(::NodePredicate)
    private val ancestor = node.ancestor?.let(::NodePredicate)
    private val child = node.child?.let(::NodePredicate)
    private val descendant = node.descendant?.let(::NodePredicate)

    fun matches(element: UiObject2): Boolean {
        val info = element.accessibilityNodeInfo
        if (!matchesProperties(info)) return false
        if (parent != null && !element.parentMatches(parent)) return false
        if (ancestor != null && !element.ancestorMatches(ancestor)) return false
        if (child != null && !element.childMatches(child)) return false
        if (descendant != null && !element.descendantMatches(descendant)) return false
        return true
    }

    private fun matchesProperties(info: AccessibilityNodeInfo): Boolean {
        if (text != null && !text.matches(info.text)) return false
        if (contentDescription != null && !contentDescription.matches(info.contentDescription)) return false
        if (hint != null && !hint.matches(info.hintText)) return false
        if (className != null && !className.matches(info.className)) return false
        node.resource?.let { if (!matchesResource(it, info.viewIdResourceName)) return false }
        node.enabled?.let { if (info.isEnabled != it) return false }
        node.checked?.let { if (info.isChecked != it) return false }
        node.checkable?.let { if (info.isCheckable != it) return false }
        node.clickable?.let { if (info.isClickable != it) return false }
        node.focused?.let { if (info.isFocused != it) return false }
        node.focusable?.let { if (info.isFocusable != it) return false }
        node.longClickable?.let { if (info.isLongClickable != it) return false }
        node.scrollable?.let { if (info.isScrollable != it) return false }
        node.selected?.let { if (info.isSelected != it) return false }
        return true
    }

    private fun matchesResource(resource: ResourceId, actual: String?): Boolean {
        val expected = resource.packageName?.let { "$it:id/${resource.name}" } ?: resource.name
        return actual == expected
    }

    private fun UiObject2.parentMatches(predicate: NodePredicate): Boolean {
        val parent = parent ?: return false
        return try {
            predicate.matches(parent)
        } finally {
            parent.recycle()
        }
    }

    private fun UiObject2.ancestorMatches(predicate: NodePredicate): Boolean {
        var current = parent
        var depth = 0
        while (current != null && depth++ < com.company.tap.protocol.MAX_SELECTOR_DEPTH) {
            val matched = predicate.matches(current)
            val next = if (matched) null else current.parent
            current.recycle()
            if (matched) return true
            current = next
        }
        current?.recycle()
        return false
    }

    private fun UiObject2.childMatches(predicate: NodePredicate): Boolean {
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

    private fun UiObject2.descendantMatches(predicate: NodePredicate): Boolean {
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

    private class StringPredicate(match: StringMatch) {
        private val value = match.value
        private val mode = match.mode
        private val regex: Pattern? = if (mode == MatchMode.REGEX) SelectorValidation.compileRegex(value) else null

        fun matches(actual: CharSequence?): Boolean {
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
}
