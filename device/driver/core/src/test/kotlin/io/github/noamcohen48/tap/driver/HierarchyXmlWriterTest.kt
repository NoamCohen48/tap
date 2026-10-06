package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class HierarchyXmlWriterTest {
    @Test
    fun nestsNodesWithAttributesInOrder() {
        val out = StringWriter()
        HierarchyXmlWriter(out).apply {
            startHierarchy(1)
            startNode(listOf("index" to "0", "class" to "android.widget.FrameLayout"))
            startNode(listOf("index" to "0", "showing-hint" to "true"))
            endNode()
            endNode()
            endHierarchy()
        }

        assertEquals(
            "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n" +
                "<hierarchy rotation=\"1\">\n" +
                "  <node index=\"0\" class=\"android.widget.FrameLayout\">\n" +
                "    <node index=\"0\" showing-hint=\"true\" />\n" +
                "  </node>\n" +
                "</hierarchy>",
            out.toString(),
        )
    }

    @Test
    fun escapesMarkupAndReplacesCharactersXmlCannotCarry() {
        val out = StringWriter()
        HierarchyXmlWriter(out).apply {
            startHierarchy(0)
            startNode(listOf("error" to "a<b>&\"c\"\n\t\u0001\u0085﷐é"))
            endNode()
            endHierarchy()
        }

        assertEquals(
            "  <node error=\"a&lt;b&gt;&amp;&quot;c&quot;&#10;&#9;.\u0085.é\" />",
            out.toString().lines()[2],
        )
    }
}
