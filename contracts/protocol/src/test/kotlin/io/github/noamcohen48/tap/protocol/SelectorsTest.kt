package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.ResourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun resourceIdMatchesAQualifiedOrAPackageLessName() {
        val anyPackage = ResourceId.newBuilder().setName("login").build()
        assertTrue(anyPackage.matchesId("$AUT:id/login"))
        assertTrue(anyPackage.matchesId("android:id/login"))
        assertTrue(anyPackage.matchesId("login"), "a bare Compose testTag")
        listOf(null, "", "Login", "$AUT:id/login2", "$AUT:id/xlogin", ":id/login", "a:b:id/login", "$AUT:string/login")
            .forEach { assertFalse(anyPackage.matchesId(it), "$it") }

        val qualified = ResourceId.newBuilder().setName("login").setPackageName(AUT).build()
        assertTrue(qualified.matchesId("$AUT:id/login"))
        assertFalse(qualified.matchesId("android:id/login"))
        assertFalse(qualified.matchesId("login"))
    }

    @Test
    fun rendersCompactly() {
        assertEquals("text=\"OK\"", Selectors.text("OK").render())
        assertEquals(
            "class^=\"android.\" & !enabled & parent(id=\"row\") & package=\"android\" [2]",
            Selectors.of(
                Nodes.className("android.", MatchMode.MATCH_STARTS_WITH) and
                    Nodes.flag(NodeFlag.FLAG_ENABLED, false) and
                    Nodes.parent(Nodes.resource("row")) and
                    Nodes.packageName("android"),
            ).pickAt(2).render(),
        )
        assertEquals("id=\"$AUT:id/login\"", Selectors.androidResource(AUT, "login").render())
        assertEquals(
            "(text~=\"a.*\" | desc*=\"b\") & id=\"tag\" [first]",
            Selectors.of((Nodes.text("a.*", MatchMode.MATCH_REGEX) or Nodes.contentDescription("b", MatchMode.MATCH_CONTAINS)) and Nodes.resource("tag"))
                .pickFirst()
                .render(),
        )
        assertEquals("text=\"say \\\"hi\\\"\"", Selectors.text("say \"hi\"").render())
        assertEquals("text=\"a\\nb\\\\c\\u0001\"", Selectors.text("a\nb\\c\u0001").render())
    }
}
