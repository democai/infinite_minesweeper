package com.infinite.minesweeper.core.engine

import com.infinite.minesweeper.core.coords.cellToLocalIndex
import com.infinite.minesweeper.core.engine.lock.neighboringChunkCoords
import com.infinite.minesweeper.core.generation.recomputeAdjacency
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameAction
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.GenerationResult
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Chunks at Chebyshev distance 2 may be pre-generated as the reveal-ready halo of the
        // cascade frontier, but must stay fully hidden — the cascade radius still caps reveals.
        for (coord in listOf(ChunkCoord(2, 0), ChunkCoord(0, -2), ChunkCoord(2, 2))) {
            val outer = chunks[coord] ?: continue
            assertTrue(outer.generated)
            assertTrue(
                "Cascade must not reveal beyond radius; $coord had reveals",
                outer.cells.all { it.state == CellState.HIDDEN },
            )
        }
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
        // (1,1) is preset REVEALED so (0,0) satisfies the adjacency-to-revealed-cell rule.
        val chunk = chunkWithMines(ChunkCoord(0, 0), emptySet())
            .withState(1, 1, CellState.REVEALED)
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
    fun perfectFlagSetAutoRevealsRemainingSafeCellsAndClearsSelector() = runTest {
        var chunk = chunkWithMines(ChunkCoord(0, 0), setOf(7 to 7))
        // (6,6) is Moore-adjacent to the mine at (7,7), satisfying the adjacency-to-revealed-cell
        // rule for the ToggleFlag(7,7) below.
        chunk = chunk.withState(6, 6, CellState.REVEALED)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)
        val events = mutableListOf<GameEvent>()
        val collector = launch(Dispatchers.Unconfined) { engine.events.collect { events += it } }

        engine.dispatch(GameAction.ToggleFlag(CellCoord(7, 7)))
        collector.cancel()

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.FLAGGED, stateAt(result, 7, 7))
        assertTrue(result.cells.filterNot { it.isMine }.all { it.state == CellState.REVEALED })
        assertEquals(1, engine.state.value.meta.selectorsCleared)
        assertTrue(events.any { it is GameEvent.ChunkCleared })
    }

    @Test
    fun wrongFlagDoesNotAutoCompleteSelector() = runTest {
        var chunk = chunkWithMines(ChunkCoord(0, 0), setOf(7 to 7))
        chunk = chunk.withState(0, 0, CellState.REVEALED)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        // Flag a safe cell instead of the mine.
        engine.dispatch(GameAction.ToggleFlag(CellCoord(1, 0)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.FLAGGED, stateAt(result, 1, 0))
        assertEquals(CellState.HIDDEN, stateAt(result, 7, 7))
        assertEquals(0, engine.state.value.meta.selectorsCleared)
    }

    @Test
    fun revealOnFreshBoardIsExemptFromAdjacencyRule() = runTest {
        val chunk = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.REVEALED, stateAt(result, 0, 0))
        assertTrue(engine.state.value.meta.hasEverRevealed)
    }

    @Test
    fun secondRevealAwayFromRevealedFrontierIsRejected() = runTest {
        // A mine wall down local column x=4 splits the chunk into a reachable left half and an
        // unreachable right half (same shape as revealFloodFillStopsAtNumberedFrontier...).
        val wall = (0..7).map { y -> 4 to y }.toSet()
        val chunk = chunkWithMines(ChunkCoord(0, 0), wall)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))
        // hasEverRevealed is now true, so this second, disconnected reveal must pass the
        // adjacency/ring check on its own merits — and it can't: no revealed neighbor, no ring.
        engine.dispatch(GameAction.Reveal(CellCoord(7, 7)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.HIDDEN, stateAt(result, 7, 7))
    }

    @Test
    fun toggleFlagOnFreshBoardWithNoRevealedCellsIsRejected() = runTest {
        val chunk = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.ToggleFlag(CellCoord(0, 0)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.HIDDEN, stateAt(result, 0, 0))
        assertEquals(0, engine.state.value.meta.flagsPlaced)
    }

    @Test
    fun solvedRingTier1GrantsAccessToWhollyEnclosedChunk() = runTest {
        val center = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val neighborChunks = neighboringChunkCoords(center.coord).associateWith { coord ->
            chunkWithMines(coord, emptySet()).fullyRevealed()
        }
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(
                chunks = neighborChunks + (center.coord to center),
                meta = GameMeta(hasEverRevealed = true),
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(3, 3)))

        val result = engine.state.value.chunks.getValue(center.coord)
        assertEquals(CellState.REVEALED, stateAt(result, 3, 3))
    }

    @Test
    fun solvedRingTier1FailsWithOnlySevenOfEightSolvedNeighbors() = runTest {
        val center = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val neighborChunks = neighboringChunkCoords(center.coord).toList().drop(1).associateWith { coord ->
            chunkWithMines(coord, emptySet()).fullyRevealed()
        }
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(
                chunks = neighborChunks + (center.coord to center),
                meta = GameMeta(hasEverRevealed = true),
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(3, 3)))

        val result = engine.state.value.chunks.getValue(center.coord)
        assertEquals(CellState.HIDDEN, stateAt(result, 3, 3))
    }

    @Test
    fun solvedRingTier2GrantsAccessToAnEnclosedPocketNotAlignedToChunkBoundaries() = runTest {
        // A 3x3 hidden pocket in the interior of an otherwise fully-revealed chunk: (3,3) has no
        // directly-revealed neighbor (condition b fails), no neighbor chunks exist at all (Tier 1
        // fails), but the pocket is fully walled off by revealed cells within this one chunk.
        val pocket = (2..4).flatMap { x -> (2..4).map { y -> x to y } }.toSet()
        var chunk = chunkWithMines(ChunkCoord(0, 0), emptySet())
        for (y in 0..7) {
            for (x in 0..7) {
                if ((x to y) !in pocket) chunk = chunk.withState(x, y, CellState.REVEALED)
            }
        }
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(
                chunks = mapOf(chunk.coord to chunk),
                meta = GameMeta(hasEverRevealed = true),
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(3, 3)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.REVEALED, stateAt(result, 3, 3))
    }

    @Test
    fun solvedRingTier2FailsClosedWhenFillEscapesThe24CellWindow() = runTest {
        // All 9 chunks in the 3x3 block are present, generated, and fully hidden with no walls
        // anywhere: the flood fill can reach the edge of the bounded window but never encloses.
        val center = ChunkCoord(0, 0)
        val allNineChunks = (neighboringChunkCoords(center) + center).associateWith { coord ->
            chunkWithMines(coord, emptySet())
        }
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(chunks = allNineChunks, meta = GameMeta(hasEverRevealed = true)),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(3, 3)))

        val result = engine.state.value.chunks.getValue(center)
        assertEquals(CellState.HIDDEN, stateAt(result, 3, 3))
    }

    @Test
    fun solvedRingTier2FailsClosedWhenFillReachesAnUngeneratedNeighborChunk() = runTest {
        // Only the center chunk exists; the fill hits an absent neighbor before it could ever
        // prove enclosure, and must fail closed rather than assume the pocket continues safely.
        val chunk = chunkWithMines(ChunkCoord(0, 0), emptySet())
        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(
                chunks = mapOf(chunk.coord to chunk),
                meta = GameMeta(hasEverRevealed = true),
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(3, 3)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.HIDDEN, stateAt(result, 3, 3))
    }

    @Test
    fun toggleFlagOnASolvedChunkIsRejectedEvenIfAdjacencyWouldOtherwiseAllowIt() = runTest {
        var chunk = chunkWithMines(ChunkCoord(0, 0), setOf(7 to 7))
        for (y in 0..7) {
            for (x in 0..7) {
                if (x == 7 && y == 7) continue
                chunk = chunk.withState(x, y, CellState.REVEALED)
            }
        }
        chunk = chunk.withState(7, 7, CellState.FLAGGED)
        assertTrue("fixture must actually be solved for this test to isolate the guard", chunk.isSolved)
        val engine = engineWithChunk(chunk, cascadeRadiusChunks = 0)

        engine.dispatch(GameAction.ToggleFlag(CellCoord(7, 7)))

        val result = engine.state.value.chunks.getValue(ChunkCoord(0, 0))
        assertEquals(CellState.FLAGGED, stateAt(result, 7, 7))
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
        assertTrue(kept.coord in chunks)
        assertTrue(rehydrated.coord in chunks)
        assertFalse(evicted.coord in chunks)
        assertEquals(kept, chunks.getValue(kept.coord))
        // Revealed hydrate triggers ensureNeighborsGenerated — outer ring is part of the repair.
        assertTrue(chunks.getValue(rehydrated.coord).generated)
        assertEquals(
            neighboringChunkCoords(rehydrated.coord).size,
            neighboringChunkCoords(rehydrated.coord).count { chunks[it]?.generated == true },
        )
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

    @Test
    fun cascadeIntoEvictedChunkHydratesFromLoadChunksInsteadOfRerolling() = runTest {
        // Plan §4 cascade radius can reach outside the §9 viewport window. A zero-flood that
        // steps into an explored-but-evicted neighbor must restore that neighbor via loadChunks.
        // FixtureMineGenerator would hand back a mine-free chunk here; if hydrate is skipped the
        // (7,7) mine disappears and auto-flag never lands on it.
        val origin = chunkWithMines(ChunkCoord(0, 0), emptySet())
        var explored = chunkWithMines(ChunkCoord(1, 0), setOf(7 to 7)).withState(0, 0, CellState.REVEALED)
        val linked = recomputeAdjacency(mapOf(origin.coord to origin, explored.coord to explored))
        val originReady = linked.getValue(origin.coord)
        explored = linked.getValue(explored.coord)
        val durable = mapOf(explored.coord to explored)

        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(emptyMap()),
            initialState = GameState(chunks = mapOf(origin.coord to originReady)),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 1,
            loadChunks = { coords -> coords.mapNotNull { c -> durable[c]?.let { c to it } }.toMap() },
        )

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        val restored = engine.state.value.chunks.getValue(explored.coord)
        assertEquals(CellState.REVEALED, stateAt(restored, 0, 0))
        assertEquals(0, restored.cells.count { it.state == CellState.EXPLODED })
        assertEquals(CellState.FLAGGED, stateAt(restored, 7, 7))
        assertEquals(1, restored.cells.count { it.state == CellState.FLAGGED })
        assertFalse(restored.cells[0].isMine)
    }

    @Test
    fun revealedBorderNumbersStayStableWhenAdjacentTerraIsOpened() = runTest {
        // B's right-edge cell (15,0) touches a mine in C at (16,0). Without ensureRevealReady,
        // revealing on B would bake adjacentMines=0, then opening C would patch it to 1.
        var b = chunkWithMines(ChunkCoord(1, 0), emptySet()).withState(6, 0, CellState.REVEALED)
        val c = chunkWithMines(ChunkCoord(2, 0), setOf(0 to 0))
        val withC = recomputeAdjacency(mapOf(b.coord to b, c.coord to c))
        val cReady = withC.getValue(c.coord)
        val provisionalB = recomputeAdjacency(mapOf(b.coord to b)).getValue(b.coord)
        assertEquals(
            "Sanity: without C, border cell must look like zero",
            0,
            provisionalB.cells[0 * 8 + 7].adjacentMines,
        )

        val engine = DefaultGameEngine(
            mineGenerator = FixtureMineGenerator(mapOf(c.coord to cReady)),
            initialState = GameState(
                chunks = mapOf(provisionalB.coord to provisionalB),
                meta = GameMeta(hasEverRevealed = true),
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
            cascadeRadiusChunks = 0,
        )

        engine.dispatch(GameAction.Reveal(CellCoord(15, 0)))

        val afterReveal = engine.state.value.chunks.getValue(ChunkCoord(1, 0))
        assertEquals(CellState.REVEALED, stateAt(afterReveal, 7, 0))
        assertEquals(
            "Reveal-ready must count C's border mine before the cell is shown",
            1,
            afterReveal.cells[0 * 8 + 7].adjacentMines,
        )
        assertTrue(engine.state.value.chunks[ChunkCoord(2, 0)]!!.generated)

        val snapshot = afterReveal.cells.map { it.adjacentMines to it.state }
        // Opening farther terra (ensureRevealReady on C) must not rewrite B's revealed numbers.
        engine.dispatch(GameAction.Reveal(CellCoord(16, 1)))
        val stillB = engine.state.value.chunks.getValue(ChunkCoord(1, 0))
        assertEquals(snapshot, stillB.cells.map { it.adjacentMines to it.state })
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

    /** Marks every cell REVEALED — only valid for a mine-free fixture chunk. */
    private fun Chunk.fullyRevealed(): Chunk =
        copy(cells = cells.map { it.copy(state = CellState.REVEALED) })

    /**
     * A controllable [MineGenerator] test double: chunks present in [fixture] are handed back
     * verbatim (marked generated); anything else falls back to an empty, mine-free chunk, mirroring
     * the real generator's guarantee that every requested coordinate resolves to something.
     * Newly generated chunks trigger the same adjacency patching the real generator performs.
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
            val merged = knownChunks.toMutableMap()
            val newlyGenerated = mutableSetOf<ChunkCoord>()
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val coord = ChunkCoord(centerCx + dx, centerCy + dy)
                    if (merged[coord]?.generated == true) continue
                    merged[coord] = (fixture[coord] ?: Chunk(coord = coord)).copy(generated = true)
                    newlyGenerated += coord
                }
            }
            return patchedResult(knownChunks, merged, newlyGenerated)
        }

        override suspend fun ensureNeighborsGenerated(
            center: ChunkCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult {
            val merged = knownChunks.toMutableMap()
            val newlyGenerated = mutableSetOf<ChunkCoord>()
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val coord = ChunkCoord(center.cx + dx, center.cy + dy)
                    if (merged[coord]?.generated == true) continue
                    merged[coord] = (fixture[coord] ?: Chunk(coord = coord)).copy(generated = true)
                    newlyGenerated += coord
                }
            }
            if (newlyGenerated.isEmpty()) return GenerationResult(emptyMap())
            return patchedResult(knownChunks, merged, newlyGenerated)
        }

        override suspend fun reroll(
            coord: ChunkCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult = GenerationResult(emptyMap())

        private fun patchedResult(
            knownChunks: Map<ChunkCoord, Chunk>,
            merged: Map<ChunkCoord, Chunk>,
            newlyGenerated: Set<ChunkCoord>,
        ): GenerationResult {
            val targets = newlyGenerated
                .asSequence()
                .flatMap { c -> neighboringChunkCoords(c) + c }
                .filterTo(mutableSetOf()) { merged[it]?.generated == true }
            val withAdjacency = recomputeAdjacency(merged, targets)
            val changed = targets
                .filterTo(linkedSetOf()) { coord ->
                    coord in newlyGenerated || withAdjacency[coord] != knownChunks[coord]
                }
                .associateWith { coord -> requireNotNull(withAdjacency[coord]) }
            return GenerationResult(changed)
        }
    }
}
