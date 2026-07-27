package com.infinite.minesweeper.data.db

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.GameMeta
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 *
 * @param delayMillis suspend sleeper used by the debounce timer (overridable in tests).
 */
class RoomChunkRepository(
    private val chunkDao: ChunkDao,
    private val gameMetaDao: GameMetaDao,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : ChunkRepository {
    private val queueMutex = Mutex()
    private val persistMutex = Mutex()
    private val pendingChunks = linkedMapOf<ChunkCoord, Chunk>()
    private var pendingMeta: GameMeta? = null
    private val debounceJob = AtomicReference<Job?>(null)

    /** Counts durable chunk upsert batches; useful for debounce coalescing tests. */
    @Volatile
    var chunkWriteBatchCount: Int = 0
        private set

    /** Counts durable meta upserts; useful for debounce coalescing tests. */
    @Volatile
    var metaWriteCount: Int = 0
        private set

    /** Last unexpected debounce failure, if any (tests assert this stays null). */
    @Volatile
    var lastDebounceError: Throwable? = null
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
        }
        scheduleDebouncedFlush()
    }

    override suspend fun saveChunks(chunks: Collection<Chunk>) {
        if (chunks.isEmpty()) return
        queueMutex.withLock {
            for (chunk in chunks) {
                pendingChunks[chunk.coord] = chunk
            }
        }
        scheduleDebouncedFlush()
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
        }
        scheduleDebouncedFlush()
    }

    override suspend fun flush() {
        debounceJob.getAndSet(null)?.let { job ->
            job.cancel()
            job.join()
        }
        persistPending()
    }

    private fun scheduleDebouncedFlush() {
        debounceJob.getAndSet(
            scope.launch {
                try {
                    delayMillis(debounceMs)
                    ensureActive()
                    persistPending()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastDebounceError = error
                    throw error
                }
            },
        )?.cancel()
    }

    private suspend fun persistPending() {
        persistMutex.withLock {
            val chunksSnapshot: List<Chunk>
            val metaSnapshot: GameMeta?
            queueMutex.withLock {
                if (pendingChunks.isEmpty() && pendingMeta == null) {
                    return
                }
                chunksSnapshot = pendingChunks.values.toList()
                metaSnapshot = pendingMeta
            }

            withContext(NonCancellable) {
                withContext(ioDispatcher) {
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
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 500L
    }
}
