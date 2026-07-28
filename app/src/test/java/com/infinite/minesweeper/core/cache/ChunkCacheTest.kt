package com.infinite.minesweeper.core.cache

import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkRepository
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkCacheTest {
    @Test
    fun leastRecentlyUsedEntryIsEvictedFirst() = runTest {
        val cache = ChunkCache<Any>(FakeRepository(), maxChunks = 2)
        val first = chunk(1)
        val second = chunk(2)
        val third = chunk(3)
        cache.put(first)
        cache.put(second)

        assertEquals(first, cache[first.coord])
        cache.put(third)

        assertEquals(first, cache.peek(first.coord))
        assertNull(cache.peek(second.coord))
        assertEquals(third, cache.peek(third.coord))
    }

    @Test
    fun retentionMarginProtectsNearViewportEntries() = runTest {
        val cache = ChunkCache<Any>(
            repository = FakeRepository(),
            maxChunks = 2,
            retentionMarginChunks = 3,
        )
        val visible = chunk(0)
        val far = chunk(20)
        cache.put(visible)
        cache.put(far)
        cache.updateVisibleChunks(setOf(visible.coord))
        // Make the far entry newer; retention still takes priority over pure access order.
        cache[far.coord]

        val incoming = chunk(30)
        cache.put(incoming)

        assertEquals(visible, cache.peek(visible.coord))
        assertNull(cache.peek(far.coord))
        assertEquals(incoming, cache.peek(incoming.coord))
    }

    @Test
    fun dirtyChunkIsFlushedBeforeEviction() = runTest {
        val repository = FakeRepository()
        val cache = ChunkCache<Any>(repository, maxChunks = 1)
        val dirty = chunk(1)

        cache.put(dirty, dirty = true)
        cache.put(chunk(2))

        assertEquals(listOf("save:1,0", "flush"), repository.events)
        assertEquals(dirty, repository.persisted[dirty.coord])
        assertNull(cache.peek(dirty.coord))
    }

    @Test
    fun failedFlushLeavesDirtyEntryAndArtifactInCache() = runTest {
        val repository = FakeRepository(failFlush = true)
        val cache = ChunkCache<String>(repository, maxChunks = 1)
        val dirty = chunk(1)
        cache.put(dirty, dirty = true)
        cache.setLodArtifact(dirty.coord, "bitmap")

        val failure = runCatching { cache.put(chunk(2)) }

        assertTrue(failure.isFailure)
        assertEquals(dirty, cache.peek(dirty.coord))
        assertEquals("bitmap", cache.lodArtifact(dirty.coord))
        assertTrue(cache.isDirty(dirty.coord))
    }

    @Test
    fun changedChunkInvalidatesLodArtifactAndPreservesDirtyFlag() = runTest {
        val cache = ChunkCache<String>(FakeRepository())
        val original = chunk(1)
        cache.put(original, dirty = true)
        val artifact = "bitmap"
        cache.setLodArtifact(original.coord, artifact)

        cache.put(original)
        assertSame(artifact, cache.lodArtifact(original.coord))

        cache.put(original.copy(generated = true))
        assertNull(cache.lodArtifact(original.coord))
        assertTrue(cache.isDirty(original.coord))
    }

    @Test
    fun flushDirtyBatchesWritesAndClearsDirtyState() = runTest {
        val repository = FakeRepository()
        val cache = ChunkCache<Any>(repository)
        val first = chunk(1)
        val second = chunk(2)
        cache.put(first, dirty = true)
        cache.put(second, dirty = true)

        cache.flushDirty()

        assertEquals(listOf("saveAll:1,0|2,0", "flush"), repository.events)
        assertFalse(cache.isDirty(first.coord))
        assertFalse(cache.isDirty(second.coord))
    }

    @Test
    fun loadVisibleChunksFetchesOnlyCacheMisses() = runTest {
        val first = chunk(1)
        val second = chunk(2)
        val repository = FakeRepository().apply {
            persisted[first.coord] = first
            persisted[second.coord] = second
        }
        val cache = ChunkCache<Any>(repository)

        assertEquals(
            mapOf(first.coord to first),
            cache.loadVisibleChunks(setOf(first.coord)),
        )
        repository.requested.clear()

        assertEquals(
            mapOf(first.coord to first, second.coord to second),
            cache.loadVisibleChunks(setOf(first.coord, second.coord)),
        )
        assertEquals(listOf(setOf(second.coord)), repository.requested)
    }

    private fun chunk(cx: Int, cy: Int = 0): Chunk = Chunk(ChunkCoord(cx, cy))

    private class FakeRepository(
        private val failFlush: Boolean = false,
    ) : ChunkRepository {
        val persisted = mutableMapOf<ChunkCoord, Chunk>()
        val events = mutableListOf<String>()
        val requested = mutableListOf<Set<ChunkCoord>>()

        override suspend fun getChunk(coord: ChunkCoord): Chunk? = persisted[coord]

        override suspend fun getChunks(coords: Set<ChunkCoord>): Map<ChunkCoord, Chunk> {
            requested += coords
            return persisted.filterKeys { it in coords }
        }

        override suspend fun getLockedChunks(): Map<ChunkCoord, Chunk> =
            persisted.filterValues { it.status == ChunkStatus.LOCKED }

        override suspend fun saveChunk(chunk: Chunk) {
            events += "save:${chunk.coord.cx},${chunk.coord.cy}"
            persisted[chunk.coord] = chunk
        }

        override suspend fun saveChunks(chunks: Collection<Chunk>) {
            events += "saveAll:" + chunks.joinToString("|") {
                "${it.coord.cx},${it.coord.cy}"
            }
            chunks.forEach { persisted[it.coord] = it }
        }

        override suspend fun getGameMeta(): GameMeta? = null

        override suspend fun saveGameMeta(meta: GameMeta) = Unit

        override suspend fun flush() {
            events += "flush"
            if (failFlush) error("flush failed")
        }

        override suspend fun clearAll() {
            persisted.clear()
        }
    }
}
