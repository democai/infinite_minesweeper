package com.infinite.minesweeper.core.model

data class GenerationResult(
    /**
     * Every new or modified chunk, keyed by coordinate.
     *
     * This may include neighboring chunks whose adjacency values were patched.
     */
    val chunks: Map<ChunkCoord, Chunk>,
)

interface MineGenerator {
    fun mineDensityFor(coord: ChunkCoord): Float

    /**
     * Generates the touched chunk and any lazy neighbors needed to make its adjacency values
     * immediately usable. [knownChunks] is the caller's current working set.
     */
    suspend fun generateForFirstTouch(
        firstTouch: CellCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult

    /**
     * Rolls any not-yet-generated neighbor of [center] with an empty exclusion zone, then patches
     * adjacency on the affected generated set. Used so a chunk that is about to reveal cells has
     * a complete mine neighborhood (stable border numbers).
     *
     * Pre-generated neighbors are not first-touch-safe on later entry — the same tradeoff as
     * today's lazy neighbors of an original touch.
     */
    suspend fun ensureNeighborsGenerated(
        center: ChunkCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult

    /**
     * Wipes and re-rolls [coord], returning it plus all neighboring chunks whose one-cell border
     * changed. The generator's configured seed makes equivalent requests deterministic.
     */
    suspend fun reroll(
        coord: ChunkCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult
}
