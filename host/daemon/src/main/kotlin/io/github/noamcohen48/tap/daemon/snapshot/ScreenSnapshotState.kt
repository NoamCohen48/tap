package io.github.noamcohen48.tap.daemon.snapshot

import io.github.noamcohen48.tap.api.v1.NodeChange
import io.github.noamcohen48.tap.api.v1.ResolveRefResponse
import io.github.noamcohen48.tap.api.v1.ScreenNode
import io.github.noamcohen48.tap.api.v1.ScreenSnapshotResponse

/** The ref is not in the attached device's latest screen snapshot. Maps to NOT_FOUND / UNKNOWN_REF. */
class UnknownRefException(
    ref: String,
    snapshotId: Long,
) : NoSuchElementException(
        if (snapshotId == 0L) {
            "Unknown ref @$ref: no screen snapshot was taken on this device yet"
        } else {
            "Unknown ref @$ref: not in the latest screen snapshot ($snapshotId); take a new snapshot"
        },
    )

/** The ref's node had no selector that matched it alone. Maps to FAILED_PRECONDITION / REF_NOT_ADDRESSABLE. */
class RefNotAddressableException(
    ref: String,
) : RuntimeException("Ref @$ref has no selector that matches its node alone; it cannot be acted on")

/**
 * The screen-snapshot state of one attached device: its latest snapshot, the ref counter and the
 * snapshot id. A ref is `e<N>` from a per-device counter and is never reused; a new snapshot is
 * aligned with the latest one ([RefAlignment]) so unchanged nodes keep their ref. Recording and
 * resolving are serialised, so concurrent snapshots on one device cannot interleave.
 */
internal class ScreenSnapshotState {
    private var snapshotId = 0L
    private var nextRef = 1L
    private var latest: List<ScreenNode> = emptyList()
    private var byRef: Map<String, ScreenNode> = emptyMap()

    /** Aligns [screen] with the latest snapshot, assigns refs and changes, and makes it the latest. */
    @Synchronized
    fun record(screen: Screen): ScreenSnapshotResponse {
        val first = snapshotId == 0L
        val previous = latest
        val matched = RefAlignment.align(previous.map(NodeSignature::of), screen.nodes.map(NodeSignature::of))
        val kept = BooleanArray(previous.size)
        val nodes =
            screen.nodes.mapIndexed { i, node ->
                val old = matched[i]
                node
                    .toBuilder()
                    .apply {
                        if (old >= 0) {
                            kept[old] = true
                            ref = previous[old].ref
                            change = NodeChange.NODE_UNCHANGED
                        } else {
                            ref = "e${nextRef++}"
                            change = if (first) NodeChange.NODE_CHANGE_UNSPECIFIED else NodeChange.NODE_ADDED
                        }
                    }.build()
            }
        val removed = previous.filterIndexed { j, _ -> !kept[j] }.map { it.toBuilder().setChange(NodeChange.NODE_REMOVED).build() }
        snapshotId++
        latest = nodes
        byRef = nodes.associateBy { it.ref }
        return ScreenSnapshotResponse
            .newBuilder()
            .setSnapshotId(snapshotId)
            .addAllNodes(nodes)
            .addAllRemoved(removed)
            .setRotation(screen.rotation)
            .build()
    }

    /** The selector [ref] (with or without a leading `@`) names in the latest snapshot. */
    @Synchronized
    fun resolve(ref: String): ResolveRefResponse {
        val name = ref.removePrefix("@")
        val node = byRef[name] ?: throw UnknownRefException(name, snapshotId)
        if (!node.hasSelector()) throw RefNotAddressableException(name)
        return ResolveRefResponse
            .newBuilder()
            .setSelector(node.selector)
            .setByIndex(node.byIndex)
            .setSnapshotId(snapshotId)
            .build()
    }
}
