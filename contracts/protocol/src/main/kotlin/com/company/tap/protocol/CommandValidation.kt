package com.company.tap.protocol

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Which internal evaluator a selector compiles to (plan §10, "Query plans"). */
enum class SelectorPlanKind {
    /** Every predicate maps onto one window-scoped `BySelector`. */
    NATIVE,

    /**
     * Needs the driver's accessibility traversal evaluator: any `REGEX` match, any `any_of`, or a
     * conjunction repeating a predicate `BySelector` holds only once (a text property, a flag,
     * the resource, a parent or an ancestor).
     */
    TRAVERSAL,
}

class InvalidSelectorException(val detail: String, message: String) : IllegalArgumentException(message)

/**
 * Structural command validation shared by host and driver. The host validates before transmitting
 * so a malformed selector never consumes a request ID; the driver validates again because it
 * trusts nothing on the wire. Never weakens a selector: anything not representable is rejected.
 */
object CommandValidation {
    /** Validates every selector carried by [command]. */
    fun validate(command: Command) {
        when (command) {
            is ScrollUntil -> {
                validateSelector(command.selector)
                validateSelector(command.container)
            }
            is Targeted -> validateSelector(command.selector)
            else -> Unit
        }
    }

    /** Validates [selector] and returns the device evaluator needed to preserve its semantics. */
    fun validateSelector(selector: Selector): SelectorPlanKind {
        val counter = Counter()
        val needsTraversal = visit(selector.node, depth = 1, counter)
        return if (needsTraversal) SelectorPlanKind.TRAVERSAL else SelectorPlanKind.NATIVE
    }

    /** Compiles a `REGEX` match for full-string matching with RE2 semantics. */
    fun compileRegex(source: String): Pattern = try {
        Pattern.compile(source)
    } catch (error: PatternSyntaxException) {
        fail(ErrorDetail.INVALID_REGEX, "Invalid regex: ${error.message}")
    }

    /** Returns true when any node in the subtree requires the traversal plan. */
    private fun visit(node: Node, depth: Int, counter: Counter): Boolean {
        if (depth > MAX_SELECTOR_DEPTH) {
            fail(ErrorDetail.SELECTOR_TOO_DEEP, "Selector nesting exceeds $MAX_SELECTOR_DEPTH")
        }
        if (++counter.nodes > MAX_SELECTOR_NODES) {
            fail(ErrorDetail.SELECTOR_TOO_LARGE, "Selector exceeds $MAX_SELECTOR_NODES nodes")
        }
        var traversal = false
        when (node) {
            is Node.Match -> {
                checkLength(node.property.name, node.value)
                if (node.mode == MatchMode.REGEX) {
                    compileRegex(node.value)
                    traversal = true
                }
            }
            is Node.Flag -> Unit
            is Node.Resource -> {
                checkLength("resource.name", node.name)
                if (node.name.isEmpty()) fail(ErrorDetail.EMPTY_VALUE, "resource.name must not be empty")
                node.packageName?.let { packageName ->
                    checkLength("resource.packageName", packageName)
                    if (packageName.isEmpty()) {
                        fail(ErrorDetail.EMPTY_VALUE, "resource.packageName must not be empty")
                    }
                }
            }
            is Node.Related -> Unit
            is Node.AllOf -> {
                if (node.nodes.size < 2) fail(ErrorDetail.EMPTY_NODE, "all_of needs at least two nodes")
                if (repeatsSingleValuedPredicate(node.conjunction)) traversal = true
            }
            is Node.AnyOf -> {
                if (node.nodes.size < 2) fail(ErrorDetail.EMPTY_NODE, "any_of needs at least two nodes")
                traversal = true
            }
        }
        node.children.forEach { child ->
            if (visit(child, depth + 1, counter)) traversal = true
        }
        return traversal
    }

    /**
     * `BySelector` keeps one constraint per text property, flag and resource and one parent and
     * ancestor selector; a conjunction naming any of them twice is only expressible by traversal.
     */
    private fun repeatsSingleValuedPredicate(conjunction: List<Node>): Boolean {
        val seen = HashSet<Any>()
        return conjunction.any { operand ->
            val key: Any = when (operand) {
                is Node.Match -> operand.property
                is Node.Flag -> operand.property
                is Node.Resource -> Node.Resource::class
                is Node.Related -> when (operand.relation) {
                    Relation.PARENT, Relation.ANCESTOR -> operand.relation
                    Relation.CHILD, Relation.DESCENDANT -> return@any false
                }
                is Node.AllOf, is Node.AnyOf -> return@any false
            }
            !seen.add(key)
        }
    }

    private fun checkLength(name: String, value: String) {
        if (value.length > MAX_SELECTOR_STRING_CHARS) {
            fail(ErrorDetail.STRING_TOO_LONG, "$name exceeds $MAX_SELECTOR_STRING_CHARS characters")
        }
    }

    private fun fail(detail: String, message: String): Nothing = throw InvalidSelectorException(detail, message)

    private class Counter {
        var nodes = 0
    }
}
