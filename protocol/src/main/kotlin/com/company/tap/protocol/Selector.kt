package com.company.tap.protocol

import kotlinx.serialization.Serializable

/** Maximum nesting of relation nodes ([NodeSelector.parent], [NodeSelector.child], ...). */
const val MAX_SELECTOR_DEPTH = 32

/** Maximum total [NodeSelector] nodes in one [Selector], relations included. */
const val MAX_SELECTOR_NODES = 256

/** Maximum length of any matched string or regex source. */
const val MAX_SELECTOR_STRING_CHARS = 1_024

/**
 * String matching modes. All but [REGEX] are case-sensitive code-point comparisons and are
 * evaluated natively by UiAutomator. [REGEX] is full-string RE2 matching evaluated by the
 * driver's traversal plan; it is never handed to a `java.util.regex` backed AndroidX overload.
 */
enum class MatchMode {
    EXACT,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    REGEX,
}

@Serializable
data class StringMatch(
    val value: String,
    val mode: MatchMode = MatchMode.EXACT,
)

/**
 * Resource identity. With [packageName] this is an Android resource ID (`package:id/name`);
 * without it, it is a raw resource name such as a Compose `testTag` exposed through
 * `testTagsAsResourceId`. Both are literal values, never patterns.
 */
@Serializable
data class ResourceId(
    val name: String,
    val packageName: String? = null,
)

/**
 * One node predicate of the selector AST. Every non-null property must hold; a node with no
 * property is invalid. Relations are themselves node predicates: [parent] must match the direct
 * parent, [ancestor] any ancestor, [child] at least one direct child, [descendant] at least one
 * descendant. Sibling, nearest, OR, NOT, and nth-match are deliberately absent (plan §10).
 *
 * Package is not a node property: [Selector.scope] already confines every match to one package.
 */
@Serializable
data class NodeSelector(
    val text: StringMatch? = null,
    val contentDescription: StringMatch? = null,
    val hint: StringMatch? = null,
    val className: StringMatch? = null,
    val resource: ResourceId? = null,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val checkable: Boolean? = null,
    val clickable: Boolean? = null,
    val focused: Boolean? = null,
    val focusable: Boolean? = null,
    val longClickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val selected: Boolean? = null,
    val parent: NodeSelector? = null,
    val ancestor: NodeSelector? = null,
    val child: NodeSelector? = null,
    val descendant: NodeSelector? = null,
) {
    val stringProperties: List<Pair<String, StringMatch>>
        get() = listOfNotNull(
            text?.let { "text" to it },
            contentDescription?.let { "contentDescription" to it },
            hint?.let { "hint" to it },
            className?.let { "className" to it },
        )

    val booleanProperties: List<Pair<String, Boolean>>
        get() = listOfNotNull(
            enabled?.let { "enabled" to it },
            checked?.let { "checked" to it },
            checkable?.let { "checkable" to it },
            clickable?.let { "clickable" to it },
            focused?.let { "focused" to it },
            focusable?.let { "focusable" to it },
            longClickable?.let { "longClickable" to it },
            scrollable?.let { "scrollable" to it },
            selected?.let { "selected" to it },
        )

    val relations: List<Pair<String, NodeSelector>>
        get() = listOfNotNull(
            parent?.let { "parent" to it },
            ancestor?.let { "ancestor" to it },
            child?.let { "child" to it },
            descendant?.let { "descendant" to it },
        )

    val isEmpty: Boolean
        get() = stringProperties.isEmpty() && booleanProperties.isEmpty() &&
            resource == null && relations.isEmpty()
}

/**
 * How an action chooses among matches. Queries (`EXISTS`, `WAIT_VISIBLE`) ignore the limit;
 * actions and scroll containers apply it. [FIRST] and [AT] rely on the driver's result order,
 * which in v1 is accessibility traversal order within the single focused window of the scope
 * package; callers must opt in with [Selector.acceptAccessibilityOrder].
 */
enum class MatchLimit {
    EXACTLY_ONE,
    FIRST,
    AT,
}

@Serializable
data class Selector(
    val node: NodeSelector,
    val scope: TargetScope = TargetScope.AUT,
    /** Required for [TargetScope.SYSTEM]; must be on the driver's allowlist. */
    val scopePackage: String? = null,
    val limit: MatchLimit = MatchLimit.EXACTLY_ONE,
    /** Zero-based match index; required exactly when [limit] is [MatchLimit.AT]. */
    val index: Int? = null,
    val acceptAccessibilityOrder: Boolean = false,
) {
    companion object {
        fun text(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
            Selector(NodeSelector(text = StringMatch(value, mode)))

        fun contentDescription(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
            Selector(NodeSelector(contentDescription = StringMatch(value, mode)))

        fun rawResource(name: String): Selector =
            Selector(NodeSelector(resource = ResourceId(name)))

        fun androidResource(packageName: String, name: String): Selector =
            Selector(NodeSelector(resource = ResourceId(name, packageName)))
    }

    fun inSystemPackage(packageName: String): Selector =
        copy(scope = TargetScope.SYSTEM, scopePackage = packageName)

    fun first(): Selector = copy(limit = MatchLimit.FIRST, index = null, acceptAccessibilityOrder = true)

    fun at(index: Int): Selector = copy(limit = MatchLimit.AT, index = index, acceptAccessibilityOrder = true)
}
