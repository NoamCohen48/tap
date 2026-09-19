@file:JvmName("Selectors")

package com.company.tap.sdk

import com.company.tap.api.v1.MatchLimit
import com.company.tap.api.v1.MatchMode
import com.company.tap.api.v1.NodeSelector
import com.company.tap.api.v1.ResourceId
import com.company.tap.api.v1.StringMatch
import com.company.tap.api.v1.TargetScope
import com.google.protobuf.TextFormat
import com.company.tap.api.v1.Selector as SelectorProto

/*
 * Selector DSL. A [Selector] wraps the service API's own `tap.v1.Selector`, which mirrors the
 * device protocol's AST one-to-one, so anything built here is validated identically by the
 * service and the driver and rendered in exceptions. Building a selector performs no I/O;
 * each terminal action resolves it again.
 */

class Selector internal constructor(val proto: SelectorProto) {

    private fun node(transform: NodeSelector.Builder.() -> Unit): Selector =
        Selector(proto.toBuilder().apply { nodeBuilder.transform() }.build())

    // --- Property refinements ---------------------------------------------------------------

    fun andText(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = node { text = match(value, mode) }
    fun andDesc(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = node { contentDescription = match(value, mode) }
    fun andClassName(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = node { className = match(value, mode) }
    fun andHint(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = node { hint = match(value, mode) }
    fun andRes(packageName: String, name: String): Selector =
        node { resource = ResourceId.newBuilder().setName(name).setPackageName(packageName).build() }

    fun checkable(value: Boolean = true): Selector = node { checkable = value }
    fun checked(value: Boolean = true): Selector = node { checked = value }
    fun clickable(value: Boolean = true): Selector = node { clickable = value }
    fun enabled(value: Boolean = true): Selector = node { enabled = value }
    fun focusable(value: Boolean = true): Selector = node { focusable = value }
    fun focused(value: Boolean = true): Selector = node { focused = value }
    fun longClickable(value: Boolean = true): Selector = node { longClickable = value }
    fun scrollable(value: Boolean = true): Selector = node { scrollable = value }
    fun selected(value: Boolean = true): Selector = node { selected = value }

    // --- Relations ----------------------------------------------------------------------------

    /** This element must have a descendant matching [other]: `rawRes("card").hasDescendant(text("Play"))`. */
    fun hasDescendant(other: Selector): Selector = node { descendant = other.proto.node }
    fun hasChild(other: Selector): Selector = node { child = other.proto.node }
    fun hasParent(other: Selector): Selector = node { parent = other.proto.node }
    fun hasAncestor(other: Selector): Selector = node { ancestor = other.proto.node }

    /** An element matching [other] somewhere below this one, in this selector's scope. */
    fun descendant(other: Selector): Selector = Selector(
        other.proto.toBuilder().apply {
            nodeBuilder.ancestor = proto.node
            copyScope(this)
        }.build(),
    )

    /** A direct child of this element matching [other]. */
    fun child(other: Selector): Selector = Selector(
        other.proto.toBuilder().apply {
            nodeBuilder.parent = proto.node
            copyScope(this)
        }.build(),
    )

    private fun copyScope(target: SelectorProto.Builder) {
        target.scope = proto.scope
        if (proto.hasScopePackage()) target.scopePackage = proto.scopePackage else target.clearScopePackage()
    }

    // --- Scope and match limit ------------------------------------------------------------------

    fun inSystemPackage(packageName: String): Selector =
        Selector(proto.toBuilder().setScope(TargetScope.SCOPE_SYSTEM).setScopePackage(packageName).build())

    /** Accept the first match in accessibility order instead of requiring exactly one. */
    fun first(): Selector =
        Selector(proto.toBuilder().setLimit(MatchLimit.LIMIT_FIRST).clearIndex().setAcceptAccessibilityOrder(true).build())

    fun at(index: Int): Selector =
        Selector(proto.toBuilder().setLimit(MatchLimit.LIMIT_AT).setIndex(index).setAcceptAccessibilityOrder(true).build())

    fun render(): String = TextFormat.printer().shortDebugString(proto)

    override fun toString(): String = render()
    override fun equals(other: Any?): Boolean = other is Selector && other.proto == proto
    override fun hashCode(): Int = proto.hashCode()
}

private fun match(value: String, mode: MatchMode): StringMatch =
    StringMatch.newBuilder().setValue(value).setMode(mode).build()

private fun selector(build: NodeSelector.Builder.() -> Unit): Selector =
    Selector(SelectorProto.newBuilder().setNode(NodeSelector.newBuilder().apply(build)).build())

/** Visible text. Compose `Text` and View `TextView`/`Button` both expose this. */
fun text(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = selector { text = match(value, mode) }

fun textContains(value: String): Selector = text(value, MatchMode.MATCH_CONTAINS)
fun textStartsWith(value: String): Selector = text(value, MatchMode.MATCH_STARTS_WITH)
fun textMatches(re2: String): Selector = text(re2, MatchMode.MATCH_REGEX)

/** `contentDescription` (View) / `contentDescription` semantics (Compose). */
fun desc(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = selector { contentDescription = match(value, mode) }

/** Compose `testTag` projected through `testTagsAsResourceId`; never package-qualified. */
fun rawRes(name: String): Selector = selector { resource = ResourceId.newBuilder().setName(name).build() }

/** Android View resource id `packageName:id/name`. */
fun resId(packageName: String, name: String): Selector =
    selector { resource = ResourceId.newBuilder().setName(name).setPackageName(packageName).build() }

fun className(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = selector { className = match(value, mode) }
fun hint(value: String, mode: MatchMode = MatchMode.MATCH_EXACT): Selector = selector { hint = match(value, mode) }

/** Any node with the given boolean property; combine with the `and*` builders. */
fun clickable(): Selector = selector { clickable = true }
fun scrollable(): Selector = selector { scrollable = true }
