@file:JvmName("Selectors")

package com.company.tap.sdk

import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.NodeSelector
import com.company.tap.protocol.ResourceId
import com.company.tap.protocol.Selector
import com.company.tap.protocol.StringMatch

/*
 * Selector DSL. Values are the protocol's own [Selector] AST, so anything built here is
 * validated identically on host and driver and rendered in exceptions. Building a selector
 * performs no I/O; each terminal action resolves it again.
 */

/** Visible text. Compose `Text` and View `TextView`/`Button` both expose this. */
fun text(value: String, mode: MatchMode = MatchMode.EXACT): Selector = Selector.text(value, mode)

fun textContains(value: String): Selector = text(value, MatchMode.CONTAINS)
fun textStartsWith(value: String): Selector = text(value, MatchMode.STARTS_WITH)
fun textMatches(re2: String): Selector = text(re2, MatchMode.REGEX)

/** `contentDescription` (View) / `contentDescription` semantics (Compose). */
fun desc(value: String, mode: MatchMode = MatchMode.EXACT): Selector = Selector.contentDescription(value, mode)

/** Compose `testTag` projected through `testTagsAsResourceId`; never package-qualified. */
fun rawRes(name: String): Selector = Selector.rawResource(name)

/** Android View resource id `packageName:id/name`. */
fun resId(packageName: String, name: String): Selector = Selector.androidResource(packageName, name)

fun className(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
    Selector(NodeSelector(className = StringMatch(value, mode)))

fun hint(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
    Selector(NodeSelector(hint = StringMatch(value, mode)))

/** Any node with the given boolean property; combine with the `and*` builders below. */
fun clickable(): Selector = Selector(NodeSelector(clickable = true))
fun scrollable(): Selector = Selector(NodeSelector(scrollable = true))

// --- Property refinements on an existing selector -------------------------------------------

fun Selector.andText(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
    node { copy(text = StringMatch(value, mode)) }
fun Selector.andDesc(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
    node { copy(contentDescription = StringMatch(value, mode)) }
fun Selector.andClassName(value: String, mode: MatchMode = MatchMode.EXACT): Selector =
    node { copy(className = StringMatch(value, mode)) }
fun Selector.andRes(packageName: String, name: String): Selector =
    node { copy(resource = ResourceId(name, packageName)) }
fun Selector.checkable(value: Boolean = true): Selector = node { copy(checkable = value) }
fun Selector.checked(value: Boolean = true): Selector = node { copy(checked = value) }
fun Selector.clickable(value: Boolean = true): Selector = node { copy(clickable = value) }
fun Selector.enabled(value: Boolean = true): Selector = node { copy(enabled = value) }
fun Selector.focusable(value: Boolean = true): Selector = node { copy(focusable = value) }
fun Selector.focused(value: Boolean = true): Selector = node { copy(focused = value) }
fun Selector.longClickable(value: Boolean = true): Selector = node { copy(longClickable = value) }
fun Selector.scrollable(value: Boolean = true): Selector = node { copy(scrollable = value) }
fun Selector.selected(value: Boolean = true): Selector = node { copy(selected = value) }

// --- Relations --------------------------------------------------------------------------------

/** This element must have a descendant matching [other]: `rawRes("card").hasDescendant(text("Play"))`. */
fun Selector.hasDescendant(other: Selector): Selector = node { copy(descendant = other.node) }
fun Selector.hasChild(other: Selector): Selector = node { copy(child = other.node) }
fun Selector.hasParent(other: Selector): Selector = node { copy(parent = other.node) }
fun Selector.hasAncestor(other: Selector): Selector = node { copy(ancestor = other.node) }

/** An element matching [other] somewhere below this one: `card.descendant(text("Play"))`. */
fun Selector.descendant(other: Selector): Selector = other.copy(
    node = other.node.copy(ancestor = node),
    scope = scope,
    scopePackage = scopePackage,
)

/** A direct child of this element matching [other]. */
fun Selector.child(other: Selector): Selector = other.copy(
    node = other.node.copy(parent = node),
    scope = scope,
    scopePackage = scopePackage,
)

private inline fun Selector.node(transform: NodeSelector.() -> NodeSelector): Selector =
    copy(node = node.transform())
