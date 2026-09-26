package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.AutScope
import io.github.noamcohen48.tap.api.v1.ExactlyOne
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.ResourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SelectorsTest {
    @Test
    fun combinatorsFlattenAndCollapse() {
        val a = Nodes.text("a")
        val b = Nodes.text("b")
        val c = Nodes.text("c")
        assertEquals(a, Nodes.allOf(a))
        assertEquals(listOf(a, b, c), (a and b and c).allOf.nodesList)
        assertEquals(listOf(a, b, c), (a or (b or c)).anyOf.nodesList)
        assertEquals(Node.KindCase.ANY_OF, ((a or b) and c).allOf.nodesList.first().kindCase)
        assertEquals(listOf(a, b, c), (a and (b and c)).conjunction)
        assertFailsWith<IllegalArgumentException> { Nodes.anyOf(emptyList()) }
    }

    @Test
    fun defaultsAreMadeExplicitForComparison() {
        val selector = Selectors.text("OK")
        assertEquals(AutScope.getDefaultInstance(), selector.effectiveScope)
        assertEquals(ExactlyOne.getDefaultInstance(), selector.effectivePick)
        assertEquals(selector.toBuilder().setAut(AutScope.getDefaultInstance()).build().effectiveScope, selector.effectiveScope)
        assertNull(selector.systemPackage)
        assertEquals("android", selector.inSystemPackage("android").systemPackage)
    }

    @Test
    fun resourceQualificationResolvesTheAutPackage() {
        assertEquals(AUT, ResourceId.newBuilder().setName("x").setAutPackage(true).build().qualifyingPackage(AUT))
        assertEquals("other", ResourceId.newBuilder().setName("x").setPackageName("other").build().qualifyingPackage(AUT))
        assertNull(ResourceId.newBuilder().setName("x").build().qualifyingPackage(AUT))
    }

    @Test
    fun rendersCompactly() {
        assertEquals("text=\"OK\"", Selectors.text("OK").render())
        assertEquals(
            "class^=\"android.\" & !enabled & parent(id=\"<aut>:id/row\") in android [2]",
            Selectors.of(
                Nodes.className("android.", MatchMode.MATCH_STARTS_WITH) and
                    Nodes.flag(NodeFlag.FLAG_ENABLED, false) and
                    Nodes.parent(Nodes.autResource("row")),
            ).inSystemPackage("android").pickAt(2).render(),
        )
        assertEquals(
            "(text~=\"a.*\" | desc*=\"b\") & id=\"tag\" [first]",
            Selectors.of((Nodes.text("a.*", MatchMode.MATCH_REGEX) or Nodes.contentDescription("b", MatchMode.MATCH_CONTAINS)) and Nodes.rawResource("tag"))
                .pickFirst()
                .render(),
        )
        assertEquals("text=\"say \\\"hi\\\"\"", Selectors.text("say \"hi\"").render())
    }
}
