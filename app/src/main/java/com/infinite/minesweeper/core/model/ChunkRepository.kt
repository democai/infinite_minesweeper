package com.infinite.minesweeper.core.model

interface ChunkRepository {
    suspend fun getChunk(coord: ChunkCoord): Chunk?

    suspend fun getChunks(coords: Set<ChunkCoord>): Map<ChunkCoord, Chunk>

    /**
     * Every chunk currently [ChunkStatus.LOCKED]. Used on cold start so surrounded locks outside
     * the viewport window can still soft-resolve without waiting for the player to pan back.
     */
    suspend fun getLockedChunks(): Map<ChunkCoord, Chunk>

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
