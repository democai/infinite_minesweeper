package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.engine.DefaultGameEngine
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.GameAction
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.GenerationResult
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GamePersistenceCoordinator] tests for T10: dirty-chunk tracking and merged meta saves.
 */
class GamePersistenceCoordinatorTest {

    @Test
    fun run_persistsOnlyDirtyChunksAndMergesViewportIntoMeta() = runTest {
        val repository = InMemoryChunkRepository()
        val engine = DefaultGameEngine(
            mineGenerator = MineFreeGenerator(),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )
        val viewport = MutableStateFlow(ViewportSnapshot(centerX = 0f, centerY = 0f, zoom = 1f))
        val coordinator = GamePersistenceCoordinator(engine.state, viewport, repository)
        val collector = launch(Dispatchers.Unconfined) { coordinator.run() }

        // Reveal(0,0) reveals and auto-clears the whole origin chunk (no mines anywhere), plus
        // generates its 8 lazy neighbors: every one of those 9 chunks is new, so the first batch
        // must contain all of them.
        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))
        val firstBatch = repository.saveChunksCalls.single()
        assertEquals(engine.state.value.chunks.keys, firstBatch.map(Chunk::coord).toSet())

        // A neighbor chunk was generated but never revealed, so this flips a genuinely hidden
        // cell and should mutate only that one chunk.
        engine.dispatch(GameAction.ToggleFlag(CellCoord(8, 0)))
        val secondBatch = repository.saveChunksCalls[1]
        assertEquals(setOf(ChunkCoord(1, 0)), secondBatch.map(Chunk::coord).toSet())
        assertEquals(2, repository.saveChunksCalls.size)

        // A viewport-only change must update the saved meta without re-saving any chunk.
        viewport.value = ViewportSnapshot(centerX = 12f, centerY = -4f, zoom = 2f)
        val lastMeta = repository.savedMetas.last()
        assertEquals(12f, lastMeta.viewportX)
        assertEquals(-4f, lastMeta.viewportY)
        assertEquals(2f, lastMeta.zoom)
        assertEquals(2, repository.saveChunksCalls.size)

        collector.cancel()
    }

    @Test
    fun flush_delegatesToRepository() = runTest {
        val repository = InMemoryChunkRepository()
        val coordinator = GamePersistenceCoordinator(
            gameState = MutableStateFlow(GameState()),
            viewport = MutableStateFlow(ViewportSnapshot(0f, 0f, 1f)),
            repository = repository,
        )

        coordinator.flush()
        coordinator.flush()

        assertEquals(2, repository.flushCount)
    }

    /**
     * Always-empty-density generator: every rolled chunk is entirely mine-free, so a reveal
     * auto-clears its whole chunk deterministically without needing a seeded RNG fixture.
     */
    private class MineFreeGenerator : MineGenerator {
        override fun mineDensityFor(coord: ChunkCoord): Float = 0f

        override suspend fun generateForFirstTouch(
            firstTouch: CellCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult {
            val centerCx = Math.floorDiv(firstTouch.x, 8)
            val centerCy = Math.floorDiv(firstTouch.y, 8)
            val changed = linkedMapOf<ChunkCoord, Chunk>()
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val coord = ChunkCoord(centerCx + dx, centerCy + dy)
                    if (knownChunks[coord]?.generated == true) continue
                    changed[coord] = Chunk(coord = coord, generated = true)
                }
            }
            return GenerationResult(changed)
        }

        override suspend fun reroll(
            coord: ChunkCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult = GenerationResult(emptyMap())
    }
}
