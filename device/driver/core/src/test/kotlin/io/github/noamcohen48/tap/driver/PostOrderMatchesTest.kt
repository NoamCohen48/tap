package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostOrderMatchesTest {
    private class Node(
        val name: String,
        vararg val kids: Node,
    )

    // A decor LinearLayout around two LinearLayout rows, each holding a Button; a second window.
    private val decor =
        Node("decor:LinearLayout", Node("row1:LinearLayout", Node("ok:Button")), Node("row2:LinearLayout", Node("cancel:Button")))
    private val dialog = Node("dialog:LinearLayout", Node("allow:Button"))

    private fun walk(
        matching: String,
        limit: Int,
        roots: List<Node> = listOf(decor, dialog),
        throwOn: String? = null,
    ): Pair<List<String>, List<String>> {
        val recycled = mutableListOf<String>()
        val found =
            postOrderMatches(
                roots,
                children = { node -> node.kids.toList() },
                matches = { node ->
                    check(node.name != throwOn) { "stale ${node.name}" }
                    node.name.endsWith(matching)
                },
                recycle = { recycled += it.name },
                limit = limit,
            )
        return found.map { it.name } to recycled
    }

    @Test
    fun aMatchComesAfterTheMatchesInsideItAsByMatcherReturnsThem() {
        // The rows before the decor around them: at(0) is the first row on either plan.
        assertEquals(
            listOf("row1:LinearLayout", "row2:LinearLayout", "decor:LinearLayout", "dialog:LinearLayout"),
            walk(":LinearLayout", limit = 10).first,
        )
        assertEquals(listOf("ok:Button", "cancel:Button", "allow:Button"), walk(":Button", limit = 10).first)
    }

    @Test
    fun theLimitStopsTheWalkAndEveryNodeNotReturnedIsRecycled() {
        val (found, recycled) = walk(":LinearLayout", limit = 2)
        assertEquals(listOf("row1:LinearLayout", "row2:LinearLayout"), found)
        // The decor came after its rows and was not returned; the second window was never opened.
        assertEquals(setOf("ok:Button", "cancel:Button", "decor:LinearLayout", "dialog:LinearLayout"), recycled.toSet())
        assertTrue(found.none { it in recycled })
    }

    @Test
    fun aFailingNodeRecyclesEverythingAndPropagates() {
        val recycled = mutableListOf<String>()
        val error =
            runCatching {
                postOrderMatches(
                    listOf(decor, dialog),
                    { node -> node.kids.toList() },
                    { node ->
                        check(node.name != "cancel:Button") { "stale" }
                        node.name.endsWith(":LinearLayout")
                    },
                    { recycled += it.name },
                    limit = 10,
                )
            }.exceptionOrNull()
        assertEquals("stale", error?.message)
        // row1 had been found; it is released too, with every other node.
        assertEquals(setOf("decor:LinearLayout", "row1:LinearLayout", "ok:Button", "row2:LinearLayout", "cancel:Button", "dialog:LinearLayout"), recycled.toSet())
    }
}
