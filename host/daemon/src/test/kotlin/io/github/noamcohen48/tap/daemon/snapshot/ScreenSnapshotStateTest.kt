package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.NodeChange
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.ScreenNode
import io.github.noamcohen48.tap.daemon.snapshot.Dumps.AUT
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenSnapshotStateTest {
    private fun screen(name: String): Screen = ScreenSnapshots.screen(Dumps.xml(name), AUT)

    private fun Screen.without(index: Int): Screen = Screen(rotation, nodes.filterIndexed { i, _ -> i != index })

    private fun Screen.with(
        index: Int,
        node: ScreenNode,
    ): Screen = Screen(rotation, nodes.toMutableList().apply { add(index, node) })

    @Test
    fun `nodes carry the dump's properties, flags and selector`() {
        val screen = screen("emulator-5554-MainActivity")
        val button = screen.nodes.single { it.resourceName == "$AUT:id/view_button" }
        assertEquals(AUT, button.windowPackage)
        assertEquals("android.widget.Button", button.className)
        assertTrue(button.interactive)
        assertTrue(NodeFlag.FLAG_CLICKABLE in button.flagsList && NodeFlag.FLAG_ENABLED in button.flagsList)
        assertFalse(NodeFlag.FLAG_CHECKED in button.flagsList)
        assertTrue(button.hasSelector() && !button.byIndex)
        assertTrue(button.bounds.right > button.bounds.left)
        val input = screen.nodes.single { it.resourceName == "$AUT:id/view_input" }
        assertTrue(input.interactive, "an EditText is editable, so interactive")
        assertFalse(screen.nodes.first().interactive)
        assertFalse(screen.nodes.first().hasText())
    }

    @Test
    fun `the same screen twice keeps every ref and marks it unchanged`() {
        val state = ScreenSnapshotState()
        val first = state.record(screen("85e49002-MainActivity"))
        assertEquals(1, first.snapshotId)
        assertTrue(first.nodesList.all { it.change == NodeChange.NODE_CHANGE_UNSPECIFIED })
        assertEquals((1..first.nodesCount).map { "e$it" }, first.nodesList.map { it.ref })
        assertEquals(0, first.removedCount)

        val second = state.record(screen("85e49002-MainActivity"))
        assertEquals(2, second.snapshotId)
        assertEquals(first.nodesList.map { it.ref }, second.nodesList.map { it.ref })
        assertTrue(second.nodesList.all { it.change == NodeChange.NODE_UNCHANGED })
        assertEquals(0, second.removedCount)
    }

    @Test
    fun `a removed and an added node are the only changes, and refs are never reused`() {
        val state = ScreenSnapshotState()
        val base = screen("emulator-5554-AmbiguityActivity")
        val first = state.record(base)
        val refs = first.nodesList.map { it.ref }

        // Drop the "Duplicate taps" status and add a new row elsewhere.
        val dropped = base.nodes.indexOfFirst { it.resourceName == "$AUT:id/duplicate_status" }
        val added = ScreenNode.newBuilder().setDepth(5).setWindowPackage(AUT).setClassName("android.widget.TextView").setText("Toast").build()
        val second = state.record(base.without(dropped).with(10, added))
        assertEquals(listOf(refs[dropped]), second.removedList.map { it.ref })
        assertEquals(NodeChange.NODE_REMOVED, second.removedList.single().change)
        val addedNodes = second.nodesList.filter { it.change == NodeChange.NODE_ADDED }
        assertEquals(listOf("Toast"), addedNodes.map { it.text })
        assertEquals("e${refs.size + 1}", addedNodes.single().ref)
        assertEquals(refs - refs[dropped], second.nodesList.filter { it.change == NodeChange.NODE_UNCHANGED }.map { it.ref })

        // The status comes back: it is a new node with a new ref, not the old one.
        val third = state.record(base)
        val status = third.nodesList.single { it.resourceName == "$AUT:id/duplicate_status" }
        assertEquals(NodeChange.NODE_ADDED, status.change)
        assertEquals("e${refs.size + 2}", status.ref)
        assertEquals(listOf("Toast"), third.removedList.map { it.text })
        val everRef = (first.nodesList + second.nodesList + third.nodesList).groupBy { it.ref }
        everRef.values.forEach { same -> assertEquals(1, same.map(NodeSignature::of).distinct().size, "a ref names one node") }

        assertFailsWith<UnknownRefException> { state.resolve("@${refs[dropped]}") }
        assertFailsWith<UnknownRefException> { state.resolve(addedNodes.single().ref) }
        assertEquals(status.selector, state.resolve("@${status.ref}").selector)
        assertEquals(3, state.resolve(status.ref).snapshotId)
    }

    @Test
    fun `a moved node keeps its ref and a node whose text changed does not`() {
        val state = ScreenSnapshotState()
        val base = screen("emulator-5554-ViewListActivity")
        val first = state.record(base)
        val moved =
            Screen(
                base.rotation,
                base.nodes.map { node ->
                    node.toBuilder().apply { bounds = bounds.toBuilder().setTop(bounds.top + 50).setBottom(bounds.bottom + 50).build() }.build()
                },
            )
        assertEquals(first.nodesList.map { it.ref }, state.record(moved).nodesList.map { it.ref })
        val index = base.nodes.indexOfFirst { it.hasText() }
        val renamed = Screen(base.rotation, base.nodes.toMutableList().apply { set(index, get(index).toBuilder().setText("changed").build()) })
        val third = state.record(renamed)
        assertEquals(NodeChange.NODE_ADDED, third.nodesList[index].change)
        assertEquals(1, third.removedCount)
    }

    @Test
    fun `resolve names the selector, or says why it cannot`() {
        val state = ScreenSnapshotState()
        assertTrue(assertFailsWith<UnknownRefException> { state.resolve("e1") }.message!!.contains("no screen snapshot"))
        val snapshot = state.record(screen("emulator-5554-MainActivity"))
        val button = snapshot.nodesList.single { it.resourceName == "$AUT:id/view_button" }
        val resolved = state.resolve("@${button.ref}")
        assertEquals(button.selector, resolved.selector)
        assertFalse(resolved.byIndex)
        assertEquals(1, resolved.snapshotId)
        assertEquals(resolved, state.resolve(button.ref))
        assertTrue(assertFailsWith<UnknownRefException> { state.resolve("@e9999") }.message!!.contains("@e9999"))

        val bare = ScreenNode.newBuilder().setWindowPackage(AUT).build()
        val ref = state.record(Screen(0, listOf(bare))).nodesList.single().ref
        assertTrue(assertFailsWith<RefNotAddressableException> { state.resolve(ref) }.message!!.contains("@$ref"))
    }

    @Test
    fun `alignment matches the longest common subsequence, or greedily above the budget`() {
        fun sig(text: String) = NodeSignature("p", "C", null, text, null, null, 0)
        val old = listOf("a", "b", "c", "d", "b").map(::sig)
        val new = listOf("x", "b", "c", "y", "d", "b").map(::sig)
        assertContentEquals(intArrayOf(-1, 1, 2, -1, 3, 4), RefAlignment.align(old, new))
        // Greedy (budget 0): each new node takes the first unused old node with its signature.
        assertContentEquals(intArrayOf(-1, 1, 2, -1, 3, 4), RefAlignment.align(old, new, budget = 0))
        assertContentEquals(intArrayOf(1, 0), RefAlignment.align(listOf(sig("a"), sig("b")), listOf(sig("b"), sig("a")), budget = 0))
        assertContentEquals(intArrayOf(-1, 1), RefAlignment.align(listOf(sig("a"), sig("b")), listOf(sig("b2"), sig("b"))))
        assertContentEquals(intArrayOf(), RefAlignment.align(old, emptyList()))
    }
}
