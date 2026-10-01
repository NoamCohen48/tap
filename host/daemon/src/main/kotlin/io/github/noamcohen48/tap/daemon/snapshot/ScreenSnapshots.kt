package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.Bounds
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.ScreenNode
import io.github.noamcohen48.tap.api.v1.SelectorCandidate

/** One parsed screen before ref alignment: [nodes] have no `ref` and no `change` yet. */
internal class Screen(
    val rotation: Int,
    val nodes: List<ScreenNode>,
)

/**
 * Dump XML → [Screen]: parse, synthesise a selector per node (every candidate with
 * [candidates]), convert to `ScreenNode`s.
 */
internal object ScreenSnapshots {
    fun screen(
        xml: String,
        candidates: Boolean = false,
    ): Screen {
        val hierarchy = HierarchyParser.parse(xml)
        val synthesis = SelectorSynthesis(hierarchy)
        val selectors = if (candidates) synthesis.candidates() else synthesis.synthesise().map(::listOfNotNull)
        return Screen(hierarchy.rotation, hierarchy.nodes.map { screenNode(it, selectors[it.index], candidates) })
    }

    /** [synthesised] ranked, best first; with [candidates] all of them go into the node. */
    fun screenNode(
        node: DumpNode,
        synthesised: List<Synthesised>,
        candidates: Boolean = false,
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
                synthesised.firstOrNull()?.let {
                    selector = it.selector
                    byIndex = it.byIndex
                }
                if (candidates) {
                    synthesised.forEach { addCandidates(SelectorCandidate.newBuilder().setSelector(it.selector).setKind(it.kind.proto)) }
                }
            }.build()

    private val SelectorKind.proto: io.github.noamcohen48.tap.api.v1.SelectorKind
        get() =
            when (this) {
                SelectorKind.PLAIN -> io.github.noamcohen48.tap.api.v1.SelectorKind.SELECTOR_KIND_PLAIN
                SelectorKind.COMBINED -> io.github.noamcohen48.tap.api.v1.SelectorKind.SELECTOR_KIND_COMBINED
                SelectorKind.ANCESTOR -> io.github.noamcohen48.tap.api.v1.SelectorKind.SELECTOR_KIND_ANCESTOR
                SelectorKind.BY_INDEX -> io.github.noamcohen48.tap.api.v1.SelectorKind.SELECTOR_KIND_BY_INDEX
            }

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
