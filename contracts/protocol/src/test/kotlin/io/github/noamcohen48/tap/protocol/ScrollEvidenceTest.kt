package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ScrollObservation.Edge
import io.github.noamcohen48.tap.api.v1.ScrollObservation.Movement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScrollEvidenceTest {
    @Test
    fun `no events zero ranges and unset deltas stay unknown`() {
        val evidence = ScrollEvidence(Direction.DIR_DOWN)
        assertEquals(Edge.EDGE_UNSPECIFIED, evidence.snapshot().edge)
        evidence.observe(0, 0, 0)
        evidence.observe(-1, -1, -1)
        assertEquals(Edge.EDGE_UNSPECIFIED, evidence.snapshot().edge)
        assertEquals(Movement.MOVEMENT_UNSPECIFIED, evidence.snapshot().movement)
    }

    @Test
    fun `last gesture can move and reach edge`() {
        val evidence = ScrollEvidence(Direction.DIR_DOWN)
        evidence.observe(100, 100, 25)
        assertEquals(Edge.EDGE_REACHED, evidence.snapshot().edge)
        assertEquals(Movement.MOVEMENT_MOVED, evidence.snapshot().movement)
    }

    @Test
    fun `latest usable offset supersedes earlier edge`() {
        val evidence = ScrollEvidence(Direction.DIR_UP)
        evidence.observe(0, 100, -10)
        evidence.observe(10, 100, 10)
        evidence.observe(-1, -1, 0)
        assertEquals(Edge.EDGE_NOT_REACHED, evidence.snapshot().edge)
    }

    @Test
    fun `all four directional edges use offsets not item ordering`() {
        for (direction in listOf(Direction.DIR_DOWN, Direction.DIR_RIGHT)) {
            val evidence = ScrollEvidence(direction)
            evidence.observe(100, 100, 0)
            assertEquals(Edge.EDGE_REACHED, evidence.snapshot().edge)
        }
        for (direction in listOf(Direction.DIR_UP, Direction.DIR_LEFT)) {
            val evidence = ScrollEvidence(direction)
            evidence.observe(0, 100, 0)
            assertEquals(Edge.EDGE_REACHED, evidence.snapshot().edge)
        }
    }
}
