package com.company.tap.protocol

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Which internal evaluator a selector compiles to (plan §10, "Query plans"). */
enum class SelectorPlanKind {
    /** Every predicate maps onto current `BySelector` APIs. */
    NATIVE,

    /** Needs the driver's accessibility traversal evaluator (currently: any `REGEX` match). */
    TRAVERSAL,
}

class InvalidSelectorException(val detail: String, message: String) : IllegalArgumentException(message)

/**
 * Structural validation shared by host and driver. The host validates before transmitting so a
 * malformed selector never consumes a request ID; the driver validates again because it trusts
 * nothing on the wire. Never weakens a selector: anything not representable is rejected.
 */
object SelectorValidation {
    fun validate(selector: Selector): SelectorPlanKind {
        when (selector.scope) {
            TargetScope.AUT -> if (selector.scopePackage != null) {
                fail(ErrorDetail.SCOPE_PACKAGE_UNEXPECTED, "scopePackage is only valid for SYSTEM scope")
            }
            TargetScope.SYSTEM -> if (selector.scopePackage.isNullOrBlank()) {
                fail(ErrorDetail.SCOPE_PACKAGE_REQUIRED, "SYSTEM scope requires scopePackage")
            }
        }
        when (selector.limit) {
            MatchLimit.EXACTLY_ONE -> if (selector.index != null) {
                fail(ErrorDetail.INDEX_UNEXPECTED, "index is only valid with limit AT")
            }
            MatchLimit.FIRST -> {
                if (selector.index != null) fail(ErrorDetail.INDEX_UNEXPECTED, "index is only valid with limit AT")
                requireOrderAccepted(selector)
            }
            MatchLimit.AT -> {
                val index = selector.index
                if (index == null || index < 0) fail(ErrorDetail.INDEX_REQUIRED, "limit AT requires index >= 0")
                requireOrderAccepted(selector)
            }
        }
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

    private fun requireOrderAccepted(selector: Selector) {
        if (!selector.acceptAccessibilityOrder) {
            fail(
                ErrorDetail.ORDER_NOT_ACCEPTED,
                "limit ${selector.limit} depends on accessibility traversal order; set acceptAccessibilityOrder",
            )
        }
    }

    /** Returns true when any node in the subtree requires the traversal plan. */
    private fun visit(node: NodeSelector, depth: Int, counter: Counter): Boolean {
        if (depth > MAX_SELECTOR_DEPTH) {
            fail(ErrorDetail.SELECTOR_TOO_DEEP, "Selector nesting exceeds $MAX_SELECTOR_DEPTH")
        }
        if (++counter.nodes > MAX_SELECTOR_NODES) {
            fail(ErrorDetail.SELECTOR_TOO_LARGE, "Selector exceeds $MAX_SELECTOR_NODES nodes")
        }
        if (node.isEmpty) fail(ErrorDetail.EMPTY_NODE, "Selector node has no property")
        var traversal = false
        node.stringProperties.forEach { (name, match) ->
            checkLength(name, match.value)
            if (match.mode == MatchMode.REGEX) {
                compileRegex(match.value)
                traversal = true
            }
        }
        node.resource?.let { resource ->
            checkLength("resource.name", resource.name)
            if (resource.name.isEmpty()) fail(ErrorDetail.EMPTY_VALUE, "resource.name must not be empty")
            resource.packageName?.let { packageName ->
                checkLength("resource.packageName", packageName)
                if (packageName.isEmpty()) {
                    fail(ErrorDetail.EMPTY_VALUE, "resource.packageName must not be empty")
                }
            }
        }
        node.relations.forEach { (_, related) ->
            if (visit(related, depth + 1, counter)) traversal = true
        }
        return traversal
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
