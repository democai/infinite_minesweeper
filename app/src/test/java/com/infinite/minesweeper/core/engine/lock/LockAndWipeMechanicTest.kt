package com.infinite.minesweeper.core.engine.lock

import com.infinite.minesweeper.core.coords.LocalCellCoord
import com.infinite.minesweeper.core.coords.chunkLocalToCell
import com.infinite.minesweeper.core.generation.SeededMineGenerator
import com.infinite.minesweeper.core.generation.recomputeAdjacency
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameEvent
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.core.model.GenerationResult
import com.infinite.minesweeper.core.model.MineGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockAndWipeMechanicTest {

    @Test
    fun softResolveRemovesMineAndPatchesRevealedNeighborNumber() = runTest {
        val center = ChunkCoord(0, 0)
        val east = ChunkCoord(1, 0)
        val exploded = CellCoord(7, 3)
        val chunks = completedNeighborhood(center).toMutableMap()
        chunks[center] = lockedChunk(center, exploded)
        chunks[east] = chunks.getValue(east).withCell(
            x = 0,
            y = 3,
            cell = Cell(state = CellState.REVEALED, adjacentMines = 1),
        )
        val state = GameState(chunks = recomputeAdjacency(chunks))
        assertEquals(1, state.cell(east, 0, 3).adjacentMines)

        val result = mechanic().process(GameEvent.ChunkCleared(east), state)

        val resolved = result.state.chunks.getValue(center)
        assertEquals(ChunkStatus.NORMAL, resolved.status)
        assertTrue(resolved.everSurrounded)
        assertEquals(null, resolved.lockedAt)
        assertEquals(CellState.REVEALED, resolved.cells[3 * 8 + 7].state)
        assertFalse(resolved.cells[3 * 8 + 7].isMine)
        assertEquals(0, result.state.cell(east, 0, 3).adjacentMines)
        assertEquals(CellState.REVEALED, result.state.cell(east, 0, 3).state)
        assertEquals(listOf(GameEvent.ChunkSoftResolved(center)), result.events)
    }

    @Test
    fun lockedNeighborNeverCountsAsCleared() = runTest {
        val center = ChunkCoord(0, 0)
        val east = ChunkCoord(1, 0)
        val chunks = completedNeighborhood(center).toMutableMap()
        chunks[center] = lockedChunk(center, CellCoord(3, 3))
        chunks[east] = lockedChunk(east, CellCoord(8, 3))

        val result = mechanic().process(
            GameEvent.ChunkCleared(ChunkCoord(-1, 0)),
            GameState(chunks = recomputeAdjacency(chunks)),
        )

        assertEquals(ChunkStatus.LOCKED, result.state.chunks.getValue(center).status)
        assertEquals(ChunkStatus.LOCKED, result.state.chunks.getValue(east).status)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun previouslySurroundedLockCanNeverSoftResolveAgain() = runTest {
        val center = ChunkCoord(0, 0)
        val chunks = completedNeighborhood(center).toMutableMap()
        chunks[center] = lockedChunk(center, CellCoord(3, 3)).copy(everSurrounded = true)

        val result = mechanic().process(
            GameEvent.ChunkCleared(ChunkCoord(-1, 0)),
            GameState(chunks = recomputeAdjacency(chunks)),
        )

        assertEquals(ChunkStatus.LOCKED, result.state.chunks.getValue(center).status)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun oneClearTransitionReevaluatesAdjacentLocksIndependently() = runTest {
        val cleared = ChunkCoord(0, 0)
        val westLock = ChunkCoord(-1, 0)
        val eastLock = ChunkCoord(1, 0)
        val chunks = (completedNeighborhood(westLock) + completedNeighborhood(eastLock)).toMutableMap()
        chunks[westLock] = lockedChunk(westLock, CellCoord(-1, 3))
        chunks[eastLock] = lockedChunk(eastLock, CellCoord(8, 3))

        val result = mechanic().process(
            GameEvent.ChunkCleared(cleared),
            GameState(chunks = recomputeAdjacency(chunks)),
        )

        assertEquals(ChunkStatus.NORMAL, result.state.chunks.getValue(westLock).status)
        assertEquals(ChunkStatus.NORMAL, result.state.chunks.getValue(eastLock).status)
        assertEquals(
            listOf(
                GameEvent.ChunkSoftResolved(westLock),
                GameEvent.ChunkSoftResolved(eastLock),
            ),
            result.events,
        )
    }

    @Test
    fun hardWipeIsDeterministicResetsFlagsAndPatchesNeighborBorder() = runTest {
        val center = ChunkCoord(0, 0)
        val east = ChunkCoord(1, 0)
        val seed = 99173L
        val initialGenerator = SeededMineGenerator(seed)
        val known = completedNeighborhood(center)
        val firstRoll = initialGenerator.reroll(center, known).chunks
        var hit = firstRoll.getValue(center).copy(everSurrounded = true)
        val mineIndex = hit.cells.indexOfFirst { it.isMine }
        assertTrue("Fixture seed must produce a mine", mineIndex >= 0)
        val hitCells = hit.cells.toMutableList()
        hitCells[mineIndex] = hitCells[mineIndex].copy(state = CellState.EXPLODED)
        val flagIndex = hit.cells.indexOfFirst { !it.isMine && it.state == CellState.HIDDEN }
        hitCells[flagIndex] = hitCells[flagIndex].copy(state = CellState.FLAGGED)
        hit = hit.copy(cells = hitCells, status = ChunkStatus.LOCKED, lockedAt = 4L)

        // Deliberately stale border values prove the reroll result is applied even though an
        // equivalent same-seed reroll produces the same center mine layout.
        val neighborBefore = known.getValue(east).copy(
            cells = known.getValue(east).cells.map {
                it.copy(state = CellState.REVEALED, adjacentMines = 8)
            },
        )
        val state = GameState(
            chunks = known + firstRoll + (center to hit) + (east to neighborBefore),
            meta = GameMeta(flagsPlaced = 1),
        )
        val mechanic = LockAndWipeMechanic(initialGenerator, Dispatchers.Unconfined)
        val event = GameEvent.ChunkLocked(
            center,
            chunkLocalToCell(center, LocalCellCoord(mineIndex % 8, mineIndex / 8)),
        )

        val first = mechanic.process(event, state)
        val second = mechanic.process(event, state)

        val wiped = first.state.chunks.getValue(center)
        assertEquals(second.state.chunks.getValue(center), wiped)
        assertTrue(wiped.cells.all { it.state == CellState.HIDDEN })
        assertEquals(ChunkStatus.NORMAL, wiped.status)
        assertFalse(wiped.everSurrounded)
        assertEquals(0, first.state.meta.flagsPlaced)
        assertEquals(1, first.state.meta.selectorsWiped)
        assertEquals(listOf(GameEvent.ChunkWiped(center)), first.events)

        val eastAfter = first.state.chunks.getValue(east)
        assertTrue(eastAfter.cells.all { it.state == CellState.REVEALED })
        assertTrue(eastAfter.cells.any { it.adjacentMines != 8 })
    }

    private fun mechanic(): LockAndWipeMechanic =
        LockAndWipeMechanic(NoOpGenerator, Dispatchers.Unconfined)

    private fun completedNeighborhood(center: ChunkCoord): Map<ChunkCoord, Chunk> =
        (neighboringChunkCoords(center) + center).associateWith { coord ->
            Chunk(
                coord = coord,
                generated = true,
                cells = List(64) { Cell(state = CellState.REVEALED) },
            )
        }

    private fun lockedChunk(coord: ChunkCoord, exploded: CellCoord): Chunk {
        val cells = List(64) { Cell(state = CellState.HIDDEN) }.toMutableList()
        val localX = Math.floorMod(exploded.x, 8)
        val localY = Math.floorMod(exploded.y, 8)
        cells[localY * 8 + localX] = Cell(
            state = CellState.EXPLODED,
            isMine = true,
        )
        return Chunk(
            coord = coord,
            generated = true,
            cells = cells,
            status = ChunkStatus.LOCKED,
            lockedAt = 1L,
        )
    }

    private fun Chunk.withCell(x: Int, y: Int, cell: Cell): Chunk {
        val updated = cells.toMutableList()
        updated[y * 8 + x] = cell
        return copy(cells = updated)
    }

    private fun GameState.cell(coord: ChunkCoord, x: Int, y: Int): Cell =
        chunks.getValue(coord).cells[y * 8 + x]

    private object NoOpGenerator : MineGenerator {
        override fun mineDensityFor(coord: ChunkCoord): Float = 0f

        override suspend fun generateForFirstTouch(
            firstTouch: CellCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult = GenerationResult(emptyMap())

        override suspend fun reroll(
            coord: ChunkCoord,
            knownChunks: Map<ChunkCoord, Chunk>,
        ): GenerationResult = GenerationResult(mapOf(coord to Chunk(coord, generated = true)))
    }
}
