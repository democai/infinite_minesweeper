package com.infinite.minesweeper.core.model

interface ChunkRepository {
    suspend fun getChunk(coord: ChunkCoord): Chunk?

    suspend fun getChunks(coords: Set<ChunkCoord>): Map<ChunkCoord, Chunk>

    /**
     * Returns only saved chunks inside an inclusive coordinate rectangle.
     *
     * Overview rendering uses this instead of expanding a large viewport into a set containing
     * every possible coordinate (most of which have never been explored).
     */
    suspend fun getChunksInBounds(
        minCx: Int,
        minCy: Int,
        maxCx: Int,
        maxCy: Int,
    ): Map<ChunkCoord, Chunk> = getAllChunks().filterKeys { coord ->
        coord.cx in minCx..maxCx && coord.cy in minCy..maxCy
    }

    /**
     * Every chunk currently [ChunkStatus.LOCKED]. Used on cold start so surrounded locks outside
     * the viewport window can still soft-resolve without waiting for the player to pan back.
     */
    suspend fun getLockedChunks(): Map<ChunkCoord, Chunk>

    /**
     * Every durable chunk, with pending write-behind entries taking precedence. Used by save
     * export; not for normal gameplay hydration (see cold-start viewport windowing).
     */
    suspend fun getAllChunks(): Map<ChunkCoord, Chunk>

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

    /**
     * Permanently deletes every durable chunk and the meta row, and drops any queued writes.
     * Used by "Reset Game" to wipe all board/world progress.
     */
    suspend fun clearAll()
}
