package com.infinite.minesweeper.ui.game

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.infinite.minesweeper.core.model.Cell
import com.infinite.minesweeper.core.model.CellCoord
import com.infinite.minesweeper.core.model.CellState
import com.infinite.minesweeper.core.model.Chunk
import com.infinite.minesweeper.core.model.ChunkCoord
import com.infinite.minesweeper.data.persistence.InMemoryChunkRepository
import com.infinite.minesweeper.ui.settings.InputBindingPreferences
import com.infinite.minesweeper.ui.settings.TapKind
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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
        Dispatchers.resetMain()
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

    private fun inputBindingPreferences(): InputBindingPreferences {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("binding.preferences_pb")
        }
        return InputBindingPreferences(dataStore)
    }
}
