package com.infinite.minesweeper.ui.settings

import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HintMenuPolicyTest {

    private val coord = ChunkCoord(0, 0)
    private val revealed = CellCoord(0, 0)
    private val playableHidden = CellCoord(1, 0)
    private val farHidden = CellCoord(7, 7)

    @Test
    fun longPressOnRevealedCellInActiveSelector_opensMenu() {
        val chunk = activeChunk(revealedAt = setOf(0 to 0))
        assertTrue(
            HintMenuPolicy.shouldOpen(
                cell = revealed,
                cellState = CellState.REVEALED,
                binding = InputBinding.Default,
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    @Test
    fun longPressOnPlayableHidden_doesNotOpenMenu() {
        val chunk = activeChunk(revealedAt = setOf(0 to 0))
        assertFalse(
            HintMenuPolicy.shouldOpen(
                cell = playableHidden,
                cellState = CellState.HIDDEN,
                binding = InputBinding.Default,
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    @Test
    fun longPressOnUnreachableHidden_opensMenu() {
        val chunk = activeChunk(revealedAt = setOf(0 to 0))
        assertTrue(
            HintMenuPolicy.shouldOpen(
                cell = farHidden,
                cellState = CellState.HIDDEN,
                binding = InputBinding.Default,
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    @Test
    fun longPressRevealBindingOnFlaggedCell_opensMenu() {
        val chunk = activeChunk(revealedAt = setOf(0 to 0), flaggedAt = setOf(1 to 0))
        assertTrue(
            HintMenuPolicy.shouldOpen(
                cell = playableHidden,
                cellState = CellState.FLAGGED,
                binding = InputBinding.Default, // long-press → reveal
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    @Test
    fun longPressFlagBindingOnFlaggedCell_doesNotOpenMenu() {
        val chunk = activeChunk(revealedAt = setOf(0 to 0), flaggedAt = setOf(1 to 0))
        assertFalse(
            HintMenuPolicy.shouldOpen(
                cell = playableHidden,
                cellState = CellState.FLAGGED,
                binding = InputBinding.TAP_REVEAL_LONG_PRESS_FLAG,
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    @Test
    fun solvedSelector_neverOpensHintMenu() {
        val cells = List(64) { Cell(state = CellState.REVEALED) }
        val chunk = Chunk(coord = coord, generated = true, cells = cells)
        assertTrue(chunk.isSolved)
        assertFalse(
            HintMenuPolicy.shouldOpen(
                cell = revealed,
                cellState = CellState.REVEALED,
                binding = InputBinding.Default,
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    @Test
    fun lockedSelector_neverOpensHintMenu() {
        val chunk = activeChunk(revealedAt = setOf(0 to 0))
            .copy(status = ChunkStatus.LOCKED)
        assertFalse(
            HintMenuPolicy.shouldOpen(
                cell = revealed,
                cellState = CellState.REVEALED,
                binding = InputBinding.Default,
                chunk = chunk,
                chunks = mapOf(coord to chunk),
                hasEverRevealed = true,
            ),
        )
    }

    private fun activeChunk(
        revealedAt: Set<Pair<Int, Int>>,
        flaggedAt: Set<Pair<Int, Int>> = emptySet(),
    ): Chunk {
        val cells = List(64) { index ->
            val x = index % 8
            val y = index / 8
            when {
                (x to y) in revealedAt -> Cell(state = CellState.REVEALED)
                (x to y) in flaggedAt -> Cell(state = CellState.FLAGGED)
                else -> Cell()
            }
        }
        return Chunk(coord = coord, generated = true, cells = cells)
    }
}
