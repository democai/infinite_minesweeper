package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta

/**
 * Test double standing in for T3's durable store.
 *
 * [DurableStore] is the "disk": two [InMemoryChunkRepository] instances built over the same
 * [DurableStore] observe each other's writes, letting tests simulate a fresh process reopening a
 * saved session without pulling in Room/Robolectric. Writes are synchronous (no debounce) since
 * T3 already covers write-behind timing; this double only needs to be durable, not asynchronous.
 */
class InMemoryChunkRepository(private val store: DurableStore = DurableStore()) : ChunkRepository {
    val saveChunksCalls: List<List<Chunk>> get() = _saveChunksCalls
    val savedMetas: List<GameMeta> get() = _savedMetas
    val flushCount: Int get() = _flushCount

    private val _saveChunksCalls = mutableListOf<List<Chunk>>()
    private val _savedMetas = mutableListOf<GameMeta>()
    private var _flushCount = 0

    override suspend fun getChunk(coord: ChunkCoord): Chunk? = store.chunks[coord]

    override suspend fun getChunks(coords: Set<ChunkCoord>): Map<ChunkCoord, Chunk> =
        coords.mapNotNull { coord -> store.chunks[coord]?.let { coord to it } }.toMap()

    override suspend fun getLockedChunks(): Map<ChunkCoord, Chunk> =
        store.chunks.filterValues { it.status == ChunkStatus.LOCKED }

    override suspend fun getAllChunks(): Map<ChunkCoord, Chunk> = store.chunks.toMap()

    override suspend fun saveChunk(chunk: Chunk) {
        store.chunks[chunk.coord] = chunk
        _saveChunksCalls += listOf(chunk)
    }

    override suspend fun saveChunks(chunks: Collection<Chunk>) {
        for (chunk in chunks) store.chunks[chunk.coord] = chunk
        _saveChunksCalls += chunks.toList()
    }

    override suspend fun getGameMeta(): GameMeta? = store.meta

    override suspend fun saveGameMeta(meta: GameMeta) {
        store.meta = meta
        _savedMetas += meta
    }

    override suspend fun flush() {
        _flushCount++
    }

    override suspend fun clearAll() {
        store.chunks.clear()
        store.meta = null
    }

    class DurableStore {
        val chunks = linkedMapOf<ChunkCoord, Chunk>()
        var meta: GameMeta? = null
    }
}
