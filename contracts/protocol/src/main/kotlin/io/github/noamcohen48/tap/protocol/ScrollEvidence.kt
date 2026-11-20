package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ScrollObservation
import io.github.noamcohen48.tap.api.v1.ScrollObservation.Edge
import io.github.noamcohen48.tap.api.v1.ScrollObservation.Movement

/** Accumulates only events already attributed to one command's scroll target. */
class ScrollEvidence(
    private val direction: Direction,
) {
    private var moved = false
    private var edge = Edge.EDGE_UNSPECIFIED

    /** Missing/zero deltas do not establish absence of movement. Latest usable edge wins. */
    @Synchronized
    fun observe(
        position: Int,
        maximum: Int,
        delta: Int,
    ) {
        // Android 9 uses -1 for an unset delta; a real -1 pixel move is indistinguishable.
        if (delta != 0 && delta != -1) moved = true
        val atEdge =
            when {
                position >= 0 && maximum > 0 && position <= maximum -> {
                    when (direction) {
                        Direction.DIR_UP, Direction.DIR_LEFT -> position == 0
                        Direction.DIR_DOWN, Direction.DIR_RIGHT -> position == maximum
                        else -> return
                    }
                }

                else -> {
                    return
                }
            }
        edge = if (atEdge) Edge.EDGE_REACHED else Edge.EDGE_NOT_REACHED
    }

    /** Detached evidence; no event or node retained. */
    @Synchronized
    fun snapshot(): ScrollObservation =
        ScrollObservation
            .newBuilder()
            .setMovement(if (moved) Movement.MOVEMENT_MOVED else Movement.MOVEMENT_UNSPECIFIED)
            .setEdge(edge)
            .build()
}
