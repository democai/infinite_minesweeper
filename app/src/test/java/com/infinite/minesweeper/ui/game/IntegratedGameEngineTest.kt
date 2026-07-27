package com.infinite.minesweeper.ui.game

import com.infinite.minesweeper.core.engine.DefaultGameEngine
import com.infinite.minesweeper.core.engine.lock.LockAndWipeMechanic
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedGameEngineTest {
    @Test
    fun secondLifetimeMineHitPublishesWipedStateAndEvents() = runTest {
        val coord = ChunkCoord(0, 0)
        val mineCells = MutableList(64) { Cell() }
        mineCells[0] = Cell(isMine = true)
        val initial = Chunk(
            coord = coord,
            generated = true,
            cells = mineCells,
            everSurrounded = true,
        )
        val generator = WipeFixtureGenerator()
        val engine = DefaultGameEngine(
            mineGenerator = generator,
            initialState = GameState(chunks = mapOf(coord to initial)),
            lockAndWipeMechanic = LockAndWipeMechanic(generator, Dispatchers.Unconfined),
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        val events = mutableListOf<GameEvent>()
        val collector = launch(Dispatchers.Unconfined) {
            engine.events.collect { events += it }
        }

        engine.dispatch(GameAction.Reveal(CellCoord(0, 0)))

        val wiped = engine.state.value.chunks.getValue(coord)
        assertEquals(ChunkStatus.NORMAL, wiped.status)
        assertFalse(wiped.everSurrounded)
        assertTrue(wiped.cells.all { it.state == CellState.HIDDEN })
        assertEquals(1, engine.state.value.meta.selectorsWiped)
        assertTrue(events.any { it is GameEvent.ChunkLocked })
        assertTrue(events.any { it is GameEvent.ChunkWiped })
        collector.cancel()
    }
}

private class WipeFixtureGenerator : MineGenerator {
    override fun mineDensityFor(coord: ChunkCoord): Float = 0.156f

    override suspend fun generateForFirstTouch(
        firstTouch: CellCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult = GenerationResult(emptyMap())

    override suspend fun reroll(
        coord: ChunkCoord,
        knownChunks: Map<ChunkCoord, Chunk>,
    ): GenerationResult = GenerationResult(
        chunks = mapOf(
            coord to Chunk(
                coord = coord,
                generated = true,
                cells = List(64) { Cell() },
            ),
        ),
    )
}
