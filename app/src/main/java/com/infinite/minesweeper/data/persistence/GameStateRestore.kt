package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import kotlin.math.roundToInt

const val DEFAULT_RESTORE_WINDOW_RADIUS_CHUNKS: Int = 2

/**
 * Rehydrates just enough of a saved session to resume play.
 *
 * A running [com.infinite.minesweeper.core.model.GameEngine]'s [GameState.chunks] only ever
 * grows for the life of the process (T8 never evicts a touched chunk), so seeding a fresh
 * instance from every row the player has ever touched would keep an unbounded amount of history
 * resident for the rest of the process's life. Restore instead loads [GameMeta] — which carries
 * the last viewport position — and a bounded square of chunks around it: "restore-on-launch
 * hydrating only viewport chunks", not a full-table load.
 */
suspend fun restoreGameState(
    repository: ChunkRepository,
    windowRadiusChunks: Int = DEFAULT_RESTORE_WINDOW_RADIUS_CHUNKS,
): GameState {
    require(windowRadiusChunks >= 0) { "windowRadiusChunks must be non-negative" }

    val meta = repository.getGameMeta() ?: GameMeta()
    val center = cellToChunk(CellCoord(meta.viewportX.roundToInt(), meta.viewportY.roundToInt()))
    val window = buildSet {
        for (dcy in -windowRadiusChunks..windowRadiusChunks) {
            for (dcx in -windowRadiusChunks..windowRadiusChunks) {
                add(ChunkCoord(cx = center.cx + dcx, cy = center.cy + dcy))
            }
        }
    }

    return GameState(chunks = repository.getChunks(window), meta = meta)
}
