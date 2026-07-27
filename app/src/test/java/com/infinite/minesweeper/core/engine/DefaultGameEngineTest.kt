package com.infinite.minesweeper.core.engine

import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.generation.recomputeAdjacency
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameAction
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.GenerationResult
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGameEngineTest {

    @Test
    fun revealFloodFillStopsAtNumberedFrontierLeavingIsolatedRegionHidden() = runTest {
        // A mine wall down local column x=4 splits the chunk into a reachable left half and an
        // unreachable right half.
        val wall = (0..7).map { y -> 4 to y }.toSet()
        val chunk = chunkWithMines(ChunkCoord(0, 0), wall)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        for (y in 0..7) {
            for (x in 0..3) {
                assertEquals("($x,$y) should be revealed", CellState.REVEALED, stateAt(result, x, y))
            }
            assertEquals("(4,$y) is a mine and stays hidden", CellState.HIDDEN, stateAt(result, 4, y))
            for (x in 5..7) {
                assertEquals("($x,$y) is unreachable and stays hidden", CellState.HIDDEN, stateAt(result, x, y))
            }
        }
        assertEquals(0, engine.state.value.meta.selectorsCleared)
    }

    @Test
    fun cascadeCrossingIntoUngeneratedChunkTriggersGeneration() = runTest {
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 1,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        val neighborChunk = requireNotNull(engine.state.value.chunks[ChunkCoord(1, 0)]) {
            "Cascade crossing the chunk boundary should have generated the neighbor"
        }
        assertTrue(neighborChunk.generated)
        assertEquals(
            CellState.REVEALED,
            stateAt(neighborChunk, 0, 0),
        )
    }

    @Test
    fun cascadeCapRadiusStopsExpansionBeyondConfiguredChunkDistance() = runTest {
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 1,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        val chunks = engine.state.value.chunks
        for (cx in -1..1) {
            for (cy in -1..1) {
                val chunk = requireNotNull(chunks[ChunkCoord(cx, cy)])
                assertTrue(chunk.generated)
                assertTrue(chunk.cells.all { it.state == CellState.REVEALED })
            }
        }
        // Chunks at Chebyshev distance 2 were never even generated: the frontier stops cleanly.
        assertNull(chunks[ChunkCoord(2, 0)])
        assertNull(chunks[ChunkCoord(0, -2)])
        assertNull(chunks[ChunkCoord(2, 2)])
    }

    @Test
    fun chordWithCorrectFlagsRevealsExactlyTheUnflaggedNeighbors() = runTest {
        var chunk = chunkWithMines(ChunkCoord(0, 0), setOf(2 to 2, 4 to 4))
        chunk = chunk
            .withState(3, 3, CellState.REVEALED)
            .withState(2, 2, CellState.FLAGGED)
            .withState(4, 4, CellState.FLAGGED)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.Chord(CellCoord(3, 3)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        val unflaggedNeighbors = listOf(3 to 2, 2 to 3, 4 to 3, 2 to 4, 3 to 4)
        for ((x, y) in unflaggedNeighbors) {
            assertEquals("($x,$y) should be revealed by the chord", CellState.REVEALED, stateAt(result, x, y))
        }
        assertEquals(CellState.FLAGGED, stateAt(result, 2, 2))
        assertEquals(CellState.FLAGGED, stateAt(result, 4, 4))
    }

    @Test
    fun chordWithWrongFlagRevealsAndExplodesTheMine() = runTest {
        // Only (4,4) is a real mine; (2,2) is a wrong flag that happens to make the flagged
        // count match the revealed cell's adjacency number.
        var chunk = chunkWithMines(ChunkCoord(0, 0), setOf(4 to 4))
        chunk = chunk
            .withState(3, 3, CellState.REVEALED)
            .withState(2, 2, CellState.FLAGGED)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)
        val events = mutableListOf<GameEvent>()
        val collector = launch(Dispatchers.Unconfined) { engine.events.collect { events += it } }

        engine.dispatch(GameAction.Chord(CellCoord(3, 3)))
        collector.cancel()

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(ChunkStatus.LOCKED, result.status)
        assertEquals(CellState.EXPLODED, stateAt(result, 4, 4))
        assertEquals(listOf(GameEvent.ChunkLocked(ChunkCoord(0, 0), CellCoord(4, 4))), events)
        // The mine detonates before any safe neighbor is revealed, so the chunk locks immediately
        // and the rest of its neighbors are left untouched rather than partially revealed.
        for ((x, y) in listOf(3 to 2, 4 to 2, 2 to 3, 4 to 3, 2 to 4, 3 to 4)) {
            assertEquals("($x,$y) should stay hidden", CellState.HIDDEN, stateAt(result, x, y))
        }
    }

    @Test
    fun autoFlagFiresExactlyOnLastNonMineRevealAndEmitsChunkCleared() = runTest {
        var chunk = chunkWithMines(ChunkCoord(0, 0), setOf(7 to 7))
        for (y in 0..7) {
            for (x in 0..7) {
                if (x == 7 && y == 7) continue // the mine: stays hidden until auto-flagged
                if (x == 6 && y == 7) continue // the one cell we will reveal
                chunk = chunk.withState(x, y, CellState.REVEALED)
            }
        }
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)
        val events = mutableListOf<GameEvent>()
        val collector = launch(Dispatchers.Unconfined) { engine.events.collect { events += it } }

        engine.dispatch(GameAction.Reveal(CellCoord(6, 7)))
        collector.cancel()

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.REVEALED, stateAt(result, 6, 7))
        assertEquals(CellState.FLAGGED, stateAt(result, 7, 7))
        assertEquals(1, engine.state.value.meta.flagsPlaced)
        assertEquals(1, engine.state.value.meta.selectorsCleared)
        assertEquals(listOf(GameEvent.ChunkCleared(ChunkCoord(0, 0))), events)
    }

    @Test
    fun revealingAMineLocksItsChunkAndFreezesFurtherInput() = runTest {
        val chunk = chunkWithMines(ChunkCoord(0, 0), setOf(0 to 0))
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)
        val events = mutableListOf<GameEvent>()
        val collector = launch(Dispatchers.Unconfined) { engine.events.collect { events += it } }

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        var result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(ChunkStatus.LOCKED, result.status)
        assertEquals(CellState.EXPLODED, stateAt(result, 0, 0))
        assertTrue(result.lockedAt != null)
        assertEquals(listOf(GameEvent.ChunkLocked(ChunkCoord(0, 0), CellCoord(0, 0))), events)

        // Further input into the now-locked chunk is frozen.
        engine.dispatch(GameAction.ToggleFlag(CellCoord(1, 1)))
        engine.dispatch(GameAction.Reveal(CellCoord(2, 2)))
        collector.cancel()

        result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.HIDDEN, stateAt(result, 1, 1))
        assertEquals(CellState.HIDDEN, stateAt(result, 2, 2))
        assertEquals(0, engine.state.value.meta.flagsPlaced)
    }

    @Test
    fun toggleFlagTracksFlagsPlacedAndIgnoresNonHiddenCells() = runTest {
        val chunk = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.ToggleFlag(CellCoord(0, 0)))
        assertEquals(CellState.FLAGGED, stateAt(engine.state.value.chunks.getValue(ChunkCoord(0, 0)), 0, 0))
        assertEquals(1, engine.state.value.meta.flagsPlaced)

        engine.dispatch(GameAction.ToggleFlag(CellCoord(0, 0)))
        assertEquals(CellState.HIDDEN, stateAt(engine.state.value.chunks.getValue(ChunkCoord(0, 0)), 0, 0))
        assertEquals(0, engine.state.value.meta.flagsPlaced)

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))
        engine.dispatch(GameAction.ToggleFlag(CellCoord(0, 0)))
        assertEquals(CellState.REVEALED, stateAt(engine.state.value.chunks.getValue(ChunkCoord(0, 0)), 0, 0))
        assertEquals(0, engine.state.value.meta.flagsPlaced)
    }

    @Test
    fun syncWindowTrimsChunksOutsideKeepAndMergesHydratedChunks() = runTest {
        val kept = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val evicted = chunkWithMines(ChunkCoord(5, 5), emptySet())
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(chunks = mapOf(kept.coord to kept, evicted.coord to evicted)),
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        val rehydrated = chunkWithMines(ChunkCoord(9, 9), emptySet())
            .withState(0, 0, CellState.REVEALED)

        engine.syncWindow(
            keep = setOf(kept.coord, rehydrated.coord),
            hydrated = mapOf(rehydrated.coord to rehydrated),
        )

        val chunks = engine.state.value.chunks
        assertEquals(setOf(kept.coord, rehydrated.coord), chunks.keys)
        assertEquals(kept, chunks.getValue(kept.coord))
        assertEquals(rehydrated, chunks.getValue(rehydrated.coord))
    }

    @Test
    fun syncWindowRestoresAnEvictedChunkWithoutRegeneratingIt() = runTest {
        // A chunk with real progress (a revealed cell) that gets evicted and later panned back
        // into must come back exactly as it was, not as a fresh first-touch roll: dispatching
        // into a chunk absent from the live map is indistinguishable from "never touched" to the
        // engine (see `ensureGenerated`), so the integration layer must rehydrate before dispatch.
        var chunk = chunkWithMines(ChunkCoord(2, 2), setOf(7 to 7))
        chunk = chunk.withState(0, 0, CellState.REVEALED)
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(mapOf(chunk.coord to chunk)),
            initialState = GameState(chunks = mapOf(chunk.coord to chunk)),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        engine.syncWindow(keep = emptySet())
        assertTrue(engine.state.value.chunks.isEmpty())

        engine.syncWindow(keep = setOf(chunk.coord), hydrated = mapOf(chunk.coord to chunk))
        engine.dispatch(GameAction.Reveal(CellCoord(2 * 8 + 1, 2 * 8 + 0)))

        val result = engine.state.value.chunks.getValue(chunk.coord)
        assertTrue(result.generated)
        assertEquals(CellState.REVEALED, stateAt(result, 0, 0))
        assertEquals(CellState.REVEALED, stateAt(result, 1, 0))
        // The chunk's single mine was planted at (7,7) before eviction. The zero-adjacency
        // cascade from an unrelated corner floods the rest of the chunk and auto-flags it, which
        // only lands on (7,7) if the pre-eviction layout survived rehydration intact. A re-roll
        // would put the mine somewhere else and this cascade would detonate it instead.
        assertEquals(CellState.FLAGGED, stateAt(result, 7, 7))
        assertEquals(1, result.cells.count { it.state == CellState.FLAGGED })
        assertEquals(0, result.cells.count { it.state == CellState.EXPLODED })
    }

    private fun engineWithChunk(chunk: Chunk, cascadeRadiusChunks: Int): DefaultGameEngine =
        DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(mapOf(chunk.coord to chunk)),
            initialState = GameState(chunks = mapOf(chunk.coord to chunk)),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = cascadeRadiusChunks,
        )

    private fun stateAt(chunk: Chunk, x: Int, y: Int): CellState =
        chunk.cells[cellToLocalIndex(chunkLocalCell(chunk.coord, x, y))].state

    private fun chunkLocalCell(coord: ChunkCoord, x: Int, y: Int): CellCoord =
        CellCoord(coord.cx * 8 + x, coord.cy * 8 + y)

    private fun chunkWithMines(coord: ChunkCoord, mineLocals: Set<Pair<Int, Int>>): Chunk {
        val cells = (0 until 64).map { index ->
            val x = index % 8
            val y = index / 8
            Cell(isMine = (x to y) in mineLocals)
        }
        val rolled = Chunk(coord = coord, generated = true, cells = cells)
        return recomputeAdjacency(mapOf(coord to rolled)).getValue(coord)
    }

    private fun Chunk.withState(x: Int, y: Int, state: CellState): Chunk {
        val index = y * 8 + x
        val updated = cells.toMutableList()
        updated[index] = updated[index].copy(state = state)
        return copy(cells = updated)
    }

    /**
     * A controllable [MineGenerator] test double: chunks present in [fixture] are handed back
     * verbatim (marked generated); anything else falls back to an empty, mine-free chunk, mirroring
     * the real generator's guarantee that every requested coordinate resolves to something.
     */
    private class FixtureMineGenerator(
        private val fixture: Map<ChunkCoord, Chunk>,
    ) : MineGenerator {
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
                    changed[coord] = (fixture[coord] ?: Chunk(coord = coord)).copy(generated = true)
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
