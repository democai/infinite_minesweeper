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
     * Wipes and re-rolls [coord], returning it plus all neighboring chunks whose one-cell border
     * changed. The generator's configured seed makes equivalent requests deterministic.
     */
    suspend fun reroll(
        coord: ChunkCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult
}
