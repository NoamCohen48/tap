package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.ScreenNode

/**
 * What makes a node "the same node" across two snapshots. Bounds are left out on purpose, so a
 * node that scrolled or moved keeps its ref; a changed text is a new node.
 */
internal data class NodeSignature(
    val windowPackage: String,
    val className: String?,
    val resourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val depth: Int,
) {
    companion object {
        fun of(node: ScreenNode): NodeSignature =
            NodeSignature(
                windowPackage = node.windowPackage,
                className = node.className.takeIf { node.hasClassName() },
                resourceName = node.resourceName.takeIf { node.hasResourceName() },
                text = node.text.takeIf { node.hasText() },
                contentDescription = node.contentDescription.takeIf { node.hasContentDescription() },
                hint = node.hint.takeIf { node.hasHint() },
                depth = node.depth,
            )
    }
}

/**
 * Aligns two pre-order node lists: the longest common subsequence of their signatures, after
 * the common prefix and suffix are matched directly. Above [budget] cells of LCS table the
 * middle falls back to a greedy match (each new node takes the earliest unused old node with
 * its signature), which keeps identity for unchanged nodes but may misplace duplicates.
 */
internal object RefAlignment {
    const val LCS_BUDGET = 4_000_000L

    /** For each index of [new], the index of its match in [old], or -1 for a new node. */
    fun align(
        old: List<NodeSignature>,
        new: List<NodeSignature>,
        budget: Long = LCS_BUDGET,
    ): IntArray {
        val result = IntArray(new.size) { -1 }
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) {
            result[prefix] = prefix
            prefix++
        }
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) {
            result[new.size - 1 - suffix] = old.size - 1 - suffix
            suffix++
        }
        val oldMiddle = old.subList(prefix, old.size - suffix)
        val newMiddle = new.subList(prefix, new.size - suffix)
        val middle =
            if (oldMiddle.size.toLong() * newMiddle.size <= budget) lcs(oldMiddle, newMiddle) else greedy(oldMiddle, newMiddle)
        middle.forEachIndexed { i, j -> if (j >= 0) result[prefix + i] = prefix + j }
        return result
    }

    private fun lcs(
        old: List<NodeSignature>,
        new: List<NodeSignature>,
    ): IntArray {
        val n = new.size
        val m = old.size
        val result = IntArray(n) { -1 }
        if (n == 0 || m == 0) return result
        // length[i * (m + 1) + j]: LCS of new[i..] and old[j..].
        val width = m + 1
        val length = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                length[i * width + j] =
                    if (new[i] == old[j]) {
                        length[(i + 1) * width + j + 1] + 1
                    } else {
                        maxOf(length[(i + 1) * width + j], length[i * width + j + 1])
                    }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                new[i] == old[j] -> result[i++] = j++
                length[(i + 1) * width + j] >= length[i * width + j + 1] -> i++
                else -> j++
            }
        }
        return result
    }

    private fun greedy(
        old: List<NodeSignature>,
        new: List<NodeSignature>,
    ): IntArray {
        val available = HashMap<NodeSignature, ArrayDeque<Int>>()
        old.forEachIndexed { j, signature -> available.getOrPut(signature) { ArrayDeque() }.addLast(j) }
        return IntArray(new.size) { i -> available[new[i]]?.removeFirstOrNull() ?: -1 }
    }
}
