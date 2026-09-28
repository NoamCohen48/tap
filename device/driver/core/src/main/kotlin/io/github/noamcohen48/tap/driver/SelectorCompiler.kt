package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.SelectorPlanKind
import io.github.noamcohen48.tap.protocol.children
import io.github.noamcohen48.tap.protocol.conjunction
import io.github.noamcohen48.tap.protocol.qualifyingPackage

/** Where a compiled selector searches. */
internal sealed interface SearchScope {
    /** The focused window of [packageName]; nothing outside it is ever matched. */
    data class FocusedWindow(val packageName: String) : SearchScope

    /** Every window on screen, of any package. */
    data object AllWindows : SearchScope
}

/**
 * A selector compiled for one command, once per request and reused by every poll of it.
 * [scope] says which windows are searched; [pick] says which match an action targets.
 */
internal sealed interface CompiledSelector {
    val scope: SearchScope
    val pick: SelectorPick

    /** Every predicate maps onto `BySelector`; UiAutomator evaluates it natively. */
    class Native(
        override val scope: SearchScope,
        override val pick: SelectorPick,
        val by: BySelector,
    ) : CompiledSelector

    /**
     * Evaluated by walking the searched windows' node trees. Only used for predicates
     * `BySelector` cannot express safely: RE2 regex matching, `any_of`, and conjunctions that
     * repeat one of its single-valued constraints.
     */
    class Traversal(
        override val scope: SearchScope,
        override val pick: SelectorPick,
        val predicate: NodePredicate,
    ) : CompiledSelector
}

/**
 * Turns a wire [Selector] into a [CompiledSelector] on top of the structural validation shared
 * with the host. Compilation never weakens a selector: anything not representable by the
 * chosen plan is an `INVALID_SELECTOR` [InvalidCommandException].
 *
 * The selector defaults are applied here, the one place they exist: an unset scope is the AUT,
 * `MATCH_UNSPECIFIED` is exact, and a `ResourceId.aut_package` resolves to [expectedAut].
 */
internal class SelectorCompiler(
    private val expectedAut: String,
) {
    fun compile(selector: Selector): CompiledSelector {
        val plan = CommandValidation.validateSelector(selector)
        val scope = scope(selector)
        val pick = SelectorPick.of(selector)
        return when (plan) {
            SelectorPlanKind.NATIVE -> {
                val by = nativeSelector(selector.node)
                CompiledSelector.Native(
                    scope,
                    pick,
                    if (scope is SearchScope.FocusedWindow) by.pkg(scope.packageName) else by,
                )
            }

            SelectorPlanKind.TRAVERSAL -> {
                CompiledSelector.Traversal(scope, pick, NodePredicate(selector.node, expectedAut))
            }
        }
    }

    fun scope(selector: Selector): SearchScope =
        when (selector.scopeCase) {
            Selector.ScopeCase.SYSTEM -> {
                SearchScope.FocusedWindow(selector.system.packageName)
            }

            Selector.ScopeCase.ANY_WINDOW -> {
                SearchScope.AllWindows
            }

            Selector.ScopeCase.AUT, Selector.ScopeCase.SCOPE_NOT_SET, null -> {
                requireAutResources(selector.node)
                SearchScope.FocusedWindow(expectedAut)
            }
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
