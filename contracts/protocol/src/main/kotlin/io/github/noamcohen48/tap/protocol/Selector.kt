package io.github.noamcohen48.tap.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Maximum nesting of [Node]s ([Node.Related], [Node.AllOf], [Node.AnyOf]). */
const val MAX_SELECTOR_DEPTH = 32

/** Maximum total [Node]s in one [Selector], relations and combinators included. */
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

/** The string-valued node properties a [Node.Match] can constrain. */
enum class TextProperty {
    TEXT,
    CONTENT_DESCRIPTION,
    HINT,
    CLASS_NAME,
}

/** The boolean node properties a [Node.Flag] can constrain. */
enum class NodeFlag {
    ENABLED,
    CHECKED,
    CHECKABLE,
    CLICKABLE,
    FOCUSED,
    FOCUSABLE,
    LONG_CLICKABLE,
    SCROLLABLE,
    SELECTED,
}

/** Which relative a [Node.Related] constrains. */
enum class Relation {
    /** The direct parent must match. */
    PARENT,

    /** Some ancestor must match. */
    ANCESTOR,

    /** At least one direct child must match. */
    CHILD,

    /** At least one descendant must match. */
    DESCENDANT,
}

/**
 * A node predicate: the selector AST is a small expression tree over one accessibility node.
 * Every kind is a distinct class, discriminated by `kind` on the wire, so each carries exactly
 * the fields it needs. Sibling, nearest, NOT and nth-match are deliberately absent (plan §10);
 * disjunction ([AnyOf]) is a recorded departure from the plan (`protocol-contract.md`).
 *
 * Package is not a node property: [Selector.scope] already confines every match to one package.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Node {
    /** A string property compared against [value] under [mode]. */
    @Serializable
    @SerialName("match")
    data class Match(
        val property: TextProperty,
        val value: String,
        val mode: MatchMode = MatchMode.EXACT,
    ) : Node

    /** A boolean property that must equal [value]. */
    @Serializable
    @SerialName("flag")
    data class Flag(
        val property: NodeFlag,
        val value: Boolean = true,
    ) : Node

    /**
     * Resource identity. With [packageName] this is an Android resource ID (`package:id/name`);
     * without it, it is a raw resource name such as a Compose `testTag` exposed through
     * `testTagsAsResourceId`. Both are literal values, never patterns.
     */
    @Serializable
    @SerialName("resource")
    data class Resource(
        val name: String,
        val packageName: String? = null,
    ) : Node

    /** The [relation] of the node must satisfy [node]. */
    @Serializable
    @SerialName("related")
    data class Related(
        val relation: Relation,
        val node: Node,
    ) : Node

    /** Conjunction: every operand must hold. At least two operands. */
    @Serializable
    @SerialName("all_of")
    data class AllOf(
        val nodes: List<Node>,
    ) : Node

    /** Disjunction: at least one operand must hold. At least two operands. */
    @Serializable
    @SerialName("any_of")
    data class AnyOf(
        val nodes: List<Node>,
    ) : Node

    /** The operands of this node as one conjunction: nested [AllOf]s flattened, anything else itself. */
    val conjunction: List<Node>
        get() = if (this is AllOf) nodes.flatMap { it.conjunction } else listOf(this)

    /** The direct sub-nodes of this node. */
    val children: List<Node>
        get() =
            when (this) {
                is Match, is Flag, is Resource -> emptyList()
                is Related -> listOf(node)
                is AllOf -> nodes
                is AnyOf -> nodes
            }

    companion object {
        fun text(
            value: String,
            mode: MatchMode = MatchMode.EXACT,
        ): Node = Match(TextProperty.TEXT, value, mode)

        fun contentDescription(
            value: String,
            mode: MatchMode = MatchMode.EXACT,
        ): Node = Match(TextProperty.CONTENT_DESCRIPTION, value, mode)

        fun hint(
            value: String,
            mode: MatchMode = MatchMode.EXACT,
        ): Node = Match(TextProperty.HINT, value, mode)

        fun className(
            value: String,
            mode: MatchMode = MatchMode.EXACT,
        ): Node = Match(TextProperty.CLASS_NAME, value, mode)

        fun parent(node: Node): Node = Related(Relation.PARENT, node)

        fun ancestor(node: Node): Node = Related(Relation.ANCESTOR, node)

        fun child(node: Node): Node = Related(Relation.CHILD, node)

        fun descendant(node: Node): Node = Related(Relation.DESCENDANT, node)

        /** A conjunction, normalised: nested [AllOf]s are flattened and a single operand is returned as is. */
        fun allOf(nodes: List<Node>): Node = combine(nodes, ::AllOf) { it is AllOf }

        fun allOf(vararg nodes: Node): Node = allOf(nodes.toList())

        /** A disjunction, normalised: nested [AnyOf]s are flattened and a single operand is returned as is. */
        fun anyOf(nodes: List<Node>): Node = combine(nodes, ::AnyOf) { it is AnyOf }

        fun anyOf(vararg nodes: Node): Node = anyOf(nodes.toList())

        private inline fun combine(
            nodes: List<Node>,
            build: (List<Node>) -> Node,
            same: (Node) -> Boolean,
        ): Node {
            require(nodes.isNotEmpty()) { "A combinator needs at least one node" }
            val flat = nodes.flatMap { if (same(it)) it.children else listOf(it) }
            return flat.singleOrNull() ?: build(flat)
        }
    }
}

/** Both operands must hold; see [Node.allOf]. */
infix fun Node.and(other: Node): Node = Node.allOf(this, other)

/** Either operand must hold; see [Node.anyOf]. */
infix fun Node.or(other: Node): Node = Node.anyOf(this, other)

/** Which windows a selector may match. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Scope {
    /** The focused window of the app under test (default). */
    @Serializable
    @SerialName("aut")
    data object Aut : Scope

    /** The focused window of an allowlisted system package (permission dialogs). */
    @Serializable
    @SerialName("system")
    data class System(
        val packageName: String,
    ) : Scope {
        init {
            require(packageName.isNotBlank()) { "System scope needs a package name" }
        }
    }
}

/**
 * How an action chooses among matches. Queries (`exists`, `wait_visible`, `count`) ignore it;
 * actions and scroll containers apply it. [First] and [At] rely on the driver's result order,
 * which is accessibility traversal order within the single focused window of the scope
 * package; choosing them is the caller's explicit acceptance of that order.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Pick {
    /** Exactly one node must match (default); more is `AMBIGUOUS`, none is `NOT_FOUND`. */
    @Serializable
    @SerialName("exactly_one")
    data object ExactlyOne : Pick

    /** The first match in accessibility order. */
    @Serializable
    @SerialName("first")
    data object First : Pick

    /** The zero-based [index]-th match in accessibility order; `NOT_FOUND` when absent. */
    @Serializable
    @SerialName("at")
    data class At(
        val index: Int,
    ) : Pick {
        init {
            require(index >= 0) { "Match index must be >= 0" }
        }
    }
}

@Serializable
data class Selector(
    val node: Node,
    val scope: Scope = Scope.Aut,
    val pick: Pick = Pick.ExactlyOne,
) {
    companion object {
        fun text(
            value: String,
            mode: MatchMode = MatchMode.EXACT,
        ): Selector = Selector(Node.text(value, mode))

        fun contentDescription(
            value: String,
            mode: MatchMode = MatchMode.EXACT,
        ): Selector = Selector(Node.contentDescription(value, mode))

        fun rawResource(name: String): Selector = Selector(Node.Resource(name))

        fun androidResource(
            packageName: String,
            name: String,
        ): Selector = Selector(Node.Resource(name, packageName))
    }

    fun inSystemPackage(packageName: String): Selector = copy(scope = Scope.System(packageName))

    fun first(): Selector = copy(pick = Pick.First)

    fun at(index: Int): Selector = copy(pick = Pick.At(index))
}
