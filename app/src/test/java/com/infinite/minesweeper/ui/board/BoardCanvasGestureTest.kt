package com.infinite.minesweeper.ui.board

import androidx.compose.ui.geometry.Offset
import com.infinite.minesweeper.core.model.ChunkCoord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCanvasGestureTest {
    @Test
    fun resolveChunkCoord_centerOfCanvasResolvesToViewportCenterChunk() {
        val coord = resolveChunkCoord(
            position = Offset(400f, 400f),
            canvasWidth = 800f,
            canvasHeight = 800f,
            viewportCenterX = 12.0,
            viewportCenterY = -20.0,
            pixelsPerCell = 40.0,
        )

        // Cell (12, -20) sits in chunk (1, -3) — 8 cells per selector, floor-div toward -infinity.
        assertEquals(ChunkCoord(1, -3), coord)
    }

    @Test
    fun resolveChunkCoord_offsetPositionCrossesIntoNeighborChunk() {
        // One chunk-width (8 cells * 40px = 320px) east of center, from a viewport centered on
        // chunk (0, 0)'s origin cell.
        val coord = resolveChunkCoord(
            position = Offset(400f + 320f, 400f),
            canvasWidth = 800f,
            canvasHeight = 800f,
            viewportCenterX = 0.0,
            viewportCenterY = 0.0,
            pixelsPerCell = 40.0,
        )

        assertEquals(ChunkCoord(1, 0), coord)
    }

    @Test
    fun resolveChunkCoord_negativeWorldCoordinatesFloorTowardNegativeInfinity() {
        val coord = resolveChunkCoord(
            position = Offset(400f, 400f),
            canvasWidth = 800f,
            canvasHeight = 800f,
            viewportCenterX = -1.0,
            viewportCenterY = -1.0,
            pixelsPerCell = 40.0,
        )

        // Cell (-1, -1) is one cell west/north of the origin selector's top-left corner, so it
        // belongs to chunk (-1, -1), not (0, 0) — floorDiv(-1, 8) == -1, not 0.
        assertEquals(ChunkCoord(-1, -1), coord)
    }


    @Test
    fun exceedsTouchSlop_underSlopIsNotExceeded() {
        val start = Offset(0f, 0f)
        val current = Offset(5f, 0f)

        assertFalse(exceedsTouchSlop(start, current, slopPx = 10f))
    }

    @Test
    fun exceedsTouchSlop_exactlyAtSlopIsNotExceeded() {
        val start = Offset(0f, 0f)
        val current = Offset(10f, 0f)

        assertFalse(exceedsTouchSlop(start, current, slopPx = 10f))
    }

    @Test
    fun exceedsTouchSlop_justOverSlopIsExceeded() {
        val start = Offset(0f, 0f)
        val current = Offset(10.5f, 0f)

        assertTrue(exceedsTouchSlop(start, current, slopPx = 10f))
    }

    @Test
    fun exceedsTouchSlop_diagonalMovementUsesEuclideanDistance() {
        // 3-4-5 triangle: distance is exactly 5, not the larger of the two axis deltas.
        val start = Offset(0f, 0f)
        val current = Offset(3f, 4f)

        assertFalse(exceedsTouchSlop(start, current, slopPx = 5f))
        assertTrue(exceedsTouchSlop(start, current, slopPx = 4.9f))
    }

    @Test
    fun exceedsTouchSlop_noMovementIsNotExceeded() {
        val start = Offset(12f, 8f)

        assertFalse(exceedsTouchSlop(start, start, slopPx = 0f))
    }
}
