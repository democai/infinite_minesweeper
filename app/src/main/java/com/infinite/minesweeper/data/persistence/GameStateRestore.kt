package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.coords.cellToChunk
import com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic
import com.infinite.minesweeper.core.engine.lock.neighboringChunkCoords
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.MineGenerator
import kotlin.math.roundToInt

const val DEFAULT_RESTORE_WINDOW_RADIUS_CHUNKS: Int = 2

/**
 * Rehydrates just enough of a saved session to resume play, then soft-resolves any locks that
 * are already surrounded by solved selectors.
 *
 * Viewport chunks alone are not enough: a locked selector outside the restore window (or locked
 * into a ring that completed while it was off-screen) would otherwise sit red forever. Restore
 * therefore also pulls every [com.infinite.minesweeper.core.model.ChunkStatus.LOCKED] row plus its
 * 8 neighbors, runs [LockAndWipeMechanic.recheckSurroundedLocks], and writes back any chunks the
 * recheck mutated so the unlock survives the next cold start.
 *
 * Chunks with already-explored cells also get their outer neighbor ring generated before the
 * restored state is returned, so border numbers are not provisional zeros that jump when the
 * player later opens adjacent terra.
 */
suspend fun restoreGameState(
    repository: ChunkRepository,
    mineGenerator: MineGenerator,
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

    val locked = repository.getLockedChunks()
    val lockNeighborhood = buildSet {
        for (coord in locked.keys) {
            add(coord)
            addAll(neighboringChunkCoords(coord))
        }
    }

    val loadedKeys = window + lockNeighborhood
    val working = repository.getChunks(loadedKeys).toMutableMap()
    val repaired = repairRevealedNeighborhoods(working, repository, mineGenerator)

    val seeded = GameState(chunks = working, meta = meta)
    val resolved = LockAndWipeMechanic(mineGenerator).recheckSurroundedLocks(seeded)
    val chunksChanged = resolved.state.chunks != seeded.chunks || repaired
    val metaChanged = resolved.state.meta != seeded.meta
    if (chunksChanged || metaChanged) {
        if (chunksChanged) repository.saveChunks(resolved.state.chunks.values)
        if (metaChanged) repository.saveGameMeta(resolved.state.meta)
        repository.flush()
    }
    return resolved.state
}

/**
 * For every chunk that already has revealed/exploded cells, hydrate then generate any missing
 * neighbor selectors and patch adjacency in place on [chunks].
 *
 * @return true if [chunks] was mutated.
 */
internal suspend fun repairRevealedNeighborhoods(
    chunks: MutableMap<ChunkCoord, Chunk>,
    repository: ChunkRepository,
    mineGenerator: MineGenerator,
): Boolean {
    val centers = chunks.values
        .filter { chunk ->
            chunk.generated &&
                chunk.cells.any { it.state == CellState.REVEALED || it.state == CellState.EXPLODED }
        }
        .map { it.coord }
    if (centers.isEmpty()) return false

    var mutated = false
    for (center in centers) {
        val missing = neighboringChunkCoords(center)
            .filter { chunks[it]?.generated != true }
            .toSet()
        if (missing.isNotEmpty()) {
            for ((coord, loaded) in repository.getChunks(missing)) {
                if (loaded.generated && chunks[coord]?.generated != true) {
                    chunks[coord] = loaded
                    mutated = true
                }
            }
        }
        val result = mineGenerator.ensureNeighborsGenerated(center, chunks)
        if (result.chunks.isNotEmpty()) {
            chunks.putAll(result.chunks)
            mutated = true
        }
    }
    return mutated
}
