package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.AnyOf
import io.github.noamcohen48.tap.api.v1.AnyWindowScope
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
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.SystemScope
import io.github.noamcohen48.tap.api.v1.TextProperty

/** Maximum nesting of [Node]s (related, all_of, any_of). */
const val MAX_SELECTOR_DEPTH = 32

/** Maximum total [Node]s in one [Selector], relations and combinators included. */
const val MAX_SELECTOR_NODES = 256

/** Maximum length of any matched string or regex source. */
const val MAX_SELECTOR_STRING_CHARS = 1_024

/** Builders for selector nodes, used by host code and tests (clients have their own DSLs). */
object Nodes {
    fun match(
        property: TextProperty,
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Node = Node.newBuilder().setMatch(Match.newBuilder().setProperty(property).setValue(value).setMode(mode)).build()

    fun text(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Node = match(TextProperty.PROPERTY_TEXT, value, mode)

    fun contentDescription(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Node = match(TextProperty.PROPERTY_CONTENT_DESCRIPTION, value, mode)

    fun hint(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Node = match(TextProperty.PROPERTY_HINT, value, mode)

    fun className(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Node = match(TextProperty.PROPERTY_CLASS_NAME, value, mode)

    fun flag(
        property: NodeFlag,
        value: Boolean = true,
    ): Node = Node.newBuilder().setFlag(Flag.newBuilder().setProperty(property).setValue(value)).build()

    /** A raw resource name (a Compose testTag under testTagsAsResourceId). */
    fun rawResource(name: String): Node = Node.newBuilder().setResource(ResourceId.newBuilder().setName(name)).build()

    /** The View id `packageName:id/name`. */
    fun androidResource(
        packageName: String,
        name: String,
    ): Node = Node.newBuilder().setResource(ResourceId.newBuilder().setName(name).setPackageName(packageName)).build()

    /** The View id `<app under test>:id/name`; the driver fills in the session's AUT. */
    fun autResource(name: String): Node =
        Node.newBuilder().setResource(ResourceId.newBuilder().setName(name).setAutPackage(true)).build()

    fun related(
        relation: Relation,
        node: Node,
    ): Node = Node.newBuilder().setRelated(Related.newBuilder().setRelation(relation).setNode(node)).build()

    fun parent(node: Node): Node = related(Relation.RELATION_PARENT, node)

    fun ancestor(node: Node): Node = related(Relation.RELATION_ANCESTOR, node)

    fun child(node: Node): Node = related(Relation.RELATION_CHILD, node)

    fun descendant(node: Node): Node = related(Relation.RELATION_DESCENDANT, node)

    /** A conjunction, normalised: nested all_of flattened, a single operand returned as is. */
    fun allOf(nodes: List<Node>): Node =
        combine(nodes, Node.KindCase.ALL_OF) { Node.newBuilder().setAllOf(AllOf.newBuilder().addAllNodes(it)).build() }

    fun allOf(vararg nodes: Node): Node = allOf(nodes.toList())

    /** A disjunction, normalised: nested any_of flattened, a single operand returned as is. */
    fun anyOf(nodes: List<Node>): Node =
        combine(nodes, Node.KindCase.ANY_OF) { Node.newBuilder().setAnyOf(AnyOf.newBuilder().addAllNodes(it)).build() }

    fun anyOf(vararg nodes: Node): Node = anyOf(nodes.toList())

    private inline fun combine(
        nodes: List<Node>,
        kind: Node.KindCase,
        build: (List<Node>) -> Node,
    ): Node {
        require(nodes.isNotEmpty()) { "A combinator needs at least one node" }
        val flat = nodes.flatMap { if (it.kindCase == kind) it.children else listOf(it) }
        return flat.singleOrNull() ?: build(flat)
    }
}

/** Both operands must hold; see [Nodes.allOf]. */
infix fun Node.and(other: Node): Node = Nodes.allOf(this, other)

/** Either operand must hold; see [Nodes.anyOf]. */
infix fun Node.or(other: Node): Node = Nodes.anyOf(this, other)

/** The direct sub-nodes of this node. */
val Node.children: List<Node>
    get() =
        when (kindCase) {
            Node.KindCase.RELATED -> listOf(related.node)
            Node.KindCase.ALL_OF -> allOf.nodesList
            Node.KindCase.ANY_OF -> anyOf.nodesList
            Node.KindCase.MATCH, Node.KindCase.FLAG, Node.KindCase.RESOURCE, Node.KindCase.KIND_NOT_SET, null -> emptyList()
        }

/** The operands of this node as one conjunction: nested all_of flattened, anything else itself. */
val Node.conjunction: List<Node>
    get() = if (kindCase == Node.KindCase.ALL_OF) allOf.nodesList.flatMap { it.conjunction } else listOf(this)

/** Selector construction; the scope defaults to the AUT and the pick to exactly one. */
object Selectors {
    fun of(node: Node): Selector = Selector.newBuilder().setNode(node).build()

    fun text(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Selector = of(Nodes.text(value, mode))

    fun contentDescription(
        value: String,
        mode: MatchMode = MatchMode.MATCH_EXACT,
    ): Selector = of(Nodes.contentDescription(value, mode))

    fun rawResource(name: String): Selector = of(Nodes.rawResource(name))

    fun androidResource(
        packageName: String,
        name: String,
    ): Selector = of(Nodes.androidResource(packageName, name))
}

fun Node.toSelector(): Selector = Selectors.of(this)

/** Search the focused window of [packageName] (any package) instead of the AUT's. */
fun Selector.inPackage(packageName: String): Selector =
    toBuilder().setSystem(SystemScope.newBuilder().setPackageName(packageName)).build()

/** Search every window on screen, of any package. */
fun Selector.inAnyWindow(): Selector = toBuilder().setAnyWindow(AnyWindowScope.getDefaultInstance()).build()

fun Selector.pickFirst(): Selector = toBuilder().setFirst(First.getDefaultInstance()).build()

fun Selector.pickAt(index: Int): Selector = toBuilder().setAt(At.newBuilder().setIndex(index)).build()

/** The package this selector is scoped to (`inPackage`), or null for the AUT (the default) or any window. */
val Selector.scopedPackage: String? get() = if (scopeCase == Selector.ScopeCase.SYSTEM) system.packageName else null

/** The package a resource id is qualified with, `null` for a raw resource name. */
fun ResourceId.qualifyingPackage(autPackage: String): String? =
    when {
        this.autPackage -> autPackage
        hasPackageName() -> packageName
        else -> null
    }

/** A compact, stable rendering for messages and logs, e.g. `text="OK" & parent(class~"List")`. */
fun Selector.render(): String {
    val base = node.render()
    val scope =
        when (scopeCase) {
            Selector.ScopeCase.SYSTEM -> " in ${system.packageName}"
            Selector.ScopeCase.ANY_WINDOW -> " in any window"
            Selector.ScopeCase.AUT, Selector.ScopeCase.SCOPE_NOT_SET, null -> ""
        }
    val pick =
        when (pickCase) {
            Selector.PickCase.FIRST -> " [first]"
            Selector.PickCase.AT -> " [${at.index}]"
            Selector.PickCase.EXACTLY_ONE, Selector.PickCase.PICK_NOT_SET, null -> ""
        }
    return base + scope + pick
}

fun Node.render(): String =
    when (kindCase) {
        Node.KindCase.MATCH -> {
            val name =
                when (match.property) {
                    TextProperty.PROPERTY_TEXT -> "text"
                    TextProperty.PROPERTY_CONTENT_DESCRIPTION -> "desc"
                    TextProperty.PROPERTY_HINT -> "hint"
                    TextProperty.PROPERTY_CLASS_NAME -> "class"
                    TextProperty.PROPERTY_UNSPECIFIED, TextProperty.UNRECOGNIZED, null -> "?"
                }
            val op =
                when (match.mode) {
                    MatchMode.MATCH_CONTAINS -> "*="
                    MatchMode.MATCH_STARTS_WITH -> "^="
                    MatchMode.MATCH_ENDS_WITH -> "$="
                    MatchMode.MATCH_REGEX -> "~="
                    MatchMode.MATCH_EXACT, MatchMode.MATCH_UNSPECIFIED, MatchMode.UNRECOGNIZED, null -> "="
                }
            "$name$op${quote(match.value)}"
        }

        Node.KindCase.FLAG -> {
            val name = flag.property.name.removePrefix("FLAG_").lowercase()
            if (flag.value) name else "!$name"
        }

        Node.KindCase.RESOURCE -> {
            val qualifier =
                when {
                    resource.autPackage -> "<aut>:id/"
                    resource.hasPackageName() -> "${resource.packageName}:id/"
                    else -> ""
                }
            "id=${quote(qualifier + resource.name)}"
        }

        Node.KindCase.RELATED -> "${related.relation.name.removePrefix("RELATION_").lowercase()}(${related.node.render()})"
        Node.KindCase.ALL_OF -> allOf.nodesList.joinToString(" & ") { it.renderOperand() }
        Node.KindCase.ANY_OF -> anyOf.nodesList.joinToString(" | ") { it.renderOperand() }
        Node.KindCase.KIND_NOT_SET, null -> "<empty>"
    }

private fun Node.renderOperand(): String =
    if (kindCase == Node.KindCase.ALL_OF || kindCase == Node.KindCase.ANY_OF) "(${render()})" else render()

private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
