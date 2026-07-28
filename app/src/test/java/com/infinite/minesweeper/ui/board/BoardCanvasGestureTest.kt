package com.infinite.minesweeper.ui.board

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCanvasGestureTest {
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
