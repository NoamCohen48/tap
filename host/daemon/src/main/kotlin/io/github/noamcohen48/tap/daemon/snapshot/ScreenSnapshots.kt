package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.Bounds
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.ScreenNode

/** One parsed screen before ref alignment: [nodes] have no `ref` and no `change` yet. */
internal class Screen(
    val rotation: Int,
    val nodes: List<ScreenNode>,
)

/** Dump XML → [Screen]: parse, synthesise a selector per node, convert to `ScreenNode`s. */
internal object ScreenSnapshots {
    fun screen(
        xml: String,
        autPackage: String,
    ): Screen {
        val hierarchy = HierarchyParser.parse(xml)
        val selectors = SelectorSynthesis(hierarchy, autPackage).synthesise()
        return Screen(hierarchy.rotation, hierarchy.nodes.map { screenNode(it, selectors[it.index]) })
    }

    fun screenNode(
        node: DumpNode,
        synthesised: Synthesised?,
    ): ScreenNode =
        ScreenNode
            .newBuilder()
            .setDepth(node.depth)
            .setWindowPackage(node.windowPackage)
            .apply {
                node.className?.let { className = it }
                node.resourceName?.let { resourceName = it }
                node.text?.let { text = it }
                node.contentDescription?.let { contentDescription = it }
                node.hint?.let { hint = it }
                node.bounds?.let { (l, t, r, b) -> bounds = Bounds.newBuilder().setLeft(l).setTop(t).setRight(r).setBottom(b).build() }
                addAllFlags(flags(node))
                password = node.password
                interactive = node.clickable || node.longClickable || node.checkable || node.scrollable || node.editable
                synthesised?.let {
                    selector = it.selector
                    byIndex = it.byIndex
                }
            }.build()

    private val DumpNode.editable: Boolean get() = className?.endsWith("EditText") == true

    private fun flags(node: DumpNode): List<NodeFlag> =
        listOfNotNull(
            NodeFlag.FLAG_ENABLED.takeIf { node.enabled },
            NodeFlag.FLAG_CHECKED.takeIf { node.checked },
            NodeFlag.FLAG_CHECKABLE.takeIf { node.checkable },
            NodeFlag.FLAG_CLICKABLE.takeIf { node.clickable },
            NodeFlag.FLAG_FOCUSED.takeIf { node.focused },
            NodeFlag.FLAG_FOCUSABLE.takeIf { node.focusable },
            NodeFlag.FLAG_LONG_CLICKABLE.takeIf { node.longClickable },
            NodeFlag.FLAG_SCROLLABLE.takeIf { node.scrollable },
            NodeFlag.FLAG_SELECTED.takeIf { node.selected },
        )
}
