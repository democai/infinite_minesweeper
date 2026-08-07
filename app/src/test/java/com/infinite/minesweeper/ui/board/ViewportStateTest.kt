package com.infinite.minesweeper.ui.board

import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.ui.theme.BoardDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportStateTest {
    @Test
    fun visibleChunks_exactBoundaryIncludesRenderMargin() {
        val bounds = calculateVisibleChunkBounds(
            centerX = 0.0,
            centerY = 0.0,
            zoom = 1.0,
            viewportWidthPx = 640.0,
            viewportHeightPx = 640.0,
            baseCellSizePx = 40.0,
        )

        assertEquals(ChunkBounds(-2, -2, 1, 1), bounds)
        assertEquals(16, bounds!!.toSet().size)
    }

    @Test
    fun visibleChunks_handlesNegativeOffsetAndZoom() {
        val bounds = calculateVisibleChunkBounds(
            centerX = -12.0,
            centerY = 20.0,
            zoom = 2.0,
            viewportWidthPx = 640.0,
            viewportHeightPx = 320.0,
            baseCellSizePx = 40.0,
            renderMarginChunks = 0,
        )

        assertEquals(ChunkBounds(-2, 2, -2, 2), bounds)
        assertTrue(ChunkCoord(-2, 2) in bounds!!)
        assertTrue(ChunkCoord(0, 2) !in bounds)
    }

    @Test
    fun zeroSizedViewportHasNoVisibleChunks() {
        val state = ViewportState()

        assertTrue(state.visibleChunks(baseCellSizePx = 40.0).isEmpty())
    }

    @Test
    fun panMovesWorldCenterOppositeBoardMotion() {
        val state = ViewportState()
        state.updateViewportSize(widthPx = 400.0, heightPx = 400.0)

        state.applyTransformGesture(
            centroidX = 200.0,
            centroidY = 200.0,
            panX = 80.0,
            panY = -40.0,
            zoomChange = 1.0,
            baseCellSizePx = 40.0,
        )

        assertEquals(-2.0, state.centerX, 0.000_001)
        assertEquals(1.0, state.centerY, 0.000_001)
    }

    @Test
    fun pinchKeepsOffCenterWorldPointAnchored() {
        val state = ViewportState()
        state.updateViewportSize(widthPx = 400.0, heightPx = 400.0)

        state.applyTransformGesture(
            centroidX = 300.0,
            centroidY = 200.0,
            panX = 0.0,
            panY = 0.0,
            zoomChange = 2.0,
            baseCellSizePx = 40.0,
        )

        assertEquals(2.0, state.zoom, 0.000_001)
        assertEquals(1.25, state.centerX, 0.000_001)
        assertEquals(0.0, state.centerY, 0.000_001)
    }

    @Test
    fun zoomClampsAtBothLimits() {
        val state = ViewportState(minZoom = 0.5, maxZoom = 2.0)
        state.updateViewportSize(widthPx = 100.0, heightPx = 100.0)

        state.applyTransformGesture(50.0, 50.0, 0.0, 0.0, 100.0, 40.0)
        assertEquals(2.0, state.zoom, 0.0)

        state.applyTransformGesture(50.0, 50.0, 0.0, 0.0, 0.001, 40.0)
        assertEquals(0.5, state.zoom, 0.0)
    }

    @Test
    fun updateMinZoomReclampsCurrentZoom() {
        val state = ViewportState(initialZoom = 0.04, minZoom = 0.02, maxZoom = 4.0)
        state.updateMinZoom(0.05)
        assertEquals(0.05, state.minZoom, 0.0)
        assertEquals(0.05, state.zoom, 0.0)
    }

    @Test
    fun computeMinZoom_smallExploredBoundsStaysAtBaseline() {
        // One chunk: 8 cells × 40px = 320px per edge at zoom 1. Viewport 640×640 fits at zoom 2,
        // so fitZoom = 2 >> baseline 0.05.
        val minZoom = computeMinZoomFromExploredBounds(
            viewportWidthPx = 640.0,
            viewportHeightPx = 640.0,
            baseCellSizePx = 40.0,
            hasExploredBounds = true,
            exploredMinCx = 0,
            exploredMaxCx = 0,
            exploredMinCy = 0,
            exploredMaxCy = 0,
        )
        assertEquals(BoardDimens.MinZoom.toDouble(), minZoom, 0.0)
    }

    @Test
    fun computeMinZoom_largeExploredBoundsDropsBelowBaseline() {
        // 40×40 chunks = 320 cells per edge; at 40px/cell need zoom 640/(40*320)=0.05 to fit
        // width. Widen to 80×80 chunks so fitZoom falls under baseline.
        val minZoom = computeMinZoomFromExploredBounds(
            viewportWidthPx = 640.0,
            viewportHeightPx = 640.0,
            baseCellSizePx = 40.0,
            hasExploredBounds = true,
            exploredMinCx = 0,
            exploredMaxCx = 79,
            exploredMinCy = 0,
            exploredMaxCy = 79,
        )
        // fitZoom = 640 / (40 * 80 * 8) = 640 / 25600 = 0.025
        assertEquals(0.025, minZoom, 0.000_001)
    }

    @Test
    fun computeMinZoom_neverDropsBelowAbsoluteFloor() {
        val minZoom = computeMinZoomFromExploredBounds(
            viewportWidthPx = 640.0,
            viewportHeightPx = 640.0,
            baseCellSizePx = 40.0,
            hasExploredBounds = true,
            exploredMinCx = 0,
            exploredMaxCx = 999,
            exploredMinCy = 0,
            exploredMaxCy = 999,
            absoluteMinZoom = 0.01,
        )
        assertEquals(0.01, minZoom, 0.0)
    }

    @Test
    fun computeMinZoom_withoutExploredBoundsUsesSingleChunkSpan() {
        val minZoom = computeMinZoomFromExploredBounds(
            viewportWidthPx = 640.0,
            viewportHeightPx = 640.0,
            baseCellSizePx = 40.0,
            hasExploredBounds = false,
        )
        assertEquals(BoardDimens.MinZoom.toDouble(), minZoom, 0.0)
    }
}
