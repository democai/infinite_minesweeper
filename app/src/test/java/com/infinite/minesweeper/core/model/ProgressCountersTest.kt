package com.infinite.minesweeper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProgressCountersTest {

    @Test
    fun recountProgress_countsFlagsAndSolvedSelectors() {
        val solved = Chunk(
            coord = ChunkCoord(0, 0),
            generated = true,
            cells = List(64) { index ->
                if (index == 0) {
                    Cell(state = CellState.FLAGGED, isMine = true)
                } else {
                    Cell(state = CellState.REVEALED)
                }
            },
        )
        val partial = Chunk(
            coord = ChunkCoord(1, 0),
            generated = true,
            cells = List(64) { index ->
                when (index) {
                    0 -> Cell(state = CellState.FLAGGED, isMine = true)
                    1 -> Cell(state = CellState.REVEALED)
                    else -> Cell()
                }
            },
        )

        val recounted = recountProgress(listOf(solved, partial))
        assertEquals(2, recounted.flagsPlaced)
        assertEquals(1, recounted.selectorsCleared)
    }

    @Test
    fun withRecountedProgress_healsDriftedMeta() {
        val solved = Chunk(
            coord = ChunkCoord(0, 0),
            generated = true,
            cells = List(64) { index ->
                if (index < 3) {
                    Cell(state = CellState.FLAGGED, isMine = true)
                } else {
                    Cell(state = CellState.REVEALED)
                }
            },
        )
        val drifted = GameMeta(flagsPlaced = 1, selectorsCleared = 0)
        val healed = drifted.withRecountedProgress(listOf(solved))
        assertEquals(3, healed.flagsPlaced)
        assertEquals(1, healed.selectorsCleared)
    }

    @Test
    fun withRecountedProgress_returnsSameInstanceWhenAlreadyCorrect() {
        val meta = GameMeta(flagsPlaced = 0, selectorsCleared = 0)
        assertSame(meta, meta.withRecountedProgress(emptyList()))
    }
}
