package io.github.noamcohen48.tap.protocol

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
import io.github.noamcohen48.tap.api.v1.Selector
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

    fun packageName(value: String): Node = match(TextProperty.PROPERTY_PACKAGE_NAME, value)

    fun flag(
        property: NodeFlag,
        value: Boolean = true,
    ): Node = Node.newBuilder().setFlag(Flag.newBuilder().setProperty(property).setValue(value)).build()

    /** Resource [name] in any package (`<any>:id/name`), or a Compose testTag [name]. */
    fun resource(name: String): Node = Node.newBuilder().setResource(ResourceId.newBuilder().setName(name)).build()

    /** Exactly the View id `packageName:id/name`. */
    fun androidResource(
        packageName: String,
        name: String,
    ): Node = Node.newBuilder().setResource(ResourceId.newBuilder().setName(name).setPackageName(packageName)).build()

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

/** Selector construction; selectors are screen-wide unless a package predicate is present. */
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

    fun resource(name: String): Selector = of(Nodes.resource(name))

    fun androidResource(
        packageName: String,
        name: String,
    ): Selector = of(Nodes.androidResource(packageName, name))
}

fun Node.toSelector(): Selector = Selectors.of(this)

fun Selector.pickFirst(): Selector = toBuilder().setFirst(First.getDefaultInstance()).build()

fun Selector.pickAt(index: Int): Selector = toBuilder().setAt(At.newBuilder().setIndex(index)).build()

/**
 * Whether the accessibility id [actual] is this resource: exactly `package_name:id/name` with a
 * package; otherwise `name` in any package, or the bare `name` (a Compose testTag).
 */
fun ResourceId.matchesId(actual: String?): Boolean {
    if (actual == null) return false
    if (hasPackageName()) return actual == "$packageName$ID_SEPARATOR$name"
    if (actual == name) return true
    // `<package>:id/name`: a non-empty package with no ':' of its own.
    val packageLength = actual.length - ID_SEPARATOR.length - name.length
    return packageLength > 0 &&
        actual.endsWith(name) &&
        actual.startsWith(ID_SEPARATOR, packageLength) &&
        actual.lastIndexOf(':', packageLength - 1) < 0
}

private const val ID_SEPARATOR = ":id/"

/** A compact, stable rendering for messages and logs, e.g. `text="OK" & parent(class~"List")`. */
fun Selector.render(): String {
    val base = node.render()
    val pick =
        when (pickCase) {
            Selector.PickCase.FIRST -> " [first]"
            Selector.PickCase.AT -> " [${at.index}]"
            Selector.PickCase.EXACTLY_ONE, Selector.PickCase.PICK_NOT_SET, null -> ""
        }
    return base + pick
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
                    TextProperty.PROPERTY_PACKAGE_NAME -> "package"
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

        Node.KindCase.RESOURCE -> "id=${quote(if (resource.hasPackageName()) "${resource.packageName}:id/${resource.name}" else resource.name)}"

        Node.KindCase.RELATED -> "${related.relation.name.removePrefix("RELATION_").lowercase()}(${related.node.render()})"
        Node.KindCase.ALL_OF -> allOf.nodesList.joinToString(" & ") { it.renderOperand() }
        Node.KindCase.ANY_OF -> anyOf.nodesList.joinToString(" | ") { it.renderOperand() }
        Node.KindCase.KIND_NOT_SET, null -> "<empty>"
    }

private fun Node.renderOperand(): String =
    if (kindCase == Node.KindCase.ALL_OF || kindCase == Node.KindCase.ANY_OF) "(${render()})" else render()

/** JSON string escaping, so a value with quotes or line breaks still renders on one unambiguous line. */
private fun quote(value: String): String =
    buildString(value.length + 2) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
