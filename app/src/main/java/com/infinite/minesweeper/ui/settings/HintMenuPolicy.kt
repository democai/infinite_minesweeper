package com.infinite.minesweeper.ui.settings

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.engine.isPlayableCell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameAction

/**
 * Decides when a cell-level long-press should open the active-selector hint menu instead of
 * dispatching a reveal/flag action. Mirrors the engine's no-op cases: cleared cells, reveal
 * gestures on flagged cells, and touches outside the playable frontier.
 */
object HintMenuPolicy {
    fun shouldOpen(
        cell: CellCoord,
        cellState: CellState,
        binding: InputBinding,
        chunk: Chunk,
        chunks: Map<ChunkCoord, Chunk>,
        hasEverRevealed: Boolean,
    ): Boolean {
        if (!chunk.generated || chunk.isSolved || chunk.status == ChunkStatus.LOCKED) return false
        if (cellToChunk(cell) != chunk.coord) return false

        val action = InputActionMapper.map(TapKind.LONG_PRESS, cell, cellState, binding)
        if (action == null) return true

        // First-ever reveal is bootstrap-exempt in the engine; never steal that into the menu.
        if (!hasEverRevealed && action is GameAction.Reveal) return false

        return when (action) {
            is GameAction.Reveal, is GameAction.ToggleFlag -> !isPlayableCell(cell, chunks)
            is GameAction.Chord -> false
        }
    }
}
