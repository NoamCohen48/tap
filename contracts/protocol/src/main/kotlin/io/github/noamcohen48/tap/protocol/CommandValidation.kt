package io.github.noamcohen48.tap.protocol

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Command.OpCase
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.StabilitySignal
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Request.BodyCase

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

/**
 * A command the driver must not execute. [code] is `INVALID_SELECTOR` for a selector problem
 * (with a stable [detail]) and `INVALID_REQUEST` for any other argument, `UNSUPPORTED` when no
 * operation is set.
 */
class InvalidCommandException(
    val code: ErrorCode,
    val detail: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    fun toFailure(): CommandFailure = CommandFailure(code, detail, message)
}

private fun invalidSelector(
    detail: String,
    message: String,
    cause: Throwable? = null,
): Nothing = throw InvalidCommandException(ErrorCode.ERR_INVALID_SELECTOR, detail, message, cause)

private fun invalidRequest(message: String): Nothing = throw InvalidCommandException(ErrorCode.ERR_INVALID_REQUEST, null, message)

/**
 * Structural command validation shared by the daemon (pre-flight, before any device work), host
 * core (before transmitting, so a malformed command never consumes a request ID) and the driver
 * (which trusts nothing on the wire). Never weakens a command: anything not representable is
 * rejected, including enum values this build does not know.
 */
object CommandValidation {
    /** @throws InvalidCommandException when the request body is unset or invalid. */
    fun validate(request: Request) {
        when (request.bodyCase) {
            BodyCase.COMMAND -> {
                validate(request.command)
            }

            BodyCase.SYNC_BOOTSTRAP -> {
                val bootstrap = request.syncBootstrap
                if (bootstrap.observedStartToken.isBlank()) invalidRequest("observed_start_token must not be blank")
                requirePackage(bootstrap.packageName)
                if (bootstrap.authority.isBlank()) invalidRequest("authority must not be blank")
            }

            BodyCase.SYNC_POLL -> {
                val poll = request.syncPoll
                if (poll.observedStartToken.isBlank()) invalidRequest("observed_start_token must not be blank")
                if (poll.expectedProcessStartUuid.isBlank()) invalidRequest("expected_process_start_uuid must not be blank")
                if (poll.expectedSessionIdentity.isBlank()) invalidRequest("expected_session_identity must not be blank")
                requirePackage(poll.packageName)
                if (poll.authority.isBlank()) invalidRequest("authority must not be blank")
            }

            BodyCase.HEALTH, BodyCase.SCREENSHOT -> {
                Unit
            }

            BodyCase.BODY_NOT_SET, null -> {
                throw InvalidCommandException(ErrorCode.ERR_UNSUPPORTED, null, "No operation is set")
            }
        }
    }

    /** @throws InvalidCommandException when an argument or selector of [command] is invalid. */
    fun validate(command: Command) {
        if (command.hasTimeoutMs() && command.timeoutMs !in 0..MAX_REQUEST_TIMEOUT_MS) {
            invalidRequest("timeout_ms must be in 0..$MAX_REQUEST_TIMEOUT_MS")
        }
        when (command.opCase) {
            OpCase.PRESS_KEY -> {
                if (command.pressKey.keyCode < 0) invalidRequest("key_code must not be negative")
            }

            OpCase.WAIT_APP_VISIBLE -> {
                requirePackage(command.waitAppVisible.packageName)
            }

            OpCase.WAIT_SCREEN_STABLE -> {
                val wait = command.waitScreenStable
                requirePackage(wait.packageName)
                if (wait.hasStableForMs() && wait.stableForMs !in 1..MAX_STABLE_FOR_MS) {
                    invalidRequest("stable_for_ms must be in 1..$MAX_STABLE_FOR_MS")
                }
                if (wait.signal == StabilitySignal.UNRECOGNIZED) invalidRequest("Unknown stability signal")
            }

            OpCase.SET_TEXT -> {
                checkText(command.setText.text)
            }

            OpCase.TYPE_TEXT -> {
                checkText(command.typeText.text)
            }

            OpCase.SWIPE -> {
                requireDirection(command.swipe.direction)
                if (command.swipe.hasDistancePercent()) checkPercent(command.swipe.distancePercent)
            }

            OpCase.SCROLL -> {
                requireDirection(command.scroll.direction)
                if (command.scroll.hasDistancePercent()) checkPercent(command.scroll.distancePercent)
            }

            OpCase.OPEN_SYSTEM_PANEL -> {
                val panel = command.openSystemPanel.panel
                if (panel == SystemPanel.SYSTEM_PANEL_UNSPECIFIED || panel == SystemPanel.UNRECOGNIZED) invalidRequest("A panel is required")
            }


            OpCase.OP_NOT_SET, null -> {
                throw InvalidCommandException(ErrorCode.ERR_UNSUPPORTED, null, "No command op is set")
            }

            OpCase.DEVICE_INFO, OpCase.DUMP_HIERARCHY, OpCase.EXISTS, OpCase.COUNT, OpCase.SNAPSHOT,
            OpCase.WAIT_VISIBLE, OpCase.WAIT_GONE, OpCase.TAP, OpCase.LONG_TAP, OpCase.CLEAR_TEXT,
            -> {
                Unit
            }
        }
        command.targetSelector?.let { checkPresent(command, it) }
        command.selectors.forEach { validateSelector(it) }
    }

    /**
     * Validates [selector] and returns the device evaluator needed to preserve its semantics.
     *
     * @throws InvalidCommandException when the selector is structurally invalid.
     */
    fun validateSelector(selector: Selector): SelectorPlanKind {
        if (!selector.hasNode()) invalidSelector(ErrorDetail.EMPTY_NODE, "Selector has no node")
        if (selector.pickCase == Selector.PickCase.AT && selector.at.index < 0) {
            invalidRequest("at.index must not be negative")
        }
        val result = visit(selector.node, depth = 1)
        return if (result.needsTraversal) SelectorPlanKind.TRAVERSAL else SelectorPlanKind.NATIVE
    }

    /**
     * Compiles a `REGEX` match for full-string matching with RE2 semantics.
     *
     * @throws InvalidCommandException when [source] is not valid RE2 syntax.
     */
    fun compileRegex(source: String): Pattern =
        try {
            Pattern.compile(source)
        } catch (error: PatternSyntaxException) {
            invalidSelector(ErrorDetail.INVALID_REGEX, "Invalid regex '$source': ${error.message}", error)
        }

    private fun checkPresent(
        command: Command,
        selector: Selector,
    ) {
        if (selector == Selector.getDefaultInstance()) invalidSelector(ErrorDetail.EMPTY_NODE, "${command.op} needs a selector")
    }

    private fun visit(
        node: Node,
        depth: Int,
    ): ValidationResult {
        if (depth > MAX_SELECTOR_DEPTH) invalidSelector(ErrorDetail.SELECTOR_TOO_DEEP, "Selector nesting exceeds $MAX_SELECTOR_DEPTH")

        val needsTraversal =
            when (node.kindCase) {
                Node.KindCase.MATCH -> {
                    val match = node.match
                    if (match.property == TextProperty.PROPERTY_UNSPECIFIED || match.property == TextProperty.UNRECOGNIZED) {
                        invalidSelector(ErrorDetail.UNSPECIFIED_VALUE, "match needs a known property")
                    }
                    if (match.mode == MatchMode.UNRECOGNIZED) invalidSelector(ErrorDetail.UNSPECIFIED_VALUE, "Unknown match mode")
                    val name = match.property.name.removePrefix("PROPERTY_")
                    checkLength(name, match.value)
                    // `CONTAINS ""` (and every other non-exact empty pattern) matches every node,
                    // so with `first` a mutation would hit an arbitrary one. Only EXACT "" is
                    // meaningful: it matches an empty value.
                    if (match.value.isEmpty() && match.mode.isNonExact) {
                        invalidSelector(ErrorDetail.EMPTY_VALUE, "$name ${match.mode.name.removePrefix("MATCH_")} needs a non-empty value")
                    }
                    if (match.mode == MatchMode.MATCH_REGEX) compileRegex(match.value)
                    match.mode == MatchMode.MATCH_REGEX
                }

                Node.KindCase.FLAG -> {
                    if (node.flag.property == NodeFlag.FLAG_UNSPECIFIED || node.flag.property == NodeFlag.UNRECOGNIZED) {
                        invalidSelector(ErrorDetail.UNSPECIFIED_VALUE, "flag needs a known property")
                    }
                    false
                }

                Node.KindCase.RESOURCE -> {
                    val resource = node.resource
                    checkLength("resource.name", resource.name)
                    if (resource.name.isEmpty()) invalidSelector(ErrorDetail.EMPTY_VALUE, "resource.name must not be empty")
                    if (":id/" in resource.name) {
                        invalidSelector(ErrorDetail.QUALIFIED_RESOURCE_NAME, "resource.name must not contain ':id/'; set resource.package_name")
                    }
                    if (resource.hasPackageName()) {
                        checkLength("resource.package_name", resource.packageName)
                        if (resource.packageName.isEmpty()) {
                            invalidSelector(ErrorDetail.EMPTY_VALUE, "resource.package_name must not be empty")
                        }
                    }
                    false
                }

                Node.KindCase.RELATED -> {
                    val relation = node.related.relation
                    if (relation == Relation.RELATION_UNSPECIFIED || relation == Relation.UNRECOGNIZED) {
                        invalidSelector(ErrorDetail.UNSPECIFIED_VALUE, "related needs a known relation")
                    }
                    if (!node.related.hasNode()) invalidSelector(ErrorDetail.EMPTY_NODE, "related needs a node")
                    false
                }

                Node.KindCase.ALL_OF -> {
                    if (node.allOf.nodesCount < 2) invalidSelector(ErrorDetail.EMPTY_NODE, "all_of needs at least two nodes")
                    repeatsSingleValuedPredicate(node.conjunction)
                }

                Node.KindCase.ANY_OF -> {
                    if (node.anyOf.nodesCount < 2) invalidSelector(ErrorDetail.EMPTY_NODE, "any_of needs at least two nodes")
                    true
                }

                Node.KindCase.KIND_NOT_SET, null -> {
                    invalidSelector(ErrorDetail.EMPTY_NODE, "Selector node has no kind")
                }
            }

        return node.children.fold(ValidationResult(needsTraversal, nodeCount = 1)) { result, child ->
            val childResult = visit(child, depth + 1)
            val nodeCount = result.nodeCount + childResult.nodeCount
            if (nodeCount > MAX_SELECTOR_NODES) invalidSelector(ErrorDetail.SELECTOR_TOO_LARGE, "Selector exceeds $MAX_SELECTOR_NODES nodes")
            ValidationResult(
                needsTraversal = result.needsTraversal || childResult.needsTraversal,
                nodeCount = nodeCount,
            )
        }
    }

    private val MatchMode.isNonExact: Boolean
        get() = this != MatchMode.MATCH_EXACT && this != MatchMode.MATCH_UNSPECIFIED

    /**
     * `BySelector` keeps one constraint per text property, flag and resource and one parent and
     * ancestor selector; a conjunction naming any of them twice is only expressible by traversal.
     */
    private fun repeatsSingleValuedPredicate(conjunction: List<Node>): Boolean {
        val seen = HashSet<Any>()
        return conjunction.any { operand ->
            val key: Any =
                when (operand.kindCase) {
                    Node.KindCase.MATCH -> {
                        operand.match.property
                    }

                    Node.KindCase.FLAG -> {
                        operand.flag.property
                    }

                    Node.KindCase.RESOURCE -> {
                        Node.KindCase.RESOURCE
                    }

                    Node.KindCase.RELATED -> {
                        when (operand.related.relation) {
                            Relation.RELATION_PARENT, Relation.RELATION_ANCESTOR -> operand.related.relation
                            else -> return@any false
                        }
                    }

                    Node.KindCase.ALL_OF, Node.KindCase.ANY_OF, Node.KindCase.KIND_NOT_SET, null -> {
                        return@any false
                    }
                }
            !seen.add(key)
        }
    }

    private fun requirePackage(packageName: String) {
        if (packageName.isBlank()) invalidRequest("package_name must not be blank")
    }

    private fun requireDirection(direction: Direction) {
        if (direction == Direction.DIR_UNSPECIFIED || direction == Direction.UNRECOGNIZED) invalidRequest("A direction is required")
    }

    private fun checkPercent(value: Int) {
        if (value !in 1..100) invalidRequest("distance_percent must be in 1..100")
    }

    private fun checkText(text: String) {
        if (text.length > MAX_TEXT_INPUT_CHARS) invalidRequest("text must be at most $MAX_TEXT_INPUT_CHARS chars")
    }

    private fun checkLength(
        name: String,
        value: String,
    ) {
        if (value.length > MAX_SELECTOR_STRING_CHARS) {
            invalidSelector(ErrorDetail.STRING_TOO_LONG, "$name exceeds $MAX_SELECTOR_STRING_CHARS characters")
        }
    }

    private data class ValidationResult(
        val needsTraversal: Boolean,
        val nodeCount: Int,
    )
}
