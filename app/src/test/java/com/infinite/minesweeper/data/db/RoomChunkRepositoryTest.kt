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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * Room + write-behind tests for T3.
 *
 * Debounce timing uses the [TestScope] virtual clock (`advanceTimeBy`) so coalescing is
 * deterministic. The repository scope uses [UnconfinedTestDispatcher] so debounce jobs start
 * eagerly while still honoring virtual `delay`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomChunkRepositoryTest {
    private lateinit var database: MinesweeperDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MinesweeperDatabase::class.java)
            .allowMainThreadQueries()
            .setTransactionExecutor(Runnable::run)
            .setQueryExecutor(Runnable::run)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndReadChunk_roundTripsThroughRoom() = runTest {
        val repository = newRepository()
        val chunk = sampleChunk(
            coord = ChunkCoord(cx = -3, cy = 2),
            cellIndex = 0,
            cell = Cell(state = CellState.REVEALED, isMine = false, adjacentMines = 4),
            generated = true,
            everSurrounded = true,
        )

        repository.saveChunk(chunk)
        repository.flush()

        assertEquals(chunk, repository.getChunk(chunk.coord))
        assertEquals(1, repository.chunkWriteBatchCount)
    }

    @Test
    fun getChunks_filtersCartesianExtrasFromInQuery() = runTest {
        val repository = newRepository()
        val wanted = listOf(
            sampleChunk(ChunkCoord(1, 2)),
            sampleChunk(ChunkCoord(3, 4)),
        )
        val decoy = sampleChunk(ChunkCoord(1, 4))

        repository.saveChunks(wanted + decoy)
        repository.flush()

        val loaded = repository.getChunks(wanted.map { it.coord }.toSet())
        assertEquals(wanted.associateBy { it.coord }, loaded)
    }

    @Test
    fun metaRoundTrip_persistsViewportAndCounters() = runTest {
        val repository = newRepository()
        val meta = GameMeta(
            flagsPlaced = 12,
            selectorsCleared = 3,
            selectorsWiped = 1,
            viewportX = -76.5f,
            viewportY = 59f,
            zoom = 1.25f,
            hasEverRevealed = true,
        )

        repository.saveGameMeta(meta)
        repository.flush()

        assertEquals(meta, repository.getGameMeta())
        assertEquals(1, repository.metaWriteCount)
    }

    @Test
    fun debounce_timerFiresPersistAfterDebounceMs() = runTest {
        val repository = newRepository()
        val chunk = sampleChunk(ChunkCoord(0, 0), generated = true)

        repository.saveChunk(chunk)
        assertEquals(0, repository.chunkWriteBatchCount)

        advanceTimeBy(RoomChunkRepository.DEFAULT_DEBOUNCE_MS - 1)
        assertEquals(0, repository.chunkWriteBatchCount)

        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(1, repository.chunkWriteBatchCount)
        assertEquals(chunk, repository.getChunk(chunk.coord))
    }

    @Test
    fun debounce_coalescesRapidChunkWritesIntoOneBatch() = runTest {
        val repository = newRepository()
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

        assertEquals(0, repository.chunkWriteBatchCount)

        advanceTimeBy(RoomChunkRepository.DEFAULT_DEBOUNCE_MS)
        advanceUntilIdle()

        assertEquals(1, repository.chunkWriteBatchCount)
        val persisted = repository.getChunk(coord)
        assertEquals(CellState.FLAGGED, persisted!!.cells[4].state)
        assertEquals(4, persisted.cells[4].adjacentMines)
    }

    @Test
    fun flush_drainsQueueBeforeDebounceElapses() = runTest {
        val repository = newRepository()
        val meta = GameMeta(flagsPlaced = 7, zoom = 2f)
        val chunk = sampleChunk(ChunkCoord(9, -1), generated = true)

        repository.saveChunk(chunk)
        repository.saveGameMeta(meta)

        assertEquals(0, repository.chunkWriteBatchCount)
        assertEquals(0, repository.metaWriteCount)

        repository.flush()

        assertEquals(1, repository.chunkWriteBatchCount)
        assertEquals(1, repository.metaWriteCount)

        val cold = newRepository()
        assertEquals(chunk, cold.getChunk(chunk.coord))
        assertEquals(meta, cold.getGameMeta())
    }

    @Test
    fun saveChunk_readYourWritesBeforeFlush() = runTest {
        val repository = newRepository()
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
    fun getChunk_returnsNullWhenMissing() = runTest {
        assertNull(newRepository().getChunk(ChunkCoord(99, 99)))
    }

    @Test
    fun clearAll_removesAllChunksAndMetaAndDropsQueuedWrites() = runTest {
        val repository = newRepository()
        val chunk = sampleChunk(ChunkCoord(4, 4), generated = true)
        repository.saveChunk(chunk)
        repository.saveGameMeta(GameMeta(flagsPlaced = 3, hasEverRevealed = true))
        repository.flush()
        assertEquals(chunk, repository.getChunk(chunk.coord))

        // A fresh write queued right before the reset must not survive it either.
        repository.saveChunk(sampleChunk(ChunkCoord(5, 5), generated = true))
        repository.clearAll()

        assertNull(repository.getChunk(chunk.coord))
        assertNull(repository.getChunk(ChunkCoord(5, 5)))
        assertNull(repository.getGameMeta())

        // No stray debounced write from before the reset should reappear once its timer fires.
        advanceTimeBy(RoomChunkRepository.DEFAULT_DEBOUNCE_MS)
        advanceUntilIdle()
        assertNull(repository.getChunk(ChunkCoord(5, 5)))
    }

    @Test
    fun lockedChunk_roundTripsStatusAndLockedAt() = runTest {
        val repository = newRepository()
        val chunk = sampleChunk(
            coord = ChunkCoord(0, 1),
            status = ChunkStatus.LOCKED,
            lockedAt = 99L,
            generated = true,
        )
        repository.saveChunk(chunk)
        repository.flush()

        val loaded = repository.getChunk(chunk.coord)
        assertEquals(ChunkStatus.LOCKED, loaded!!.status)
        assertEquals(99L, loaded.lockedAt)
        assertTrue(loaded.generated)
    }

    /**
     * [UnconfinedTestDispatcher] starts debounce jobs immediately; virtual `delay` still
     * respects [advanceTimeBy]. Child [Job] is cancelled via [backgroundScope] parenting so
     * unread debounce timers do not leak past the test.
     */
    private fun TestScope.newRepository(): RoomChunkRepository {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        return RoomChunkRepository(
            chunkDao = database.chunkDao(),
            gameMetaDao = database.gameMetaDao(),
            scope = CoroutineScope(dispatcher + job),
            ioDispatcher = dispatcher,
            debounceMs = RoomChunkRepository.DEFAULT_DEBOUNCE_MS,
        )
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
