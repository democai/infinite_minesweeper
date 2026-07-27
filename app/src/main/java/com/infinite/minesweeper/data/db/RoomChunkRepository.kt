package com.infinite.minesweeper.data.db

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.GameMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed [ChunkRepository] with a coalescing write-behind queue.
 *
 * [saveChunk], [saveChunks], and [saveGameMeta] enqueue on the caller's thread (main-safe).
 * Durable IO runs on [ioDispatcher] after [debounceMs] of quiet time, or immediately on [flush].
 * Reads prefer queued values so callers observe their own writes before debounce fires.
 */
class RoomChunkRepository(
    private val chunkDao: ChunkDao,
    private val gameMetaDao: GameMetaDao,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : ChunkRepository {
    private val queueMutex = Mutex()
    private val persistMutex = Mutex()
    private val pendingChunks = linkedMapOf<ChunkCoord, Chunk>()
    private var pendingMeta: GameMeta? = null
    private var debounceJob: Job? = null

    /** Counts durable chunk upsert batches; useful for debounce coalescing tests. */
    @Volatile
    var chunkWriteBatchCount: Int = 0
        private set

    /** Counts durable meta upserts; useful for debounce coalescing tests. */
    @Volatile
    var metaWriteCount: Int = 0
        private set

    override suspend fun getChunk(coord: ChunkCoord): Chunk? {
        queueMutex.withLock {
            pendingChunks[coord]?.let { return it }
        }
        return withContext(ioDispatcher) {
            chunkDao.getChunk(coord.cx, coord.cy)?.let(ChunkMapper::toDomain)
        }
    }

    override suspend fun getChunks(coords: Set<ChunkCoord>): Map<ChunkCoord, Chunk> {
        if (coords.isEmpty()) return emptyMap()

        val result = LinkedHashMap<ChunkCoord, Chunk>(coords.size)
        val missing = ArrayList<ChunkCoord>(coords.size)

        queueMutex.withLock {
            for (coord in coords) {
                val pending = pendingChunks[coord]
                if (pending != null) {
                    result[coord] = pending
                } else {
                    missing += coord
                }
            }
        }

        if (missing.isEmpty()) return result

        val missingSet = missing.toSet()
        val fromDb = withContext(ioDispatcher) {
            val cxs = missing.map { it.cx }.distinct()
            val cys = missing.map { it.cy }.distinct()
            chunkDao.getChunksWhereCxCyIn(cxs, cys)
                .asSequence()
                .map(ChunkMapper::toDomain)
                .filter { it.coord in missingSet }
                .associateBy { it.coord }
        }
        result.putAll(fromDb)
        return result
    }

    override suspend fun saveChunk(chunk: Chunk) {
        queueMutex.withLock {
            pendingChunks[chunk.coord] = chunk
            scheduleDebouncedFlushLocked()
        }
    }

    override suspend fun saveChunks(chunks: Collection<Chunk>) {
        if (chunks.isEmpty()) return
        queueMutex.withLock {
            for (chunk in chunks) {
                pendingChunks[chunk.coord] = chunk
            }
            scheduleDebouncedFlushLocked()
        }
    }

    override suspend fun getGameMeta(): GameMeta? {
        queueMutex.withLock {
            pendingMeta?.let { return it }
        }
        return withContext(ioDispatcher) {
            gameMetaDao.getMeta()?.let(ChunkMapper::toDomain)
        }
    }

    override suspend fun saveGameMeta(meta: GameMeta) {
        queueMutex.withLock {
            pendingMeta = meta
            scheduleDebouncedFlushLocked()
        }
    }

    override suspend fun flush() {
        val job = queueMutex.withLock {
            val current = debounceJob
            debounceJob = null
            current
        }
        job?.cancel()
        job?.join()
        persistPending()
    }

    private fun scheduleDebouncedFlushLocked() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            persistPending()
        }
    }

    private suspend fun persistPending() {
        persistMutex.withLock {
            val chunksSnapshot: List<Chunk>
            val metaSnapshot: GameMeta?
            queueMutex.withLock {
                if (pendingChunks.isEmpty() && pendingMeta == null) return
                chunksSnapshot = pendingChunks.values.toList()
                metaSnapshot = pendingMeta
            }

            withContext(ioDispatcher + NonCancellable) {
                if (chunksSnapshot.isNotEmpty()) {
                    chunkDao.upsertAll(chunksSnapshot.map(ChunkMapper::toEntity))
                    chunkWriteBatchCount++
                }
                if (metaSnapshot != null) {
                    gameMetaDao.upsert(ChunkMapper.toEntity(metaSnapshot))
                    metaWriteCount++
                }
            }

            queueMutex.withLock {
                for (chunk in chunksSnapshot) {
                    if (pendingChunks[chunk.coord] == chunk) {
                        pendingChunks.remove(chunk.coord)
                    }
                }
                if (metaSnapshot != null && pendingMeta == metaSnapshot) {
                    pendingMeta = null
                }
            }
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 500L
    }
}
