package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.ResourceId
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
            when (scopeCase) {
                SelectorProto.ScopeCase.AUT -> message("aut") {}
                SelectorProto.ScopeCase.SYSTEM -> message("system") { string("package_name", system.packageName) }
                SelectorProto.ScopeCase.ANY_WINDOW, SelectorProto.ScopeCase.SCOPE_NOT_SET, null -> Unit
            }
            when (pickCase) {
                SelectorProto.PickCase.EXACTLY_ONE -> message("exactly_one") {}
                SelectorProto.PickCase.FIRST -> message("first") {}
                SelectorProto.PickCase.AT -> message("at") { int("index", at.index) }
                SelectorProto.PickCase.PICK_NOT_SET, null -> Unit
            }
            // Field 7, so after the pick (4-6) in number order.
            if (scopeCase == SelectorProto.ScopeCase.ANY_WINDOW) message("any_window") {}
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
    bool("aut_package", resource.autPackage)
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
