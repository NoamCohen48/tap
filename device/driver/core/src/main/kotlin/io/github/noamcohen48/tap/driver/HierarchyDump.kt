package io.github.noamcohen48.tap.driver

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.UiDevice
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * The diagnostic hierarchy dump: the XML `UiDevice.dumpWindowHierarchy` writes (uiautomator
 * 2.4 `AccessibilityNodeInfoDumper`: same elements, attributes, visible-children rule and
 * clipped bounds), plus the field state that dump leaves out: `showing-hint`,
 * `content-invalid` and `error`. Written independently, not copied, so the extra attributes
 * need no fork of UiAutomator. Diagnostic only: never on the selector or mutation path.
 */
internal object HierarchyDump {
    private val NAF_EXCLUDED_CLASSES =
        listOf("android.widget.GridView", "android.widget.GridLayout", "android.widget.ListView", "android.widget.TableLayout")

    fun write(
        device: UiDevice,
        out: OutputStream,
    ) {
        val width = device.displayWidth
        val height = device.displayHeight
        val xml = HierarchyXmlWriter(OutputStreamWriter(out, Charsets.UTF_8))
        xml.startHierarchy(device.displayRotation)
        for (root in device.windowRoots) {
            dumpNode(root, xml, 0, Rect(0, 0, width, height))
        }
        xml.endHierarchy()
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo,
        xml: HierarchyXmlWriter,
        index: Int,
        display: Rect,
    ) {
        xml.startNode(attributes(node, index, display))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isVisibleToUser) dumpNode(child, xml, i, display)
            @Suppress("DEPRECATION")
            child.recycle()
        }
        xml.endNode()
    }

    private fun attributes(
        node: AccessibilityNodeInfo,
        index: Int,
        display: Rect,
    ): List<Pair<String, String>> =
        buildList {
            if (!nafExcluded(node) && isNaf(node)) add("NAF" to "true")
            add("index" to index.toString())
            add("text" to node.text?.toString().orEmpty())
            add("resource-id" to node.viewIdResourceName.orEmpty())
            add("class" to node.className?.toString().orEmpty())
            add("package" to node.packageName?.toString().orEmpty())
            add("content-desc" to node.contentDescription?.toString().orEmpty())
            add("checkable" to node.isCheckable.toString())
            add("checked" to node.isChecked.toString())
            add("clickable" to node.isClickable.toString())
            add("enabled" to node.isEnabled.toString())
            add("focusable" to node.isFocusable.toString())
            add("focused" to node.isFocused.toString())
            add("scrollable" to node.isScrollable.toString())
            add("long-clickable" to node.isLongClickable.toString())
            add("password" to node.isPassword.toString())
            add("selected" to node.isSelected.toString())
            add("visible-to-user" to node.isVisibleToUser.toString())
            add("bounds" to visibleBounds(node, display).toShortString())
            add("drawing-order" to node.drawingOrder.toString())
            add("hint" to node.hintText?.toString().orEmpty())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("display-id" to (node.window?.displayId ?: Display.DEFAULT_DISPLAY).toString())
            }
            add("showing-hint" to node.isShowingHintText.toString())
            add("content-invalid" to node.isContentInvalid.toString())
            add("error" to node.error?.toString().orEmpty())
        }

    /**
     * Screen bounds clipped to the display and then to the node's window, as UiAutomator dumps
     * them. A clip with no overlap leaves the bounds unchanged (`intersect` returns false), also
     * as UiAutomator does.
     */
    @SuppressLint("CheckResult")
    private fun visibleBounds(
        node: AccessibilityNodeInfo,
        display: Rect,
    ): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        bounds.intersect(display)
        node.window?.let { window ->
            val windowBounds = Rect()
            window.getBoundsInScreen(windowBounds)
            bounds.intersect(windowBounds)
        }
        return bounds
    }

    private fun nafExcluded(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return NAF_EXCLUDED_CLASSES.any { className.endsWith(it) }
    }

    /** Enabled and clickable with no text or description on it or anywhere below it. */
    private fun isNaf(node: AccessibilityNodeInfo): Boolean =
        node.isClickable && node.isEnabled && node.contentDescription.isNullOrEmpty() && node.text.isNullOrEmpty() &&
            !hasLabelledDescendant(node)

    private fun hasLabelledDescendant(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.contentDescription.isNullOrEmpty() || !child.text.isNullOrEmpty() || hasLabelledDescendant(child)) return true
        }
        return false
    }
}

/**
 * Writes the dump's XML: `<?xml?>`, `<hierarchy rotation>` and nested `<node>`s with their
 * attributes in the given order, indented like the platform serializer. Characters XML cannot
 * carry become `.` (as UiAutomator does); markup and line breaks are escaped.
 */
internal class HierarchyXmlWriter(
    private val out: Writer,
) {
    private var depth = 0

    /** The innermost `<node>` has been opened but its start tag not yet closed with `>`. */
    private var pending = false

    fun startHierarchy(rotation: Int) {
        out.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        out.write("\n<hierarchy rotation=\"$rotation\">")
        depth = 1
    }

    fun startNode(attributes: List<Pair<String, String>>) {
        closePending()
        out.write("\n")
        repeat(depth) { out.write("  ") }
        out.write("<node")
        for ((name, value) in attributes) {
            out.write(" $name=\"")
            writeEscaped(value)
            out.write("\"")
        }
        pending = true
        depth++
    }

    fun endNode() {
        depth--
        if (pending) {
            out.write(" />")
            pending = false
        } else {
            out.write("\n")
            repeat(depth) { out.write("  ") }
            out.write("</node>")
        }
    }

    fun endHierarchy() {
        closePending()
        out.write("\n</hierarchy>")
        out.flush()
    }

    private fun closePending() {
        if (pending) {
            out.write(">")
            pending = false
        }
    }

    private fun writeEscaped(value: String) {
        for (ch in value) {
            when {
                ch == '&' -> out.write("&amp;")
                ch == '<' -> out.write("&lt;")
                ch == '>' -> out.write("&gt;")
                ch == '"' -> out.write("&quot;")
                ch == '\n' -> out.write("&#10;")
                ch == '\r' -> out.write("&#13;")
                ch == '\t' -> out.write("&#9;")
                invalidXmlChar(ch) -> out.write(".")
                else -> out.write(ch.code)
            }
        }
    }

    private companion object {
        /** The ranges UiAutomator's dumper replaces (XML 1.1 restricted and non-characters). */
        fun invalidXmlChar(ch: Char): Boolean =
            ch == '\u0000' || ch in '\u0001'..'\u0008' || ch in '\u000B'..'\u000C' || ch in '\u000E'..'\u001F' ||
                ch in '\u007F'..'\u0084' || ch in '\u0086'..'\u009F' || ch in '﷐'..'﷟'
    }
}
