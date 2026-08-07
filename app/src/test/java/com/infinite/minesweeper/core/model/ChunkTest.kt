package com.infinite.minesweeper.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkTest {

    @Test
    fun isSolvedRequiresExactFlagsAndRevealedSafeCells() {
        val solved = solvedChunk()

        assertTrue(solved.isSolved)
        assertFalse(
            solved.withCells(
                0 to solved.cells[0].copy(state = CellState.HIDDEN),
            ).isSolved,
        )
        assertFalse(
            solved.withCells(
                1 to solved.cells[1].copy(state = CellState.FLAGGED),
                0 to solved.cells[0].copy(state = CellState.HIDDEN),
            ).isSolved,
        )
        assertFalse(
            solved.withCells(
                1 to solved.cells[1].copy(state = CellState.FLAGGED),
            ).isSolved,
        )
        assertFalse(solved.copy(status = ChunkStatus.LOCKED, lockedAt = 1L).isSolved)
    }

    private fun solvedChunk(): Chunk = Chunk(
        coord = ChunkCoord(0, 0),
        generated = true,
        cells = List(CELLS_PER_CHUNK) { index ->
            if (index == 0) {
                Cell(state = CellState.FLAGGED, isMine = true)
            } else {
                Cell(state = CellState.REVEALED)
            }
        },
    )

    private fun Chunk.withCells(vararg replacements: Pair<Int, Cell>): Chunk {
        val updated = cells.toMutableList()
        replacements.forEach { (index, cell) -> updated[index] = cell }
        return copy(cells = updated)
    }
}
