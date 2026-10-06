package io.github.noamcohen48.tap.daemon.snapshot

/**
 * One visible node of a UiAutomator hierarchy dump, in dump pre-order. Empty attributes are
 * absent (`null`). [parent] is the index of the parent node in [Hierarchy.nodes] (-1 for a
 * window root); the subtree of a node is the contiguous range `index + 1 until subtreeEnd`.
 */
internal class DumpNode(
    val index: Int,
    val parent: Int,
    val depth: Int,
    /** Which top-level window root the node is under, counting from 0 in dump order. */
    val window: Int,
    /** The package of that window root. */
    val windowPackage: String,
    val packageName: String?,
    val className: String?,
    val resourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    /** `[left, top, right, bottom]`, or null when the dump had no parsable bounds. */
    val bounds: IntArray?,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val password: Boolean,
    val selected: Boolean,
    /** `showing-hint`: false when the dump does not report it (UiAutomator's own dumper). */
    val showingHint: Boolean = false,
    val contentInvalid: Boolean = false,
    val error: String? = null,
) {
    var subtreeEnd: Int = index + 1
        internal set
}

/** A parsed dump: the `rotation` of `<hierarchy>` and every visible node in pre-order. */
internal class Hierarchy(
    val rotation: Int,
    val nodes: List<DumpNode>,
) {
    /** Window root packages in dump order; index = [DumpNode.window]. */
    val windowPackages: List<String> = nodes.filter { it.parent < 0 }.map { it.windowPackage }
}

/** The device's hierarchy dump is not the XML this parser accepts. */
class HierarchyParseException(
    message: String,
) : RuntimeException("Hierarchy dump rejected: $message")

/**
 * Parses the XML `UiDevice.dumpWindowHierarchy` writes (androidx `AccessibilityNodeInfoDumper`):
 * `<hierarchy rotation="…">` holding one `<node>` per window root, nested `<node>`s below.
 *
 * A small hand parser rather than SAX/StAX: the dump comes from a device, so the parser knows
 * no DTD at all (a `<!DOCTYPE` is rejected, and the only entities are the five predefined ones
 * and character references), and it needs no JAXP service lookup or resource bundles in the
 * native image. Nodes with `visible-to-user="false"` are dropped with their subtree, matching
 * what UiAutomator's own matching sees.
 */
internal object HierarchyParser {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private const val BYTE_ORDER_MARK = '﻿'

    fun parse(xml: String): Hierarchy = Reader(xml).parse()

    private class OpenElement(
        val name: String,
        val node: DumpNode?,
    )

    private class Reader(
        private val src: String,
    ) {
        private var pos = if (src.startsWith(BYTE_ORDER_MARK)) 1 else 0
        private val nodes = ArrayList<DumpNode>()

        /** One entry per open element; `null` node for `<hierarchy>`, unknown or invisible elements. */
        private val open = ArrayList<OpenElement>()
        private var hiddenDepth = 0
        private var rotation = 0
        private var sawHierarchy = false
        private var windowCount = 0

        fun parse(): Hierarchy {
            while (pos < src.length) {
                when {
                    src.startsWith("<?", pos) -> skipPast("?>")
                    src.startsWith("<!--", pos) -> skipPast("-->")
                    src.startsWith("<![CDATA[", pos) -> skipPast("]]>")
                    src.startsWith("<!", pos) -> fail("DTDs and declarations are not accepted")
                    src.startsWith("</", pos) -> endElement()
                    src[pos] == '<' -> startElement()
                    else -> skipText()
                }
            }
            if (open.isNotEmpty()) fail("unclosed element")
            if (!sawHierarchy) fail("no <hierarchy> element")
            return Hierarchy(rotation, nodes)
        }

        private fun startElement() {
            pos++
            val name = readName()
            val attributes = HashMap<String, String>()
            while (true) {
                skipWhitespace()
                if (pos >= src.length) fail("unterminated <$name>")
                when {
                    src.startsWith("/>", pos) -> {
                        pos += 2
                        open(name, attributes)
                        close(name)
                        return
                    }

                    src[pos] == '>' -> {
                        pos++
                        open(name, attributes)
                        return
                    }

                    else -> {
                        val key = readName()
                        skipWhitespace()
                        expect('=')
                        skipWhitespace()
                        if (attributes.put(key, readQuoted()) != null) fail("duplicate attribute $key")
                    }
                }
            }
        }

        private fun endElement() {
            pos += 2
            val name = readName()
            skipWhitespace()
            expect('>')
            close(name)
        }

        private fun open(
            name: String,
            attributes: Map<String, String>,
        ) {
            if (name == "hierarchy") {
                sawHierarchy = true
                rotation = attributes["rotation"]?.toIntOrNull() ?: 0
            }
            val visible = attributes["visible-to-user"] != "false"
            if (name != "node" || hiddenDepth > 0 || !visible) {
                if (name == "node" && (hiddenDepth > 0 || !visible)) hiddenDepth++
                open += OpenElement(name, null)
                return
            }
            val parent = open.lastOrNull { it.node != null }?.node
            val window = if (parent == null) windowCount++ else parent.window
            val node =
                DumpNode(
                    index = nodes.size,
                    parent = parent?.index ?: -1,
                    depth = if (parent == null) 0 else parent.depth + 1,
                    window = window,
                    windowPackage = parent?.windowPackage ?: attributes["package"].orEmpty(),
                    packageName = attributes.present("package"),
                    className = attributes.present("class"),
                    resourceName = attributes.present("resource-id"),
                    text = attributes.present("text"),
                    contentDescription = attributes.present("content-desc"),
                    hint = attributes.present("hint"),
                    bounds =
                        attributes["bounds"]?.let { BOUNDS.matchEntire(it) }?.groupValues?.drop(1)?.map { it.toInt() }?.toIntArray(),
                    checkable = attributes.flag("checkable"),
                    checked = attributes.flag("checked"),
                    clickable = attributes.flag("clickable"),
                    enabled = attributes.flag("enabled"),
                    focusable = attributes.flag("focusable"),
                    focused = attributes.flag("focused"),
                    scrollable = attributes.flag("scrollable"),
                    longClickable = attributes.flag("long-clickable"),
                    password = attributes.flag("password"),
                    selected = attributes.flag("selected"),
                    showingHint = attributes.flag("showing-hint"),
                    contentInvalid = attributes.flag("content-invalid"),
                    error = attributes.present("error"),
                )
            nodes += node
            open += OpenElement(name, node)
        }

        private fun close(name: String) {
            val top = open.removeLastOrNull() ?: fail("unexpected </$name>")
            if (top.name != name) fail("</$name> closes <${top.name}>")
            top.node?.subtreeEnd = nodes.size
            if (top.node == null && name == "node" && hiddenDepth > 0) hiddenDepth--
        }

        private fun readName(): String {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] in "-_:.")) pos++
            if (pos == start) fail("expected a name at offset $pos")
            return src.substring(start, pos)
        }

        /** A quoted attribute value, unescaped and with literal whitespace normalised as XML specifies. */
        private fun readQuoted(): String {
            if (pos >= src.length || (src[pos] != '"' && src[pos] != '\'')) fail("expected a quoted value at offset $pos")
            val quote = src[pos++]
            val out = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("unterminated attribute value")
                val c = src[pos]
                when (c) {
                    quote -> {
                        pos++
                        return out.toString()
                    }

                    '<' -> {
                        fail("'<' in an attribute value")
                    }

                    '&' -> {
                        out.append(readEntity())
                    }

                    '\n', '\r', '\t' -> {
                        out.append(' ')
                        pos++
                    }

                    else -> {
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        /** `&…;` at [pos]: the predefined entities and character references only; there is no DTD. */
        private fun readEntity(): String {
            val end = src.indexOf(';', pos)
            if (end < 0 || end - pos > 12) fail("malformed entity reference at offset $pos")
            val name = src.substring(pos + 1, end)
            pos = end + 1
            return when {
                name == "amp" -> "&"
                name == "lt" -> "<"
                name == "gt" -> ">"
                name == "quot" -> "\""
                name == "apos" -> "'"
                name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.substring(2).toIntOrNull(16), name)
                name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull(), name)
                else -> fail("undefined entity &$name;")
            }
        }

        private fun codePoint(
            value: Int?,
            name: String,
        ): String {
            if (value == null || value <= 0 || !Character.isValidCodePoint(value)) fail("invalid character reference &$name;")
            return String(Character.toChars(value))
        }

        private fun skipText() {
            val next = src.indexOf('<', pos)
            pos = if (next < 0) src.length else next
        }

        private fun skipPast(terminator: String) {
            val end = src.indexOf(terminator, pos)
            if (end < 0) fail("unterminated markup at offset $pos")
            pos = end + terminator.length
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) fail("expected '$c' at offset $pos")
            pos++
        }

        private fun fail(message: String): Nothing = throw HierarchyParseException(message)
    }

    private fun Map<String, String>.present(key: String): String? = this[key]?.takeIf { it.isNotEmpty() }

    private fun Map<String, String>.flag(key: String): Boolean = this[key] == "true"
}
