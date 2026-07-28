package com.infinite.minesweeper.data.persistence

import com.infinite.minesweeper.core.engine.DefaultGameEngine
import com.infinite.minesweeper.core.engine.lock.neighboringChunkCoords
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
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
        val restored = restoreGameState(InMemoryChunkRepository(), MineFreeGenerator())

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

        val restored = restoreGameState(
            InMemoryChunkRepository(store),
            MineFreeGenerator(),
            windowRadiusChunks = 1,
        )

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
    fun restore_softResolvesSurroundedLocksOutsideTheViewportWindow() = runTest {
        // A lock parked far from the saved viewport must still unlock on cold start once its
        // eight neighbors are cleared — otherwise those red selectors are never revisited.
        val store = InMemoryChunkRepository.DurableStore()
        store.meta = GameMeta(viewportX = 0f, viewportY = 0f, zoom = 1f)
        val lockedCoord = ChunkCoord(40, 40)
        for (coord in neighboringChunkCoords(lockedCoord) + lockedCoord) {
            store.chunks[coord] = if (coord == lockedCoord) {
                lockedChunk(coord)
            } else {
                clearedChunk(coord)
            }
        }

        val restored = restoreGameState(
            InMemoryChunkRepository(store),
            MineFreeGenerator(),
            windowRadiusChunks = 1,
        )

        val resolved = restored.chunks.getValue(lockedCoord)
        assertEquals(ChunkStatus.NORMAL, resolved.status)
        assertTrue(resolved.everSurrounded)
        assertEquals(0, restored.selectorsLocked)
        // Unlock must be durable for the next launch.
        assertEquals(ChunkStatus.NORMAL, store.chunks.getValue(lockedCoord).status)
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

        val coldRepository = InMemoryChunkRepository(store)
        val restored = restoreGameState(coldRepository, MineFreeGenerator(), windowRadiusChunks = 5)

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

    private fun clearedChunk(coord: ChunkCoord): Chunk = Chunk(
        coord = coord,
        generated = true,
        cells = List(64) { Cell(state = CellState.REVEALED) },
    )

    private fun lockedChunk(coord: ChunkCoord): Chunk {
        val cells = List(64) { Cell(state = CellState.HIDDEN) }.toMutableList()
        cells[0] = Cell(state = CellState.EXPLODED, isMine = true)
        return Chunk(
            coord = coord,
            generated = true,
            cells = cells,
            status = ChunkStatus.LOCKED,
            lockedAt = 1L,
        )
    }

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

        override suspend fun ensureNeighborsGenerated(
            center: ChunkCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult {
            val changed = linkedMapOf<ChunkCoord, Chunk>()
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val coord = ChunkCoord(center.cx + dx, center.cy + dy)
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
