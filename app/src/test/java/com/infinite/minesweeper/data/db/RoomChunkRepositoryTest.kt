package com.infinite.minesweeper.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomChunkRepositoryTest {
    private lateinit var database: MinesweeperDatabase
    private lateinit var testDispatcher: StandardTestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var repository: RoomChunkRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MinesweeperDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        repository = RoomChunkRepository(
            chunkDao = database.chunkDao(),
            gameMetaDao = database.gameMetaDao(),
            scope = testScope,
            ioDispatcher = testDispatcher,
            debounceMs = 500L,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndReadChunk_roundTripsThroughRoom() = testScope.runTest {
        val chunk = sampleChunk(
            coord = ChunkCoord(cx = -3, cy = 2),
            cellIndex = 0,
            cell = Cell(state = CellState.REVEALED, isMine = false, adjacentMines = 4),
            generated = true,
            everSurrounded = true,
        )

        repository.saveChunk(chunk)
        repository.flush()
        advanceUntilIdle()

        assertEquals(chunk, repository.getChunk(chunk.coord))
        assertEquals(1, repository.chunkWriteBatchCount)
    }

    @Test
    fun getChunks_filtersCartesianExtrasFromInQuery() = testScope.runTest {
        val wanted = listOf(
            sampleChunk(ChunkCoord(1, 2)),
            sampleChunk(ChunkCoord(3, 4)),
        )
        val decoy = sampleChunk(ChunkCoord(1, 4))

        repository.saveChunks(wanted + decoy)
        repository.flush()
        advanceUntilIdle()

        val loaded = repository.getChunks(wanted.map { it.coord }.toSet())
        assertEquals(wanted.associateBy { it.coord }, loaded)
    }

    @Test
    fun metaRoundTrip_persistsViewportAndCounters() = testScope.runTest {
        val meta = GameMeta(
            flagsPlaced = 12,
            selectorsCleared = 3,
            selectorsWiped = 1,
            viewportX = -76.5f,
            viewportY = 59f,
            zoom = 1.25f,
        )

        repository.saveGameMeta(meta)
        repository.flush()
        advanceUntilIdle()

        assertEquals(meta, repository.getGameMeta())
        assertEquals(1, repository.metaWriteCount)
    }

    @Test
    fun debounce_coalescesRapidChunkWritesIntoOneBatch() = testScope.runTest {
        val coord = ChunkCoord(0, 0)
        repeat(5) { version ->
            repository.saveChunk(
                sampleChunk(
                    coord = coord,
                    cellIndex = version,
                    cell = Cell(state = CellState.FLAGGED, adjacentMines = version),
                ),
            )
        }

        advanceTimeBy(499)
        assertEquals(0, repository.chunkWriteBatchCount)

        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(1, repository.chunkWriteBatchCount)
        val persisted = repository.getChunk(coord)
        assertEquals(CellState.FLAGGED, persisted!!.cells[4].state)
        assertEquals(4, persisted.cells[4].adjacentMines)
    }

    @Test
    fun flush_drainsQueueBeforeDebounceElapses() = testScope.runTest {
        val meta = GameMeta(flagsPlaced = 7, zoom = 2f)
        val chunk = sampleChunk(ChunkCoord(9, -1), generated = true)

        repository.saveChunk(chunk)
        repository.saveGameMeta(meta)

        assertEquals(0, repository.chunkWriteBatchCount)
        assertEquals(0, repository.metaWriteCount)

        repository.flush()
        advanceUntilIdle()

        assertEquals(1, repository.chunkWriteBatchCount)
        assertEquals(1, repository.metaWriteCount)

        // A fresh repository instance must see durable rows (no pending queue).
        val cold = RoomChunkRepository(
            chunkDao = database.chunkDao(),
            gameMetaDao = database.gameMetaDao(),
            scope = testScope,
            ioDispatcher = testDispatcher,
            debounceMs = 500L,
        )
        assertEquals(chunk, cold.getChunk(chunk.coord))
        assertEquals(meta, cold.getGameMeta())
    }

    @Test
    fun saveChunk_readYourWritesBeforeFlush() = testScope.runTest {
        val chunk = sampleChunk(
            coord = ChunkCoord(5, 5),
            status = ChunkStatus.LOCKED,
            lockedAt = 1234L,
            cellIndex = 10,
            cell = Cell(state = CellState.EXPLODED, isMine = true, adjacentMines = 0),
        )

        repository.saveChunk(chunk)
        assertEquals(chunk, repository.getChunk(chunk.coord))
        assertEquals(0, repository.chunkWriteBatchCount)
    }

    @Test
    fun getChunk_returnsNullWhenMissing() = testScope.runTest {
        assertNull(repository.getChunk(ChunkCoord(99, 99)))
    }

    @Test
    fun lockedChunk_roundTripsStatusAndLockedAt() = testScope.runTest {
        val chunk = sampleChunk(
            coord = ChunkCoord(0, 1),
            status = ChunkStatus.LOCKED,
            lockedAt = 99L,
            generated = true,
        )
        repository.saveChunk(chunk)
        repository.flush()
        advanceUntilIdle()

        val loaded = repository.getChunk(chunk.coord)
        assertEquals(ChunkStatus.LOCKED, loaded!!.status)
        assertEquals(99L, loaded.lockedAt)
        assertTrue(loaded.generated)
    }

    private fun sampleChunk(
        coord: ChunkCoord,
        cellIndex: Int = 0,
        cell: Cell = Cell(),
        generated: Boolean = false,
        status: ChunkStatus = ChunkStatus.NORMAL,
        everSurrounded: Boolean = false,
        lockedAt: Long? = null,
    ): Chunk {
        val cells = MutableList(64) { Cell() }
        cells[cellIndex] = cell
        return Chunk(
            coord = coord,
            generated = generated,
            cells = cells,
            status = status,
            everSurrounded = everSurrounded,
            lockedAt = lockedAt,
        )
    }
}
