package com.infinite.minesweeper.ui.board

import androidx.compose.ui.graphics.toArgb
import com.infinite.minesweeper.core.model.CELLS_PER_CHUNK
import com.infinite.minesweeper.core.model.CHUNK_SIDE_LENGTH
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.ui.theme.BoardDimens
import com.infinite.minesweeper.ui.theme.LodPalette
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LodRendererTest {
    private val hidden = LodPalette.Hidden.toArgb()
    private val revealed = LodPalette.Revealed.toArgb()
    private val flagged = LodPalette.Flagged.toArgb()
    private val completed = LodPalette.CompletedChunk.toArgb()
    private val locked = LodPalette.LockedChunk.toArgb()

    @Test
    fun shouldUseLod_respectsThreshold() {
        assertTrue(LodRenderer.shouldUseLod(BoardDimens.LodThresholdDp - 0.01f))
        assertFalse(LodRenderer.shouldUseLod(BoardDimens.LodThresholdDp))
        assertFalse(LodRenderer.shouldUseLod(BoardDimens.BaseCellSizeDp))
    }

    @Test
    fun bake_perCellPalette_hiddenRevealedFlagged() {
        val cells = MutableList(CELLS_PER_CHUNK) { Cell(state = CellState.HIDDEN) }
        cells[0] = Cell(state = CellState.HIDDEN, isMine = true)
        cells[1] = Cell(state = CellState.REVEALED, adjacentMines = 3)
        cells[2] = Cell(state = CellState.FLAGGED, isMine = true)
        cells[3] = Cell(state = CellState.REVEALED, adjacentMines = 0)

        val pixels = LodRenderer.bakeArgbPixels(
            Chunk(coord = ChunkCoord(0, 0), generated = true, cells = cells),
        )

        assertEquals(CELLS_PER_CHUNK, pixels.size)
        assertEquals(hidden, pixels[0]) // hidden mine stays black — never mine-colored
        assertEquals(revealed, pixels[1])
        assertEquals(flagged, pixels[2])
        assertEquals(revealed, pixels[3])
        assertEquals(hidden, pixels[4])
    }

    @Test
    fun bake_lockedOverride_isFlatRed() {
        val cells = MutableList(CELLS_PER_CHUNK) { index ->
            when (index % 3) {
                0 -> Cell(state = CellState.HIDDEN)
                1 -> Cell(state = CellState.REVEALED, adjacentMines = 1)
                else -> Cell(state = CellState.FLAGGED, isMine = true)
            }
        }
        cells[27] = Cell(state = CellState.EXPLODED, isMine = true)

        val pixels = LodRenderer.bakeArgbPixels(
            Chunk(
                coord = ChunkCoord(1, -1),
                generated = true,
                cells = cells,
                status = ChunkStatus.LOCKED,
                lockedAt = 42L,
            ),
        )

        assertTrue(pixels.all { it == locked })
        assertArrayEquals(IntArray(CELLS_PER_CHUNK) { locked }, pixels)
    }

    @Test
    fun bake_completedAutoFlaggedChunk_isSolidGreyNotSpeckled() {
        // Auto-flag on completion: every non-mine revealed, every mine flagged.
        // Without the completed override this would be grey with red speckles.
        val mineIndices = setOf(5, 12, 33, 60)
        val cells = List(CELLS_PER_CHUNK) { index ->
            if (index in mineIndices) {
                Cell(state = CellState.FLAGGED, isMine = true)
            } else {
                Cell(state = CellState.REVEALED, adjacentMines = adjacencyStub(index))
            }
        }

        val chunk = Chunk(coord = ChunkCoord(-2, 3), generated = true, cells = cells)
        assertTrue(LodRenderer.isCompletedChunk(chunk))

        val pixels = LodRenderer.bakeArgbPixels(chunk)

        assertTrue(
            "completed chunk must be solid grey, found non-grey pixels",
            pixels.all { it == completed },
        )
        assertFalse(
            "auto-flagged mines must not appear as red speckles",
            pixels.any { it == flagged },
        )
    }

    @Test
    fun bake_activeFrontier_keepsPerCellSpeckle() {
        val cells = MutableList(CELLS_PER_CHUNK) { Cell(state = CellState.HIDDEN) }
        cells[0] = Cell(state = CellState.REVEALED, adjacentMines = 1)
        cells[1] = Cell(state = CellState.FLAGGED, isMine = true)
        // Remaining cells hidden — not completed.

        val pixels = LodRenderer.bakeArgbPixels(
            Chunk(coord = ChunkCoord(0, 0), generated = true, cells = cells),
        )

        assertEquals(revealed, pixels[0])
        assertEquals(flagged, pixels[1])
        assertEquals(hidden, pixels[2])
        assertFalse(LodRenderer.isCompletedChunk(
            Chunk(coord = ChunkCoord(0, 0), generated = true, cells = cells),
        ))
    }

    @Test
    fun bake_lockedTakesPriorityOverWouldBeCompletedCells() {
        val cells = List(CELLS_PER_CHUNK) { index ->
            if (index == 0) {
                Cell(state = CellState.EXPLODED, isMine = true)
            } else if (index % 11 == 0) {
                Cell(state = CellState.FLAGGED, isMine = true)
            } else {
                Cell(state = CellState.REVEALED, adjacentMines = 1)
            }
        }

        val pixels = LodRenderer.bakeArgbPixels(
            Chunk(
                coord = ChunkCoord(0, 0),
                generated = true,
                cells = cells,
                status = ChunkStatus.LOCKED,
                lockedAt = 1L,
            ),
        )

        assertTrue(pixels.all { it == locked })
    }

    @Test
    fun bake_ungeneratedChunk_isNotCompleted() {
        val chunk = Chunk(coord = ChunkCoord(9, 9), generated = false)
        assertFalse(LodRenderer.isCompletedChunk(chunk))
        assertTrue(LodRenderer.bakeArgbPixels(chunk).all { it == hidden })
    }

    @Test
    fun bake_rowMajorLayoutMatchesLocalIndices() {
        val cells = MutableList(CELLS_PER_CHUNK) { Cell(state = CellState.HIDDEN) }
        // local (7, 0) → index 7; local (0, 7) → index 56
        cells[7] = Cell(state = CellState.FLAGGED, isMine = true)
        cells[7 * CHUNK_SIDE_LENGTH] = Cell(state = CellState.REVEALED, adjacentMines = 2)

        val pixels = LodRenderer.bakeArgbPixels(
            Chunk(coord = ChunkCoord(0, 0), generated = true, cells = cells),
        )

        assertEquals(flagged, pixels[7])
        assertEquals(revealed, pixels[56])
    }

    private fun adjacencyStub(index: Int): Int = (index % 8)
}
