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

sealed class InvalidSelectorException(
    val detail: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class InvalidSelectorRegexException(
    source: String,
    cause: PatternSyntaxException,
) : InvalidSelectorException(ErrorDetail.INVALID_REGEX, "Invalid regex '$source': ${cause.message}", cause)

class SelectorTooDeepException(
    limit: Int,
) : InvalidSelectorException(ErrorDetail.SELECTOR_TOO_DEEP, "Selector nesting exceeds $limit")

class SelectorTooLargeException(
    limit: Int,
) : InvalidSelectorException(ErrorDetail.SELECTOR_TOO_LARGE, "Selector exceeds $limit nodes")

class EmptySelectorNodeException(
    message: String,
) : InvalidSelectorException(ErrorDetail.EMPTY_NODE, message)

class EmptySelectorValueException(
    message: String,
) : InvalidSelectorException(ErrorDetail.EMPTY_VALUE, message)

class SelectorStringTooLongException(
    name: String,
    limit: Int,
) : InvalidSelectorException(ErrorDetail.STRING_TOO_LONG, "$name exceeds $limit characters")

class SelectorScopeDeniedException(
    message: String,
) : InvalidSelectorException(ErrorDetail.SCOPE_DENIED, message)

class SelectorScopeMismatchException(
    message: String,
) : InvalidSelectorException(ErrorDetail.SCOPE_MISMATCH, message)

/**
 * Structural command validation shared by host and driver. The host validates before transmitting
 * so a malformed selector never consumes a request ID; the driver validates again because it
 * trusts nothing on the wire. Never weakens a selector: anything not representable is rejected.
 */
object CommandValidation {
    /**
     * Validates every selector carried by [command].
     *
     * @throws InvalidSelectorException when any selector is structurally invalid.
     */
    fun validate(command: Command) {
        when (command) {
            is ScrollUntil -> {
                validateSelector(command.selector)
                validateSelector(command.container)
            }

            is Targeted -> {
                validateSelector(command.selector)
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * Validates [selector] and returns the device evaluator needed to preserve its semantics.
     *
     * @throws InvalidSelectorException when the selector is structurally invalid.
     */
    fun validateSelector(selector: Selector): SelectorPlanKind {
        val result = visit(selector.node, depth = 1)
        return if (result.needsTraversal) SelectorPlanKind.TRAVERSAL else SelectorPlanKind.NATIVE
    }

    /**
     * Compiles a `REGEX` match for full-string matching with RE2 semantics.
     *
     * @throws InvalidSelectorRegexException when [source] is not valid RE2 syntax.
     */
    fun compileRegex(source: String): Pattern =
        try {
            Pattern.compile(source)
        } catch (error: PatternSyntaxException) {
            throw InvalidSelectorRegexException(source, error)
        }

    private fun visit(
        node: Node,
        depth: Int,
    ): ValidationResult {
        if (depth > MAX_SELECTOR_DEPTH) throw SelectorTooDeepException(MAX_SELECTOR_DEPTH)

        val needsTraversal =
            when (node) {
                is Node.Match -> {
                    checkLength(node.property.name, node.value)
                    // `CONTAINS ""` (and every other non-exact empty pattern) matches every node,
                    // so with `first()` a mutation would hit an arbitrary one. Only EXACT "" is
                    // meaningful: it matches an empty value.
                    if (node.value.isEmpty() && node.mode != MatchMode.EXACT) {
                        throw EmptySelectorValueException("${node.property.name} ${node.mode} needs a non-empty value")
                    }
                    if (node.mode == MatchMode.REGEX) compileRegex(node.value)
                    node.mode == MatchMode.REGEX
                }

                is Node.Flag -> {
                    false
                }

                is Node.Resource -> {
                    checkLength("resource.name", node.name)
                    if (node.name.isEmpty()) {
                        throw EmptySelectorValueException("resource.name must not be empty")
                    }
                    node.packageName?.let { packageName ->
                        checkLength("resource.packageName", packageName)
                        if (packageName.isEmpty()) {
                            throw EmptySelectorValueException("resource.packageName must not be empty")
                        }
                    }
                    false
                }

                is Node.Related -> {
                    false
                }

                is Node.AllOf -> {
                    if (node.nodes.size < 2) {
                        throw EmptySelectorNodeException("all_of needs at least two nodes")
                    }
                    repeatsSingleValuedPredicate(node.conjunction)
                }

                is Node.AnyOf -> {
                    if (node.nodes.size < 2) {
                        throw EmptySelectorNodeException("any_of needs at least two nodes")
                    }
                    true
                }
            }

        return node.children.fold(ValidationResult(needsTraversal, nodeCount = 1)) { result, child ->
            val childResult = visit(child, depth + 1)
            val nodeCount = result.nodeCount + childResult.nodeCount
            if (nodeCount > MAX_SELECTOR_NODES) throw SelectorTooLargeException(MAX_SELECTOR_NODES)
            ValidationResult(
                needsTraversal = result.needsTraversal || childResult.needsTraversal,
                nodeCount = nodeCount,
            )
        }
    }

    /**
     * `BySelector` keeps one constraint per text property, flag and resource and one parent and
     * ancestor selector; a conjunction naming any of them twice is only expressible by traversal.
     */
    private fun repeatsSingleValuedPredicate(conjunction: List<Node>): Boolean {
        val seen = HashSet<Any>()
        return conjunction.any { operand ->
            val key: Any =
                when (operand) {
                    is Node.Match -> {
                        operand.property
                    }

                    is Node.Flag -> {
                        operand.property
                    }

                    is Node.Resource -> {
                        Node.Resource::class
                    }

                    is Node.Related -> {
                        when (operand.relation) {
                            Relation.PARENT, Relation.ANCESTOR -> operand.relation
                            Relation.CHILD, Relation.DESCENDANT -> return@any false
                        }
                    }

                    is Node.AllOf, is Node.AnyOf -> {
                        return@any false
                    }
                }
            !seen.add(key)
        }
    }

    private fun checkLength(
        name: String,
        value: String,
    ) {
        if (value.length > MAX_SELECTOR_STRING_CHARS) {
            throw SelectorStringTooLongException(name, MAX_SELECTOR_STRING_CHARS)
        }
    }

    private data class ValidationResult(
        val needsTraversal: Boolean,
        val nodeCount: Int,
    )
}
