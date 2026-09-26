@file:JvmName("Selectors")

package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.AnyOf
import io.github.noamcohen48.tap.api.v1.At
import io.github.noamcohen48.tap.api.v1.First
import io.github.noamcohen48.tap.api.v1.Flag
import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Related
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.ResourceId
import io.github.noamcohen48.tap.api.v1.SystemScope
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.api.v1.Selector as SelectorProto

/**
 * An immutable description of one accessibility node.
 *
 * Build one with the top-level functions ([text], [res], [desc], ...), narrow it with the
 * methods below and combine selectors with [and] / [or]; each call returns a new selector. Bind
 * it with [Device.element]. Matching happens on the device, scoped to the app under test, and
 * mutations require exactly one match.
 *
 * A [Selector] wraps the server API's own `tap.v1.Selector`, a small expression tree that the
 * server forwards to the driver unchanged, so anything built here is validated identically by
 * the server and the driver and rendered in exceptions. Building a selector performs no
 * I/O; each terminal action resolves it again.
 */
class Selector internal constructor(
    val proto: SelectorProto,
) {
    private val node: Node get() = proto.node

    private fun withNode(node: Node): Selector = Selector(proto.toBuilder().setNode(node).build())

    /** This selector's node, further constrained by [other]. */
    private fun also(other: Node): Selector = withNode(allOf(node, other))

    // --- Property refinements ---------------------------------------------------------------

    /** Also require this text ([mode]: exact, contains, starts with, ends with, RE2 regex). */
    fun andText(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Selector = also(match(TextProperty.PROPERTY_TEXT, value, mode))

    /** Also require this content description. */
    fun andDesc(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Selector = also(match(TextProperty.PROPERTY_CONTENT_DESCRIPTION, value, mode))

    /** Also require this widget class name. */
    fun andClassName(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Selector = also(match(TextProperty.PROPERTY_CLASS_NAME, value, mode))

    /** Also require this hint (empty text fields). */
    fun andHint(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Selector = also(match(TextProperty.PROPERTY_HINT, value, mode))

    /** Also require the resource id `packageName:id/name`. */
    fun andRes(
        packageName: String,
        name: String,
    ): Selector = also(resource(name, packageName))

    /** Also require the app-under-test resource id `name` (see [res]). */
    fun andRes(name: String): Selector = also(autResource(name))

    /** Require `isCheckable == value`. */
    fun checkable(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_CHECKABLE, value))

    /** Require `isChecked == value`. */
    fun checked(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_CHECKED, value))

    /** Require `isClickable == value`. */
    fun clickable(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_CLICKABLE, value))

    /** Require `isEnabled == value`. */
    fun enabled(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_ENABLED, value))

    /** Require `isFocusable == value`. */
    fun focusable(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_FOCUSABLE, value))

    /** Require `isFocused == value`. */
    fun focused(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_FOCUSED, value))

    /** Require `isLongClickable == value`. */
    fun longClickable(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_LONG_CLICKABLE, value))

    /** Require `isScrollable == value`. */
    fun scrollable(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_SCROLLABLE, value))

    /** Require `isSelected == value`. */
    fun selected(value: Boolean = true): Selector = also(flag(NodeFlag.FLAG_SELECTED, value))

    // --- Combinators ------------------------------------------------------------------------

    /**
     * Both must hold on the same node: `text("Add") and clickable()`. The result keeps this
     * selector's scope and match choice; [other] must not carry its own match choice
     * ([first]/[at]) or a different scope, because a node predicate cannot express either and
     * silently dropping them would target a different element.
     *
     * @throws IllegalArgumentException when [other] has a match choice or a different scope.
     */
    infix fun and(other: Selector): Selector = also(operand("and", other))

    /**
     * Either may hold: `text("Allow") or text("Allow only while using the app")`. Same operand
     * rules as [and]. Disjunctions are evaluated by the driver's tree walk rather than a native
     * UiAutomator lookup, which is a little slower on big screens.
     *
     * @throws IllegalArgumentException when [other] has a match choice or a different scope.
     */
    infix fun or(other: Selector): Selector = withNode(anyOf(node, operand("or", other)))

    // --- Relations ----------------------------------------------------------------------------
    // A related node is a bare predicate (`tap.v1.Related.node`), so the operand of every
    // `has*` relation follows the same rules as [and].

    /**
     * This element must have a descendant matching [other]: `rawRes("card").hasDescendant(text("Play"))`.
     *
     * @throws IllegalArgumentException when [other] has a match choice or a different scope.
     */
    fun hasDescendant(other: Selector): Selector = also(related(Relation.RELATION_DESCENDANT, operand("hasDescendant", other)))

    /**
     * Keep matching this node, but only when a direct child matches [other].
     *
     * @throws IllegalArgumentException when [other] has a match choice or a different scope.
     */
    fun hasChild(other: Selector): Selector = also(related(Relation.RELATION_CHILD, operand("hasChild", other)))

    /**
     * Keep matching this node, but only when its parent matches [other].
     *
     * @throws IllegalArgumentException when [other] has a match choice or a different scope.
     */
    fun hasParent(other: Selector): Selector = also(related(Relation.RELATION_PARENT, operand("hasParent", other)))

    /**
     * Keep matching this node, but only when an ancestor matches [other].
     *
     * @throws IllegalArgumentException when [other] has a match choice or a different scope.
     */
    fun hasAncestor(other: Selector): Selector = also(related(Relation.RELATION_ANCESTOR, operand("hasAncestor", other)))

    /**
     * An element matching [other] somewhere below this one. The result keeps [other]'s match
     * choice and this selector's scope. This selector must not carry a match choice itself:
     * the ancestor is a bare predicate, so `list.at(2).descendant(x)` would otherwise search
     * under every list. Narrow the ancestor with predicates instead, or pick among the results
     * (`list.descendant(x).at(2)`).
     *
     * @throws IllegalArgumentException when this selector has a match choice, or [other] has a
     *   scope different from this one.
     */
    fun descendant(other: Selector): Selector = relative("descendant", Relation.RELATION_ANCESTOR, other)

    /**
     * A direct child of this element matching [other]. Same rules as [descendant].
     *
     * @throws IllegalArgumentException when this selector has a match choice, or [other] has a
     *   scope different from this one.
     */
    fun child(other: Selector): Selector = relative("child", Relation.RELATION_PARENT, other)

    private fun relative(
        operation: String,
        relation: Relation,
        other: Selector,
    ): Selector {
        require(!hasPick) {
            "$operation: the receiver ${render()} has a match choice (first()/at()), which a relation cannot carry; " +
                "apply first()/at() to the result instead"
        }
        requireSameScope(operation, other)
        return Selector(
            other.proto
                .toBuilder()
                .setNode(allOf(other.node, related(relation, node)))
                .also(::copyScope)
                .build(),
        )
    }

    /** [other]'s node, after checking that composing it into this selector loses nothing. */
    private fun operand(
        operation: String,
        other: Selector,
    ): Node {
        require(!other.hasPick) {
            "$operation: the operand ${other.render()} has a match choice (first()/at()), which a node predicate cannot carry; " +
                "apply first()/at() to the combined selector instead"
        }
        requireSameScope(operation, other)
        return other.node
    }

    private fun requireSameScope(
        operation: String,
        other: Selector,
    ) {
        require(!other.hasExplicitScope || other.scopeKey == scopeKey) {
            "$operation: the operand ${other.render()} has a different scope than ${render()}; " +
                "set inSystemPackage(...) on the combined selector instead"
        }
    }

    /** True when a non-default match choice (`first`/`at`) is set; `exactly_one` is the default. */
    private val hasPick: Boolean
        get() = proto.pickCase == SelectorProto.PickCase.FIRST || proto.pickCase == SelectorProto.PickCase.AT

    /** True when a non-default scope (a system package) is set; `aut` is the default. */
    private val hasExplicitScope: Boolean get() = proto.scopeCase == SelectorProto.ScopeCase.SYSTEM

    /** The effective scope: null for the app under test, else the system package. */
    private val scopeKey: String? get() = if (hasExplicitScope) proto.system.packageName else null

    private fun copyScope(target: SelectorProto.Builder) {
        when (proto.scopeCase) {
            SelectorProto.ScopeCase.SYSTEM -> target.system = proto.system
            SelectorProto.ScopeCase.AUT, SelectorProto.ScopeCase.SCOPE_NOT_SET -> target.clearScope()
        }
    }

    // --- Scope and match choice ---------------------------------------------------------------

    /**
     * Allow this selector to match inside the allowlisted system package [packageName] (by
     * default only `com.google.android.permissioncontroller`) instead of the app under test.
     */
    fun inSystemPackage(packageName: String): Selector =
        Selector(proto.toBuilder().setSystem(SystemScope.newBuilder().setPackageName(packageName)).build())

    /** Accept the first match in accessibility order instead of requiring exactly one. */
    fun first(): Selector = Selector(proto.toBuilder().setFirst(First.getDefaultInstance()).build())

    /** Accept the [index]-th match (0-based) in accessibility order instead of requiring exactly one. */
    fun at(index: Int): Selector = Selector(proto.toBuilder().setAt(At.newBuilder().setIndex(index)).build())

    /**
     * The selector as one-line protobuf text, exactly the tree the device will see (for example
     * `node { match { property: PROPERTY_TEXT value: "OK" } }`); also used in exceptions.
     */
    fun render(): String = proto.renderText()

    override fun toString(): String = render()

    override fun equals(other: Any?): Boolean = other is Selector && other.proto == proto

    override fun hashCode(): Int = proto.hashCode()
}

// --- Node constructors (the proto tree, normalised) ------------------------------------------

private fun match(
    property: TextProperty,
    value: String,
    mode: MatchMode,
): Node =
    Node
        .newBuilder()
        .setMatch(
            Match
                .newBuilder()
                .setProperty(property)
                .setValue(value)
                .setMode(mode),
        ).build()

private fun flag(
    property: NodeFlag,
    value: Boolean,
): Node = Node.newBuilder().setFlag(Flag.newBuilder().setProperty(property).setValue(value)).build()

private fun resource(
    name: String,
    packageName: String? = null,
): Node = Node.newBuilder().setResource(ResourceId.newBuilder().setName(name).apply { packageName?.let(::setPackageName) }).build()

private fun autResource(name: String): Node =
    Node.newBuilder().setResource(ResourceId.newBuilder().setName(name).setAutPackage(true)).build()

private fun related(
    relation: Relation,
    node: Node,
): Node = Node.newBuilder().setRelated(Related.newBuilder().setRelation(relation).setNode(node)).build()

/** A conjunction with nested `all_of`s flattened, so refinements chain into one flat node. */
internal fun allOf(vararg nodes: Node): Node {
    val flat = nodes.flatMap { if (it.hasAllOf()) it.allOf.nodesList else listOf(it) }
    return flat.singleOrNull() ?: Node.newBuilder().setAllOf(AllOf.newBuilder().addAllNodes(flat)).build()
}

/** A disjunction with nested `any_of`s flattened. */
internal fun anyOf(vararg nodes: Node): Node {
    val flat = nodes.flatMap { if (it.hasAnyOf()) it.anyOf.nodesList else listOf(it) }
    return flat.singleOrNull() ?: Node.newBuilder().setAnyOf(AnyOf.newBuilder().addAllNodes(flat)).build()
}

private fun selector(node: Node): Selector = Selector(SelectorProto.newBuilder().setNode(node).build())

// --- Entry points -----------------------------------------------------------------------------

/** Visible text. Compose `Text` and View `TextView`/`Button` both expose this. */
fun text(
    value: String,
    mode: MatchMode = MatchMode.MATCH_EXACT,
): Selector = selector(match(TextProperty.PROPERTY_TEXT, value, mode))

/** Text containing [value]. */
fun textContains(value: String): Selector = text(value, MatchMode.MATCH_CONTAINS)

/** Text starting with [value]. */
fun textStartsWith(value: String): Selector = text(value, MatchMode.MATCH_STARTS_WITH)

/** Text fully matching the RE2 regular expression [re2] (linear time; no backreferences or lookaround). */
fun textMatches(re2: String): Selector = text(re2, MatchMode.MATCH_REGEX)

/** `contentDescription` (View) / `contentDescription` semantics (Compose). */
fun desc(
    value: String,
    mode: MatchMode = MatchMode.MATCH_EXACT,
): Selector = selector(match(TextProperty.PROPERTY_CONTENT_DESCRIPTION, value, mode))

/** Compose `testTag` projected through `testTagsAsResourceId`; never package-qualified. */
fun rawRes(name: String): Selector = selector(resource(name))

/** Android View resource id `packageName:id/name`. */
fun resId(
    packageName: String,
    name: String,
): Selector = selector(resource(name, packageName))

/**
 * View resource id `name` of the **app under test**: `<aut>:id/name`, with the package filled in
 * on the device from the session, so the same selector works on every device and role. Use
 * [resId] for another package (a system dialog with [Selector.inSystemPackage]).
 */
fun res(name: String): Selector = selector(autResource(name))

/** Widget class name, e.g. `android.widget.EditText`. */
fun className(
    value: String,
    mode: MatchMode = MatchMode.MATCH_EXACT,
): Selector = selector(match(TextProperty.PROPERTY_CLASS_NAME, value, mode))

/** Hint of an empty text field (an empty `EditText` shows its hint as its text). */
fun hint(
    value: String,
    mode: MatchMode = MatchMode.MATCH_EXACT,
): Selector = selector(match(TextProperty.PROPERTY_HINT, value, mode))

/** Any node with the given boolean property; combine with the `and*` builders. */
fun clickable(): Selector = selector(flag(NodeFlag.FLAG_CLICKABLE, true))

/** Any scrollable node; combine with the `and*` builders. */
fun scrollable(): Selector = selector(flag(NodeFlag.FLAG_SCROLLABLE, true))

/**
 * Any of the selectors may match: `anyOf(text("OK"), text("Allow"), desc("Accept"))`. Scope and
 * match choice come from [first]; the others follow the operand rules of [Selector.or].
 */
fun anyOf(
    first: Selector,
    vararg rest: Selector,
): Selector = rest.fold(first) { acc, next -> acc or next }

/**
 * All of the selectors must hold on one node. Scope and match choice come from [first]; the
 * others follow the operand rules of [Selector.and].
 */
fun allOf(
    first: Selector,
    vararg rest: Selector,
): Selector = rest.fold(first) { acc, next -> acc and next }
