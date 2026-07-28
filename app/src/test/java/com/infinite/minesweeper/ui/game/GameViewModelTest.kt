package com.infinite.minesweeper.ui.game

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.core.model.ChunkStatus
import com.infinite.minesweeper.core.model.GameMeta
import com.infinite.minesweeper.core.model.GameState
import com.infinite.minesweeper.data.persistence.InMemoryChunkRepository
import com.infinite.minesweeper.ui.settings.InputBindingPreferences
import com.infinite.minesweeper.ui.settings.TapKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration-level coverage for T13's viewport-driven memory bound: [GameViewModel] must evict
 * chunks the player has panned away from and transparently rehydrate them, from the same durable
 * source, without ever letting the engine mistake an evicted chunk for a brand-new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        // resetMain() uninstalls the test Main and leaves JVM Android unit tests with no
        // Looper-backed dispatcher. Reinstall an Unconfined stand-in so a later test in the same
        // process that touches Dispatchers.Main (directly or via runTest) does not crash.
        Dispatchers.resetMain()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @Test
    fun panningAwayEvictsAChunkAndATapThereRehydratesItInsteadOfRegenerating() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val repository = InMemoryChunkRepository()
        val farCoord = ChunkCoord(50, 50)
        val farChunk = Chunk(
            coord = farCoord,
            generated = true,
            cells = List(64) { index ->
                if (index == 0) Cell(state = CellState.REVEALED) else Cell()
            },
        )
        repository.saveChunk(farChunk)

        val viewModel = GameViewModel(repository, inputBindingPreferences())
        advanceUntilIdle()

        viewModel.syncVisibleWindow(setOf(farCoord))
        advanceUntilIdle()
        assertTrue(
            "panning onto the chunk should hydrate it from storage",
            viewModel.state.value.chunks.containsKey(farCoord),
        )

        viewModel.syncVisibleWindow(setOf(ChunkCoord(0, 0)))
        advanceUntilIdle()
        assertFalse(
            "panning away should evict the chunk from the live map",
            viewModel.state.value.chunks.containsKey(farCoord),
        )

        // A tap can still land on the evicted chunk (e.g. a fast pan-then-tap before the
        // viewport's own window sync fires again). It must come back exactly as saved, not as a
        // fresh first-touch roll that would silently discard the player's progress.
        viewModel.dispatch(TapKind.TAP, CellCoord(farCoord.cx * 8 + 1, farCoord.cy * 8))
        advanceUntilIdle()

        val rehydrated = viewModel.state.value.chunks.getValue(farCoord)
        assertEquals(CellState.REVEALED, rehydrated.cells[0].state)

        // GameViewModel's init keeps several collectors running for the ViewModel's lifetime
        // (engine state/events, the persistence coordinator). A real Android lifecycle owner
        // clears the ViewModel to cancel these; here that must happen explicitly, and its
        // cancellation must finish resolving on this test's own Main dispatcher before tearDown
        // resets Main — otherwise a coroutine woken up after resetMain() finds no Main dispatcher
        // and fails, surfacing as a flaky failure in a *different*, unrelated test.
        viewModel.viewModelScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun syncVisibleWindowPinsLockedChunksSoHudAndWatcherKeepThem() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val repository = InMemoryChunkRepository()
        val lockedCoord = ChunkCoord(40, 40)
        val lockedChunk = Chunk(
            coord = lockedCoord,
            generated = true,
            cells = List(64) { index ->
                if (index == 0) {
                    Cell(state = CellState.EXPLODED, isMine = true)
                } else {
                    Cell()
                }
            },
            status = ChunkStatus.LOCKED,
            lockedAt = 1L,
        )
        repository.saveChunk(lockedChunk)

        val viewModel = GameViewModel(repository, inputBindingPreferences())
        advanceUntilIdle()

        viewModel.syncVisibleWindow(setOf(lockedCoord))
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.selectorsLocked)

        viewModel.syncVisibleWindow(setOf(ChunkCoord(0, 0)))
        advanceUntilIdle()

        assertTrue(
            "locked selectors must stay hydrated outside the viewport window",
            viewModel.state.value.chunks.containsKey(lockedCoord),
        )
        assertEquals(1, viewModel.state.value.selectorsLocked)
        assertEquals(ChunkStatus.LOCKED, viewModel.state.value.chunks.getValue(lockedCoord).status)

        viewModel.viewModelScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun resetGame_wipesRepositoryAndReturnsToAFreshOriginState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val store = InMemoryChunkRepository.DurableStore()
        val repository = InMemoryChunkRepository(store)
        // Within restoreGameState's default 2-chunk restore window around the default (0,0)
        // viewport, so it's actually loaded into the live state on startup.
        val seededCoord = ChunkCoord(1, 1)
        val seededChunk = Chunk(
            coord = seededCoord,
            generated = true,
            cells = List(64) { index -> if (index == 0) Cell(state = CellState.REVEALED) else Cell() },
        )
        repository.saveChunk(seededChunk)
        repository.saveGameMeta(GameMeta(flagsPlaced = 5, hasEverRevealed = true))

        val viewModel = GameViewModel(repository, inputBindingPreferences())
        advanceUntilIdle()
        assertTrue(viewModel.state.value.chunks.containsKey(seededCoord))

        viewModel.resetGame()
        advanceUntilIdle()

        assertEquals(GameState(), viewModel.state.value)
        assertTrue("clearAll must wipe every durable chunk", store.chunks.isEmpty())
        // The new session's persistence coordinator writes back a fresh default GameMeta on its
        // first tick — the durable state must reflect a wiped board, not merely be null.
        assertEquals(GameMeta(), store.meta)

        viewModel.viewModelScope.cancel()
        advanceUntilIdle()
    }

    private fun inputBindingPreferences(): InputBindingPreferences {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("binding.preferences_pb")
        }
        return InputBindingPreferences(dataStore)
    }
}
