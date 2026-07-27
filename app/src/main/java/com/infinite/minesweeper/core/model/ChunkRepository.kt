package com.infinite.minesweeper.core.model

interface ChunkRepository {
    suspend fun getChunk(coord: ChunkCoord): Chunk?

    suspend fun getChunks(coords: Set<ChunkCoord>): Map<ChunkCoord, Chunk>

    /**
     * Adds or replaces a chunk in the repository's write-behind queue.
     */
    suspend fun saveChunk(chunk: Chunk)

    suspend fun saveChunks(chunks: Collection<Chunk>)

    suspend fun getGameMeta(): GameMeta?

    /**
     * Adds or replaces metadata in the repository's write-behind queue.
     */
    suspend fun saveGameMeta(meta: GameMeta)

    /**
     * Persists every queued write before returning.
     */
    suspend fun flush()
}
