package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.engine.DefaultGameEngine
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.GameAction
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GenerationResult
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [restoreGameState] tests for T10, plus a full write/flush/cold-restore round trip standing in
 * for the dev tree's "simulate process death mid-session" scenario.
 */
class GameStateRestoreTest {

    @Test
    fun restore_withNoSavedSession_returnsDefaultState() = runTest {
        val restored = restoreGameState(InMemoryChunkRepository())

        assertEquals(GameMeta(), restored.meta)
        assertTrue(restored.chunks.isEmpty())
    }

    @Test
    fun restore_hydratesOnlyChunksWithinWindowAroundSavedViewport() = runTest {
        val store = InMemoryChunkRepository.DurableStore()
        store.meta = GameMeta(viewportX = 100f, viewportY = -50f, zoom = 2f, flagsPlaced = 5)
        // The saved viewport (100, -50) sits in chunk (12, -7).
        val center = ChunkCoord(12, -7)
        for (dcx in -1..1) {
            for (dcy in -1..1) {
                val coord = ChunkCoord(center.cx + dcx, center.cy + dcy)
                store.chunks[coord] = sampleChunk(coord)
            }
        }
        val farAway = ChunkCoord(500, 500)
        store.chunks[farAway] = sampleChunk(farAway)

        val restored = restoreGameState(InMemoryChunkRepository(store), windowRadiusChunks = 1)

        assertEquals(store.meta, restored.meta)
        assertEquals(9, restored.chunks.size)
        assertFalse(restored.chunks.containsKey(farAway))
        for (dcx in -1..1) {
            for (dcy in -1..1) {
                assertTrue(restored.chunks.containsKey(ChunkCoord(center.cx + dcx, center.cy + dcy)))
            }
        }
    }

    @Test
    fun restore_afterProcessDeathMidSession_recoversFullStateWrittenBySession() = runTest {
        val store = InMemoryChunkRepository.DurableStore()
        val writingRepository = InMemoryChunkRepository(store)
        val engine = DefaultGameEngine(
            mineGenerator = MineFreeGenerator(),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )
        val viewport = MutableStateFlow(ViewportSnapshot(centerX = 4f, centerY = 4f, zoom = 1.5f))
        val coordinator = GamePersistenceCoordinator(engine.state, viewport, writingRepository)
        val collector = launch(Dispatchers.Unconfined) { coordinator.run() }

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))
        engine.dispatch(GameAction.ToggleFlag(CellCoord(8, 0)))
        coordinator.flush()
        collector.cancel()

        val liveState = engine.state.value

        // A brand-new repository instance over the same durable store stands in for a fresh
        // process after the app died and was relaunched.
        val coldRepository = InMemoryChunkRepository(store)
        val restored = restoreGameState(coldRepository, windowRadiusChunks = 5)

        assertEquals(
            liveState.meta.copy(viewportX = 4f, viewportY = 4f, zoom = 1.5f),
            restored.meta,
        )
        assertEquals(liveState.chunks, restored.chunks)
    }

    private fun sampleChunk(coord: ChunkCoord): Chunk = Chunk(
        coord = coord,
        generated = true,
        cells = List(64) { Cell() },
    )

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
