package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.At
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.MAX_SELECTOR_DEPTH
import io.github.noamcohen48.tap.protocol.MAX_SELECTOR_STRING_CHARS
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.SelectorPlanKind
import io.github.noamcohen48.tap.protocol.conjunction

/** How a synthesised selector singles out its node; [BY_INDEX] is the only fragile one. */
internal enum class SelectorKind {
    /** One predicate: resource id, text, description or hint. */
    PLAIN,

    /** Two predicates of the node itself (e.g. resource and text, text and class). */
    COMBINED,

    /** The node's own predicates plus an `ancestor` relation to an addressable ancestor. */
    ANCESTOR,

    /** The most specific predicate available plus an `At(index)` pick. */
    BY_INDEX,
}

internal class Synthesised(
    val selector: Selector,
    val kind: SelectorKind,
) {
    val byIndex: Boolean get() = kind == SelectorKind.BY_INDEX

    /** The predicates it is a conjunction of, to recognise a candidate that only adds to it. */
    val operands: Set<Node> = selector.node.conjunction.toSet()
}

/**
 * Synthesises, for every node of a dump, a selector the driver would resolve to that node alone
 * (`.docs/agent-surface.md`, decision 2). Candidates go from what a person would write to the
 * fragile: resource id, text, description, their pairs with each other and the class, the hint;
 * then each of those with an `ancestor` relation to the nearest ancestor that has its own
 * non-index selector; last, the most specific conjunction with an `At` pick.
 *
 * [synthesise] keeps the first candidate that is unique; [candidates] keeps every one, in the
 * same order (the first is [synthesise]'s), leaving out a candidate that only adds predicates to
 * an earlier one (text + class when the text alone is unique), and the `At` pick unless nothing
 * else is unique (`.docs/recorder.md`, decision 8).
 *
 * Uniqueness is checked with [DumpMatcher] across all visible windows. Every emitted selector
 * includes the owning package as an ordinary predicate, passes shared [CommandValidation], and
 * compiles to the same plan the driver uses. A node with no string property gets no selector.
 */
internal class SelectorSynthesis(
    hierarchy: Hierarchy,
) {
    private val nodes = hierarchy.nodes
    private val matcher = DumpMatcher(hierarchy)
    private val byText = index { it.text }
    private val byDescription = index { it.contentDescription }
    private val byHint = index { it.hint }
    private val byClass = index { it.className }
    private val byResource = index { it.resourceName }
    /** By the id's local name (`pkg:id/name` → `name`; a bare testTag as is), for package-less ids. */
    private val byLocalResource = index { id -> id.resourceName?.let { QUALIFIED_ID.matchEntire(it)?.groupValues?.get(2) ?: it } }

    /** One entry per [Hierarchy.nodes] entry: its selector, or null where none was found. */
    fun synthesise(): List<Synthesised?> = run(all = false).map { it.firstOrNull() }

    /** One entry per [Hierarchy.nodes] entry: its ranked candidates, empty where none was found. */
    fun candidates(): List<List<Synthesised>> = run(all = true)

    private fun run(all: Boolean): List<List<Synthesised>> {
        val primary = arrayOfNulls<Synthesised>(nodes.size)
        val result = MutableList(nodes.size) { emptyList<Synthesised>() }
        // Pre-order: every ancestor is done before its descendants need it.
        nodes.forEach { node ->
            result[node.index] = candidates(node, primary, all)
            primary[node.index] = result[node.index].firstOrNull()
        }
        return result
    }

    /** [node]'s unique candidates in rank order; only the first unless [all]. */
    private fun candidates(
        node: DumpNode,
        done: Array<Synthesised?>,
        all: Boolean,
    ): List<Synthesised> {
        val packageName = node.packageName ?: node.windowPackage
        val resource = resource(node.resourceName, packageName)
        val text = node.text?.usable()?.let { Nodes.text(it) }
        val description = node.contentDescription?.usable()?.let { Nodes.contentDescription(it) }
        val hint = node.hint?.usable()?.let { Nodes.hint(it) }
        val className = node.className?.usable()?.let { Nodes.className(it) }

        fun pair(
            first: Node?,
            second: Node?,
        ): Node? = if (first != null && second != null) Nodes.allOf(first, second) else null

        val candidates =
            listOf(
                resource to SelectorKind.PLAIN,
                text to SelectorKind.PLAIN,
                description to SelectorKind.PLAIN,
                pair(resource, text) to SelectorKind.COMBINED,
                pair(resource, description) to SelectorKind.COMBINED,
                pair(text, className) to SelectorKind.COMBINED,
                pair(description, className) to SelectorKind.COMBINED,
                pair(resource, className) to SelectorKind.COMBINED,
                hint to SelectorKind.PLAIN,
            ).filter { it.first != null }
        val found = mutableListOf<Synthesised>()

        /** Adds [predicate] when it is unique and not an earlier candidate plus more; true when done. */
        fun offer(
            predicate: Node,
            kind: SelectorKind,
        ): Boolean {
            val bound = bind(predicate, packageName)
            val operands = bound.conjunction.toSet()
            if (found.none { operands.containsAll(it.operands) }) {
                unique(bound, node)?.let { found += Synthesised(it, kind) }
            }
            return !all && found.isNotEmpty()
        }

        for ((predicate, kind) in candidates) {
            if (offer(predicate!!, kind)) return found
        }

        val ancestor = addressableAncestor(node, done)
        if (ancestor != null) {
            val related = Nodes.ancestor(ancestor)
            // The class alone is no candidate by itself, but "a Button under X" is.
            val own = candidates.map { it.first!! } + listOfNotNull(className)
            for (predicate in own) {
                if (offer(Nodes.allOf(predicate, related), SelectorKind.ANCESTOR)) return found
            }
        }
        if (found.isNotEmpty()) return found

        val specific = listOfNotNull(resource, text, description, hint, className)
        return listOfNotNull(byIndex(node, specific, packageName))
    }

    /** The conjunction of all of [node]'s predicates ([specific]) with an `At` pick, when valid. */
    private fun byIndex(
        node: DumpNode,
        specific: List<Node>,
        packageName: String,
    ): Synthesised? {
        if (specific.isEmpty()) return null
        val predicate = bind(Nodes.allOf(specific), packageName)
        val position =
            seeds(predicate)
                .map { nodes[it] }
                .filter { matcher.matches(predicate, it) }
                .indexOf(node)
        if (position < 0) return null
        val selector = selector(predicate).toBuilder().setAt(At.newBuilder().setIndex(position)).build()
        return selector.takeIf { valid(it) }?.let { Synthesised(it, SelectorKind.BY_INDEX) }
    }

    /**
     * The selector of [predicate] when it matches [target] and nothing else on the screen (a
     * selector the driver would reject is no candidate).
     */
    private fun unique(
        predicate: Node,
        target: DumpNode,
    ): Selector? {
        var matchedTarget = false
        for (i in seeds(predicate)) {
            val node = nodes[i]
            if (!matcher.matches(predicate, node)) continue
            if (node !== target) return null
            matchedTarget = true
        }
        if (!matchedTarget) return null
        return selector(predicate).takeIf { valid(it) }
    }

    private fun selector(predicate: Node): Selector =
        Selector.newBuilder().setNode(predicate).build()

    /** Adds package ownership as data and leaves execution screen-wide. */
    private fun bind(predicate: Node, packageName: String): Node =
        Nodes.allOf(predicate, Nodes.packageName(packageName))

    /** Only selectors the shared validation accepts, on the native plan [DumpMatcher] emulates. */
    private fun valid(selector: Selector): Boolean =
        try {
            CommandValidation.validateSelector(selector) == SelectorPlanKind.NATIVE
        } catch (_: InvalidCommandException) {
            false
        }

    /**
     * The nearest ancestor, at most [MAX_SELECTOR_DEPTH] levels up (the driver's traversal walk
     * stops there), whose selector is not an index pick; its predicate, without the package
     * predicate (the node's own one already says whose window it is), becomes the relation.
     */
    private fun addressableAncestor(
        node: DumpNode,
        done: Array<Synthesised?>,
    ): Node? {
        var parent = node.parent
        var distance = 1
        while (parent >= 0 && distance <= MAX_SELECTOR_DEPTH) {
            val synthesised = done[parent]
            if (synthesised != null && !synthesised.byIndex) {
                return Nodes.allOf(synthesised.selector.node.conjunction.filterNot { it.isPackagePredicate() })
            }
            parent = nodes[parent].parent
            distance++
        }
        return null
    }

    /**
     * A resource-id predicate. An id of the node's own package is package-less (`res("login")`):
     * the bound package predicate already says whose it is. Another package's id (a library or
     * `android:id/…`) keeps its package; an unqualified id is a Compose testTag.
     */
    private fun resource(
        id: String?,
        ownPackage: String,
    ): Node? {
        val usable = id?.usable() ?: return null
        val qualified = QUALIFIED_ID.matchEntire(usable) ?: return Nodes.resource(usable)
        val (packageName, name) = qualified.destructured
        return if (packageName == ownPackage) Nodes.resource(name) else Nodes.androidResource(packageName, name)
    }

    /**
     * Candidate node indexes for [predicate], in pre-order: the shortest posting list among its
     * top-level exact operands, so a check never scans the whole dump for a rare value.
     */
    private fun seeds(predicate: Node): IntArray {
        val lists =
            predicate.conjunction.mapNotNull { operand ->
                when (operand.kindCase) {
                    Node.KindCase.RESOURCE -> {
                        // A package-less id matches its name in any package: seed by the local name.
                        val resource = operand.resource
                        if (resource.hasPackageName()) {
                            byResource["${resource.packageName}:id/${resource.name}"] ?: EMPTY
                        } else {
                            byLocalResource[resource.name] ?: EMPTY
                        }
                    }

                    Node.KindCase.MATCH -> {
                        val index =
                            when (operand.match.property) {
                                TextProperty.PROPERTY_TEXT -> byText
                                TextProperty.PROPERTY_CONTENT_DESCRIPTION -> byDescription
                                TextProperty.PROPERTY_HINT -> byHint
                                TextProperty.PROPERTY_CLASS_NAME -> byClass
                                else -> null
                            }
                        index?.let { it[operand.match.value] ?: EMPTY }
                    }

                    else -> {
                        null
                    }
                }
            }
        return lists.minByOrNull { it.size } ?: IntArray(nodes.size) { it }
    }

    private fun index(property: (DumpNode) -> String?): Map<String, IntArray> =
        nodes
            .mapNotNull { node -> property(node)?.let { it to node.index } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toIntArray() }

    private companion object {
        val QUALIFIED_ID = Regex("([^:/]+):id/(.+)", RegexOption.DOT_MATCHES_ALL)
        val EMPTY = IntArray(0)

        fun Node.isPackagePredicate(): Boolean = kindCase == Node.KindCase.MATCH && match.property == TextProperty.PROPERTY_PACKAGE_NAME

        fun String.usable(): String? = takeIf { it.isNotEmpty() && it.length <= MAX_SELECTOR_STRING_CHARS }
    }
}
