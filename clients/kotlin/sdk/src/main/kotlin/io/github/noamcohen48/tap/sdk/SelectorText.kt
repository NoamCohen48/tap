package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.ResourceId
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.api.v1.Error as ErrorProto
import io.github.noamcohen48.tap.api.v1.Selector as SelectorProto

/*
 * One-line protobuf text rendering of a `tap.v1.Selector`, the form the full-protobuf
 * `TextFormat.shortDebugString` (and Python's `text_format.MessageToString(as_one_line=True)`)
 * prints: fields in number order, proto3 defaults omitted, fields with presence always printed,
 * enums by name, strings C-escaped per UTF-8 byte. The client ships protobuf-lite, which has no
 * text format, and the selector schema is small and closed, so it is written out here.
 */

internal fun SelectorProto.renderText(): String =
    TextWriter()
        .apply {
            if (hasNode()) message("node") { node(node) }
            when (pickCase) {
                SelectorProto.PickCase.EXACTLY_ONE -> message("exactly_one") {}
                SelectorProto.PickCase.FIRST -> message("first") {}
                SelectorProto.PickCase.AT -> message("at") { int("index", at.index) }
                SelectorProto.PickCase.PICK_NOT_SET, null -> Unit
            }
        }.toString()

private fun TextWriter.node(node: Node) {
    when (node.kindCase) {
        Node.KindCase.MATCH -> message("match") { match(node.match) }
        Node.KindCase.FLAG ->
            message("flag") {
                enum("property", node.flag.propertyValue, node.flag.property.takeUnless { it.isUnrecognized() }?.name)
                bool("value", node.flag.value)
            }
        Node.KindCase.RESOURCE -> message("resource") { resource(node.resource) }
        Node.KindCase.RELATED ->
            message("related") {
                enum("relation", node.related.relationValue, node.related.relation.takeUnless { it.isUnrecognized() }?.name)
                if (node.related.hasNode()) message("node") { node(node.related.node) }
            }
        Node.KindCase.ALL_OF -> message("all_of") { node.allOf.nodesList.forEach { message("nodes") { node(it) } } }
        Node.KindCase.ANY_OF -> message("any_of") { node.anyOf.nodesList.forEach { message("nodes") { node(it) } } }
        Node.KindCase.KIND_NOT_SET, null -> Unit
    }
}

private fun TextWriter.match(match: Match) {
    enum("property", match.propertyValue, match.property.takeUnless { it.isUnrecognized() }?.name)
    string("value", match.value)
    enum("mode", match.modeValue, match.mode.takeUnless { it.isUnrecognized() }?.name)
}

private fun TextWriter.resource(resource: ResourceId) {
    string("name", resource.name)
    if (resource.hasPackageName()) string("package_name", resource.packageName, always = true)
}

private fun Enum<*>.isUnrecognized(): Boolean = name == "UNRECOGNIZED"

private class TextWriter {
    private val out = StringBuilder()

    fun message(
        name: String,
        body: TextWriter.() -> Unit,
    ) {
        field("$name {")
        body()
        field("}")
    }

    fun string(
        name: String,
        value: String,
        always: Boolean = false,
    ) {
        if (value.isNotEmpty() || always) field("$name: \"${escape(value)}\"")
    }

    fun int(
        name: String,
        value: Int,
    ) {
        if (value != 0) field("$name: $value")
    }

    fun bool(
        name: String,
        value: Boolean,
    ) {
        if (value) field("$name: true")
    }

    /** [label] is null for a value this build does not know, which prints as its number. */
    fun enum(
        name: String,
        number: Int,
        label: String?,
    ) {
        if (number != 0) field("$name: ${label ?: number}")
    }

    private fun field(text: String) {
        if (out.isNotEmpty()) out.append(' ')
        out.append(text)
    }

    override fun toString(): String = out.toString()

    private fun escape(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                when (val b = byte.toInt() and 0xFF) {
                    '\n'.code -> append("\\n")
                    '\r'.code -> append("\\r")
                    '\t'.code -> append("\\t")
                    '\\'.code -> append("\\\\")
                    '\''.code -> append("\\'")
                    '"'.code -> append("\\\"")
                    in 0x20..0x7E -> append(b.toChar())
                    else -> append('\\').append(b.toString(8).padStart(3, '0'))
                }
            }
        }
}

/**
 * [node] in the builder syntax a test writes (`text("Pay")`, `clickable()`,
 * `hasAncestor(res("list"))`). An exact package predicate reads as the `app(...)` it came from.
 */
internal fun describe(node: Node): String =
    when (node.kindCase) {
        Node.KindCase.MATCH -> {
            val match = node.match
            val exact = match.mode == MatchMode.MATCH_EXACT || match.mode == MatchMode.MATCH_UNSPECIFIED
            if (match.property == TextProperty.PROPERTY_PACKAGE_NAME && exact) {
                "app(${quoted(match.value)})"
            } else {
                val name =
                    when (match.property) {
                        TextProperty.PROPERTY_TEXT -> "text"
                        TextProperty.PROPERTY_CONTENT_DESCRIPTION -> "desc"
                        TextProperty.PROPERTY_HINT -> "hint"
                        TextProperty.PROPERTY_CLASS_NAME -> "className"
                        TextProperty.PROPERTY_PACKAGE_NAME -> "packageName"
                        else -> "match"
                    }
                val mode = if (exact) "" else ", MatchMode.${match.mode.name.removePrefix("MATCH_")}"
                "$name(${quoted(match.value)}$mode)"
            }
        }

        Node.KindCase.FLAG -> {
            val name = camel(node.flag.property.name.removePrefix("FLAG_"))
            if (node.flag.value) "$name()" else "$name(false)"
        }

        Node.KindCase.RESOURCE -> {
            val resource = node.resource
            if (resource.hasPackageName()) "resId(${quoted(resource.packageName)}, ${quoted(resource.name)})" else "res(${quoted(resource.name)})"
        }

        Node.KindCase.RELATED -> {
            val name =
                when (node.related.relation) {
                    Relation.RELATION_PARENT -> "hasParent"
                    Relation.RELATION_ANCESTOR -> "hasAncestor"
                    Relation.RELATION_CHILD -> "hasChild"
                    Relation.RELATION_DESCENDANT -> "hasDescendant"
                    else -> "related"
                }
            "$name(${describe(node.related.node)})"
        }

        Node.KindCase.ALL_OF -> node.allOf.nodesList.joinToString(" and ") { operand(it) }

        Node.KindCase.ANY_OF -> node.anyOf.nodesList.joinToString(" or ") { operand(it) }

        Node.KindCase.KIND_NOT_SET, null -> "?"
    }

/** [selector] in builder syntax, with its pick. */
internal fun describe(selector: SelectorProto): String {
    val pick =
        when (selector.pickCase) {
            SelectorProto.PickCase.FIRST -> ".first()"
            SelectorProto.PickCase.AT -> ".at(${selector.at.index})"
            else -> ""
        }
    val base = describe(selector.node)
    val combined = selector.node.kindCase == Node.KindCase.ALL_OF || selector.node.kindCase == Node.KindCase.ANY_OF
    return if (pick.isNotEmpty() && combined) "($base)$pick" else base + pick
}

/**
 * [ErrorProto.getConditionHoldsList] against the [condition] the state wait sent, with what the
 * node ([ErrorProto.getLastState]) had for each property or flag part; empty when they do not fit.
 */
internal fun conditionParts(
    condition: Node,
    error: ErrorProto,
): List<ConditionPart> {
    val operands =
        when (condition.kindCase) {
            Node.KindCase.ALL_OF -> condition.allOf.nodesList
            Node.KindCase.ANY_OF -> condition.anyOf.nodesList
            else -> listOf(condition)
        }
    if (operands.size != error.conditionHoldsCount) return emptyList()
    val state = if (error.hasLastState()) error.lastState else null
    return operands.zip(error.conditionHoldsList) { node, holds -> ConditionPart(describe(node), holds, state?.let { observed(node, it) }) }
}

private fun observed(
    node: Node,
    state: io.github.noamcohen48.tap.api.v1.ElementSnapshot,
): String? =
    when (node.kindCase) {
        Node.KindCase.MATCH -> {
            when (node.match.property) {
                TextProperty.PROPERTY_TEXT -> if (state.hasText()) quoted(state.text) else "null"
                TextProperty.PROPERTY_CONTENT_DESCRIPTION -> if (state.hasContentDescription()) quoted(state.contentDescription) else "null"
                TextProperty.PROPERTY_HINT -> if (state.hasHint()) quoted(state.hint) else "null"
                TextProperty.PROPERTY_CLASS_NAME -> if (state.hasClassName()) quoted(state.className) else "null"
                TextProperty.PROPERTY_PACKAGE_NAME -> if (state.hasPackageName()) quoted(state.packageName) else "null"
                else -> null
            }
        }

        Node.KindCase.FLAG -> {
            when (node.flag.property) {
                NodeFlag.FLAG_ENABLED -> state.enabled
                NodeFlag.FLAG_CHECKED -> state.checked
                NodeFlag.FLAG_CHECKABLE -> state.checkable
                NodeFlag.FLAG_CLICKABLE -> state.clickable
                NodeFlag.FLAG_FOCUSED -> state.focused
                NodeFlag.FLAG_FOCUSABLE -> state.focusable
                NodeFlag.FLAG_LONG_CLICKABLE -> state.longClickable
                NodeFlag.FLAG_SCROLLABLE -> state.scrollable
                NodeFlag.FLAG_SELECTED -> state.selected
                else -> null
            }?.toString()
        }

        Node.KindCase.RESOURCE -> if (state.hasResourceName()) quoted(state.resourceName) else "null"

        else -> null
    }

/**
 * [ErrorProto.getNarrowing] described against the command's [selectors] (`selector`, then drag's
 * `target`); null when absent or when it does not fit them.
 */
internal fun ErrorProto.narrowingOrNull(selectors: List<SelectorProto>): SelectorNarrowing? {
    if (!hasNarrowing()) return null
    val selector = selectors.getOrNull(narrowing.selectorIndex) ?: return null
    val operands = if (selector.node.kindCase == Node.KindCase.ALL_OF) selector.node.allOf.nodesList else emptyList()
    if (narrowing.partsCount > 0 && narrowing.partsCount != operands.size) return null
    return SelectorNarrowing(
        selector = describe(selector),
        matches = narrowing.matches,
        needed = if (selector.pickCase == SelectorProto.PickCase.AT) selector.at.index + 1 else 1,
        parts =
            operands.zip(narrowing.partsList) { operand, part ->
                NarrowingPart(describe(operand), part.alone, part.without, part.nearMissesList.toList())
            },
    )
}

/** The selectors a sent [command] carries, in `Error.narrowing` order; [target] when it has no drag. */
internal fun commandSelectors(
    command: Command,
    target: SelectorProto?,
): List<SelectorProto> = if (command.hasDrag()) listOf(command.drag.selector, command.drag.target) else listOfNotNull(target)

private fun operand(node: Node): String =
    if (node.kindCase == Node.KindCase.ALL_OF || node.kindCase == Node.KindCase.ANY_OF) "(${describe(node)})" else describe(node)

private fun camel(name: String): String =
    name.lowercase().split('_').mapIndexed { i, word -> if (i == 0) word else word.replaceFirstChar(Char::uppercase) }.joinToString("")

internal fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
